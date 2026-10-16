"""As mensagens abaixo são copiadas dos logs reais do agente Java."""

import pytest

from ha_dashboard.events import classify, level_of


@pytest.mark.parametrize("line, kind, term", [
    ("2026-10-14 10:00:00.123 [INFO] [node-2] [c.t.r.core.RaftNode] - Nó node-2 iniciou eleição para o mandato 4.",
     "ELECTION", 4),
    ("2026-10-14 10:00:00.456 [INFO] [node-2] [c.t.r.core.RaftNode] - Nó node-2 eleito LÍDER para o mandato 4.",
     "LEADER", 4),
    ("[INFO] Nó node-1 deixou de ser LEADER. Revertendo para FOLLOWER no mandato 5.", "STEP_DOWN", None),
    ("[WARN] O PostgreSQL local falhou enquanto este nó era LEADER. Renunciando à liderança imediatamente.",
     "STEP_DOWN", None),
    ("[ERROR] PostgreSQL declarado UNHEALTHY após 3 falhas consecutivas. O cluster Raft será notificado.",
     "PG_UNHEALTHY", None),
    ("[INFO] PostgreSQL local é STANDBY. Iniciando promoção para PRIMARY.", "PROMOTING", None),
    ("[INFO] PostgreSQL confirmou papel de PRIMARY após 2 segundos.", "PROMOTED", None),
    ("[ERROR] Falha ao promover PostgreSQL para PRIMARY.", "PROMOTE_FAILED", None),
    ("[ERROR]  SPLIT-BRAIN DETECTADO! Banco de dados local reportando como PRIMARY,", "SPLIT_BRAIN", None),
    ("[WARN] Detectado estado Rogue Primary! Acionando Fencing e Auto-Healing assíncrono. Líder legítimo: node-2",
     "SPLIT_BRAIN", None),
    ("[INFO] Auto-Healing concluído com sucesso. O PostgreSQL local agora é STANDBY.", "HEALED", None),
    ("[INFO] [Auto-Healing]   Auto-Healing concluído com sucesso!", "HEALED", None),
    ("[ERROR] Auto-Healing falhou com código de saída 1", "HEAL_FAILED", None),
    ("[INFO] Nó node-3 pronto. Aguardando eventos do cluster.", "STARTED", None),
    # Pré-votação, proteção contra servidores disruptivos e check-quorum (RaftNode, item 1 do aperfeiçoamento)
    ("[INFO] Pré-voto negado para node-2 (mandato proposto 23): líder node-1 ativo (último contato há 812 ms).",
     "PRE_VOTE_DENIED", None),
    ("[INFO] Voto negado para node-2 no mandato 27: líder node-1 ativo (último contato há 40 ms). "
     "Mandato mantido em 22.", "PRE_VOTE_DENIED", None),
    ("[INFO] Pré-votação sem maioria (1/3) para o mandato 23: há um líder ativo ou o log deste nó está "
     "desatualizado. Mandato mantido em 22.", "PRE_VOTE_LOST", None),
    ("[WARN] Renunciando à liderança: sem resposta da maioria do cluster há mais de 15000 ms (check-quorum).",
     "QUORUM_LOST", None),
])
def test_classify_real_messages(line, kind, term):
    det = classify(line)
    assert det is not None
    assert det.kind == kind
    assert det.term == term


def test_ordinary_lines_are_not_events():
    assert classify("[INFO] Voto concedido para node-2 no mandato 4.") is None
    assert classify("[INFO] Pré-voto concedido para node-2 (mandato proposto 23).") is None
    assert classify("[DEBUG] Voto negado para node-2: canVote=true, logUpToDate=false.") is None
    assert classify("[DEBUG] Scheduler de heartbeats iniciado (intervalo: 5000 ms).") is None


def test_level():
    assert level_of("2026-10-14 10:00:00.123 [WARN] [node-1] x") == "WARN"
    assert level_of("sem nível") is None
