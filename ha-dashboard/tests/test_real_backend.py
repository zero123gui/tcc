"""O backend real é criado fora do loop do servidor e usado dentro dele.

Regressão: canais grpc.aio criados no __init__ ficavam presos ao loop errado e
todo ciclo de polling falhava com "attached to a different loop".
"""

import asyncio
import dataclasses
from pathlib import Path

from ha_dashboard import config
from ha_dashboard.backend_real import RealBackend

ROOT = Path(__file__).resolve().parents[1]


def unreachable_cfg():
    base = config.load(ROOT / "config.example.yaml")
    nodes = tuple(dataclasses.replace(n, host="127.0.0.1") for n in base.nodes)
    # Porta gRPC sem servidor, chave SSH inexistente e sem senha do banco
    return dataclasses.replace(
        base, nodes=nodes, grpc_port=1, grpc_timeout_s=1.0, pg_password_env="HA_TESTE_SEM_SENHA",
        ssh_key_file=str(ROOT / "tests" / "nao-existe"),
    )


def test_poll_in_a_different_loop_than_construction():
    cfg = unreachable_cfg()
    backend = RealBackend(cfg)          # criado sem loop em execução, como no __main__

    async def run():
        statuses = await asyncio.gather(*(backend.poll(n) for n in cfg.nodes))
        # um segundo ciclo reaproveita os canais
        again = await asyncio.gather(*(backend.poll(n) for n in cfg.nodes))
        await backend.close()
        return statuses, again

    statuses, again = asyncio.run(run())
    for s in statuses + again:
        assert s.grpc_ok is False
        # erro de rede do gRPC (o código varia por SO), e não RuntimeError
        assert s.grpc_error in ("UNAVAILABLE", "DEADLINE_EXCEEDED")
        assert s.ssh_ok is False and "chave SSH" in s.ssh_error
    assert [s.pg_state for s in statuses] == ["SEM_CREDENCIAL", "SEM_CREDENCIAL", "N/A"]
