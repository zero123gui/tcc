#!/bin/bash
# =============================================================================
# recovery-standby.sh — Recupera um PostgreSQL para o estado de Standby
#
# Este script é chamado automaticamente pelo raft-ha-manager (Auto-Healing)
# quando detecta que o nó local está operando como um Rogue Primary
# (Split-Brain). Ele limpa o banco local corrompido/divergente e o
# clona a partir do líder legítimo atual.
#
# Uso (como root ou via sudo):
#   sudo bash recovery-standby.sh <ip-do-lider>
# =============================================================================

set -euo pipefail

# ─── Argumentos ───────────────────────────────────────────────────────────────
LEADER_IP="${1:?'Erro: informe o IP do novo líder. Exemplo: sudo bash recovery-standby.sh 192.168.56.102'}"

echo "============================================================"
echo "  Iniciando Auto-Healing do PostgreSQL"
echo "  Clonando do líder: ${LEADER_IP}"
echo "============================================================"

# 1. Garante que o PostgreSQL local está parado
echo "[1/4] Parando serviço PostgreSQL local..."
systemctl stop postgresql

# 2. Limpa o diretório de dados (inclusive arquivos ocultos)
echo "[2/4] Limpando diretório de dados inconsistente..."
rm -rf /var/lib/postgresql/16/main/*
rm -rf /var/lib/postgresql/16/main/.* 2>/dev/null || true

# 3. Executa o pg_basebackup
echo "[3/4] Clonando dados do líder (${LEADER_IP})..."
# -h: host, -U: usuário, -D: diretório, -R: cria standby.signal, -P: progresso, -Xs: stream WAL
sudo -u postgres bash -c "PGPASSWORD='repl_pass_123' pg_basebackup -h ${LEADER_IP} -U replicator -D /var/lib/postgresql/16/main -R -P -Xs"

# 4. Reinicia o PostgreSQL
echo "[4/4] Iniciando PostgreSQL como STANDBY..."
systemctl start postgresql

# Verifica se entrou em modo de recuperação
sleep 2
IS_IN_RECOVERY=$(sudo -u postgres psql -t -c "SELECT pg_is_in_recovery();" | xargs)

if [ "${IS_IN_RECOVERY}" == "t" ]; then
    echo "============================================================"
    echo "  Auto-Healing concluído com sucesso!"
    echo "  O PostgreSQL local agora é um STANDBY."
    echo "============================================================"
    exit 0
else
    echo "============================================================"
    echo "  ERRO: O PostgreSQL não entrou em modo de recuperação."
    echo "============================================================"
    exit 1
fi
