"""Aplicação FastAPI: página única, API REST para ações e WebSocket para eventos."""

from __future__ import annotations

from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from .hub import Hub

STATIC = Path(__file__).parent / "static"
LOCAL_HOSTS = {"127.0.0.1", "localhost", "[::1]"}
# Exigido em toda requisição que altera estado. Navegadores não enviam cabeçalhos
# personalizados entre origens sem preflight CORS (que o painel não autoriza), então
# uma página maliciosa aberta em outra aba não consegue disparar ações.
ACTION_HEADER = "x-ha-dashboard"


def _hostname(value: str | None) -> str | None:
    if not value:
        return None
    value = value.split("://", 1)[-1]
    if value.startswith("["):
        return value.split("]")[0] + "]"
    return value.split(":")[0]


def create_app(hub: Hub) -> FastAPI:
    @asynccontextmanager
    async def lifespan(_: FastAPI):
        await hub.start()
        yield
        await hub.stop()

    app = FastAPI(title="ha-dashboard", lifespan=lifespan, docs_url=None, redoc_url=None)

    @app.middleware("http")
    async def only_local(request: Request, call_next):
        # Bloqueia DNS rebinding: o Host precisa ser o próprio loopback.
        if _hostname(request.headers.get("host")) not in LOCAL_HOSTS:
            return JSONResponse({"detail": "Host não permitido"}, status_code=403)
        if request.method not in ("GET", "HEAD") and request.headers.get(ACTION_HEADER) != "1":
            return JSONResponse({"detail": "Cabeçalho X-HA-Dashboard ausente"}, status_code=403)
        return await call_next(request)

    app.mount("/static", StaticFiles(directory=STATIC), name="static")

    @app.get("/")
    async def index():
        return FileResponse(STATIC / "index.html", headers={"Cache-Control": "no-store"})

    @app.get("/api/state")
    async def state():
        return hub.snapshot()

    @app.post("/api/nodes/{node_id}/actions/{action}")
    async def run_action(node_id: str, action: str):
        try:
            return await hub.run_action(node_id, action)
        except KeyError:
            raise HTTPException(404, "Nó ou ação desconhecidos")
        except ValueError as e:
            raise HTTPException(400, str(e))

    @app.post("/api/experiments/{exp_id}/finish")
    async def finish_experiment(exp_id: int):
        try:
            await hub.finish_experiment(exp_id)
        except KeyError:
            raise HTTPException(404, "Experimento desconhecido")
        return {"ok": True}

    @app.get("/api/experiments.csv")
    async def experiments_csv():
        path = hub.experiments.csv_path
        if not path.exists():
            raise HTTPException(404, "Nenhum experimento concluído ainda")
        return FileResponse(path, media_type="text/csv", filename=path.name)

    @app.websocket("/ws")
    async def ws(websocket: WebSocket):
        # WebSockets não seguem CORS: confere a origem explicitamente.
        if (_hostname(websocket.headers.get("origin")) not in LOCAL_HOSTS
                or _hostname(websocket.headers.get("host")) not in LOCAL_HOSTS):
            await websocket.close(code=1008)
            return
        await websocket.accept()
        await websocket.send_json(hub.snapshot())
        hub.clients.add(websocket)
        try:
            while True:
                await websocket.receive_text()  # o cliente não envia nada; só mantém a conexão
        except WebSocketDisconnect:
            pass
        finally:
            hub.clients.discard(websocket)

    return app
