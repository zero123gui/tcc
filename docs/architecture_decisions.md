# Decisões de Arquitetura

Este documento registra as principais decisões de design e arquitetura do projeto `raft-ha-manager`.

## 1. Proteção contra Split-Brain no PostgreSQL

**Problema:**
Em um cluster de Alta Disponibilidade (HA) com o PostgreSQL, ferramentas de consenso baseadas em quorum (como Raft) garantem que apenas um nó possua a liderança no nível de gerência. Contudo, o Raft não gerencia as conexões do banco de dados diretamente. Se ocorrer uma partição de rede, o líder anterior pode ser rebaixado pelo Raft (por falta de quorum), mas o serviço PostgreSQL local continuará rodando como `PRIMARY` e aceitando gravações diretas de sistemas clientes (ex: SCADA). Simultaneamente, a outra partição da rede elegerá um novo líder Raft e promoverá seu PostgreSQL a `PRIMARY`. Isso resulta em dois bancos de dados independentes gravando dados diferentes, causando *Split-Brain*.

**Decisão:**
Implementar um modelo de **Fencing + Self-Healing Ativo**.
O `raft-ha-manager` (agente) em execução em cada nó monitorará ativamente o status de seu PostgreSQL local comparado ao seu papel atual no cluster Raft. 

* **Fencing (Isolamento Estrito):** Se o nó for um `FOLLOWER` no Raft, mas seu banco local reportar o estado de `PRIMARY`, o nó está em estado inconsistente (delusional leader). Imediatamente o agente Java executará um comando `sudo systemctl stop postgresql` para derrubar o banco de dados. Isso impede fisicamente que clientes SCADA continuem gravando dados em um banco obsoleto.
* **Self-Healing (Recuperação Automática):** Imediatamente após o Fencing, o agente identificará o IP do novo líder Raft legítimo do cluster e executará um script bash (`recovery-standby.sh`) que deletará o diretório de dados inconsistente e fará um novo clone (`pg_basebackup`) do líder atual. Em seguida, iniciará o PostgreSQL como `STANDBY`.

**Justificativa:**
Aplicações SCADA lidam com dados industriais críticos. Perder a disponibilidade temporária de um nó isolado é preferível a permitir a escrita dupla e consequente corrupção dos dados de operação (que poderiam causar falhas em maquinários industriais). O Self-Healing via clone garante um MTTD (Mean Time To Detection) e MTTR (Mean Time To Recovery) extremamente curtos.
