from pathlib import Path

import pytest

from ha_dashboard import actions, config
from ha_dashboard.backend_real import parse_partition_status

ROOT = Path(__file__).resolve().parents[1]


def cfg():
    return config.load(ROOT / "config.example.yaml")


def test_example_config_loads():
    c = cfg()
    assert [n.id for n in c.nodes] == ["node-1", "node-2", "node-3"]
    assert c.node("node-3").arbiter
    assert c.listen_host == "127.0.0.1"


def test_refuses_public_bind():
    with pytest.raises(ValueError, match="127.0.0.1"):
        config.from_dict({"listen": {"host": "0.0.0.0"}, "nodes": [{"id": "a", "host": "10.0.0.1"}]})


def test_node_host_must_be_ip():
    with pytest.raises(ValueError):
        config.from_dict({"nodes": [{"id": "a", "host": "x; rm -rf /"}]})


def test_partition_isolates_from_peers_only():
    c = cfg()
    argv = actions.command_for("partition-on", c.node("node-1"), c)
    assert argv == ["sudo", "-n", "/usr/local/sbin/ha-partition", "isolate", "192.168.56.102", "192.168.56.103"]


def test_pg_actions_rejected_on_arbiter():
    c = cfg()
    with pytest.raises(ValueError):
        actions.command_for("pg-stop", c.node("node-3"), c)


def test_unknown_action():
    c = cfg()
    with pytest.raises(KeyError):
        actions.command_for("rm-rf", c.node("node-1"), c)


def test_sudoers_covers_every_command():
    """Cada comando do catálogo precisa estar liberado no sudoers gerado pelo setup."""
    setup = (ROOT / "vm" / "setup-vm-access.sh").read_text(encoding="utf-8")
    c = cfg()
    for key in actions.CATALOG:
        argv = actions.command_for(key, c.node("node-1"), c)
        cmd = argv[2:] if argv[2] != actions.PARTITION else argv[2:3]
        assert " ".join(cmd) in setup.replace("${PARTITION_BIN}", actions.PARTITION), key


@pytest.mark.parametrize("text, expected", [
    ("connected\n", (False, [])),
    ("isolated 192.168.56.102 192.168.56.103\n", (True, ["192.168.56.102", "192.168.56.103"])),
    ("sudo: a password is required\n", (None, [])),
    ("", (None, [])),
])
def test_parse_partition_status(text, expected):
    assert parse_partition_status(text) == expected
