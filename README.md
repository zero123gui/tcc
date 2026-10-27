# Alta disponibilidade para PostgreSQL em ambientes SCADA com Raft

Projeto do Trabalho de Conclusão de Curso em Ciência da Computação (UNIOESTE).

Um agente em Java, o **raft-ha-manager**, roda em cada nó do cluster e usa o algoritmo de
consenso **Raft** para decidir qual nó hospeda o PostgreSQL **PRIMARY**. Quando o primary
falha, os nós restantes elegem um novo líder, que promove o próprio PostgreSQL. Quando o
nó antigo volta, ele é isolado (*fencing*) e refeito como standby a partir do novo primary
(*self-healing*). Um painel web local, o **ha-dashboard**, mostra o estado do cluster e
executa os cenários de falha.

```
          ┌──────────────── gRPC :9090 (Raft) ─────────────────┐
          │                                                     │
 ┌────────▼────────┐        ┌─────────────────┐        ┌───────┴─────────┐
 │ node-1          │◄──────►│ node-2          │◄──────►│ node-3          │
 │ raft-ha-manager │        │ raft-ha-manager │        │ raft-ha-manager │
 │ PostgreSQL 16   │─WAL───►│ PostgreSQL 16   │        │ (árbitro, sem   │
 │ PRIMARY         │ :5432  │ STANDBY         │        │  PostgreSQL)    │
 └─────────────────┘        └─────────────────┘        └─────────────────┘
          ▲                          ▲                          ▲
          └────── SSH, gRPC e SQL ───┴──────────────────────────┘
                              │
                     ha-dashboard (Windows, 127.0.0.1:8000)
```

Dois nós têm PostgreSQL (primary e standby, com *streaming replication*). O terceiro é um
**árbitro**: vota nas eleições, mas não tem banco. Assim a maioria (2 de 3) existe sem uma
terceira cópia dos dados.

## Estrutura do repositório

| Caminho | Conteúdo |
|---|---|
| [`raft-ha-manager/`](raft-ha-manager/README.md) | O agente Java: Raft, monitoramento e failover do PostgreSQL, testes |
| [`raft-ha-manager/scripts/`](raft-ha-manager/scripts/README.md) | Criação das VMs e instalação do cluster do zero |
| [`ha-dashboard/`](ha-dashboard/README.md) | Painel web (Python/FastAPI) para observar e operar o cluster |
| [`ha-dashboard/results/`](ha-dashboard/results/README.md) | Dados dos experimentos de failover (CSV) |
| [`docs/`](docs/) | Documentação técnica |
| `Artigos/` | PDFs de referência (só na máquina local, fora do Git) |

## Documentação

| Documento | Para quê |
|---|---|
| [Arquitetura](docs/arquitetura.md) | Como o sistema funciona: componentes, eleição, failover, proteções, API e portas |
| [Decisões de arquitetura](docs/decisoes-de-arquitetura.md) | Por que cada mecanismo existe e quais alternativas foram descartadas |
| [Operação do laboratório](docs/laboratorio.md) | Ligar e desligar o cluster, snapshots, implantar uma nova versão, problemas comuns |
| [Roteiro da apresentação prática](docs/roteiro-apresentacao-pratica.md) | Demonstração para a banca, passo a passo |

## Início rápido

**Requisitos:** Java 21 e Maven 3.9 (agente), Python 3.12 (painel), VirtualBox 7 (laboratório).

Compilar e testar o agente:

```bash
cd raft-ha-manager
mvn package
```

O `package` roda os 26 testes e gera `target/raft-ha-manager-1.0-SNAPSHOT.jar`.

Ver o painel sem as VMs, com o cluster simulado:

```powershell
cd ha-dashboard
py -3.12 -m venv .venv
.venv\Scripts\pip install -r requirements-dev.txt
.venv\Scripts\python -m ha_dashboard --demo
```

Abra http://127.0.0.1:8000.

Para o cluster real, monte as VMs com o guia de [`raft-ha-manager/scripts/`](raft-ha-manager/scripts/README.md)
e abra o painel com `ha-dashboard\run.ps1`.

## Resultados

Medidos nas VMs do laboratório, com tempo de eleição sorteado entre 15 e 30 s. Os valores
são as médias das rodadas de 19/10 (5 repetições) e de 25/10 (3 repetições); os dados estão
em [`ha-dashboard/results/`](ha-dashboard/results/README.md).

| Falha no líder | Novo PRIMARY visível, média por rodada |
|---|---|
| O agente para | 25,3 s e 27,8 s |
| O PostgreSQL para | 33,9 s e 31,9 s |
| O nó fica isolado da rede | 27,8 s e 24,9 s |

Um standby isolado por 60 s que volta ao cluster derrubou o líder em 5 de 5 repetições com
a pré-votação e o check-quorum desligados, e em 0 de 8 com eles ligados.
