"""Leitura e validação do config.yaml do painel."""

from __future__ import annotations

import ipaddress
import os
from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass(frozen=True)
class NodeDef:
    id: str
    host: str
    arbiter: bool


@dataclass(frozen=True)
class Config:
    listen_host: str = "127.0.0.1"
    listen_port: int = 8000
    poll_interval_s: float = 1.0
    experiment_timeout_s: float = 180.0
    ssh_user: str = "ubuntu"
    ssh_key_file: str = "~/.ssh/id_ed25519"
    ssh_strict_host_keys: bool = True
    grpc_port: int = 9090
    grpc_timeout_s: float = 1.5
    pg_port: int = 5432
    pg_database: str = "postgres"
    pg_user: str = "raft_monitor"
    pg_password_env: str = "HA_PG_PASSWORD"
    pg_timeout_s: float = 2.0
    nodes: tuple[NodeDef, ...] = field(default_factory=tuple)

    @property
    def pg_password(self) -> str | None:
        return os.environ.get(self.pg_password_env)

    def node(self, node_id: str) -> NodeDef | None:
        return next((n for n in self.nodes if n.id == node_id), None)

    def peers_of(self, node_id: str) -> list[NodeDef]:
        return [n for n in self.nodes if n.id != node_id]


def load(path: str | Path) -> Config:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    return from_dict(raw)


def from_dict(raw: dict) -> Config:
    listen = raw.get("listen", {})
    ssh = raw.get("ssh", {})
    grpc = raw.get("grpc", {})
    pg = raw.get("postgres", {})

    nodes = []
    for n in raw.get("nodes", []):
        if not n.get("id") or not n.get("host"):
            raise ValueError("Cada nó precisa de 'id' e 'host'.")
        # O host vira argumento do script de partição nas VMs, então só IPs são aceitos.
        ipaddress.ip_address(n["host"])
        nodes.append(NodeDef(id=str(n["id"]), host=str(n["host"]), arbiter=bool(n.get("arbiter", False))))
    if not nodes:
        raise ValueError("A lista 'nodes' não pode ser vazia.")
    if len({n.id for n in nodes}) != len(nodes):
        raise ValueError("IDs de nó repetidos em 'nodes'.")

    cfg = Config(
        listen_host=str(listen.get("host", "127.0.0.1")),
        listen_port=int(listen.get("port", 8000)),
        poll_interval_s=float(raw.get("poll-interval-s", 1.0)),
        experiment_timeout_s=float(raw.get("experiment-timeout-s", 180)),
        ssh_user=str(ssh.get("user", "ubuntu")),
        ssh_key_file=str(ssh.get("key-file", "~/.ssh/id_ed25519")),
        ssh_strict_host_keys=bool(ssh.get("strict-host-keys", True)),
        grpc_port=int(grpc.get("port", 9090)),
        grpc_timeout_s=float(grpc.get("timeout-s", 1.5)),
        pg_port=int(pg.get("port", 5432)),
        pg_database=str(pg.get("database", "postgres")),
        pg_user=str(pg.get("user", "raft_monitor")),
        pg_password_env=str(pg.get("password-env", "HA_PG_PASSWORD")),
        pg_timeout_s=float(pg.get("timeout-s", 2.0)),
        nodes=tuple(nodes),
    )
    if cfg.listen_host not in ("127.0.0.1", "localhost", "::1"):
        raise ValueError(
            "listen.host deve ser 127.0.0.1: o painel executa comandos com sudo nas VMs "
            "e não pode ficar exposto na rede."
        )
    return cfg
