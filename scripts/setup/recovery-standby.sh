#!/bin/bash
# =============================================================================
# recovery-standby.sh — Recupera um PostgreSQL para o estado de Standby
#
# Este script é chamado automaticamente pelo raft-ha-manager (Auto-Healing)
# quando detecta que o nó local está operando como um Rogue Primary
# (Split-Brain). Ele clona o banco a partir do líder legítimo atual.
#
# Antes de mexer no banco local, confere que a origem é de fato um PRIMARY:
# clonar um standby deixaria o cluster sem nenhum PRIMARY. Os dados antigos
# só são descartados depois que o clone termina: ficam em main.anterior e
# voltam para o lugar se o pg_basebackup falhar.
#
# Uso (como root ou via sudo):
#   sudo bash recovery-standby.sh <ip-do-lider>
#
# Códigos de saída:
#   0  clone concluído; o PostgreSQL local é STANDBY do líder
#   1  falha durante o clone; os dados anteriores foram restaurados
#   2  a origem não é um PRIMARY alcançável; nada foi alterado
# =============================================================================

set -euo pipefail

# ─── Argumentos ───────────────────────────────────────────────────────────────
LEADER_IP="${1:?'Erro: informe o IP do novo líder. Exemplo: sudo bash recovery-standby.sh 192.168.56.102'}"

DATA_DIR=/var/lib/postgresql/16/main
BACKUP_DIR=/var/lib/postgresql/16/main.anterior
REPL_CONN="host=${LEADER_IP} user=replicator connect_timeout=5"
REPL_PASS='repl_pass_123'

echo "============================================================"
echo "  Iniciando Auto-Healing do PostgreSQL"
echo "  Clonando do líder: ${LEADER_IP}"
echo "============================================================"

# 1. Confere que a origem é um PRIMARY, pela mesma conexão de replicação usada no
#    clone. in_hot_standby = off -> PRIMARY; on -> STANDBY.
echo "[1/5] Verificando se ${LEADER_IP} é PRIMARY..."
if ! SOURCE_STANDBY=$(sudo -u postgres env PGPASSWORD="${REPL_PASS}" \
        psql "${REPL_CONN} replication=true" -XAtc "SHOW in_hot_standby" 2>&1); then
    echo "  ERRO: não foi possível consultar ${LEADER_IP}: ${SOURCE_STANDBY}"
    echo "  Nada foi alterado. Religando o PostgreSQL local."
    systemctl start postgresql
    exit 2
fi
if [ "${SOURCE_STANDBY}" != "off" ]; then
    echo "  ERRO: ${LEADER_IP} não é PRIMARY (in_hot_standby=${SOURCE_STANDBY})."
    echo "  Clonar um standby deixaria o cluster sem PRIMARY. Nada foi alterado."
    echo "  Religando o PostgreSQL local."
    systemctl start postgresql
    exit 2
fi
echo "  OK: ${LEADER_IP} é PRIMARY."

# 2. Garante que o PostgreSQL local está parado
echo "[2/5] Parando serviço PostgreSQL local..."
systemctl stop postgresql

# 3. Guarda os dados atuais (uma cópia só: a anterior é descartada)
echo "[3/5] Guardando os dados atuais em ${BACKUP_DIR}..."
rm -rf "${BACKUP_DIR}"
mv "${DATA_DIR}" "${BACKUP_DIR}"
install -d -m 700 -o postgres -g postgres "${DATA_DIR}"

restore_previous() {
    echo "  Restaurando os dados anteriores..."
    rm -rf "${DATA_DIR}"
    mv "${BACKUP_DIR}" "${DATA_DIR}"
    systemctl start postgresql || true
}

# 4. Executa o pg_basebackup
echo "[4/5] Clonando dados do líder (${LEADER_IP})..."
# -d: conexão, -D: diretório, -R: cria standby.signal, -P: progresso, -Xs: stream WAL
if ! sudo -u postgres env PGPASSWORD="${REPL_PASS}" \
        pg_basebackup -d "${REPL_CONN}" -D "${DATA_DIR}" -R -P -Xs; then
    echo "  ERRO: pg_basebackup falhou."
    restore_previous
    exit 1
fi

# 5. Reinicia o PostgreSQL
echo "[5/5] Iniciando PostgreSQL como STANDBY..."
systemctl start postgresql

# Verifica se entrou em modo de recuperação
sleep 2
IS_IN_RECOVERY=$(sudo -u postgres psql -t -c "SELECT pg_is_in_recovery();" | xargs)

if [ "${IS_IN_RECOVERY}" == "t" ]; then
    echo "============================================================"
    echo "  Auto-Healing concluído com sucesso!"
    echo "  O PostgreSQL local agora é um STANDBY."
    echo "  Dados anteriores guardados em ${BACKUP_DIR}."
    echo "============================================================"
    exit 0
else
    echo "============================================================"
    echo "  ERRO: O PostgreSQL não entrou em modo de recuperação."
    echo "============================================================"
    exit 1
fi
