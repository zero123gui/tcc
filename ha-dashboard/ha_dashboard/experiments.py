"""Cronômetro de experimentos de failover.

Um experimento começa quando o painel executa uma ação disruptiva no líder
ou no nó PRIMARY, e registra o primeiro instante de cada marco. Todos os
tempos usam o relógio do Windows (momento em que o painel recebeu a linha de
log ou observou o status), então não dependem de sincronização entre as VMs.

Marcos vindos dos logs têm a latência do SSH (milissegundos). Marcos vindos
do polling ("primary_visible") têm resolução de poll-interval-s.
"""

from __future__ import annotations

import csv
import itertools
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

from .models import NodeStatus

# Marcos na ordem em que aparecem no CSV
MILESTONES = [
    ("pg_unhealthy", "PostgreSQL do alvo declarado UNHEALTHY"),
    ("step_down", "Líder renunciou"),
    ("first_election", "Primeira eleição iniciada"),
    ("leader_elected", "Novo líder eleito"),
    ("promoting", "Promoção do PostgreSQL iniciada"),
    ("promoted", "PostgreSQL promovido (log)"),
    ("primary_visible", "Novo PRIMARY visível (consulta SQL)"),
    ("split_brain", "Split-brain detectado"),
    ("healed", "Self-Healing concluído"),
]

_ids = itertools.count(1)

# Espera máxima pelo log de confirmação da promoção depois que o novo primary já está visível
PROMOTED_GRACE_S = 5.0


@dataclass
class Experiment:
    scenario: str
    target: str
    started_at: float
    old_leader: str | None
    old_primary: str | None
    timeout_s: float
    id: int = field(default_factory=lambda: next(_ids))
    marks: dict[str, float] = field(default_factory=dict)
    new_leader: str | None = None
    new_term: int | None = None
    new_primary: str | None = None
    notes: list[str] = field(default_factory=list)
    finished_at: float | None = None
    outcome: str | None = None
    persisted: bool = False
    # Quando já havia novo líder e novo primary visível (início da espera pela confirmação)
    _failover_ready_at: float | None = field(default=None, repr=False)

    @property
    def active(self) -> bool:
        return self.finished_at is None

    def _mark(self, key: str, ts: float) -> None:
        self.marks.setdefault(key, ts)

    # ------------------------------------------------------------------ eventos

    def on_log(self, node: str, kind: str, ts: float, term: int | None = None) -> None:
        if not self.active:
            return
        if kind == "PG_UNHEALTHY" and node == self.target:
            self._mark("pg_unhealthy", ts)
        elif kind in ("STEP_DOWN", "QUORUM_LOST") and node == self.old_leader:
            self._mark("step_down", ts)
        elif kind == "ELECTION":
            self._mark("first_election", ts)
        elif kind == "LEADER" and node != self.old_leader and "leader_elected" not in self.marks:
            self._mark("leader_elected", ts)
            self.new_leader, self.new_term = node, term
        elif kind == "PROMOTING":
            self._mark("promoting", ts)
        elif kind == "PROMOTED":
            self._mark("promoted", ts)
        elif kind in ("SPLIT_BRAIN", "HEALED"):
            # Só o antigo primary deveria se reconciliar; em outro nó é anomalia, não marco.
            if node == self.old_primary:
                self._mark("split_brain" if kind == "SPLIT_BRAIN" else "healed", ts)
            else:
                self.notes.append(f"{kind} inesperado em {node} (+{ts - self.started_at:.1f}s)")
        elif kind in ("PROMOTE_FAILED", "HEAL_FAILED"):
            self.notes.append(f"{kind} em {node} (+{ts - self.started_at:.1f}s)")
        self._check_done(ts)

    def on_status(self, statuses: list[NodeStatus], ts: float) -> None:
        if not self.active:
            return
        for s in statuses:
            if s.pg_state == "PRIMARY" and s.id != self.old_primary and "primary_visible" not in self.marks:
                self._mark("primary_visible", ts)
                self.new_primary = s.id
        self._check_done(ts)

    def on_action(self, node: str, action: str, ts: float) -> None:
        if self.active:
            self.notes.append(f"ação {action} em {node} (+{ts - self.started_at:.1f}s)")

    def tick(self, ts: float) -> None:
        if not self.active:
            return
        if ts - self.started_at >= self.timeout_s:
            self.finish(ts, "timeout")
        else:
            self._check_done(ts)  # a espera pela confirmação da promoção expira com o tempo

    def finish(self, ts: float, outcome: str) -> None:
        if self.active:
            self.finished_at, self.outcome = ts, outcome

    def _check_done(self, ts: float) -> None:
        failover_done = "leader_elected" in self.marks and "primary_visible" in self.marks
        if self.scenario == "partition-on":
            # A partição só termina quando o antigo primary é reconciliado (depois de reconectar).
            if failover_done and ("healed" in self.marks):
                self.finish(ts, "concluído")
        elif failover_done:
            # O novo primary costuma ficar visível na consulta SQL ~0,5 s antes de o agente
            # registrar "confirmou papel de PRIMARY". Espera esse log para o marco "promoted"
            # não ficar vazio, mas não prende o experimento se ele nunca chegar.
            if "promoted" in self.marks:
                self.finish(ts, "concluído")
            elif self._failover_ready_at is None:
                self._failover_ready_at = ts
            elif ts - self._failover_ready_at >= PROMOTED_GRACE_S:
                self.notes.append(
                    f"log de confirmação da promoção não chegou em {PROMOTED_GRACE_S:g} s")
                self.finish(ts, "concluído")

    # ------------------------------------------------------------------ saída

    def seconds(self, key: str) -> float | None:
        t = self.marks.get(key)
        return round(t - self.started_at, 3) if t is not None else None

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "scenario": self.scenario,
            "target": self.target,
            "started_at": self.started_at,
            "old_leader": self.old_leader,
            "old_primary": self.old_primary,
            "new_leader": self.new_leader,
            "new_term": self.new_term,
            "new_primary": self.new_primary,
            "marks": {k: self.seconds(k) for k, _ in MILESTONES},
            "notes": self.notes,
            "active": self.active,
            "outcome": self.outcome,
            "elapsed": round((self.finished_at or 0) - self.started_at, 3) if self.finished_at else None,
        }

    def csv_row(self) -> dict:
        row = {
            "id": self.id,
            "inicio": datetime.fromtimestamp(self.started_at).isoformat(timespec="milliseconds"),
            "cenario": self.scenario,
            "alvo": self.target,
            "lider_anterior": self.old_leader or "",
            "primary_anterior": self.old_primary or "",
            "novo_lider": self.new_leader or "",
            "novo_mandato": self.new_term if self.new_term is not None else "",
            "novo_primary": self.new_primary or "",
        }
        for key, _ in MILESTONES:
            v = self.seconds(key)
            row[f"{key}_s"] = "" if v is None else f"{v:.3f}"
        row["desfecho"] = self.outcome or ""
        row["observacoes"] = "; ".join(self.notes)
        return row


class ExperimentLog:
    """Mantém o experimento ativo, o histórico em memória e o CSV em disco."""

    def __init__(self, csv_path: Path, timeout_s: float):
        self.csv_path = csv_path
        self.timeout_s = timeout_s
        self.history: list[Experiment] = []

    @property
    def active(self) -> Experiment | None:
        return next((e for e in reversed(self.history) if e.active), None)

    def start(self, scenario: str, target: str, ts: float,
              old_leader: str | None, old_primary: str | None) -> Experiment:
        # Partições ficam abertas até o operador reconectar o nó, então ganham mais tempo.
        timeout = max(self.timeout_s, 600.0) if scenario == "partition-on" else self.timeout_s
        exp = Experiment(scenario, target, ts, old_leader, old_primary, timeout)
        self.history.append(exp)
        return exp

    def persist(self, exp: Experiment) -> None:
        self.csv_path.parent.mkdir(parents=True, exist_ok=True)
        row = exp.csv_row()
        new_file = not self.csv_path.exists()
        with self.csv_path.open("a", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(row.keys()))
            if new_file:
                writer.writeheader()
            writer.writerow(row)
