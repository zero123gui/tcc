"""Uso:
    python -m ha_dashboard                  # VMs reais, lê config.yaml
    python -m ha_dashboard --demo           # cluster simulado, sem VMs
    python -m ha_dashboard --config outro.yaml
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

import uvicorn

from . import config as config_mod
from .hub import Hub
from .server import create_app

ROOT = Path(__file__).resolve().parent.parent


def main() -> None:
    parser = argparse.ArgumentParser(prog="ha_dashboard", description="Painel local do cluster raft-ha-manager")
    parser.add_argument("--config", default=str(ROOT / "config.yaml"))
    parser.add_argument("--demo", action="store_true", help="usa um cluster simulado em vez das VMs")
    parser.add_argument("--results", default=str(ROOT / "results" / "experimentos.csv"),
                        help="CSV onde os experimentos concluídos são acrescentados")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    cfg_path = Path(args.config)
    if not cfg_path.exists():
        if not args.demo:
            sys.exit(f"{cfg_path} não encontrado. Copie config.example.yaml para config.yaml e ajuste.")
        cfg_path = ROOT / "config.example.yaml"
    cfg = config_mod.load(cfg_path)

    results = Path(args.results)
    if args.demo:
        from .backend_demo import DemoBackend
        backend, mode = DemoBackend(cfg), "demo"
        # Experimentos da simulação não se misturam com os dados reais do TCC
        if results == ROOT / "results" / "experimentos.csv":
            results = results.with_name("experimentos-demo.csv")
    else:
        from .backend_real import RealBackend
        backend, mode = RealBackend(cfg), "real"

    hub = Hub(cfg, backend, results, mode)
    print(f"ha-dashboard ({mode}) em http://{cfg.listen_host}:{cfg.listen_port}")
    uvicorn.run(create_app(hub), host=cfg.listen_host, port=cfg.listen_port, log_level="warning")


if __name__ == "__main__":
    main()
