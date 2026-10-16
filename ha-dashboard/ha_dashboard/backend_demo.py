"""Simulador do cluster para usar o painel sem as VMs (python -m ha_dashboard --demo).

Imita o comportamento observável do agente Java, incluindo as mensagens de log
reais:
  - pré-votação: um nó só se candidata se a maioria não estiver ouvindo um líder;
  - check-quorum: um líder isolado renuncia depois de election-timeout-min;
e as limitações que ainda existem:
  - parar só o raft-ha-manager do líder deixa o PostgreSQL dele como PRIMARY;
  - standbys não são reapontados para o novo primary após o failover.
Os timeouts são menores que os do node.yaml para a demonstração ser rápida.
"""

from __future__ import annotations

import asyncio
import random
import time
from dataclasses import dataclass, field
from datetime import datetime

from .backend_real import LineCallback
from .config import Config, NodeDef
from .models import NodeStatus

HEARTBEAT_S = 1.0
ELECTION_MIN_S, ELECTION_MAX_S = 4.0, 7.0
PG_FAIL_DETECT_S = 3.0
PROMOTION_S = 2.0
HEALING_S = 4.0


@dataclass
class SimNode:
    d: NodeDef
    raft_on: bool = True
    pg_on: bool = True
    isolated: bool = False
    role: str = "FOLLOWER"
    term: int = 1
    leader_id: str | None = None
    commit: int = 1
    pg_role: str = "STANDBY"
    upstream: str | None = None
    last_hb: float = field(default_factory=time.time)
    timeout: float = field(default_factory=lambda: random.uniform(ELECTION_MIN_S, ELECTION_MAX_S))
    pg_down_since: float | None = None
    quorum_lost_since: float | None = None  # líder sem contato com a maioria desde
    pending: list[tuple[float, str]] = field(default_factory=list)  # (quando, ação agendada)


class DemoBackend:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self.nodes = {n.id: SimNode(n) for n in cfg.nodes}
        self._on_line: LineCallback | None = None
        self._task: asyncio.Task | None = None

        # Estado inicial igual ao do scripts/README.md: node-1 líder e primary
        data_nodes = [n for n in self.nodes.values() if not n.d.arbiter]
        first = data_nodes[0]
        first.role, first.pg_role = "LEADER", "PRIMARY"
        for n in self.nodes.values():
            n.leader_id = first.d.id
            if n is not first and not n.d.arbiter:
                n.upstream = first.d.host

    async def start(self, on_line: LineCallback) -> None:
        self._on_line = on_line
        for n in self.nodes.values():
            await on_line(n.d.id, self._fmt(n, "INFO", "Main", f"Nó {n.d.id} pronto. Aguardando eventos do cluster."), True)
        self._task = asyncio.create_task(self._loop())

    async def close(self) -> None:
        if self._task:
            self._task.cancel()

    # ------------------------------------------------------------------ API do backend

    async def poll(self, node: NodeDef) -> NodeStatus:
        n = self.nodes[node.id]
        st = NodeStatus(id=node.id, host=node.host, arbiter=node.arbiter, ssh_ok=True, updated_at=time.time())
        st.services = {"raft-ha-manager": "active" if n.raft_on else "inactive"}
        st.partitioned = n.isolated
        st.partition_peers = [p.host for p in self.cfg.peers_of(node.id)] if n.isolated else []
        if n.raft_on:
            st.grpc_ok, st.role, st.term, st.leader_id, st.commit_index = True, n.role, n.term, n.leader_id, n.commit
        else:
            st.grpc_error = "UNAVAILABLE"
        if node.arbiter:
            return st
        st.services["postgresql"] = "active" if n.pg_on else "inactive"
        if not n.pg_on:
            st.pg_state, st.pg_error = "DOWN", "Connection refused"
        elif n.pg_role == "PRIMARY":
            st.pg_state = "PRIMARY"
            st.replicas = [
                {"addr": o.d.host, "state": "streaming", "app": o.d.id}
                for o in self.nodes.values()
                if o.pg_on and o.pg_role == "STANDBY" and o.upstream == node.host and self._linked(n, o)
            ]
        else:
            st.pg_state = "STANDBY"
            up = next((o for o in self.nodes.values() if o.d.host == n.upstream), None)
            streaming = up is not None and up.pg_on and up.pg_role == "PRIMARY" and self._linked(n, up)
            st.upstream_host = n.upstream if streaming else None
            st.upstream_status = "streaming" if streaming else None
        return st

    async def run_action(self, node: NodeDef, action: str, argv: list[str]) -> dict:
        n = self.nodes[node.id]
        now = time.time()
        if action == "raft-stop" and n.raft_on:
            await self._log(n, "INFO", "Main", f"Sinal de encerramento recebido. Iniciando parada do nó {n.d.id}...")
            n.raft_on, n.role = False, "FOLLOWER"
        elif action == "raft-start" and not n.raft_on:
            n.raft_on, n.role, n.leader_id, n.last_hb = True, "FOLLOWER", None, now
            await self._log(n, "INFO", "Main", f"Nó {n.d.id} pronto. Aguardando eventos do cluster.")
        elif action == "pg-stop":
            n.pg_on, n.pg_down_since = False, now
        elif action == "pg-start":
            n.pg_on, n.pg_down_since = True, None
            # handleHealthChange(): o banco volta como PRIMARY num seguidor → checkSplitBrain()
            if n.raft_on and n.role == "FOLLOWER":
                await self._become_follower(n, n.term, n.leader_id)
        elif action == "partition-on":
            n.isolated = True
        elif action == "partition-off":
            n.isolated = False
        return {"exit": 0, "stdout": f"[demo] {' '.join(argv)}", "stderr": ""}

    # ------------------------------------------------------------------ simulação

    def _linked(self, a: SimNode, b: SimNode) -> bool:
        return a is b or not (a.isolated or b.isolated)

    def _alive(self, n: SimNode) -> bool:
        return n.raft_on

    async def _loop(self) -> None:
        while True:
            await self._step(time.time())
            await asyncio.sleep(0.25)

    async def _step(self, now: float) -> None:
        nodes = list(self.nodes.values())

        # Ações agendadas (promoção, self-healing)
        for n in nodes:
            due = [p for p in n.pending if p[0] <= now]
            n.pending = [p for p in n.pending if p[0] > now]
            for _, what in due:
                await self._run_pending(n, what)

        # Health check do PostgreSQL no líder → step-down
        for n in nodes:
            if (self._alive(n) and n.role == "LEADER" and not n.pg_on
                    and n.pg_down_since and now - n.pg_down_since >= PG_FAIL_DETECT_S):
                await self._log(n, "ERROR", "PostgresHealthChecker",
                                "PostgreSQL declarado UNHEALTHY após 3 falhas consecutivas. O cluster Raft será notificado.")
                await self._log(n, "WARN", "RaftNode", "O PostgreSQL local falhou enquanto este nó era LEADER. "
                                                       "Renunciando à liderança imediatamente.")
                await self._become_follower(n, n.term, None)
                n.last_hb = now

        # Heartbeats do(s) líder(es)
        quorum = len(nodes) // 2 + 1
        for leader in [n for n in nodes if self._alive(n) and n.role == "LEADER"]:
            if leader.role != "LEADER":
                continue  # recuou neste mesmo ciclo ao encontrar um mandato maior
            # Check-quorum: sem contato com a maioria por election-timeout-min → renuncia
            reachable = 1 + sum(1 for f in nodes if f is not leader and self._alive(f) and self._linked(leader, f))
            if reachable < quorum:
                if leader.quorum_lost_since is None:
                    leader.quorum_lost_since = now
                elif now - leader.quorum_lost_since >= ELECTION_MIN_S:
                    await self._log(leader, "WARN", "RaftNode",
                                    "Renunciando à liderança: sem resposta da maioria do cluster há mais de "
                                    f"{int(ELECTION_MIN_S * 1000)} ms (check-quorum).")
                    await self._become_follower(leader, leader.term, None)
                    leader.last_hb, leader.quorum_lost_since = now, None
                    continue
            else:
                leader.quorum_lost_since = None
            for f in nodes:
                if f is leader or not self._alive(f) or not self._linked(leader, f):
                    continue
                if leader.term >= f.term:
                    if f.role != "FOLLOWER" or f.leader_id != leader.d.id or f.term != leader.term:
                        await self._become_follower(f, leader.term, leader.d.id)
                    f.last_hb, f.commit = now, leader.commit
                else:
                    # O seguidor tem mandato maior: o líder antigo recua (reconexão após partição)
                    await self._become_follower(leader, f.term, f.leader_id)
                    break

        # Timeouts de eleição
        for n in nodes:
            if not self._alive(n) or n.role == "LEADER" or now - n.last_hb < n.timeout:
                continue
            n.last_hb, n.timeout = now, random.uniform(ELECTION_MIN_S, ELECTION_MAX_S)
            if n.d.arbiter or not n.pg_on:
                continue  # árbitros e nós com PG não saudável não se candidatam
            await self._election(n, nodes, now)

    def _sees_active_leader(self, v: SimNode, now: float) -> str | None:
        """Motivo para negar o pré-voto (líder ativo do ponto de vista de v), ou None."""
        if v.role == "LEADER":
            return "este nó é o líder e fala com a maioria"
        if v.leader_id and now - v.last_hb < ELECTION_MIN_S:
            return f"líder {v.leader_id} ativo (último contato há {int((now - v.last_hb) * 1000)} ms)"
        return None

    async def _election(self, cand: SimNode, nodes: list[SimNode], now: float) -> None:
        # Pré-votação: só sobe o mandato se a maioria não estiver ouvindo um líder
        proposed = cand.term + 1
        granted = 1
        for v in nodes:
            if v is cand or not self._alive(v) or not self._linked(cand, v):
                continue
            reason = self._sees_active_leader(v, now)
            if reason:
                await self._log(v, "INFO", "RaftNode",
                                f"Pré-voto negado para {cand.d.id} (mandato proposto {proposed}): {reason}.")
            else:
                granted += 1
        if granted < len(nodes) // 2 + 1:
            await self._log(cand, "INFO", "RaftNode",
                            f"Pré-votação sem maioria ({granted}/{len(nodes)}) para o mandato {proposed}: há um líder "
                            f"ativo ou o log deste nó está desatualizado. Mandato mantido em {cand.term}.")
            return
        await self._log(cand, "INFO", "RaftNode",
                        f"Pré-votação aprovada ({granted}/{len(nodes)}) para o mandato {proposed}. Iniciando eleição.")

        previous_leader = cand.leader_id
        cand.term += 1
        cand.role = "CANDIDATE"
        await self._log(cand, "INFO", "RaftNode", f"Nó {cand.d.id} iniciou eleição para o mandato {cand.term}.")
        votes = 1
        for v in nodes:
            if v is cand or not self._alive(v) or not self._linked(cand, v) or v.term >= cand.term:
                continue
            if v.role == "LEADER":
                await self._log(v, "INFO", "RaftNode",
                                f"Nó {v.d.id} deixou de ser LEADER. Revertendo para FOLLOWER no mandato {cand.term}.")
            v.term, v.role, v.leader_id, v.last_hb = cand.term, "FOLLOWER", None, now
            await self._log(v, "INFO", "RaftNode", f"Voto concedido para {cand.d.id} no mandato {cand.term}.")
            votes += 1
        if votes < len(nodes) // 2 + 1:
            cand.role = "FOLLOWER"
            return
        cand.role, cand.leader_id = "LEADER", cand.d.id
        cand.commit += 1  # NO_OP
        await self._log(cand, "INFO", "RaftNode", f"Nó {cand.d.id} eleito LÍDER para o mandato {cand.term}.")
        if previous_leader and previous_leader != cand.d.id and cand.pg_role == "STANDBY":
            await self._log(cand, "INFO", "RaftNode",
                            f"Líder anterior era '{previous_leader}'. Verificando se o PostgreSQL local precisa ser promovido.")
            await self._log(cand, "INFO", "RaftNode", "PostgreSQL local é STANDBY. Iniciando promoção para PRIMARY.")
            cand.pending.append((now + PROMOTION_S, "promote"))

    async def _become_follower(self, n: SimNode, term: int, leader_id: str | None) -> None:
        if n.role == "LEADER":
            await self._log(n, "INFO", "RaftNode", f"Nó {n.d.id} deixou de ser LEADER. Revertendo para FOLLOWER no mandato {term}.")
        n.term, n.role, n.leader_id = max(n.term, term), "FOLLOWER", leader_id
        # checkSplitBrain(): seguidor com PG PRIMARY e líder conhecido → fencing + self-healing
        if (not n.d.arbiter and n.pg_on and n.pg_role == "PRIMARY" and leader_id
                and not any(w.startswith("heal") for _, w in n.pending)):
            await self._log(n, "WARN", "RaftNode",
                            f"Detectado estado Rogue Primary! Acionando Fencing e Auto-Healing assíncrono. Líder legítimo: {leader_id}")
            await self._log(n, "ERROR", "FailoverExecutor",
                            " SPLIT-BRAIN DETECTADO! Banco de dados local reportando como PRIMARY,")
            n.pg_on = False  # fencing: systemctl stop postgresql
            n.pending.append((time.time() + HEALING_S, f"heal:{leader_id}"))

    async def _run_pending(self, n: SimNode, what: str) -> None:
        if what == "promote" and n.pg_on:
            n.pg_role = "PRIMARY"
            n.commit += 1  # entrada FAILOVER
            await self._log(n, "INFO", "FailoverExecutor", "PostgreSQL promovido com sucesso via pg_promote().")
            await self._log(n, "INFO", "FailoverExecutor", f"PostgreSQL confirmou papel de PRIMARY após {int(PROMOTION_S)} segundos.")
        elif what.startswith("heal:"):
            leader = self.nodes[what.split(":", 1)[1]]
            n.pg_on, n.pg_role, n.upstream, n.pg_down_since = True, "STANDBY", leader.d.host, None
            await self._log(n, "INFO", "FailoverExecutor",
                            "Auto-Healing concluído com sucesso. O PostgreSQL local agora é STANDBY.")

    # ------------------------------------------------------------------ logs

    def _fmt(self, n: SimNode, level: str, logger: str, msg: str) -> str:
        ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
        return f"{ts} [{level}] [{n.d.id}] [c.t.r.{logger}] - {msg}"

    async def _log(self, n: SimNode, level: str, logger: str, msg: str) -> None:
        if self._on_line and n.raft_on:
            await self._on_line(n.d.id, self._fmt(n, level, logger, msg), False)
