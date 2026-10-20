# Resultados dos experimentos

Medições feitas nas 3 VMs do laboratório (node-1 e node-2 com PostgreSQL 16, node-3
árbitro), com o painel autenticado. Tempos em segundos a partir da ação disruptiva,
medidos pelo relógio do Windows. Os marcos vindos do log têm resolução de ~0,15 s, e os
vindos da consulta SQL têm a resolução do intervalo de polling.

## Arquivos

| Arquivo | Conteúdo |
|---|---|
| `experimentos.csv` | Um experimento por linha, gravado pelo painel (cronômetro de failover). |
| `seguidor-isolado-com-correcao.csv` | Standby isolado por 60 s e reconectado, com pré-votação e check-quorum ligados. |
| `seguidor-isolado-sem-correcao.csv` | O mesmo, com `pre-vote: false` e `check-quorum: false`. A coluna `sem_lider_s` foi preenchida a partir dos logs das VMs. |
| `validacao-item1-2026-10-16.csv` | Validação do item 1 (sem a senha do banco, então sem `primary_visible`). |
| `experimentos-demo.csv` | Gerado pelo modo `--demo`. Não são medições reais. |

## Rodadas de 19/10 em `experimentos.csv`

Agente da branch `alinhamento-lider-primary` (itens 1, 2 e 3). Os cenários seguem a ordem
raft-stop, pg-stop, partition-on, sempre no líder atual, com 45 s de cluster estável antes
de cada um.

| ids | Configuração | Observação |
|---|---|---|
| 3–17 | pré-votação e check-quorum **ligados** | 5 repetições de cada cenário |
| 18–23 | — | **Inválidos**: dois scripts de medição rodaram ao mesmo tempo e uma partição ficou ativa. Não usar. |
| 24–38 | pré-votação e check-quorum **desligados** | 5 repetições de cada cenário |

Os ids 1 e 2 são de 15/10 (agente anterior).

No cenário `partition-on`, o marco `pg_unhealthy` é a parada do PostgreSQL feita pelo
próprio self-healing do nó antigo, e não a falha. Ele deve ser ignorado.

## Como reproduzir

Com o painel no ar (`run.ps1`):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\medicoes-failover.ps1 -Reps 5
powershell -ExecutionPolicy Bypass -File scripts\medicoes-seguidor-isolado.ps1 -Reps 5 -ObserveSecs 90 -Csv results\x.csv
.venv\Scripts\python scripts\resumo_experimentos.py results\experimentos.csv --desde 2026-10-19T10:29:58
```

Rode um script por vez. Para interromper, pare a tarefa inteira e confira
`ha-partition status` nos três nós antes de medir de novo.
