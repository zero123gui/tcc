"""Resumo estatístico dos experimentos de failover gravados pelo painel.

Uso:
    python scripts/resumo_experimentos.py results/experimentos.csv [--desde 2026-10-19T09:25] [--ate ...]

Para cada cenário mostra n, média, desvio padrão, mínimo e máximo de cada marco
(segundos desde a ação disruptiva). Só entram experimentos "concluído".
"""

from __future__ import annotations

import argparse
import csv
import statistics
from collections import defaultdict

MARCOS = [
    ("pg_unhealthy_s", "PG declarado UNHEALTHY"),
    ("step_down_s", "líder renunciou"),
    ("leader_elected_s", "novo líder eleito"),
    ("promoted_s", "promoção confirmada (log)"),
    ("primary_visible_s", "novo PRIMARY visível (SQL)"),
    ("healed_s", "self-healing concluído"),
]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--desde", default="")
    ap.add_argument("--ate", default="9999")
    args = ap.parse_args()

    por_cenario: dict[str, list[dict]] = defaultdict(list)
    with open(args.csv, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            if row["desfecho"] == "concluído" and args.desde <= row["inicio"] < args.ate:
                por_cenario[row["cenario"]].append(row)

    for cenario, rows in por_cenario.items():
        print(f"\n{cenario}  (n={len(rows)}; ids {', '.join(r['id'] for r in rows)})")
        print(f"  {'marco':30} {'n':>2} {'média':>7} {'desvio':>7} {'mín':>7} {'máx':>7}")
        for col, nome in MARCOS:
            vals = [float(r[col]) for r in rows if r.get(col)]
            if not vals:
                continue
            dp = statistics.stdev(vals) if len(vals) > 1 else 0.0
            print(f"  {nome:30} {len(vals):>2} {statistics.mean(vals):7.1f} {dp:7.1f} "
                  f"{min(vals):7.1f} {max(vals):7.1f}")


if __name__ == "__main__":
    main()
