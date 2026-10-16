# raft-ha-manager — Documentação do Projeto

> Documento gerado a partir da leitura do repositório. Cada informação cita o arquivo de origem entre parênteses.
> Itens marcados com **[inferido]** não estão explícitos no código ou nos arquivos de configuração: foram deduzidos pela leitura.
> Nenhum segredo (senha, token, chave) aparece aqui. Só são listados **nomes** de variáveis e campos.

Caminhos relativos à raiz do repositório.

---

## 1. Visão geral

**O que é:** gerenciador de Alta Disponibilidade (HA) para ambientes SCADA. Usa o algoritmo de consenso **Raft** para decidir qual nó do cluster hospeda o **PostgreSQL Primary** e fazer o failover automático (`raft-ha-manager/pom.xml` → `<description>`). Faz parte de um Trabalho de Conclusão de Curso em Ciência da Computação (`raft-ha-manager/pom.xml`; `raft-ha-manager/src/main/java/com/tcc/raft/Main.java:53`).

**Ideia central:** o **líder Raft** é o nó cujo PostgreSQL deve ser o **Primary**. Os seguidores mantêm o PostgreSQL como **Standby** por streaming replication (`raft-ha-manager/src/main/java/com/tcc/raft/core/RaftRole.java:36-38`; `raft-ha-manager/scripts/setup/02-postgres-primary.sh`, `03-postgres-standby.sh`).

**Conteúdo do repositório:**

| Diretório | Conteúdo | Origem |
|---|---|---|
| `raft-ha-manager/` | Aplicação Java (Maven): o agente que roda em cada nó | `raft-ha-manager/pom.xml` |
| `raft-ha-manager/scripts/` | Provisionamento das VMs (VirtualBox), do PostgreSQL e do deploy | `raft-ha-manager/scripts/README.md` |
| `scripts/setup/recovery-standby.sh` | Script de Self-Healing chamado pelo agente | `scripts/setup/recovery-standby.sh` |
| `docs/` | Monografia (LaTeX), apresentação (Beamer/PPTX/HTML), notas de arquitetura | `docs/*.tex`, `docs/architecture_decisions.md`, `docs/analise_completa_codebase.md` |
| `Artigos/` | PDFs de referência bibliográfica (não versionados) | listagem do diretório; `git status` |

### 1.1 Fluxo principal

**Inicialização de um nó** (`raft-ha-manager/src/main/java/com/tcc/raft/Main.java:20-29, 57-135`):

1. Lê e valida o `node.yaml` informado como argumento (`NodeConfig.load`).
2. Abre o log Raft persistido em disco (`RaftLog`: `raft-log.jsonl`).
3. Cria um cliente gRPC para cada par, exceto o próprio nó (`RaftGrpcClient`).
4. Se **não** for árbitro, cria `PostgresHealthChecker` e `FailoverExecutor`. Se for árbitro, usa implementações vazias (no-op).
5. Cria e inicia o `RaftNode`: carrega `raft-state.json`, começa como FOLLOWER, arma o temporizador de eleição e inicia o health check do PostgreSQL (`core/RaftNode.java:180-190`).
6. Sobe o servidor gRPC (`RaftGrpcServer`).
7. Registra um shutdown hook e bloqueia até o servidor terminar.

**Operação em regime** (`raft-ha-manager/src/main/java/com/tcc/raft/core/RaftNode.java`):

- **Eleição:** quando o `ElectionTimer` expira sem heartbeat (timeout aleatório entre `election-timeout-min-ms` e `max`), o nó vira CANDIDATE, incrementa o mandato, vota em si mesmo e envia `RequestVote` em paralelo. Com maioria (`N/2 + 1`), vira LEADER (`RaftNode.java:323-406, 827-830`; `core/ElectionTimer.java`).
  - **Árbitros nunca se candidatam** (`RaftNode.java:324-328`).
  - **Um nó com PostgreSQL não saudável não se candidata** (`RaftNode.java:330-334`).
- **Liderança:** ao virar líder, o nó insere uma entrada `NO_OP` no log e passa a enviar `AppendEntries` (heartbeat/replicação) a cada `heartbeat-interval-ms` (`RaftNode.java:487-541, 547-659`).
- **Failover do PostgreSQL:** se já existia um líder anterior (ou seja, não é a primeira eleição) e o PostgreSQL local está STANDBY, o novo líder grava uma entrada `FAILOVER` no log e promove o banco com `SELECT pg_promote(...)`. Se isso falhar, usa `pg_ctl promote -D <data-dir>` como fallback. Depois espera até 60 s por `pg_is_in_recovery() = false` (`RaftNode.java:528-540, 771-801`; `postgres/FailoverExecutor.java:49-70, 176-240`).
- **Step-down:** se o PostgreSQL do líder ficar UNHEALTHY (após `health-check-failure-threshold` falhas consecutivas), o líder renuncia e volta a FOLLOWER (`RaftNode.java:443-457`; `postgres/PostgresHealthChecker.java:108-147`).
- **Proteção contra split-brain (Fencing + Self-Healing):** se o nó é FOLLOWER, conhece o líder e o PostgreSQL local responde como PRIMARY, o agente roda `sudo /bin/systemctl stop postgresql` e depois `sudo /usr/bin/bash /opt/raft-ha-manager/recovery-standby.sh <ip-do-líder>`. Esse script apaga o data dir, refaz o clone com `pg_basebackup` e sobe o banco como standby (`RaftNode.java:463-481`; `FailoverExecutor.java:130-170`; `scripts/setup/recovery-standby.sh`; justificativa em `docs/architecture_decisions.md`).

```
            ┌────────── gRPC :9090 (RaftService / ClusterManagementService) ──────────┐
            │                                                                          │
   ┌────────▼────────┐            ┌─────────────────┐            ┌─────────────────┐   │
   │ node-1          │◄──────────►│ node-2          │◄──────────►│ node-3 (árbitro)│◄──┘
   │ raft-ha-manager │            │ raft-ha-manager │            │ raft-ha-manager │
   │ PostgreSQL 16   │──stream──► │ PostgreSQL 16   │            │ sem PostgreSQL  │
   │ (Primary)       │ replication│ (Standby)       │            │                 │
   └─────────────────┘   :5432    └─────────────────┘            └─────────────────┘
```

Topologia de validação com 3 nós (`raft-ha-manager/scripts/README.md:18-24`; `raft-ha-manager/scripts/config/node-*.yaml`).

---

## 2. Arquitetura e módulos

Pacote base: `com.tcc.raft` (`raft-ha-manager/src/main/java/com/tcc/raft/`).

| Módulo / classe | Responsabilidade | Origem |
|---|---|---|
| `Main` | Ponto de entrada. Monta as dependências e cuida do ciclo de vida | `Main.java` |
| **config** | | |
| `NodeConfig` | Carrega e valida o `node.yaml` (SnakeYAML). Exige `node-id`, `grpc-address` (`host:porta`), `peers` com o próprio nó, e `postgres` quando não é árbitro | `config/NodeConfig.java:41-103` |
| `PeerConfig` | Entrada da lista `peers` (`id`, `grpc-address`, `arbiter`) | `config/PeerConfig.java` |
| `PostgresConfig` | Conexão JDBC e parâmetros do health check. A URL usa `loginTimeout`, `connectTimeout` e `socketTimeout` de 5 s | `config/PostgresConfig.java:24-56` |
| `RaftConfig` | Temporizações, com padrões 5000 / 15000 / 30000 / 3000 ms. Valida `rpc < heartbeat < election-min < election-max` | `config/RaftConfig.java:31-73` |
| `StorageConfig` | Diretório do estado persistido (padrão `/var/lib/raft-ha-manager`) | `config/StorageConfig.java` |
| **core** | | |
| `RaftNode` | Máquina de estados Raft: eleição, replicação, commit, aplicação de entradas, integração com o PostgreSQL. Um lock único (`stateLock`) protege o estado | `core/RaftNode.java` |
| `RaftLog` / `RaftLogEntry` | Log persistido em JSON Lines (`raft-log.jsonl`, com `data` em Base64). O índice 0 é sentinela | `core/RaftLog.java`, `core/RaftLogEntry.java` |
| `ElectionTimer` | Timeout de eleição com jitter aleatório | `core/ElectionTimer.java` |
| `RaftRole` | `FOLLOWER`, `CANDIDATE`, `LEADER` | `core/RaftRole.java` |
| `LogEntryType` | `NO_OP`, `FAILOVER`, `CONFIG_CHANGE` | `core/LogEntryType.java` |
| **grpc** | | |
| `RaftGrpcServer` | Servidor gRPC na porta do `grpc-address`. Mensagem de entrada limitada a 16 MB | `grpc/RaftGrpcServer.java` |
| `RaftGrpcClient` | Stub bloqueante por par, em plaintext (sem TLS), keep-alive de 10 s e deadline de `rpc-timeout-ms` | `grpc/RaftGrpcClient.java` |
| `RaftServiceImpl` | Recebe `RequestVote` e `AppendEntries` e repassa ao `RaftNode` | `grpc/RaftServiceImpl.java` |
| `ClusterManagementServiceImpl` | `GetClusterStatus` e `SubmitConfigChange` (ainda não implementado, ver §4) | `grpc/ClusterManagementServiceImpl.java` |
| **postgres** | | |
| `PostgresHealthChecker` | Roda `SELECT pg_is_in_recovery()` periodicamente e classifica o banco como `PRIMARY`, `STANDBY`, `UNHEALTHY` ou `UNKNOWN` | `postgres/PostgresHealthChecker.java` |
| `FailoverExecutor` | `promote()`, `reconfigureStandby(...)` (via `ALTER SYSTEM SET primary_conninfo`) e `fenceAndRecover(ip)` | `postgres/FailoverExecutor.java` |
| **proto** | Contratos gRPC/Protobuf (pacote Java `com.tcc.raft.grpc.proto`) | `src/main/proto/raft.proto`, `src/main/proto/cluster.proto` |

**Estado persistido em disco** (dentro de `storage.log-dir`):

- `raft-log.jsonl`: entradas do log (`RaftLog.java:63`).
- `raft-state.json`: `currentTerm` e `votedFor` (`RaftNode.java:146, 897-928`).

**Logs da aplicação:** Logback grava no console e em arquivo rotativo `${log.dir}/raft-ha-manager.log`. A rotação é diária e por tamanho (50 MB), com 30 dias de histórico e limite de 500 MB. `com.tcc.raft` fica em DEBUG; `io.grpc` e `io.netty` ficam em WARN (`raft-ha-manager/src/main/resources/logback.xml`).

---

## 3. Stack e versões

> **Não há lockfile no repositório.** O Maven não gera lockfile e não existe `pom.lock`, `.mvn/` nem wrapper `mvnw`. As versões abaixo são as **fixadas explicitamente** no `raft-ha-manager/pom.xml`. As versões das dependências transitivas não estão travadas.

### 3.1 Linguagem e build

| Item | Versão | Origem |
|---|---|---|
| Java (source/target) | 21 | `pom.xml` → `maven.compiler.source/target`; `.idea/misc.xml` (`JDK_21`) |
| Encoding | UTF-8 | `pom.xml` → `project.build.sourceEncoding` |
| Artefato | `com.tcc:raft-ha-manager:1.0-SNAPSHOT` (jar) | `pom.xml` |
| Maven | não fixado no projeto (a máquina de desenvolvimento tem 3.9.16) **[inferido]** | ambiente local, sem wrapper |

### 3.2 Dependências (runtime)

| Dependência | Versão | Uso | Origem |
|---|---|---|---|
| `io.grpc:grpc-netty-shaded` | 1.63.0 | Transporte gRPC | `pom.xml` (`grpc.version`) |
| `io.grpc:grpc-protobuf` | 1.63.0 | Integração com Protobuf | `pom.xml` |
| `io.grpc:grpc-stub` | 1.63.0 | Stubs cliente/servidor | `pom.xml` |
| `com.google.protobuf:protobuf-java` | 3.25.3 | Serialização | `pom.xml` (`protobuf.version`) |
| `org.postgresql:postgresql` | 42.7.3 | JDBC (health check e failover) | `pom.xml` |
| `org.yaml:snakeyaml` | 2.2 | Leitura do `node.yaml` | `pom.xml` |
| `com.google.code.gson:gson` | 2.10.1 | JSON do log e do estado Raft | `pom.xml` |
| `org.slf4j:slf4j-api` | 2.0.13 | API de logging | `pom.xml` |
| `ch.qos.logback:logback-classic` | 1.5.6 | Implementação de logging | `pom.xml` |
| `javax.annotation:javax.annotation-api` | 1.3.2 | Anotações do código gerado | `pom.xml` |

### 3.3 Plugins e extensões de build

| Plugin | Versão | Função | Origem |
|---|---|---|---|
| `kr.motd.maven:os-maven-plugin` (extension) | 1.7.1 | Detecta o SO para o `protoc` | `pom.xml` |
| `org.xolstice.maven.plugins:protobuf-maven-plugin` | 0.6.1 | Gera Java a partir dos `.proto` (`compile` + `compile-custom`) | `pom.xml` |
| `protoc` | 3.25.3 | Compilador Protobuf | `pom.xml`; `target/protoc-plugins/protoc-3.25.3-windows-x86_64.exe` |
| `protoc-gen-grpc-java` | 1.63.0 | Gerador dos stubs gRPC | `pom.xml`; `target/protoc-plugins/protoc-gen-grpc-java-1.63.0-windows-x86_64.exe` |
| `maven-jar-plugin` | 3.3.0 | Manifest com `Main-Class: com.tcc.raft.Main` | `pom.xml` |
| `maven-shade-plugin` | 3.5.2 | Fat JAR na fase `package`, com `ServicesResourceTransformer` para o gRPC | `pom.xml` |

> O `pom.xml` fixa `os.detected.classifier=windows-x86_64` como workaround para o VS Code/m2e (`pom.xml`, comentário em `<properties>`). **[inferido]** Para compilar em Linux ou macOS, talvez seja preciso remover essa propriedade ou sobrescrevê-la (`-Dos.detected.classifier=linux-x86_64`).

### 3.4 Infraestrutura de execução

| Item | Versão | Origem |
|---|---|---|
| SO das VMs | Ubuntu Server 22.04 LTS | `raft-ha-manager/scripts/README.md:11`; `scripts/vm-create.ps1:14` |
| JRE nas VMs | `openjdk-21-jre-headless` (pacote apt, sem versão fixa) | `scripts/setup/01-base.sh:65` |
| PostgreSQL | 16 | `scripts/setup/02-postgres-primary.sh:27`; `scripts/config/node-*.yaml` (`data-dir`) |
| Virtualização | VirtualBox (`VBoxManage`), rede Host-Only 192.168.56.0/24 | `raft-ha-manager/scripts/vm-create.ps1` |
| Supervisão | systemd (unit `raft-ha-manager.service`) | `scripts/setup/04-deploy-raft.sh:90-130` |
| Documentação | LaTeX (Beamer em `apresentacao.tex`); BibTeX em `bibliografia.bib` | `docs/apresentacao.tex:1`; `docs/bibliografia.bib` |

---

## 4. Serviços, portas e endpoints

### 4.1 Portas

| Porta | Protocolo | Serviço | Origem |
|---|---|---|---|
| 9090/tcp | gRPC (HTTP/2, plaintext) | `raft-ha-manager` (Raft + gerenciamento) | `scripts/config/node-*.yaml` (`grpc-address`); `01-base.sh:31,105` |
| 5432/tcp | PostgreSQL | Banco local; UFW só aceita conexões de 192.168.56.0/24 | `01-base.sh:32,108`; `node-*.yaml` (`postgres.port`) |
| 22/tcp | SSH | Administração das VMs | `01-base.sh:102` |

A porta gRPC vem do `grpc-address` de cada nó (`NodeConfig.getGrpcPort`, `config/NodeConfig.java:156-158`). O servidor faz bind em todas as interfaces na porta indicada (`ServerBuilder.forPort`, `grpc/RaftGrpcServer.java:29`).

### 4.2 Endpoints gRPC

**`raft.RaftService`**: comunicação entre nós (`src/main/proto/raft.proto:128-134`)

| RPC | Request → Response | Quem chama | Implementação |
|---|---|---|---|
| `RequestVote` | `RequestVoteRequest{term, candidate_id, last_log_index, last_log_term}` → `RequestVoteResponse{term, vote_granted}` | Candidatos | `RaftServiceImpl.requestVote` → `RaftNode.handleRequestVote` |
| `AppendEntries` | `AppendEntriesRequest{term, leader_id, prev_log_index, prev_log_term, entries[], leader_commit}` → `AppendEntriesResponse{term, success, match_index}` | Líder (heartbeat e replicação) | `RaftServiceImpl.appendEntries` → `RaftNode.handleAppendEntries` |

**`cluster.ClusterManagementService`**: administração (`src/main/proto/cluster.proto:122-128`)

| RPC | Comportamento atual | Origem |
|---|---|---|
| `GetClusterStatus` | Responde em qualquer nó com `leader_id`, `current_term` e `commit_index`. A lista `nodes` traz **apenas o próprio nó** (`pg_status` e `grpc_address` não são preenchidos) | `grpc/ClusterManagementServiceImpl.java:38-56` |
| `SubmitConfigChange` | Um seguidor devolve `accepted=false` e o `leader_id`. O líder também devolve `accepted=false` ("ainda não implementado") | `grpc/ClusterManagementServiceImpl.java:64-88` (TODO "Fase 3") |

Nenhum endpoint HTTP/REST foi encontrado. **[inferido]** O servidor não registra gRPC reflection (`RaftGrpcServer.java:29-34`), então ferramentas como `grpcurl` precisam receber os arquivos `.proto` (`-proto`).

### 4.3 Serviço systemd

`raft-ha-manager.service` roda como `User=raft`, com `Restart=on-failure` e `RestartSec=10s`, logando no journald (`SyslogIdentifier=raft-ha-manager`). `ExecStart` é `java -Dnode.id=… -Dlog.dir=… -jar /opt/raft-ha-manager/raft-ha-manager-1.0-SNAPSHOT.jar /etc/raft-ha-manager/node.yaml` (`scripts/setup/04-deploy-raft.sh:90-130`).

Caminhos nas VMs (`01-base.sh:77-79`; `04-deploy-raft.sh:31-35`):

- `/opt/raft-ha-manager/`: JAR e `recovery-standby.sh`
- `/etc/raft-ha-manager/node.yaml`: configuração
- `/var/lib/raft-ha-manager/`: estado Raft e `logs/`

---

## 5. Variáveis de ambiente e configuração (só nomes)

### 5.1 Variáveis de ambiente

O código Java **não lê variáveis de ambiente**: nenhuma chamada a `System.getenv` em `raft-ha-manager/src/`. **[inferido]** por busca no código. Os nomes abaixo aparecem só em scripts e na unit systemd:

| Nome | Onde | Observação |
|---|---|---|
| `JAVA_OPTS` | `scripts/setup/04-deploy-raft.sh:117` (unit systemd) | Definida, mas **não é usada** no `ExecStart`. **[inferido]** Não tem efeito sobre a JVM |
| `PGPASSWORD` | `scripts/setup/03-postgres-standby.sh:79`; `scripts/setup/recovery-standby.sh:36` | Senha de replicação usada pelo `pg_basebackup` |
| `USERPROFILE` | `raft-ha-manager/scripts/vm-create.ps1:38` | Diretório padrão das VMs (Windows) |

### 5.2 Propriedades de sistema da JVM (`-D`)

| Propriedade | Padrão | Uso | Origem |
|---|---|---|---|
| `log.dir` | `/var/lib/raft-ha-manager/logs` | Diretório dos arquivos de log | `logback.xml:11`; `04-deploy-raft.sh:105` |
| `node.id` | `unknown` | Aparece no padrão de log. O `Main` também define esse valor a partir do YAML | `logback.xml:12`; `Main.java:61`; `04-deploy-raft.sh:104` |

### 5.3 Chaves do `node.yaml`

Referência comentada: `raft-ha-manager/src/main/resources/node.yaml.example`. Parser: `config/*.java`.

| Chave | Obrigatória | Padrão |
|---|---|---|
| `node-id` | sim | — |
| `grpc-address` (`host:porta`) | sim | — |
| `arbiter` | não | `false` |
| `postgres.host` / `port` / `database` | não | `127.0.0.1` / `5432` / `postgres` |
| `postgres.user` | sim (se não for árbitro) | — |
| `postgres.password` | não (sensível) | — |
| `postgres.data-dir` | sim (se não for árbitro) | — |
| `postgres.health-check-interval-ms` | não | `3000` |
| `postgres.health-check-failure-threshold` | não | `3` |
| `raft.heartbeat-interval-ms` | não | `5000` |
| `raft.election-timeout-min-ms` / `max-ms` | não | `15000` / `30000` |
| `raft.rpc-timeout-ms` | não | `3000` |
| `storage.log-dir` | não | `/var/lib/raft-ha-manager` |
| `peers[].id` / `grpc-address` / `arbiter` | sim (lista inclui o próprio nó) | — |

`node.yaml` está no `.gitignore` (`raft-ha-manager/.gitignore`), mas os arquivos por nó em `raft-ha-manager/scripts/config/node-*.yaml` são versionados.

> ⚠️ **Segredos versionados:** `raft-ha-manager/scripts/config/node-1.yaml` e `node-2.yaml`, `raft-ha-manager/scripts/README.md`, `scripts/setup/02-postgres-primary.sh`, `03-postgres-standby.sh` e `scripts/setup/recovery-standby.sh` contêm credenciais padrão de simulação em texto puro (replicação e usuário de monitoramento). Os valores foram omitidos aqui de propósito. **[inferido]** Para qualquer uso fora do laboratório, mova essas credenciais para fora do Git (por exemplo, `.pgpass` ou um arquivo de ambiente lido pelo systemd).

Usuários de banco criados pelos scripts (só nomes): `replicator` (REPLICATION) e `raft_monitor` (`pg_monitor` + `EXECUTE` em `pg_promote`) (`02-postgres-primary.sh:23-128`). Usuário de SO: `raft`, com sudoers restrito a `systemctl stop/start postgresql` e a `recovery-standby.sh` (`01-base.sh:73-91`).

---

## 6. Como rodar

### 6.1 Build

```bash
cd raft-ha-manager
mvn package -DskipTests
```

O comando gera `target/raft-ha-manager-1.0-SNAPSHOT.jar` (`raft-ha-manager/scripts/README.md:14`). **[inferido]** Como o `maven-shade-plugin` roda na fase `package` sem `shadedArtifactAttached`, esse JAR já é o fat JAR. O comentário do `pom.xml` fala em `mvn package -P fat-jar`, mas **não existe profile `fat-jar`** no `pom.xml`. O código Protobuf/gRPC é gerado em `target/generated-sources/protobuf/` durante o build (`pom.xml`, comentário do plugin protobuf).

### 6.2 Execução local (um nó)

```bash
java -Dnode.id=node-1 -Dlog.dir=./logs -jar target/raft-ha-manager-1.0-SNAPSHOT.jar caminho/para/node.yaml
```

O uso `java -jar raft-ha-manager.jar <node.yaml>` vem de `Main.java:31-44`. **[inferido]** Os `-D` são opcionais. Sem `-Dlog.dir`, o Logback tenta gravar em `/var/lib/raft-ha-manager/logs`. Para testar localmente com vários processos, use portas `grpc-address` diferentes e um `storage.log-dir` diferente para cada nó.

### 6.3 Ambiente de simulação completo (3 VMs)

Resumo de `raft-ha-manager/scripts/README.md`:

1. **Criar VMs** (Windows, PowerShell como administrador): `.\vm-create.ps1 -IsoPath <iso> -Nodes 3`. Aceita 3 ou 5 nós (`vm-create.ps1:34`).
2. **Instalar o Ubuntu Server 22.04** em cada VM, com OpenSSH.
3. **Base em todos os nós:** `sudo bash 01-base.sh <node-id> <ip>`. Configura IP estático, Java 21, usuário `raft`, UFW, `/etc/hosts` e sudoers.
4. **Primary (node-1):** `sudo bash 02-postgres-primary.sh`.
5. **Standby (node-2):** `sudo bash 03-postgres-standby.sh 192.168.56.101`.
6. **Deploy (todos os nós):** copie o JAR, o `04-deploy-raft.sh` e `scripts/config/node-N.yaml` como `~/node.yaml`, depois rode `sudo bash 04-deploy-raft.sh node-N`.
7. **Subir o cluster:** `sudo systemctl start raft-ha-manager` em cada nó. Acompanhe com `sudo journalctl -u raft-ha-manager -f`.

Pontos de atenção:

- `02-postgres-primary.sh:38-48` e `03-postgres-standby.sh:44-54` estão com a **instalação do PostgreSQL comentada**. **[inferido]** O PostgreSQL 16 precisa estar instalado antes (no Ubuntu 22.04, pelo repositório PGDG).
- `recovery-standby.sh` fica em `scripts/setup/` na raiz do repositório, não em `raft-ha-manager/scripts/setup/`. O `04-deploy-raft.sh:79-83` só instala esse script se ele estiver no diretório corrente, e o README de scripts não manda copiá-lo. **[inferido]** Copie-o manualmente para cada nó com PostgreSQL, senão o Self-Healing falha.
- O comentário em `scripts/config/node-3.yaml:10-12` diz que a seção `postgres` é necessária para o árbitro. A validação atual **dispensa** essa seção quando `arbiter: true` (`NodeConfig.java:89-94`), então o comentário está desatualizado.

---

## 7. Como testar

- **Testes automatizados:** **não existem**. Não há `src/test/` em `raft-ha-manager/` e o `pom.xml` não declara JUnit nem outra dependência de teste. `mvn test` só compila.
- **Validação manual (cenários do TCC)** **[inferido]**, a partir do fluxo implementado em `RaftNode.java` e dos cenários descritos em `docs/analise_completa_codebase.md` (§6 e §8):
  1. **Queda do líder:** `sudo systemctl stop raft-ha-manager` no nó líder. Resultado esperado: após 15–30 s, o outro nó com PostgreSQL é eleito e promove o banco. Confira com `SELECT pg_is_in_recovery();` e procure `FAILOVER` no journal.
  2. **Falha do PostgreSQL no líder:** `sudo systemctl stop postgresql` no líder. Resultado esperado: após cerca de 3 × 3 s o banco fica UNHEALTHY, o líder renuncia e acontece uma nova eleição.
  3. **Partição de rede / split-brain:** isole o antigo primary e depois reconecte. Resultado esperado: o nó volta como FOLLOWER, detecta que o banco local está PRIMARY e executa Fencing + `recovery-standby.sh`.
  4. **Status do cluster:** chame `cluster.ClusterManagementService/GetClusterStatus` na porta 9090 com um cliente gRPC que receba os `.proto` de `raft-ha-manager/src/main/proto/`.
- **Verificação da replicação:** no primary, `sudo -u postgres psql -c "SELECT * FROM pg_stat_replication;"` (`03-postgres-standby.sh:180-181`).

---

## 8. Pendências conhecidas no código

| Item | Origem |
|---|---|
| `SubmitConfigChange` não cria a entrada `CONFIG_CHANGE` | `grpc/ClusterManagementServiceImpl.java:18-19, 79-87` |
| Aplicar `CONFIG_CHANGE` não atualiza a lista de pares | `core/RaftNode.java:753-756` |
| `FailoverExecutor.reconfigureStandby` existe, mas nada no código o chama. **[inferido]** Os standbys restantes não são reapontados automaticamente para o novo primary | `postgres/FailoverExecutor.java:87-121`; busca de uso em `src/` |
| A entrada `FAILOVER` é gravada com `old_leader_id = "unknown"` | `core/RaftNode.java:783` |
| gRPC sem TLS (plaintext) | `grpc/RaftGrpcClient.java:44-47` |

Uma análise mais extensa de issues está em `docs/analise_completa_codebase.md` (§3 e §4).
