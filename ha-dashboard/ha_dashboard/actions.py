"""Lista fechada de comandos que o painel pode executar nas VMs.

Nenhum texto vindo do navegador entra nos comandos: o navegador só escolhe
uma chave deste catálogo. Os IPs da partição vêm do config.yaml (validados
como endereços IP em config.py).

Cada comando precisa de uma linha correspondente no sudoers instalado por
vm/setup-vm-access.sh.
"""

from __future__ import annotations

from dataclasses import dataclass

from .config import Config, NodeDef

SYSTEMCTL = "/usr/bin/systemctl"
PARTITION = "/usr/local/sbin/ha-partition"


@dataclass(frozen=True)
class Action:
    key: str
    label: str
    disruptive: bool      # pode provocar failover: pede confirmação e inicia um experimento
    needs_pg: bool        # não se aplica a árbitros


CATALOG: dict[str, Action] = {a.key: a for a in [
    Action("raft-stop", "Parar raft-ha-manager", disruptive=True, needs_pg=False),
    Action("raft-start", "Iniciar raft-ha-manager", disruptive=False, needs_pg=False),
    Action("pg-stop", "Parar PostgreSQL", disruptive=True, needs_pg=True),
    Action("pg-start", "Iniciar PostgreSQL", disruptive=False, needs_pg=True),
    Action("partition-on", "Isolar da rede do cluster", disruptive=True, needs_pg=False),
    Action("partition-off", "Reconectar à rede do cluster", disruptive=False, needs_pg=False),
]}


def command_for(action: str, node: NodeDef, cfg: Config) -> list[str]:
    """Monta o argv (executado via SSH) de uma ação do catálogo."""
    if action not in CATALOG:
        raise KeyError(action)
    if CATALOG[action].needs_pg and node.arbiter:
        raise ValueError(f"{node.id} é árbitro e não tem PostgreSQL.")

    if action == "raft-stop":
        return ["sudo", "-n", SYSTEMCTL, "stop", "raft-ha-manager"]
    if action == "raft-start":
        return ["sudo", "-n", SYSTEMCTL, "start", "raft-ha-manager"]
    if action == "pg-stop":
        return ["sudo", "-n", SYSTEMCTL, "stop", "postgresql"]
    if action == "pg-start":
        return ["sudo", "-n", SYSTEMCTL, "start", "postgresql"]
    if action == "partition-on":
        return ["sudo", "-n", PARTITION, "isolate", *[p.host for p in cfg.peers_of(node.id)]]
    if action == "partition-off":
        return ["sudo", "-n", PARTITION, "heal"]
    raise KeyError(action)


def catalog_for_ui() -> list[dict]:
    return [
        {"key": a.key, "label": a.label, "disruptive": a.disruptive, "needs_pg": a.needs_pg}
        for a in CATALOG.values()
    ]
