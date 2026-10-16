"""Fluxo completo com o cluster simulado: ação → logs → status → experimento → CSV."""

import asyncio
import csv
import dataclasses
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from ha_dashboard import backend_demo, config
from ha_dashboard.hub import Hub
from ha_dashboard.server import create_app

ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def fast_demo(monkeypatch):
    monkeypatch.setattr(backend_demo, "HEARTBEAT_S", 0.05)
    monkeypatch.setattr(backend_demo, "ELECTION_MIN_S", 0.4)
    monkeypatch.setattr(backend_demo, "ELECTION_MAX_S", 0.6)
    monkeypatch.setattr(backend_demo, "PG_FAIL_DETECT_S", 0.3)
    monkeypatch.setattr(backend_demo, "PROMOTION_S", 0.2)
    monkeypatch.setattr(backend_demo, "HEALING_S", 0.3)
    cfg = dataclasses.replace(config.load(ROOT / "config.example.yaml"), poll_interval_s=0.1)
    return cfg


async def wait_for(cond, timeout=8.0):
    for _ in range(int(timeout / 0.05)):
        if cond():
            return
        await asyncio.sleep(0.05)
    raise AssertionError("condição não atingida a tempo")


async def test_pg_stop_on_leader_fails_over(fast_demo, tmp_path):
    hub = Hub(fast_demo, backend_demo.DemoBackend(fast_demo), tmp_path / "exp.csv", "demo")
    await hub.start()
    try:
        await wait_for(lambda: hub.statuses["node-1"].role == "LEADER")
        res = await hub.run_action("node-1", "pg-stop")
        assert res["exit"] == 0 and res["experiment"] is not None

        exp = hub.experiments.active
        await wait_for(lambda: not exp.active)
        assert exp.outcome == "concluído"
        assert exp.new_leader == "node-2" and exp.new_primary == "node-2"
        marks = exp.to_dict()["marks"]
        assert marks["pg_unhealthy"] <= marks["step_down"] <= marks["leader_elected"] <= marks["primary_visible"]

        rows = list(csv.DictReader((tmp_path / "exp.csv").open(encoding="utf-8")))
        assert rows[0]["novo_lider"] == "node-2"
        kinds = {l.kind for l in hub.logs["node-2"]}
        assert {"ELECTION", "LEADER", "PROMOTED"} <= kinds
    finally:
        await hub.stop()


async def test_raft_stop_then_restart_heals_rogue_primary(fast_demo, tmp_path):
    hub = Hub(fast_demo, backend_demo.DemoBackend(fast_demo), tmp_path / "exp.csv", "demo")
    await hub.start()
    try:
        await wait_for(lambda: hub.statuses["node-1"].role == "LEADER")
        await hub.run_action("node-1", "raft-stop")
        exp = hub.experiments.active
        await wait_for(lambda: not exp.active)
        # Só o agente parou: o PostgreSQL do node-1 continua PRIMARY → dois primaries
        assert hub.statuses["node-1"].pg_state == "PRIMARY"
        assert "SPLIT-BRAIN" in " ".join(a["text"] for a in hub.status_payload()["cluster"]["alerts"])

        await hub.run_action("node-1", "raft-start")
        await wait_for(lambda: any(l.kind == "HEALED" for l in hub.logs["node-1"]))
        await wait_for(lambda: hub.statuses["node-1"].pg_state == "STANDBY")
    finally:
        await hub.stop()


async def test_partition_heals_only_the_isolated_old_primary(fast_demo, tmp_path):
    hub = Hub(fast_demo, backend_demo.DemoBackend(fast_demo), tmp_path / "exp.csv", "demo")
    await hub.start()
    try:
        await wait_for(lambda: hub.statuses["node-1"].role == "LEADER")
        await hub.run_action("node-1", "partition-on")
        exp = hub.experiments.active
        await wait_for(lambda: hub.statuses["node-2"].pg_state == "PRIMARY")
        # Check-quorum: o líder isolado renuncia em vez de continuar se declarando líder
        await wait_for(lambda: hub.statuses["node-1"].role != "LEADER")
        assert any(l.kind == "QUORUM_LOST" for l in hub.logs["node-1"])
        assert exp.seconds("step_down") is not None

        await hub.run_action("node-1", "partition-off")
        await wait_for(lambda: not exp.active)
        assert exp.outcome == "concluído"
        await wait_for(lambda: hub.statuses["node-1"].pg_state == "STANDBY")
        assert hub.statuses["node-2"].role == "LEADER" and hub.statuses["node-2"].pg_state == "PRIMARY"
        assert not any(l.kind in ("SPLIT_BRAIN", "HEALED") for l in hub.logs["node-2"])
        assert not any("inesperado" in n for n in exp.notes)
    finally:
        await hub.stop()


async def test_rejoining_node_does_not_depose_the_leader(fast_demo, tmp_path):
    """Item 1: um nó que volta depois de ficar fora não derruba o líder (pré-votação)."""
    hub = Hub(fast_demo, backend_demo.DemoBackend(fast_demo), tmp_path / "exp.csv", "demo")
    await hub.start()
    try:
        await wait_for(lambda: hub.statuses["node-1"].role == "LEADER")
        term = hub.statuses["node-1"].term
        await hub.run_action("node-2", "partition-on")
        # Isolado, o node-2 tenta se candidatar e a pré-votação não passa
        await wait_for(lambda: any(l.kind == "PRE_VOTE_LOST" for l in hub.logs["node-2"]))
        await hub.run_action("node-2", "partition-off")
        await wait_for(lambda: hub.statuses["node-2"].leader_id == "node-1")
        await asyncio.sleep(1.0)  # mais de uma janela de eleição
        assert hub.statuses["node-1"].role == "LEADER"
        assert hub.statuses["node-1"].term == term
        assert not any(l.kind == "STEP_DOWN" for l in hub.logs["node-1"] if not l.history)
    finally:
        await hub.stop()


def test_http_guards(fast_demo, tmp_path):
    hub = Hub(fast_demo, backend_demo.DemoBackend(fast_demo), tmp_path / "exp.csv", "demo")
    with TestClient(create_app(hub), base_url="http://127.0.0.1:8000") as client:
        assert client.get("/").status_code == 200
        # JS/CSS antigos em cache já fizeram o botão "Parar" parar de responder
        assert client.get("/static/app.js").headers["cache-control"] == "no-store"
        assert client.get("/api/state").json()["mode"] == "demo"

        # Sem o cabeçalho, uma página de outra origem não consegue disparar ações
        assert client.post("/api/nodes/node-2/actions/raft-stop").status_code == 403
        # DNS rebinding: Host diferente do loopback
        assert client.get("/api/state", headers={"host": "evil.example:8000"}).status_code == 403

        h = {"X-HA-Dashboard": "1"}
        assert client.post("/api/nodes/node-9/actions/raft-stop", headers=h).status_code == 404
        assert client.post("/api/nodes/node-3/actions/pg-stop", headers=h).status_code == 400
        assert client.post("/api/nodes/node-2/actions/raft-stop", headers=h).json()["exit"] == 0

        # O TestClient usa Host "testserver" em WebSockets; o navegador envia o host real
        local = {"host": "127.0.0.1:8000"}
        with client.websocket_connect("/ws", headers={**local, "origin": "http://127.0.0.1:8000"}) as ws:
            assert ws.receive_json()["type"] == "snapshot"
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect("/ws", headers={**local, "origin": "http://evil.example"}) as ws:
                ws.receive_json()
