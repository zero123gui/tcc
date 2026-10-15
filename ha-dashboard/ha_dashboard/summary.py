"""Visão consolidada do cluster a partir do status individual de cada nó.

GetClusterStatus só informa sobre o próprio nó, então o painel junta as três
respostas e aponta inconsistências (dois líderes, dois primaries, standby
replicando do primary errado etc.).
"""

from __future__ import annotations

from .models import NodeStatus


def current_leader(statuses: list[NodeStatus]) -> NodeStatus | None:
    leaders = [s for s in statuses if s.grpc_ok and s.role == "LEADER"]
    return max(leaders, key=lambda s: s.term or 0) if leaders else None


def current_primaries(statuses: list[NodeStatus]) -> list[NodeStatus]:
    return [s for s in statuses if s.pg_state == "PRIMARY"]


def summarize(statuses: list[NodeStatus]) -> dict:
    alerts: list[dict] = []
    by_host = {s.host: s for s in statuses}

    leaders = [s for s in statuses if s.grpc_ok and s.role == "LEADER"]
    leader = current_leader(statuses)
    primaries = current_primaries(statuses)
    primary = primaries[0] if len(primaries) == 1 else None

    if len(leaders) > 1:
        desc = ", ".join(f"{s.id} (mandato {s.term})" for s in leaders)
        alerts.append(_alert("warn", f"Mais de um nó se declara LEADER: {desc}. "
                                     "O de mandato menor está isolado ou ainda não recebeu o novo mandato."))
    if not leaders and any(s.grpc_ok for s in statuses):
        alerts.append(_alert("warn", "Nenhum nó alcançável é LEADER (eleição em andamento?)."))

    if len(primaries) > 1:
        alerts.append(_alert("error", "SPLIT-BRAIN no PostgreSQL: há mais de um PRIMARY ("
                                      + ", ".join(s.id for s in primaries) + ")."))
    elif not primaries and any(s.pg_state in ("STANDBY", "DOWN") for s in statuses):
        alerts.append(_alert("error", "Nenhum PostgreSQL PRIMARY disponível."))

    if leader and not leader.arbiter and primary and primary.id != leader.id:
        alerts.append(_alert("warn", f"Líder Raft é {leader.id}, mas o PostgreSQL PRIMARY está em {primary.id}."))

    for s in statuses:
        if s.pg_state == "STANDBY" and primary:
            if not s.upstream_host:
                alerts.append(_alert("warn", f"{s.id} é STANDBY mas não está recebendo WAL de nenhum primary."))
            elif s.upstream_host != primary.host:
                origin = by_host.get(s.upstream_host)
                alerts.append(_alert(
                    "warn",
                    f"{s.id} replica de {origin.id if origin else s.upstream_host}, mas o PRIMARY atual é "
                    f"{primary.id}. O agente não reaponta standbys após o failover "
                    "(FailoverExecutor.reconfigureStandby não é chamado).",
                ))
        if s.partitioned:
            alerts.append(_alert("info", f"{s.id} está isolado da rede do cluster (simulação de partição)."))
        if not s.grpc_ok and not s.ssh_ok:
            alerts.append(_alert("error", f"{s.id} não responde (gRPC e SSH)."))

    if any(s.pg_state == "SENHA_INVALIDA" for s in statuses):
        alerts.append(_alert("error", "O PostgreSQL recusou a senha do usuário raft_monitor. Reinicie o painel "
                                      "com a senha correta (tabela de credenciais do scripts/README.md)."))
    if any(s.pg_state == "SEM_CREDENCIAL" for s in statuses):
        alerts.append(_alert("info", "Senha do PostgreSQL não definida: defina a variável de ambiente "
                                     "indicada em postgres.password-env para ver o estado dos bancos."))

    return {
        "leader": leader.id if leader else None,
        "term": leader.term if leader else max((s.term or 0 for s in statuses), default=None),
        "primary": primary.id if primary else None,
        "alerts": alerts,
    }


def _alert(level: str, text: str) -> dict:
    return {"level": level, "text": text}
