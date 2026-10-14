from ha_dashboard.models import NodeStatus
from ha_dashboard.summary import summarize


def node(i, **kw):
    base = dict(id=f"node-{i}", host=f"192.168.56.10{i}", arbiter=(i == 3), grpc_ok=True, ssh_ok=True,
                role="FOLLOWER", term=2, pg_state="N/A" if i == 3 else "STANDBY")
    base.update(kw)
    return NodeStatus(**base)


def texts(s):
    return " | ".join(a["text"] for a in s["alerts"])


def test_healthy_cluster_has_no_alerts():
    s = summarize([
        node(1, role="LEADER", pg_state="PRIMARY", replicas=[{"addr": "192.168.56.102", "state": "streaming"}]),
        node(2, upstream_host="192.168.56.101", upstream_status="streaming"),
        node(3),
    ])
    assert s["leader"] == "node-1" and s["primary"] == "node-1" and s["term"] == 2
    assert s["alerts"] == []


def test_two_primaries_is_split_brain():
    s = summarize([node(1, pg_state="PRIMARY"), node(2, role="LEADER", term=3, pg_state="PRIMARY"), node(3)])
    assert s["primary"] is None
    assert "SPLIT-BRAIN" in texts(s)


def test_isolated_old_leader_reports_two_leaders():
    s = summarize([node(1, role="LEADER", term=2, pg_state="PRIMARY", partitioned=True),
                   node(2, role="LEADER", term=3, pg_state="STANDBY"), node(3)])
    assert s["leader"] == "node-2"   # o de maior mandato
    assert "Mais de um nó se declara LEADER" in texts(s)
    assert "isolado" in texts(s)


def test_standby_following_old_primary_is_flagged():
    s = summarize([
        node(1, pg_state="STANDBY", upstream_host=None),
        node(2, role="LEADER", term=3, pg_state="PRIMARY"),
        node(4, host="192.168.56.104", arbiter=False, pg_state="STANDBY", upstream_host="192.168.56.101",
             upstream_status="streaming"),
    ])
    t = texts(s)
    assert "node-1 é STANDBY mas não está recebendo WAL" in t
    assert "node-4 replica de node-1" in t and "reconfigureStandby" in t


def test_missing_password_is_reported_once_without_false_no_primary_alert():
    s = summarize([node(1, role="LEADER", pg_state="SEM_CREDENCIAL"), node(2, pg_state="SEM_CREDENCIAL"), node(3)])
    t = texts(s)
    assert t.count("Senha do PostgreSQL") == 1
    assert "Nenhum PostgreSQL PRIMARY" not in t


def test_unreachable_node():
    s = summarize([node(1, role="LEADER", pg_state="PRIMARY"), node(2, grpc_ok=False, ssh_ok=False, pg_state="DOWN"),
                   node(3)])
    assert "node-2 não responde" in texts(s)
