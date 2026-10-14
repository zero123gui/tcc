"""Estruturas de dados trocadas entre os backends, o hub e o navegador."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field


@dataclass
class NodeStatus:
    id: str
    host: str
    arbiter: bool

    # gRPC GetClusterStatus (visão do próprio nó)
    grpc_ok: bool = False
    role: str | None = None            # LEADER / FOLLOWER / CANDIDATE
    term: int | None = None
    leader_id: str | None = None
    commit_index: int | None = None
    grpc_error: str | None = None

    # PostgreSQL (consultado direto do host, como o PostgresHealthChecker faz)
    pg_state: str = "N/A"              # PRIMARY / STANDBY / DOWN / SEM_CREDENCIAL / N/A
    pg_error: str | None = None
    replicas: list[dict] = field(default_factory=list)   # no primary: pg_stat_replication
    upstream_host: str | None = None   # no standby: pg_stat_wal_receiver.sender_host
    upstream_status: str | None = None

    # systemd e partição (via SSH)
    ssh_ok: bool = False
    ssh_error: str | None = None
    services: dict[str, str] = field(default_factory=dict)
    partitioned: bool | None = None
    partition_peers: list[str] = field(default_factory=list)

    updated_at: float = 0.0

    def to_dict(self) -> dict:
        return asdict(self)


@dataclass
class LogLine:
    node: str
    host_ts: float          # relógio do Windows quando a linha chegou ao painel
    text: str
    kind: str | None = None  # evento detectado (ver events.py)
    level: str | None = None
    history: bool = False    # linha antiga (backlog), não alimenta experimentos

    def to_dict(self) -> dict:
        return asdict(self)
