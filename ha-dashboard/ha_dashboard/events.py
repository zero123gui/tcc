"""Classificação das linhas de log do raft-ha-manager em eventos.

Os padrões seguem as mensagens reais do agente Java (em português), em
RaftNode.java, FailoverExecutor.java, PostgresHealthChecker.java e Main.java.
Se essas mensagens mudarem, ajuste os padrões aqui.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# Ordem importa: o primeiro padrão que casar define o tipo do evento.
_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("SPLIT_BRAIN", re.compile(r"SPLIT-BRAIN DETECTADO|estado Rogue Primary")),
    ("HEAL_FAILED", re.compile(r"Auto-Healing falhou|Falha catastrófica ao tentar executar Fencing")),
    ("HEAL_ABORTED", re.compile(r"Auto-Healing abortado")),
    ("HEALED", re.compile(r"Auto-Healing concluído com sucesso")),
    ("ELECTION", re.compile(r"iniciou eleição para o mandato (?P<term>\d+)")),
    ("LEADER", re.compile(r"eleito LÍDER para o mandato (?P<term>\d+)")),
    ("QUORUM_LOST", re.compile(r"Renunciando à liderança: sem resposta da maioria")),
    # Alinhamento entre o líder do Raft e o PRIMARY do PostgreSQL (item 2 do aperfeiçoamento)
    ("HANDOVER", re.compile(r"Entregando a liderança: o PostgreSQL PRIMARY está em")),
    ("DEFER_TO_PRIMARY", re.compile(r"Este nó não se candidata, para que a liderança")),
    ("FENCING_DEFERRED", re.compile(r"Fencing adiado")),
    ("STEP_DOWN", re.compile(r"deixou de ser LEADER|Renunciando à liderança")),
    # Proteção do líder ativo (pré-votação e proteção contra servidores disruptivos)
    ("PRE_VOTE_DENIED", re.compile(r"Pré-voto negado|Voto negado para \S+ no mandato \d+: ")),
    ("PRE_VOTE_LOST", re.compile(r"Pré-votação sem maioria")),
    ("PG_UNHEALTHY", re.compile(r"PostgreSQL declarado UNHEALTHY")),
    ("PROMOTING", re.compile(r"Iniciando promoção para PRIMARY")),
    ("PROMOTED", re.compile(r"PostgreSQL confirmou papel de PRIMARY")),
    ("PROMOTE_FAILED", re.compile(r"Falha ao promover PostgreSQL|Falha em ambos os métodos de promoção")),
    ("STARTED", re.compile(r"pronto\. Aguardando eventos do cluster")),
    ("STOPPING", re.compile(r"Sinal de encerramento recebido")),
]

_LEVEL = re.compile(r"\[(TRACE|DEBUG|INFO|WARN|ERROR)\]")

# Eventos exibidos com destaque na interface
HIGHLIGHT = {
    "SPLIT_BRAIN", "HEAL_FAILED", "HEALED", "ELECTION", "LEADER", "STEP_DOWN",
    "PG_UNHEALTHY", "PROMOTING", "PROMOTED", "PROMOTE_FAILED",
    "QUORUM_LOST", "PRE_VOTE_DENIED", "PRE_VOTE_LOST",
    "HANDOVER", "DEFER_TO_PRIMARY", "FENCING_DEFERRED", "HEAL_ABORTED",
}


@dataclass(frozen=True)
class Detected:
    kind: str
    term: int | None = None


def classify(line: str) -> Detected | None:
    for kind, pattern in _PATTERNS:
        m = pattern.search(line)
        if m:
            term = m.groupdict().get("term")
            return Detected(kind, int(term) if term else None)
    return None


def level_of(line: str) -> str | None:
    m = _LEVEL.search(line)
    return m.group(1) if m else None
