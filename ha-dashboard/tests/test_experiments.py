import csv

from ha_dashboard.experiments import PROMOTED_GRACE_S, ExperimentLog
from ha_dashboard.models import NodeStatus


def status(node_id, pg_state):
    return NodeStatus(id=node_id, host=f"h-{node_id}", arbiter=False, pg_state=pg_state)


def test_raft_stop_on_leader_measures_each_milestone(tmp_path):
    log = ExperimentLog(tmp_path / "exp.csv", timeout_s=180)
    exp = log.start("raft-stop", "node-1", 100.0, old_leader="node-1", old_primary="node-1")

    exp.on_log("node-2", "ELECTION", 118.0, 3)
    exp.on_log("node-2", "ELECTION", 125.0, 4)          # segunda eleição não sobrescreve a primeira
    exp.on_log("node-2", "LEADER", 125.5, 4)
    exp.on_log("node-2", "PROMOTING", 125.6)
    exp.on_log("node-2", "PROMOTED", 127.0)
    assert exp.active
    # O antigo primary continua PRIMARY (só o agente parou): não conta como "novo primary"
    exp.on_status([status("node-1", "PRIMARY"), status("node-2", "STANDBY")], 126.0)
    assert exp.active
    exp.on_status([status("node-1", "PRIMARY"), status("node-2", "PRIMARY")], 127.4)

    assert not exp.active
    assert exp.outcome == "concluído"
    d = exp.to_dict()
    assert d["new_leader"] == "node-2" and d["new_term"] == 4 and d["new_primary"] == "node-2"
    assert d["marks"]["first_election"] == 18.0
    assert d["marks"]["leader_elected"] == 25.5
    assert d["marks"]["promoted"] == 27.0
    assert d["marks"]["primary_visible"] == 27.4


def test_waits_for_promotion_log_after_primary_is_visible(tmp_path):
    """Caso real do Exp. 1: o SQL vê o novo primary 0,5 s antes do log de confirmação."""
    exp = ExperimentLog(tmp_path / "e.csv", 180).start("raft-stop", "node-1", 0.0, "node-1", "node-1")
    exp.on_log("node-2", "ELECTION", 14.3, 5)
    exp.on_log("node-2", "LEADER", 14.6, 5)
    exp.on_log("node-2", "PROMOTING", 14.6)
    exp.on_status([status("node-1", "PRIMARY"), status("node-2", "PRIMARY")], 15.5)
    assert exp.active                       # antes: encerrava aqui e deixava "promoted" vazio
    assert exp.seconds("promoted") is None

    exp.tick(16.0)                          # ainda dentro da espera
    assert exp.active
    exp.on_log("node-2", "PROMOTED", 16.0)
    assert not exp.active
    assert exp.outcome == "concluído"
    assert exp.seconds("primary_visible") == 15.5
    assert exp.seconds("promoted") == 16.0
    assert exp.notes == []


def test_gives_up_waiting_for_promotion_log_after_grace(tmp_path):
    exp = ExperimentLog(tmp_path / "e.csv", 180).start("raft-stop", "node-1", 0.0, "node-1", "node-1")
    exp.on_log("node-2", "LEADER", 14.6, 5)
    exp.on_status([status("node-2", "PRIMARY")], 15.5)
    assert exp.active
    exp.tick(15.5 + PROMOTED_GRACE_S - 0.1)
    assert exp.active
    exp.tick(15.5 + PROMOTED_GRACE_S)
    assert not exp.active
    assert exp.outcome == "concluído"
    assert exp.seconds("promoted") is None
    assert any("confirmação da promoção" in n for n in exp.notes)


def test_leader_event_from_old_leader_is_ignored(tmp_path):
    exp = ExperimentLog(tmp_path / "e.csv", 180).start("pg-stop", "node-1", 0.0, "node-1", "node-1")
    exp.on_log("node-1", "LEADER", 1.0, 2)
    assert "leader_elected" not in exp.marks
    exp.on_log("node-1", "PG_UNHEALTHY", 9.0)
    exp.on_log("node-1", "STEP_DOWN", 9.1)
    assert exp.seconds("pg_unhealthy") == 9.0
    assert exp.seconds("step_down") == 9.1


def test_partition_waits_for_self_healing(tmp_path):
    exp = ExperimentLog(tmp_path / "e.csv", 180).start("partition-on", "node-1", 0.0, "node-1", "node-1")
    assert exp.timeout_s >= 600
    exp.on_log("node-2", "LEADER", 20.0, 3)
    exp.on_status([status("node-2", "PRIMARY")], 23.0)
    assert exp.active
    exp.on_action("node-1", "partition-off", 60.0)
    exp.on_log("node-2", "HEALED", 60.5)             # nó errado: vira observação, não encerra
    assert exp.active and any("inesperado em node-2" in n for n in exp.notes)
    exp.on_log("node-1", "SPLIT_BRAIN", 61.0)
    exp.on_log("node-1", "HEALED", 75.0)
    assert not exp.active
    assert exp.seconds("healed") == 75.0
    assert any("partition-off" in n for n in exp.notes)


def test_timeout_and_csv(tmp_path):
    path = tmp_path / "exp.csv"
    log = ExperimentLog(path, timeout_s=30)
    exp = log.start("raft-stop", "node-1", 0.0, "node-1", "node-1")
    exp.tick(10.0)
    assert exp.active
    exp.tick(30.0)
    assert exp.outcome == "timeout"
    log.persist(exp)
    log.persist(exp)

    rows = list(csv.DictReader(path.open(encoding="utf-8")))
    assert len(rows) == 2                      # cabeçalho escrito uma vez só
    assert rows[0]["cenario"] == "raft-stop"
    assert rows[0]["leader_elected_s"] == ""
    assert rows[0]["desfecho"] == "timeout"


def test_ids_continue_from_existing_csv(tmp_path):
    """Reiniciar o painel não pode repetir IDs já gravados no CSV."""
    path = tmp_path / "exp.csv"
    first = ExperimentLog(path, timeout_s=30)
    for _ in range(2):
        e = first.start("raft-stop", "node-1", 0.0, "node-1", "node-1")
        e.finish(1.0, "timeout")
        first.persist(e)

    second = ExperimentLog(path, timeout_s=30)   # painel reiniciado
    e = second.start("pg-stop", "node-2", 0.0, "node-2", "node-2")
    assert e.id == 3
    assert ExperimentLog(tmp_path / "novo.csv", 30).start("raft-stop", "n", 0.0, None, None).id == 1


def test_events_after_finish_are_ignored(tmp_path):
    exp = ExperimentLog(tmp_path / "e.csv", 180).start("raft-stop", "node-1", 0.0, "node-1", "node-1")
    exp.finish(5.0, "encerrado manualmente")
    exp.on_log("node-2", "LEADER", 6.0, 2)
    assert exp.marks == {}
