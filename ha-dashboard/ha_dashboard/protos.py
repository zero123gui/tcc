"""Gera e carrega o cliente gRPC a partir do cluster.proto do agente Java.

O .proto é a fonte única do contrato: os stubs Python são gerados em
ha_dashboard/proto_gen/ (ignorado pelo Git) na primeira execução e sempre
que o .proto for mais novo que o código gerado.
"""

from __future__ import annotations

import importlib
import sys
from pathlib import Path
from types import ModuleType

PROTO_DIR = Path(__file__).resolve().parents[2] / "raft-ha-manager" / "src" / "main" / "proto"
OUT_DIR = Path(__file__).resolve().parent / "proto_gen"
PROTO = "cluster.proto"


def _generate() -> None:
    from grpc_tools import protoc

    OUT_DIR.mkdir(exist_ok=True)
    src = PROTO_DIR / PROTO
    if not src.exists():
        raise FileNotFoundError(f"{src} não encontrado. O painel precisa ficar ao lado de raft-ha-manager/.")
    rc = protoc.main([
        "grpc_tools.protoc",
        f"-I{PROTO_DIR}",
        f"--python_out={OUT_DIR}",
        f"--grpc_python_out={OUT_DIR}",
        str(src),
    ])
    if rc != 0:
        raise RuntimeError(f"protoc falhou ao gerar {PROTO} (código {rc}).")


def load() -> tuple[ModuleType, ModuleType]:
    """Retorna (cluster_pb2, cluster_pb2_grpc)."""
    generated = OUT_DIR / "cluster_pb2_grpc.py"
    src = PROTO_DIR / PROTO
    if not generated.exists() or (src.exists() and src.stat().st_mtime > generated.stat().st_mtime):
        _generate()
    # O código gerado usa "import cluster_pb2" absoluto
    if str(OUT_DIR) not in sys.path:
        sys.path.insert(0, str(OUT_DIR))
    return importlib.import_module("cluster_pb2"), importlib.import_module("cluster_pb2_grpc")
