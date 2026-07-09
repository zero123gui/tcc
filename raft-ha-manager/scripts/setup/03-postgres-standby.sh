#!/bin/bash
# =============================================================================
# 03-postgres-standby.sh — Instala e configura um PostgreSQL Standby
#
# Execute nos nós standby (node-2, e node-3/4 no ambiente de 5 nós) DEPOIS
# que 01-base.sh e 02-postgres-primary.sh tiverem sido executados.
#
# O que este script faz:
#   1. Instala o PostgreSQL 16
#   2. Para o PostgreSQL local e limpa o diretório de dados
#   3. Executa pg_basebackup para clonar o primary
#   4. Configura primary_conninfo e cria o arquivo standby.signal
#   5. Inicia o PostgreSQL como standby e verifica a replicação
#
# Uso (como root no standby):
#   sudo bash 03-postgres-standby.sh <ip-do-primary>
#
# Exemplo:
#   sudo bash 03-postgres-standby.sh 192.168.56.101
# =============================================================================

set -euo pipefail

# ─── Argumentos ───────────────────────────────────────────────────────────────
PRIMARY_IP="${1:?'Erro: informe o IP do primary. Exemplo: sudo bash 03-postgres-standby.sh 192.168.56.101'}"

# ─── Credenciais (devem coincidir com as definidas em 02-postgres-primary.sh) ─
REPL_USER="replicator"
REPL_PASS="repl_pass_123"
RAFT_MONITOR_USER="raft_monitor"
RAFT_MONITOR_PASS="raft_mon_123"
PG_VERSION="16"
PG_DATA="/var/lib/postgresql/${PG_VERSION}/main"
PG_CONF="/etc/postgresql/${PG_VERSION}/main"

echo "============================================================"
echo "  Configurando PostgreSQL ${PG_VERSION} STANDBY"
echo "  Primary: ${PRIMARY_IP}"
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

# Para o PostgreSQL — vamos substituir o diretório de dados pelo basebackup
systemctl stop postgresql

echo "  PostgreSQL ${PG_VERSION} instalado."

# ─── 2. Limpar o diretório de dados existente ─────────────────────────────────
echo "[2/5] Limpando diretório de dados para receber o basebackup..."

# Remove o conteúdo do diretório de dados (mantém o diretório em si)
rm -rf ${PG_DATA}/*

echo "  Diretório de dados limpo."

# ─── 3. pg_basebackup: clona o primary ───────────────────────────────────────
echo "[3/5] Executando pg_basebackup do primary ${PRIMARY_IP}..."
echo "  (Isso pode levar alguns minutos dependendo do tamanho do banco)"

# pg_basebackup clona o diretório de dados inteiro do primary.
# -R: cria automaticamente o arquivo standby.signal e escreve primary_conninfo
# -P: exibe progresso
# -Xs: usa o método de streaming do WAL durante o backup (mais seguro)
# A senha é passada via PGPASSWORD dentro do bash -c para compatibilidade com sudo
sudo -u postgres bash -c "
    PGPASSWORD='${REPL_PASS}' pg_basebackup \
        -h ${PRIMARY_IP} \
        -U ${REPL_USER} \
        -D ${PG_DATA} \
        -R \
        -P \
        -Xs
"

# O pg_basebackup com -R cria standby.signal e escreve primary_conninfo
# no arquivo postgresql.auto.conf. Verificamos isso abaixo.
echo "  pg_basebackup concluído."

# ─── 4. Ajustar configuração do standby ───────────────────────────────────────
echo "[4/5] Ajustando configuração do standby..."

# Verifica se o pg_basebackup criou o standby.signal
if [ ! -f "${PG_DATA}/standby.signal" ]; then
    echo "  standby.signal não encontrado. Criando manualmente..."
    sudo -u postgres touch ${PG_DATA}/standby.signal
fi

# O primary_conninfo já foi escrito pelo pg_basebackup -R em postgresql.auto.conf.
# Verificamos e complementamos se necessário.
AUTOCONF="${PG_DATA}/postgresql.auto.conf"

if ! grep -q "primary_conninfo" ${AUTOCONF} 2>/dev/null; then
    echo "  primary_conninfo não encontrado. Adicionando manualmente..."
    sudo -u postgres bash -c "cat >> ${AUTOCONF} << EOF
primary_conninfo = 'host=${PRIMARY_IP} port=5432 user=${REPL_USER} password=${REPL_PASS} application_name=$(hostname)'
recovery_target_timeline = 'latest'
EOF"
fi

# Adiciona configurações de hot_standby ao postgresql.conf
cat >> ${PG_CONF}/postgresql.conf << EOF

# ============================================================
# Configurações adicionadas pelo setup do raft-ha-manager (standby)
# ============================================================

listen_addresses = '*'
hot_standby = on
hot_standby_feedback = on

# Logging
log_connections = on
log_disconnections = on
log_line_prefix = '%t [%p]: [%l-1] user=%u,db=%d,app=%a,client=%h '
EOF

# Adiciona regras ao pg_hba.conf (necessário para quando este nó se tornar PRIMARY)
cat >> ${PG_CONF}/pg_hba.conf << EOF

# ============================================================
# Regras adicionadas pelo setup do raft-ha-manager
# ============================================================

# Permite que outros standbys se conectem para replicação (toda a rede do cluster)
host    replication     ${REPL_USER}    192.168.56.0/24     scram-sha-256

# Permite conexões do raft-ha-manager para monitoramento (via JDBC)
host    all             ${RAFT_MONITOR_USER}  127.0.0.1/32        scram-sha-256
host    all             ${RAFT_MONITOR_USER}  192.168.56.0/24     scram-sha-256
EOF

# Cria o usuário de monitoramento do raft localmente
# (necessário para que o raft-ha-manager possa se conectar a este standby)
systemctl start postgresql
sleep 3

sudo -u postgres psql << SQL
CREATE USER ${RAFT_MONITOR_USER} WITH ENCRYPTED PASSWORD '${RAFT_MONITOR_PASS}';
GRANT pg_monitor TO ${RAFT_MONITOR_USER};
SQL

systemctl stop postgresql

echo "  Configuração do standby concluída."

# ─── 5. Iniciar e verificar ───────────────────────────────────────────────────
echo "[5/5] Iniciando PostgreSQL standby e verificando replicação..."

systemctl start postgresql
sleep 5

# pg_is_in_recovery() deve retornar 't' (true = standby em modo recovery)
RECOVERY_STATUS=$(sudo -u postgres psql -t -c "SELECT pg_is_in_recovery();" | tr -d '[:space:]')

if [ "$RECOVERY_STATUS" = "t" ]; then
    echo "  Standby está em modo de recovery. Replicação ativa."
else
    echo "  ATENÇÃO: pg_is_in_recovery() retornou '${RECOVERY_STATUS}'."
    echo "  Verifique os logs: sudo journalctl -u postgresql"
fi

systemctl enable postgresql
systemctl status postgresql --no-pager

# Verifica a replicação no primary (execute no primary para confirmar):
echo ""
echo "  Para confirmar a replicação, execute no NODE-1 (primary):"
echo "    sudo -u postgres psql -c \"SELECT * FROM pg_stat_replication;\""

# ─── Concluído ────────────────────────────────────────────────────────────────
echo ""
echo "============================================================"
echo "  PostgreSQL ${PG_VERSION} STANDBY configurado!"
echo "  Primary: ${PRIMARY_IP}"
echo "  Modo: hot_standby (aceita consultas de leitura)"
echo ""
echo "  Próximo passo: execute 04-deploy-raft.sh em TODOS os nós"
echo "============================================================"
