# Ensaio do roteiro da apresentação prática — 25/10/2026

Todos os cenários do roteiro foram executados nas 3 VMs (node-1 e node-2 com PostgreSQL 16,
node-3 árbitro), com o painel autenticado, o agente da `main` (commit `6f2ee30`) e
pré-votação e check-quorum **ligados**. Cada cenário rodou 3 vezes, uma de cada vez, com o
cluster estável por pelo menos 45 s antes de cada repetição e sem partição ativa
(`ha-partition status` = `connected` nos 3 nós ao final).

**Resultado: 24 de 24 repetições passaram, sem intervenção manual.**

## 1. Nó secundário fora e de volta (nada deve mudar)

Agente parado por 60 s e religado; observação de 60 s depois. Critério: mesmo líder, mesmo
mandato (107), nenhuma eleição.

| Cenário | Repetições | Líder mudou | Mandato mudou | Eleições | Resultado |
|---|---|---|---|---|---|
| Agente do standby (node-1) | 3 | 0 | 0 | 0 | passou |
| Agente do árbitro (node-3) | 3 | 0 | 0 | 0 | passou |

Dados: `roteiro-nos-secundarios.csv`.

## 2. Standby isolado da rede por 60 s e reconectado

Critério: o nó isolado não infla o mandato e não derruba o líder ao voltar.

| Repetição | Líder | Mandato antes → isolado ao voltar → final | Eleições | Líder derrubado |
|---|---|---|---|---|
| 1 | node-1 | 116 → 116 → 116 | 0 | não |
| 2 | node-1 | 116 → 116 → 116 | 0 | não |
| 3 | node-1 | 116 → 116 → 116 | 0 | não |

Dados: `roteiro-seguidor-isolado.csv`. Comparação com as proteções desligadas
(`seguidor-isolado-sem-correcao.csv`, 19/10): o líder foi derrubado em 5 de 5 repetições, o
mandato do nó isolado subiu 2 unidades e o do cluster, 3 a 4.

## 3. Failover (segundos desde a falha; média, desvio, mínimo e máximo; n = 3)

| Cenário | Marco | Média | Desvio | Mín | Máx |
|---|---|---|---|---|---|
| Parar o agente do líder | Novo líder eleito | 26,5 | 3,5 | 23,7 | 30,3 |
| | Promoção confirmada (log) | 28,7 | 3,2 | 26,0 | 32,3 |
| | Novo PRIMARY visível (SQL) | 27,8 | 3,3 | 25,3 | 31,4 |
| Parar o PostgreSQL do líder | Falha detectada / líder renunciou | 8,0 | 1,5 | 7,0 | 9,7 |
| | Novo líder eleito | 29,3 | 4,0 | 24,7 | 32,3 |
| | Promoção confirmada (log) | 31,1 | 3,8 | 26,7 | 33,8 |
| | Novo PRIMARY visível (SQL) | 31,9 | 3,8 | 27,6 | 34,8 |
| Isolar o líder da rede | Líder renunciou (check-quorum) | 18,0 | 2,1 | 15,7 | 19,6 |
| | Novo líder eleito | 24,2 | 6,5 | 17,2 | 30,2 |
| | Promoção confirmada (log) | 25,9 | 6,7 | 18,7 | 32,0 |
| | Novo PRIMARY visível (SQL) | 24,9 | 6,7 | 17,7 | 31,0 |
| | Self-healing concluído, após a falha | 49,3 | 6,5 | 42,7 | 55,6 |

Em todas as 9 repetições:
- houve exatamente um novo líder, que promoveu o próprio PostgreSQL;
- o antigo primary voltou como standby pelo self-healing, uma única vez (9 execuções, 9 concluídas,
  0 falhas ou abortos nos logs dos agentes), usando o
  `recovery-standby.sh` novo (que confere que a origem é PRIMARY e guarda os dados em
  `main.anterior`);
- o cluster terminou com um líder, um PRIMARY no mesmo nó do líder e um STANDBY.

Duração do self-healing depois de religar o nó antigo: 28 a 33 s após religar o agente
(cenário "parar o agente", em que o tempo inclui a reinicialização do agente); 16 a 19 s após
religar o PostgreSQL; 21 a 22 s após reconectar a rede.

Experimentos 39 a 47 de `experimentos.csv`.

## Observações

- **Tempo de eleição:** o tempo de failover é dominado pelo tempo de eleição sorteado entre 15 e
  30 s (valor da monografia). A promoção em si leva cerca de 2 s depois da eleição.
- **Cenário de partição, marco `pg_unhealthy`:** é a parada do PostgreSQL feita pelo próprio
  self-healing do nó antigo, e não a falha original. Não usar na análise.
- **Tamanho da amostra:** 3 repetições por cenário nesta rodada. As baterias de 19/10
  (5 repetições, ids 3–17 com as proteções e 24–38 sem) continuam sendo as de referência para
  o comparativo.
