# ha-dashboard

Painel local (roda no Windows) para observar e operar o cluster `raft-ha-manager` sem abrir sessões SSH manualmente:

- **Status dos nós:** papel Raft, mandato, líder visto e commit (gRPC `GetClusterStatus`); estado do PostgreSQL e da replicação (SQL direto do host); serviços systemd e partição (SSH).
- **Alertas do cluster:** dois líderes, dois primaries (split-brain), standby replicando do primary errado, nó inalcançável.
- **Logs ao vivo:** `journalctl -u raft-ha-manager -f` dos três nós, com destaque para eleição, liderança, failover, split-brain e self-healing.
- **Cenários de falha:** botões para parar/iniciar o agente e o PostgreSQL e para isolar um nó da rede do cluster.
- **Cronômetro de failover:** mede cada etapa do failover e grava os resultados em `results/experimentos.csv`, para o capítulo de resultados.

O painel não altera o agente Java. Ele só lê o `cluster.proto` em `../raft-ha-manager/src/main/proto/` para gerar o cliente gRPC.

```
Navegador ──WebSocket──► ha-dashboard (127.0.0.1:8000) ──SSH :22────► systemd, journalctl, ha-partition
                                                       ├─gRPC :9090──► GetClusterStatus
                                                       └─SQL :5432───► pg_is_in_recovery, pg_stat_replication
```

---

## Testar sem as VMs (modo demo)

```powershell
cd ha-dashboard
py -3.12 -m venv .venv
.venv\Scripts\pip install -r requirements-dev.txt
.venv\Scripts\python -m ha_dashboard --demo
```

Abra http://127.0.0.1:8000. O modo demo simula o cluster: as mensagens de log são as reais do agente, e os timeouts são menores para a demonstração ser rápida. Os experimentos vão para `results/experimentos-demo.csv`, separados dos dados reais.

---

## Usar com as VMs

Pré-requisito: o cluster montado conforme `raft-ha-manager/scripts/README.md`.

### 1. Chave SSH e acesso do painel em cada VM

Os dois passos estão no guia de instalação (`raft-ha-manager/scripts/README.md`, passos 3 e 7).
O primeiro `ssh` grava a chave de cada VM no `known_hosts`, que o painel exige por padrão
(`ssh.strict-host-keys`).

O `setup-vm-access.sh` faz três coisas:

- instala `/usr/local/sbin/ha-partition`;
- cria `/etc/sudoers.d/ha-dashboard`, que libera sem senha **somente** `systemctl start|stop` de `raft-ha-manager` e `postgresql` e o `ha-partition`;
- coloca o usuário `ubuntu` no grupo `systemd-journal`, para ler os logs sem sudo.

### 2. Configuração

```powershell
copy config.example.yaml config.yaml
```

Ajuste IPs e o caminho da chave, se necessário. O `config.yaml` é ignorado pelo Git.

A senha do usuário `raft_monitor` do PostgreSQL **não** fica em arquivo: o painel lê da variável de ambiente indicada em `postgres.password-env` (padrão `HA_PG_PASSWORD`). Defina-a na sessão do PowerShell antes de iniciar o painel. Sem ela, o painel funciona, mas mostra o PostgreSQL como "sem senha".

O painel acessa o PostgreSQL direto do Windows (192.168.56.1). Isso funciona porque o `pg_hba.conf` criado por `02-postgres-primary.sh` já libera o `raft_monitor` para 192.168.56.0/24.

### 3. Executar

```powershell
.
un.ps1
```

O `run.ps1` confere o ambiente Python, cria o `config.yaml` a partir do exemplo na primeira vez,
avisa se a porta 8000 já estiver em uso e pede a senha do `raft_monitor` sem mostrá-la na tela.
Depois abra http://127.0.0.1:8000.

Sem o script: defina `HA_PG_PASSWORD` e rode `.venv\Scripts\python -m ha_dashboard`.

---

## Cenários e experimentos

Um experimento começa automaticamente quando uma ação disruptiva é executada no **líder** ou no **primary** atual. Os tempos são medidos com o relógio do Windows, no instante em que cada linha de log chega ao painel ou em que o polling observa a mudança. Por isso não dependem de as VMs estarem com o relógio sincronizado.

| Marco (coluna do CSV) | Origem |
|---|---|
| `pg_unhealthy_s` | log "PostgreSQL declarado UNHEALTHY" no nó alvo |
| `step_down_s` | log de renúncia do líder anterior |
| `first_election_s` | primeiro log "iniciou eleição" em qualquer nó |
| `leader_elected_s` | log "eleito LÍDER" em um nó diferente do líder anterior |
| `promoting_s` / `promoted_s` | logs de início e de confirmação da promoção |
| `primary_visible_s` | polling SQL vê `pg_is_in_recovery() = false` em outro nó (resolução: `poll-interval-s`) |
| `split_brain_s` / `healed_s` | logs de fencing e self-healing no antigo primary (cenário de partição) |

O experimento termina sozinho quando há novo líder e novo primary visível **e** o agente registrou a confirmação da promoção. Se esse log não chegar em 5 s, o experimento encerra mesmo assim e anota isso nas observações. No cenário de partição, ele espera também o self-healing do antigo primary depois da reconexão. Também termina por timeout (`experiment-timeout-s`) ou pelo botão "Encerrar medição".

**Partição de rede:** `ha-partition` usa iptables (cadeias inseridas na posição 1 de INPUT/OUTPUT) e não `ufw deny`. O UFW aceita conexões já estabelecidas antes das regras do usuário, então os canais gRPC abertos continuariam funcionando. Só os IPs dos outros nós são bloqueados: o Windows continua acessando a VM isolada. As regras não sobrevivem a um reboot.

### Comportamentos do agente que o painel deixa visíveis

Estes pontos vêm do código atual do `raft-ha-manager`; o modo demo os reproduz:

- **Parar só o `raft-ha-manager` do líder** deixa o PostgreSQL dele rodando como PRIMARY. Depois do failover existem dois primaries até o agente desse nó voltar e executar o fencing.
- **Proteção do líder ativo** (pré-votação e check-quorum, ligadas por padrão no `node.yaml`). Um nó que volta ao cluster não derruba o líder: o painel mostra **PRÉ-VOTO NEGADO** nos outros nós e **PRÉ-VOTO SEM MAIORIA** no nó que tentou se candidatar. Um líder isolado renuncia depois de `election-timeout-min-ms` e aparece como **SEM QUÓRUM**. Com `pre-vote: false` e `check-quorum: false`, o comportamento antigo volta, o que permite comparar os dois nos experimentos.
- **Alinhamento entre o líder e o primary.** Se o standby vencer a primeira eleição (cluster religado), ele entrega a liderança ao nó do primary: o painel mostra **ENTREGA AO PRIMARY** no líder, **AGUARDA O PRIMARY** no standby que desiste de se candidatar e **FENCING ADIADO** no primary, que espera o líder ser PRIMARY antes de se clonar dele. **HEALING ABORTADO** aparece se o `recovery-standby.sh` recusar a origem por ela não ser PRIMARY.
- **Os standbys não são reapontados para o novo primary.** `FailoverExecutor.reconfigureStandby` não é chamado. Com dois nós com PostgreSQL isso não faz falta, porque o antigo primary volta pelo self-healing já apontando para o novo. Num cluster com mais de um standby, o painel avisa quando um deles continua replicando do primary antigo.

---

## Segurança

- O servidor só aceita `listen.host` de loopback (validado em `config.py`). Requisições com `Host` diferente de `127.0.0.1`/`localhost` são recusadas, o que protege contra DNS rebinding.
- Ações exigem o cabeçalho `X-HA-Dashboard`, que uma página de outra origem não consegue enviar sem preflight CORS. WebSockets conferem o `Origin`.
- O navegador só escolhe uma chave do catálogo fechado em `ha_dashboard/actions.py`; nenhum texto digitado vira comando. Os IPs usados na partição vêm do `config.yaml` e são validados como endereços IP.
- Não há terminal livre no navegador. Para algo fora do catálogo, use `ssh` normalmente.

---

## Estrutura

| Caminho | Conteúdo |
|---|---|
| `ha_dashboard/__main__.py` | Entrada (`--demo`, `--config`, `--results`) |
| `ha_dashboard/server.py` | FastAPI: página, REST, WebSocket e proteções de origem |
| `ha_dashboard/hub.py` | Polling, logs, ações, experimentos e difusão para os navegadores |
| `ha_dashboard/backend_real.py` | gRPC + asyncpg + asyncssh contra as VMs |
| `ha_dashboard/backend_demo.py` | Cluster simulado |
| `ha_dashboard/events.py` | Padrões das mensagens de log do agente Java |
| `ha_dashboard/experiments.py` | Cronômetro de failover e CSV |
| `ha_dashboard/summary.py` | Visão consolidada e alertas |
| `ha_dashboard/actions.py` | Catálogo fechado de comandos |
| `ha_dashboard/static/` | Interface (HTML/CSS/JS, sem dependências externas) |
| `vm/` | Scripts instalados nas VMs: `ha-partition.sh`, `setup-vm-access.sh`, `ha-deploy-agent.sh` e `install-deploy-rule.sh` |
| `scripts/` | Medições em lote e resumo estatístico (ver `results/README.md`) |
| `results/` | CSVs dos experimentos e relatórios |
| `run.ps1` | Inicia o painel pedindo a senha do banco |
| `tests/` | 56 testes (`.venv\Scripts\python -m pytest`) |

Se as mensagens de log do agente mudarem, atualize os padrões em `events.py`. Os testes em `tests/test_events.py` usam as mensagens atuais.
