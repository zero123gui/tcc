"""Estado central do painel: polling, logs, experimentos e difusão via WebSocket."""

from __future__ import annotations

import asyncio
import collections
import json
import logging
import time
from pathlib import Path
from typing import Protocol

from fastapi import WebSocket

from . import actions, events, summary
from .config import Config, NodeDef
from .experiments import Experiment, ExperimentLog
from .models import LogLine, NodeStatus

log = logging.getLogger("ha_dashboard")

LOG_BUFFER = 600  # linhas guardadas por nó para quem abrir o painel depois


class Backend(Protocol):
    async def start(self, on_line) -> None: ...
    async def close(self) -> None: ...
    async def poll(self, node: NodeDef) -> NodeStatus: ...
    async def run_action(self, node: NodeDef, action: str, argv: list[str]) -> dict: ...


class Hub:
    def __init__(self, cfg: Config, backend: Backend, results_csv: Path, mode: str):
        self.cfg = cfg
        self.backend = backend
        self.mode = mode
        self.statuses: dict[str, NodeStatus] = {
            n.id: NodeStatus(id=n.id, host=n.host, arbiter=n.arbiter) for n in cfg.nodes
        }
        self.logs: dict[str, collections.deque[LogLine]] = {
            n.id: collections.deque(maxlen=LOG_BUFFER) for n in cfg.nodes
        }
        self.experiments = ExperimentLog(results_csv, cfg.experiment_timeout_s)
        self.clients: set[WebSocket] = set()
        self._tasks: list[asyncio.Task] = []
        self._action_lock = asyncio.Lock()

    # ------------------------------------------------------------------ ciclo de vida

    async def start(self) -> None:
        await self.backend.start(self.on_line)
        self._tasks.append(asyncio.create_task(self._poll_loop()))

    async def stop(self) -> None:
        for t in self._tasks:
            t.cancel()
        await self.backend.close()

    # ------------------------------------------------------------------ status

    async def _poll_loop(self) -> None:
        while True:
            started = time.monotonic()
            try:
                results = await asyncio.gather(*(self.backend.poll(n) for n in self.cfg.nodes))
                now = time.time()
                for st in results:
                    self.statuses[st.id] = st
                exp = self.experiments.active
                if exp:
                    exp.on_status(results, now)
                    exp.tick(now)
                    await self._after_experiment_update(exp)
                await self.broadcast({"type": "status", **self.status_payload()})
            except Exception:
                log.exception("Erro no ciclo de polling")
            await asyncio.sleep(max(0.0, self.cfg.poll_interval_s - (time.monotonic() - started)))

    def status_payload(self) -> dict:
        statuses = list(self.statuses.values())
        return {"nodes": [s.to_dict() for s in statuses], "cluster": summary.summarize(statuses)}

    # ------------------------------------------------------------------ logs

    async def on_line(self, node_id: str, text: str, history: bool) -> None:
        now = time.time()
        det = events.classify(text)
        line = LogLine(node=node_id, host_ts=now, text=text, kind=det.kind if det else None,
                       level=events.level_of(text), history=history)
        if text.startswith("[painel]"):
            line.kind = "PANEL"
        self.logs[node_id].append(line)
        if history:
            return  # o backlog é enviado no snapshot inicial de cada cliente
        await self.broadcast({"type": "log", "line": line.to_dict()})
        exp = self.experiments.active
        if exp and det:
            exp.on_log(node_id, det.kind, now, det.term)
            await self._after_experiment_update(exp)

    # ------------------------------------------------------------------ ações e experimentos

    async def run_action(self, node_id: str, action: str) -> dict:
        node = self.cfg.node(node_id)
        if node is None:
            raise KeyError(node_id)
        argv = actions.command_for(action, node, self.cfg)  # valida ação e árbitro

        async with self._action_lock:
            statuses = list(self.statuses.values())
            leader = summary.current_leader(statuses)
            primaries = summary.current_primaries(statuses)
            primary = primaries[0] if len(primaries) == 1 else None
            now = time.time()

            exp = self.experiments.active
            started = None
            if exp:
                exp.on_action(node_id, action, now)
            elif actions.CATALOG[action].disruptive and node_id in {
                leader.id if leader else None, primary.id if primary else None
            }:
                started = self.experiments.start(
                    action, node_id, now,
                    old_leader=leader.id if leader else None,
                    old_primary=primary.id if primary else None,
                )

            await self.on_line(node_id, f"[painel] executando: {' '.join(argv)}", False)
            try:
                result = await self.backend.run_action(node, action, argv)
            except Exception as e:
                result = {"exit": -1, "stdout": "", "stderr": str(e) or type(e).__name__}
            msg = f"[painel] {action} terminou com código {result['exit']}"
            if result.get("stderr"):
                msg += f": {result['stderr']}"
            await self.on_line(node_id, msg, False)

            if started and result["exit"] != 0:
                started.notes.append("ação falhou; experimento descartado")
                started.finish(time.time(), "ação falhou")
            if started or exp:
                await self._after_experiment_update(started or exp)
        return {"action": action, "node": node_id, **result,
                "experiment": started.id if started else None}

    async def finish_experiment(self, exp_id: int) -> None:
        exp = next((e for e in self.experiments.history if e.id == exp_id), None)
        if exp is None:
            raise KeyError(exp_id)
        if exp.active:
            exp.finish(time.time(), "encerrado manualmente")
            await self._after_experiment_update(exp)

    async def _after_experiment_update(self, exp: Experiment) -> None:
        if not exp.active and not exp.persisted:
            exp.persisted = True
            if exp.outcome != "ação falhou":
                self.experiments.persist(exp)
        await self.broadcast({"type": "experiment", "experiment": exp.to_dict()})

    # ------------------------------------------------------------------ WebSocket

    def snapshot(self) -> dict:
        return {
            "type": "snapshot",
            "mode": self.mode,
            "actions": actions.catalog_for_ui(),
            "highlight": sorted(events.HIGHLIGHT),
            **self.status_payload(),
            "logs": {nid: [l.to_dict() for l in buf] for nid, buf in self.logs.items()},
            "experiments": [e.to_dict() for e in self.experiments.history],
            "results_csv": str(self.experiments.csv_path),
        }

    async def broadcast(self, message: dict) -> None:
        if not self.clients:
            return
        data = json.dumps(message, ensure_ascii=False)
        dead = []
        for ws in list(self.clients):
            try:
                await ws.send_text(data)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.clients.discard(ws)
