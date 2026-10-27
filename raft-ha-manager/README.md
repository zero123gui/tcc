# raft-ha-manager

Agente Java que roda em cada nó do cluster. Implementa o algoritmo Raft e faz o PostgreSQL
local seguir a decisão do cluster: o líder promove o próprio banco, e um seguidor com o banco
PRIMARY o isola e o refaz como standby. O funcionamento está descrito em
[`docs/arquitetura.md`](../docs/arquitetura.md).

## Requisitos

- Java 21
- Maven 3.9

O `pom.xml` fixa `os.detected.classifier=windows-x86_64` para o `protoc` funcionar no VS Code.
Para compilar em Linux, use `mvn package -Dos.detected.classifier=linux-x86_64`.

## Compilar e testar

```bash
mvn package
```

Gera o código gRPC a partir de `src/main/proto/`, roda os testes e cria
`target/raft-ha-manager-1.0-SNAPSHOT.jar` (JAR com todas as dependências).

| Comando | O que faz |
|---|---|
| `mvn test` | Só os testes |
| `mvn package -DskipTests` | JAR sem rodar os testes |
| `mvn test -Dtest=LeaderStabilityTest` | Uma classe de teste |

## Executar

```bash
java -Dnode.id=node-1 -Dlog.dir=./logs -jar target/raft-ha-manager-1.0-SNAPSHOT.jar node.yaml
```

- O `node.yaml` é a configuração do nó. Modelo comentado: `src/main/resources/node.yaml.example`.
  Configurações do laboratório: `scripts/config/node-{1,2,3}.yaml`.
- Sem `-Dlog.dir`, o log em arquivo vai para `/var/lib/raft-ha-manager/logs`.
- Para vários nós na mesma máquina, use portas e `storage.log-dir` diferentes em cada um.

Nas VMs, o agente roda como serviço systemd (`raft-ha-manager`), instalado por
`scripts/setup/04-deploy-raft.sh`.

## Estrutura

```
src/main/java/com/tcc/raft/
├── Main.java                  Ponto de entrada: monta as dependências e sobe o servidor
├── config/                    Leitura e validação do node.yaml
├── core/                      Raft: RaftNode, log, temporizador de eleição, PeerClient
├── grpc/                      Servidor e cliente gRPC, serviços Raft e de administração
└── postgres/                  Health check, promoção, fencing e self-healing
src/main/proto/
├── raft.proto                 Comunicação entre nós (PreVote, RequestVote, AppendEntries)
└── cluster.proto              Administração (GetClusterStatus)
src/main/resources/
├── logback.xml                Log no console (journald) e em arquivo rotativo
└── node.yaml.example          Configuração comentada
scripts/                       Instalação das VMs e do cluster
```

A classe central é `core/RaftNode.java`. Ela concentra a máquina de estados do Raft
(eleição, pré-votação, replicação, check-quorum) e a integração com o PostgreSQL.

## Testes

26 testes JUnit 5:

| Classe | O que cobre |
|---|---|
| `core/LeaderStabilityTest` | Um nó que volta não derruba o líder; o líder isolado renuncia; o failover funciona. Cada cenário principal roda com as proteções ligadas e desligadas: desligadas, o teste afirma que o problema acontece, o que prova que ele reproduz o defeito |
| `core/LeaderPrimaryAlignmentTest` | Na primeira eleição, a liderança fica com o nó do PRIMARY, e o fencing espera o líder ser PRIMARY |
| `grpc/RaftGrpcClientReconnectTest` | O cliente gRPC reconecta em menos de 500 ms depois que o par volta |
| `postgres/FailoverExecutorTest` | Só uma recuperação (fencing e clone) roda por vez, nenhuma nos 30 s seguintes a outra, e uma falha libera nova tentativa |
| `config/NodeConfigTest` | Leitura de `pre-vote` e `check-quorum` (ligados por padrão, valores inválidos recusados) e árbitro sem seção `postgres` |

`core/ClusterHarness` monta um cluster de 3 `RaftNode` reais numa rede em memória, com
PostgreSQL e failover simulados. A rede permite cortar a comunicação num sentido só, e um
amostrador verifica continuamente as invariantes do Raft (no máximo um líder por mandato,
mandatos nunca decrescentes). Os tempos são reduzidos (eleição de 300 a 500 ms) para os testes
rodarem em segundos.

## Dependências principais

| Biblioteca | Versão | Uso |
|---|---|---|
| gRPC (`grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`) | 1.63.0 | Comunicação entre nós |
| Protobuf | 3.25.3 | Mensagens |
| PostgreSQL JDBC | 42.7.3 | Health check e promoção |
| SnakeYAML | 2.2 | `node.yaml` |
| Gson | 2.10.1 | Log e estado do Raft em disco |
| SLF4J / Logback | 2.0.13 / 1.5.6 | Log |
| JUnit | 5.10.2 | Testes |
