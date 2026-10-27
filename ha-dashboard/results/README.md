# Resultados dos experimentos

Medições feitas nas 3 VMs do laboratório (node-1 e node-2 com PostgreSQL 16, node-3
árbitro), com o painel autenticado. Tempos em segundos a partir da ação disruptiva,
medidos pelo relógio do Windows. Os marcos vindos do log têm resolução de ~0,15 s, e os
vindos da consulta SQL têm a resolução do intervalo de polling.

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `experimentos.csv` | Um experimento de failover por linha, gravado pelo painel |
| `seguidor-isolado-com-correcao.csv` | Standby isolado por 60 s e reconectado, com pré-votação e check-quorum ligados (19/10) |
| `seguidor-isolado-sem-correcao.csv` | O mesmo, com `pre-vote: false` e `check-quorum: false`. A coluna `sem_lider_s` foi preenchida a partir dos logs das VMs |
| `roteiro-resultados-2026-10-25.md` | Ensaio do roteiro da apresentação prática, com tabelas e conclusões |
| `roteiro-nos-secundarios.csv` | Ensaio de 25/10: agente do standby e do árbitro fora e de volta |
| `roteiro-seguidor-isolado.csv` | Ensaio de 25/10: standby isolado por 60 s |
| `validacao-item1-2026-10-16.csv` | Validação da pré-votação e do check-quorum (sem a senha do banco, então sem `primary_visible`) |
| `experimentos-demo.csv` | Gerado pelo modo `--demo`. Não são medições reais |

## Experimentos em `experimentos.csv`

Os cenários seguem a ordem raft-stop, pg-stop, partition-on, sempre no líder atual, com 45 s
de cluster estável antes de cada um.

| ids | Data | Configuração | Observação |
|---|---|---|---|
| 1–2 | 15/10 | agente anterior às proteções | |
| 3–17 | 19/10 | pré-votação e check-quorum **ligados** | 5 repetições de cada cenário |
| 18–23 | 19/10 | — | **Inválidos**: dois scripts de medição rodaram ao mesmo tempo e uma partição ficou ativa. Não usar |
| 24–38 | 19/10 | pré-votação e check-quorum **desligados** | 5 repetições de cada cenário |
| 39–47 | 25/10 | ligados | Ensaio do roteiro, 3 repetições de cada cenário |
| 48–50 | 25 e 26/10 | ligados | Execuções manuais pelo painel |

No cenário `partition-on`, o marco `pg_unhealthy` é a parada do PostgreSQL feita pelo
próprio self-healing do nó antigo, e não a falha. Ele deve ser ignorado.

## Scripts de medição

Em `ha-dashboard/scripts/`. Todos usam a API do painel, que precisa estar no ar (`run.ps1`).

| Script | O que mede |
|---|---|
| `medicoes-failover.ps1` | raft-stop, pg-stop e partition-on no líder; restaura o nó e espera o self-healing |
| `medicoes-seguidor-isolado.ps1` | Isola o standby e mede se o líder é derrubado ao reconectar |
| `medicoes-nos-secundarios.ps1` | Para o agente do standby e do árbitro e confere que nada muda no cluster |
| `resumo_experimentos.py` | Média, desvio, mínimo e máximo de cada marco, por cenário |

```powershell
powershell -ExecutionPolicy Bypass -File scripts\medicoes-failover.ps1 -Reps 5
powershell -ExecutionPolicy Bypass -File scripts\medicoes-seguidor-isolado.ps1 -Reps 5 -ObserveSecs 90 -Csv results\x.csv
powershell -ExecutionPolicy Bypass -File scripts\medicoes-nos-secundarios.ps1 -Reps 3 -Csv results\y.csv
.venv\Scripts\python scripts\resumo_experimentos.py results\experimentos.csv --desde 2026-10-19T10:29:58 --ate 2026-10-20
```

Rode um script por vez. Para interromper, pare a tarefa inteira e confira
`ha-partition status` nos três nós antes de medir de novo.
