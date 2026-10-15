"""Backend que fala com as VMs reais: gRPC (status Raft), PostgreSQL (direto
do host) e SSH (systemd, partição, logs e ações)."""

from __future__ import annotations

import asyncio
import os
import shlex
import time
from typing import Awaitable, Callable

import asyncpg
import asyncssh
import grpc

from . import protos
from .config import Config, NodeDef
from .models import NodeStatus

LineCallback = Callable[[str, str, bool], Awaitable[None]]  # (node_id, texto, histórico?)

JOURNAL_HISTORY = "journalctl -u raft-ha-manager -n 200 -o cat --no-pager"
JOURNAL_FOLLOW = "journalctl -u raft-ha-manager -f -n 0 -o cat --no-pager"
# Uma única ida por SSH a cada ciclo de polling
STATUS_CMD = (
    "systemctl is-active raft-ha-manager postgresql; echo ---; "
    "sudo -n /usr/local/sbin/ha-partition status 2>&1 || true"
)


class RealBackend:
    def __init__(self, cfg: Config):
        self.cfg = cfg
        self._pb2, self._pb2_grpc = protos.load()
        self._ssh: dict[str, asyncssh.SSHClientConnection] = {}
        self._ssh_locks = {n.id: asyncio.Lock() for n in cfg.nodes}
        # Canais grpc.aio ficam presos ao loop em que são criados, e o uvicorn roda em
        # outro loop: por isso são criados sob demanda, já dentro do loop em execução.
        self._channels: dict[str, grpc.aio.Channel] = {}
        self._log_tasks: list[asyncio.Task] = []
        self._pg_auth_blocked_until = 0.0

    def _channel(self, node: NodeDef) -> grpc.aio.Channel:
        ch = self._channels.get(node.id)
        if ch is None:
            ch = grpc.aio.insecure_channel(f"{node.host}:{self.cfg.grpc_port}")
            self._channels[node.id] = ch
        return ch

    # ------------------------------------------------------------------ ciclo de vida

    async def start(self, on_line: LineCallback) -> None:
        for node in self.cfg.nodes:
            self._log_tasks.append(asyncio.create_task(self._follow_logs(node, on_line)))

    async def close(self) -> None:
        for t in self._log_tasks:
            t.cancel()
        for conn in self._ssh.values():
            conn.close()
        for ch in self._channels.values():
            await ch.close()

    # ------------------------------------------------------------------ SSH

    async def _conn(self, node: NodeDef) -> asyncssh.SSHClientConnection:
        async with self._ssh_locks[node.id]:
            conn = self._ssh.get(node.id)
            if conn is not None and not conn.is_closed():
                return conn
            key = os.path.expanduser(self.cfg.ssh_key_file)
            if not os.path.exists(key):
                raise FileNotFoundError(f"chave SSH {key} não existe (gere com ssh-keygen -t ed25519; ver README)")
            options = {
                "username": self.cfg.ssh_user,
                "client_keys": [key],
                "connect_timeout": 4,
                "keepalive_interval": 5,
                "keepalive_count_max": 2,
            }
            if not self.cfg.ssh_strict_host_keys:
                options["known_hosts"] = None
            conn = await asyncssh.connect(node.host, **options)
            self._ssh[node.id] = conn
            return conn

    async def run_action(self, node: NodeDef, action: str, argv: list[str]) -> dict:
        conn = await self._conn(node)
        result = await asyncio.wait_for(conn.run(shlex.join(argv), check=False), timeout=60)
        return {
            "exit": result.exit_status,
            "stdout": str(result.stdout or "").strip(),
            "stderr": str(result.stderr or "").strip(),
        }

    async def _follow_logs(self, node: NodeDef, on_line: LineCallback) -> None:
        have_history = False
        while True:
            try:
                conn = await self._conn(node)
                if not have_history:
                    hist = await conn.run(JOURNAL_HISTORY, check=False)
                    for line in str(hist.stdout or "").splitlines():
                        await on_line(node.id, line, True)
                    have_history = True
                await on_line(node.id, "[painel] acompanhando o journal do raft-ha-manager", False)
                async with conn.create_process(JOURNAL_FOLLOW) as proc:
                    async for line in proc.stdout:
                        await on_line(node.id, line.rstrip("\r\n"), False)
                await on_line(node.id, "[painel] journalctl encerrou; reconectando", False)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # rede, autenticação, VM desligada
                await on_line(node.id, f"[painel] sem conexão de log: {_short(e)}", False)
            await asyncio.sleep(3)

    # ------------------------------------------------------------------ status

    async def poll(self, node: NodeDef) -> NodeStatus:
        st = NodeStatus(id=node.id, host=node.host, arbiter=node.arbiter)
        await asyncio.gather(self._poll_grpc(node, st), self._poll_pg(node, st), self._poll_ssh(node, st))
        st.updated_at = time.time()
        return st

    async def _poll_grpc(self, node: NodeDef, st: NodeStatus) -> None:
        stub = self._pb2_grpc.ClusterManagementServiceStub(self._channel(node))
        try:
            resp = await stub.GetClusterStatus(self._pb2.ClusterStatusRequest(), timeout=self.cfg.grpc_timeout_s)
        except grpc.aio.AioRpcError as e:
            st.grpc_error = e.code().name
            return
        st.grpc_ok = True
        st.term = resp.current_term
        st.commit_index = resp.commit_index
        st.leader_id = resp.leader_id or None
        self_info = next((n for n in resp.nodes if n.node_id == node.id), None)
        st.role = self_info.raft_role if self_info else None

    async def _poll_pg(self, node: NodeDef, st: NodeStatus) -> None:
        if node.arbiter:
            st.pg_state = "N/A"
            return
        password = self.cfg.pg_password
        if password is None:
            st.pg_state = "SEM_CREDENCIAL"
            return
        # Senha recusada: não insiste a cada ciclo (evita encher o log do banco)
        if time.monotonic() < self._pg_auth_blocked_until:
            st.pg_state = "SENHA_INVALIDA"
            st.pg_error = "senha do raft_monitor recusada pelo PostgreSQL"
            return
        try:
            conn = await asyncpg.connect(
                host=node.host, port=self.cfg.pg_port, user=self.cfg.pg_user,
                password=password, database=self.cfg.pg_database, timeout=self.cfg.pg_timeout_s,
            )
        except asyncpg.InvalidPasswordError:
            self._pg_auth_blocked_until = time.monotonic() + 30
            st.pg_state = "SENHA_INVALIDA"
            st.pg_error = "senha do raft_monitor recusada pelo PostgreSQL"
            return
        except (OSError, asyncio.TimeoutError, asyncpg.CannotConnectNowError) as e:
            st.pg_state, st.pg_error = "DOWN", _short(e)
            return
        except Exception as e:
            st.pg_state, st.pg_error = "ERRO", _short(e)
            return
        try:
            in_recovery = await conn.fetchval("SELECT pg_is_in_recovery()")
            if in_recovery:
                st.pg_state = "STANDBY"
                row = await conn.fetchrow("SELECT status, sender_host FROM pg_stat_wal_receiver")
                if row:
                    st.upstream_status, st.upstream_host = row["status"], row["sender_host"]
            else:
                st.pg_state = "PRIMARY"
                rows = await conn.fetch(
                    "SELECT client_addr::text AS addr, state, application_name FROM pg_stat_replication"
                )
                st.replicas = [
                    {"addr": (r["addr"] or "").split("/")[0], "state": r["state"], "app": r["application_name"]}
                    for r in rows
                ]
        except Exception as e:
            st.pg_state, st.pg_error = "ERRO", _short(e)
        finally:
            await conn.close()

    async def _poll_ssh(self, node: NodeDef, st: NodeStatus) -> None:
        try:
            conn = await self._conn(node)
            result = await asyncio.wait_for(conn.run(STATUS_CMD, check=False), timeout=5)
        except Exception as e:
            st.ssh_error = _short(e)
            stale = self._ssh.pop(node.id, None)
            if stale is not None:
                stale.close()
            return
        st.ssh_ok = True
        units, _, partition = str(result.stdout or "").partition("---")
        states = units.split()
        st.services = {"raft-ha-manager": states[0] if states else "?"}
        if not node.arbiter:
            st.services["postgresql"] = states[1] if len(states) > 1 else "?"
        st.partitioned, st.partition_peers = parse_partition_status(partition)


def parse_partition_status(text: str) -> tuple[bool | None, list[str]]:
    """Interpreta a saída de 'ha-partition status'. None = script ausente ou sem sudo."""
    words = text.split()
    if not words:
        return None, []
    if words[0] == "connected":
        return False, []
    if words[0] == "isolated":
        return True, words[1:]
    return None, []


def _short(e: BaseException) -> str:
    msg = str(e) or type(e).__name__
    return msg if len(msg) <= 160 else msg[:157] + "..."
