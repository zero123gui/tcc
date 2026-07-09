#!/bin/bash
# =============================================================================
# 02-postgres-primary.sh — Instala e configura o PostgreSQL Primary
#
# Execute APENAS no node-1 (192.168.56.101), depois que 01-base.sh já tiver
# sido executado com sucesso.
#
# O que este script faz:
#   1. Adiciona o repositório oficial do PostgreSQL
#   2. Instala o PostgreSQL 16
#   3. Configura postgresql.conf para Streaming Replication
#   4. Configura pg_hba.conf para aceitar conexões dos standbys e do raft
#   5. Cria o usuário de replicação e o usuário de monitoramento do raft
#   6. Reinicia o PostgreSQL e verifica o status
#
# Uso (como root no node-1):
#   sudo bash 02-postgres-primary.sh
# =============================================================================

set -euo pipefail

# ─── Credenciais (modifique se quiser senhas diferentes) ──────────────────────
REPL_USER="replicator"
REPL_PASS="repl_pass_123"
RAFT_MONITOR_USER="raft_monitor"
RAFT_MONITOR_PASS="raft_mon_123"
PG_VERSION="16"
PG_DATA="/var/lib/postgresql/${PG_VERSION}/main"
PG_CONF="/etc/postgresql/${PG_VERSION}/main"

echo "============================================================"
echo "  Configurando PostgreSQL ${PG_VERSION} PRIMARY"
echo "============================================================"

# ─── 1. Instalar PostgreSQL 16 ────────────────────────────────────────────────
echo "[1/5] Instalando PostgreSQL ${PG_VERSION}..."

# apt-get install -y -q curl ca-certificates
# install -d /usr/share/postgresql-common/pgdg
# curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc --fail \
#     https://www.postgresql.org/media/keys/ACCC4CF8.asc

# sh -c "echo 'deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] \
#     https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main' \
#     > /etc/apt/sources.list.d/pgdg.list"

# apt-get update -y -q
# apt-get install -y -q postgresql-${PG_VERSION}

# Para o PostgreSQL — vamos configurar antes de iniciar com os dados corretos
systemctl stop postgresql

echo "  PostgreSQL ${PG_VERSION} instalado."

# ─── 2. Configurar postgresql.conf ────────────────────────────────────────────
echo "[2/5] Configurando postgresql.conf para Streaming Replication..."

# Faz backup da configuração original
cp ${PG_CONF}/postgresql.conf ${PG_CONF}/postgresql.conf.original

cat >> ${PG_CONF}/postgresql.conf << EOF

# ============================================================
# Configurações adicionadas pelo setup do raft-ha-manager
# ============================================================

# Networking
listen_addresses = '*'

# Streaming Replication
wal_level = replica
max_wal_senders = 10
wal_keep_size = 256
max_replication_slots = 5

# Hot Standby (permite consultas de leitura nos standbys)
hot_standby = on

# Logging (facilita depuração durante os experimentos)
log_connections = on
log_disconnections = on
log_replication_commands = on
log_line_prefix = '%t [%p]: [%l-1] user=%u,db=%d,app=%a,client=%h '
EOF

echo "  postgresql.conf configurado."

# ─── 3. Configurar pg_hba.conf ────────────────────────────────────────────────
echo "[3/5] Configurando pg_hba.conf..."

cp ${PG_CONF}/pg_hba.conf ${PG_CONF}/pg_hba.conf.original

cat >> ${PG_CONF}/pg_hba.conf << EOF

# ============================================================
# Regras adicionadas pelo setup do raft-ha-manager
# ============================================================

# Permite que standbys se conectem para replicação (toda a rede do cluster)
host    replication     ${REPL_USER}    192.168.56.0/24     scram-sha-256

# Permite que o raft-ha-manager (PostgresHealthChecker e FailoverExecutor)
# se conecte ao banco de dados postgres
host    all             ${RAFT_MONITOR_USER}   127.0.0.1/32    scram-sha-256
host    all             ${RAFT_MONITOR_USER}   192.168.56.0/24 scram-sha-256
EOF

echo "  pg_hba.conf configurado."

# ─── 4. Criar usuários no PostgreSQL ──────────────────────────────────────────
echo "[4/5] Criando usuários no PostgreSQL..."

# Inicia o PostgreSQL temporariamente para criar os usuários
systemctl start postgresql
sleep 3

sudo -u postgres psql << SQL
-- Usuário de replicação (usado pelos standbys para se conectar ao primary)
CREATE USER ${REPL_USER} WITH REPLICATION ENCRYPTED PASSWORD '${REPL_PASS}';

-- Usuário de monitoramento do raft-ha-manager
-- Precisa de pg_monitor para executar pg_is_in_recovery() e pg_promote()
CREATE USER ${RAFT_MONITOR_USER} WITH ENCRYPTED PASSWORD '${RAFT_MONITOR_PASS}';
GRANT pg_monitor TO ${RAFT_MONITOR_USER};

-- Concede permissão para executar pg_promote() (necessário para o failover)
-- No PostgreSQL 16, esta função requer permissão de superusuário ou pg_promote
GRANT EXECUTE ON FUNCTION pg_promote(boolean, integer) TO ${RAFT_MONITOR_USER};

\du
SQL

echo "  Usuários criados."

# ─── 5. Reiniciar e verificar ─────────────────────────────────────────────────
echo "[5/5] Reiniciando PostgreSQL e verificando status..."

systemctl restart postgresql
sleep 3

# Verifica se está em execução como primary
sudo -u postgres psql -c "SELECT version();"
sudo -u postgres psql -c "SELECT pg_is_in_recovery();"  # Deve retornar 'f' (false = primary)
sudo -u postgres psql -c "SELECT * FROM pg_stat_replication;"  # Vazio por agora (sem standbys ainda)

systemctl enable postgresql
systemctl status postgresql --no-pager

# ─── Concluído ────────────────────────────────────────────────────────────────
echo ""
echo "============================================================"
echo "  PostgreSQL ${PG_VERSION} PRIMARY configurado com sucesso!"
echo ""
echo "  Credenciais:"
echo "    Replicação: ${REPL_USER} / ${REPL_PASS}"
echo "    Monitoramento raft: ${RAFT_MONITOR_USER} / ${RAFT_MONITOR_PASS}"
echo ""
echo "  Próximo passo: execute 03-postgres-standby.sh no node-2"
echo "============================================================"
