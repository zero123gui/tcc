# Arquitetura

Como o sistema funciona, do ponto de vista de quem vai ler ou alterar o código. As decisões
por trás de cada mecanismo estão em [decisoes-de-arquitetura.md](decisoes-de-arquitetura.md).

## 1. Ideia central

O cluster tem **um líder Raft** por vez, e o líder é o nó cujo PostgreSQL deve ser o
**PRIMARY**. O Raft resolve a parte difícil (concordar sobre quem é o líder, mesmo com falhas
e partições de rede); o agente faz o PostgreSQL seguir essa decisão:

- o nó que vira líder promove o próprio PostgreSQL, se ele for STANDBY;
- um nó que não é líder e encontra o próprio PostgreSQL como PRIMARY o desliga (*fencing*) e
  o refaz como standby do líder (*self-healing*).

```
                 ┌──────────────────────────── node-N ─────────────────────────────┐
                 │                                                                  │
 outros nós ◄──► │ RaftGrpcServer ──► RaftServiceImpl ──► RaftNode ◄── ElectionTimer│
   (gRPC)        │ RaftGrpcClient ◄────────────────────────┘  │  │                   │
                 │                                             │  └──► RaftLog (disco)│
 ha-dashboard ─► │ ClusterManagementServiceImpl ───────────────┘                      │
   (gRPC)        │                                             │                      │
                 │            PostgresHealthChecker ◄──────────┤                      │
                 │            FailoverExecutor ◄───────────────┘                      │
                 │                  │  pg_promote, systemctl, recovery-standby.sh     │
                 │                  ▼                                                 │
                 │            PostgreSQL 16 local (não existe no árbitro)            │
                 └──────────────────────────────────────────────────────────────────┘
```

## 2. Componentes

Código em `raft-ha-manager/src/main/java/com/tcc/raft/`.

| Pacote / classe | Responsabilidade |
|---|---|
| `Main` | Lê o `node.yaml`, monta as dependências, sobe o servidor gRPC e registra o desligamento |
| **`config`** | |
| `NodeConfig` | Carrega e valida o `node.yaml`. Exige `node-id`, `grpc-address`, `peers` (incluindo o próprio nó) e `postgres` quando o nó não é árbitro |
| `PeerConfig`, `PostgresConfig`, `RaftConfig`, `StorageConfig` | Seções do `node.yaml`. `RaftConfig` exige `rpc < heartbeat < election-min < election-max` |
| **`core`** | |
| `RaftNode` | Máquina de estados do Raft e integração com o PostgreSQL. Um único lock (`stateLock`) protege todo o estado |
| `ElectionTimer` | Temporizador de eleição com duração sorteada entre `election-timeout-min-ms` e `max` |
| `RaftLog`, `RaftLogEntry`, `LogEntryType` | Log persistido em JSON Lines. Tipos: `NO_OP`, `FAILOVER`, `CONFIG_CHANGE` |
| `RaftRole` | `FOLLOWER`, `CANDIDATE`, `LEADER` |
| `PeerClient` | Interface de comunicação com um par. Separa o `RaftNode` do gRPC, o que permite testar o cluster inteiro em memória |
| **`grpc`** | |
| `RaftGrpcServer` | Servidor gRPC (Netty) na porta do `grpc-address` |
| `RaftGrpcClient` | Implementa `PeerClient`. Um canal por par, sem TLS, com reconexão imediata após falha |
| `RaftServiceImpl` | Recebe `RequestVote`, `PreVote` e `AppendEntries` e repassa ao `RaftNode` |
| `ClusterManagementServiceImpl` | `GetClusterStatus` (usado pelo painel) e `SubmitConfigChange` (não implementado) |
| **`postgres`** | |
| `PostgresHealthChecker` | Consulta `pg_is_in_recovery()` periodicamente e classifica o banco em `PRIMARY`, `STANDBY`, `UNHEALTHY` ou `UNKNOWN` |
| `FailoverExecutor` | Promove o banco (`promote`) e executa fencing + self-healing (`fenceAndRecover`) |

Os contratos gRPC estão em `src/main/proto/`: `raft.proto` (comunicação entre nós) e
`cluster.proto` (administração).

## 3. Ciclo de vida de um nó

1. `Main` lê o `node.yaml` informado na linha de comando.
2. Abre o log (`raft-log.jsonl`) e o estado (`raft-state.json`: mandato e voto) do diretório
   `storage.log-dir`.
3. Cria um `RaftGrpcClient` por par. Se o nó não for árbitro, cria o `PostgresHealthChecker`
   e o `FailoverExecutor`; no árbitro, usa versões vazias.
4. Inicia o `RaftNode` como **FOLLOWER**, arma o temporizador de eleição e começa a monitorar
   o PostgreSQL.
5. Sobe o servidor gRPC e espera até receber o sinal de encerramento.

## 4. Eleição

Quando o temporizador expira sem receber heartbeat do líder:

1. **Restrições.** O árbitro nunca se candidata. Um nó com o PostgreSQL não saudável também não.
2. **Pré-votação** (tese do Raft, seção 9.6). O nó pergunta aos pares se votariam nele no
   mandato seguinte, **sem** incrementar o próprio mandato. Um par nega se ainda ouve um líder
   ativo, se o mandato proposto é antigo ou se o log do candidato está atrasado. Sem maioria,
   o nó continua seguidor e nada muda no cluster.
3. **Candidatura.** Com a pré-votação aprovada, o nó incrementa o mandato, vota em si mesmo e
   envia `RequestVote` aos pares. Com a maioria (2 de 3), vira líder.
4. **Liderança.** O líder grava uma entrada `NO_OP` no log e envia `AppendEntries`
   (heartbeat) a cada `heartbeat-interval-ms`.

Na pré-votação, um candidato cujo PostgreSQL **não** é PRIMARY espera também a resposta do
outro nó com PostgreSQL. Se esse nó responder que é o PRIMARY, o candidato desiste: a
liderança deve ficar com o nó que tem o banco primário (ver §6).

## 5. Proteções do líder ativo

| Mecanismo | O que evita | Onde |
|---|---|---|
| **Pré-votação** | Um nó que volta de um isolamento (com o mandato atrasado ou após várias tentativas de eleição) não derruba um líder que está funcionando | `RaftNode.runPreVote`, `handlePreVote` |
| **Proteção contra servidores disruptivos** (*leader stickiness*, tese do Raft, seção 4.2.3) | Enquanto o seguidor ouve o líder há menos de `election-timeout-min-ms`, ignora pedidos de voto com mandato maior | `RaftNode.handleRequestVote` |
| **Check-quorum** (tese do Raft, seção 6.2) | Um líder que perdeu contato com a maioria por `election-timeout-min-ms` renuncia, em vez de continuar se declarando líder durante uma partição | `RaftNode.sendHeartbeatsToAll` |
| **Reconexão imediata do gRPC** | Depois de uma falha, o canal até um par não fica minutos em *backoff* exponencial; o par que volta recebe o heartbeat na rodada seguinte | `RaftGrpcClient` |

A pré-votação e o check-quorum podem ser desligados no `node.yaml` (`raft.pre-vote` e
`raft.check-quorum`), apenas para os experimentos comparativos.

## 6. PostgreSQL: failover, fencing e self-healing

### 6.1 Promoção do novo líder
Se havia um líder anterior e o PostgreSQL local é STANDBY, o novo líder grava uma entrada
`FAILOVER` no log e promove o banco com `SELECT pg_promote()`. Se falhar, tenta
`pg_ctl promote`. Depois espera até 60 s por `pg_is_in_recovery() = false`.

### 6.2 Renúncia por falha do banco
O `PostgresHealthChecker` consulta o banco a cada `health-check-interval-ms` (3 s). Depois de
`health-check-failure-threshold` falhas seguidas (3), declara o banco UNHEALTHY. Se o nó é o
líder, ele renuncia na hora, e outro nó saudável é eleito.

### 6.3 Fencing e self-healing (Rogue Primary)
Um nó **seguidor** cujo PostgreSQL responde como PRIMARY está em *split-brain*: ele era o
primary antes de um failover. O agente então:

1. **Fencing:** `sudo systemctl stop postgresql`, para que clientes parem de gravar nele.
2. **Self-healing:** `sudo /opt/raft-ha-manager/recovery-standby.sh <ip-do-líder>`. O script
   confere que o líder é de fato PRIMARY, guarda os dados atuais em `main.anterior`, clona o
   líder com `pg_basebackup -R` e sobe o banco como standby. Se a origem não for PRIMARY, sai
   sem alterar nada (código 2).

Só uma recuperação roda por vez, e novas chamadas nos 30 s seguintes são ignoradas
(o health check pode ainda mostrar PRIMARY logo depois do clone).

### 6.4 Alinhamento entre líder e PRIMARY
Ao religar o cluster inteiro, ninguém sabe quem era o líder: quem vence a eleição vira líder
**sem promover** nada (primeira eleição). Para que esse líder não seja o standby, três regras
usam o papel do PostgreSQL que cada nó informa nas mensagens do Raft:

| Regra | Como funciona |
|---|---|
| Pré-votação | Um candidato que não é PRIMARY desiste se outro nó responde que é o PRIMARY |
| Entrega da liderança | O líder da primeira eleição com o banco STANDBY renuncia quando um seguidor informa ser o PRIMARY |
| Fencing adiado | Um seguidor com o banco PRIMARY só faz fencing quando o PostgreSQL do líder já é PRIMARY. Antes disso, o clone copiaria um standby e o cluster ficaria sem nenhum PRIMARY |

### 6.5 Reapontamento de standbys
Com dois nós com PostgreSQL, o único outro banco após um failover é o antigo primary, que volta
pelo self-healing já apontando para o novo líder. Por isso `FailoverExecutor.reconfigureStandby`
não é chamado: ele só seria necessário com três ou mais nós com PostgreSQL.

## 7. Estado persistido e logs

Em `storage.log-dir` (padrão `/var/lib/raft-ha-manager`):

| Arquivo | Conteúdo |
|---|---|
| `raft-log.jsonl` | Entradas do log, uma por linha (dados em Base64) |
| `raft-state.json` | `currentTerm` e `votedFor` |
| `logs/raft-ha-manager.log` | Log da aplicação, com rotação diária e por tamanho (50 MB) |

O log da aplicação também vai para o journald: `journalctl -u raft-ha-manager -f`. É esse o
log que o painel acompanha.

> A gravação do estado não usa `fsync` nem escrita atômica. Numa queda de energia, o
> estado pode se perder. Isso é trabalho futuro.

## 8. API gRPC

Porta definida no `grpc-address` de cada nó (9090 no laboratório). Sem TLS e sem gRPC reflection:
para usar `grpcurl`, informe os arquivos `.proto`.

**`raft.RaftService`** (`raft.proto`), entre nós:

| RPC | Quem chama | Observação |
|---|---|---|
| `PreVote` | Nó cujo temporizador expirou | Não altera o estado de quem responde |
| `RequestVote` | Candidato | |
| `AppendEntries` | Líder | Heartbeat e replicação. Leva o papel do PostgreSQL do líder (`leader_pg_primary`) |

As respostas de `PreVote`, `RequestVote` e `AppendEntries` informam se o PostgreSQL de quem
responde é PRIMARY (`pg_primary`), usado nas regras da §6.4.

**`cluster.ClusterManagementService`** (`cluster.proto`), administração:

| RPC | Comportamento |
|---|---|
| `GetClusterStatus` | Responde em qualquer nó: líder visto, mandato, índice de commit e o papel do próprio nó |
| `SubmitConfigChange` | Não implementado: sempre devolve `accepted = false` |

## 9. Portas e caminhos nas VMs

| Porta | Serviço |
|---|---|
| 9090/tcp | gRPC do `raft-ha-manager` |
| 5432/tcp | PostgreSQL (o firewall só aceita 192.168.56.0/24) |
| 22/tcp | SSH |

| Caminho | Conteúdo |
|---|---|
| `/opt/raft-ha-manager/` | JAR do agente e `recovery-standby.sh` |
| `/etc/raft-ha-manager/node.yaml` | Configuração do nó |
| `/var/lib/raft-ha-manager/` | Estado do Raft e logs |
| `/var/lib/postgresql/16/main` | Dados do PostgreSQL (`main.anterior`: cópia antes do último self-healing) |

O serviço systemd `raft-ha-manager` roda como o usuário `raft`, que só pode executar com sudo
`systemctl start|stop postgresql` e o `recovery-standby.sh`.

## 10. Configuração (`node.yaml`)

Referência comentada: `raft-ha-manager/src/main/resources/node.yaml.example`. Configurações
do laboratório: `raft-ha-manager/scripts/config/node-{1,2,3}.yaml`.

| Chave | Padrão | Observação |
|---|---|---|
| `node-id`, `grpc-address` | — | Obrigatórias |
| `arbiter` | `false` | Árbitro não tem PostgreSQL e nunca se candidata |
| `postgres.host`, `port`, `database` | `127.0.0.1`, `5432`, `postgres` | |
| `postgres.user`, `password`, `data-dir` | — | Obrigatórias, exceto no árbitro |
| `postgres.health-check-interval-ms` | `3000` | |
| `postgres.health-check-failure-threshold` | `3` | |
| `raft.heartbeat-interval-ms` | `5000` | |
| `raft.election-timeout-min-ms` / `max-ms` | `15000` / `30000` | O mínimo também é o *lease* do líder (§5) |
| `raft.rpc-timeout-ms` | `3000` | |
| `raft.pre-vote`, `raft.check-quorum` | `true` | `false` só para experimentos |
| `storage.log-dir` | `/var/lib/raft-ha-manager` | |
| `peers[]` (`id`, `grpc-address`, `arbiter`) | — | Inclui o próprio nó |

> As credenciais nos arquivos de configuração e nos scripts são do ambiente de simulação.
> Fora do laboratório, mova-as para fora do Git (por exemplo, `.pgpass` ou um arquivo de
> ambiente lido pelo systemd).

## 11. Limitações conhecidas

| Limitação | Consequência |
|---|---|
| Replicação assíncrona | Escritas ainda não replicadas no momento do failover podem ser perdidas |
| Estado do Raft sem `fsync` | Mandato e voto podem se perder numa queda de energia (trabalho futuro) |
| Parar só o agente do líder | O PostgreSQL dele continua PRIMARY até o agente voltar e fazer o fencing |
| Dois primaries ao religar o cluster | Se o cluster for desligado logo após um failover, antes do self-healing, os dois bancos sobem como PRIMARY. Vence quem ganhar a eleição; falta um critério pela *timeline* do PostgreSQL |
| `SubmitConfigChange` | Não implementado: os membros do cluster são fixos no `node.yaml` |
| gRPC sem TLS | A comunicação entre os nós não é criptografada |
