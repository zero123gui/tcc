#!/bin/bash
# =============================================================================
# setup-vm-access.sh — Prepara uma VM do cluster para ser operada pelo ha-dashboard
#
# O que este script faz:
#   1. Instala /usr/local/sbin/ha-partition (simulação de partição de rede)
#   2. Cria /etc/sudoers.d/ha-dashboard liberando, sem senha, SÓ os comandos
#      que o painel executa (lista fechada, ver ha_dashboard/actions.py)
#   3. Adiciona o usuário ao grupo systemd-journal (leitura do journalctl sem sudo)
#
# Execute em CADA nó, depois de 01-base.sh:
#   scp ha-dashboard/vm/ha-partition.sh ha-dashboard/vm/setup-vm-access.sh ubuntu@<ip>:~/
#   ssh ubuntu@<ip> "sudo bash setup-vm-access.sh"
#
# Uso (como root):
#   sudo bash setup-vm-access.sh [usuario]      # padrão: ubuntu
# =============================================================================

set -euo pipefail

PANEL_USER="${1:-ubuntu}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARTITION_SRC="${SCRIPT_DIR}/ha-partition.sh"
PARTITION_BIN="/usr/local/sbin/ha-partition"
SUDOERS_FILE="/etc/sudoers.d/ha-dashboard"

if [ "$(id -u)" -ne 0 ]; then
    echo "Execute como root: sudo bash setup-vm-access.sh [usuario]" >&2
    exit 1
fi

if ! id "${PANEL_USER}" >/dev/null 2>&1; then
    echo "Usuário '${PANEL_USER}' não existe nesta VM." >&2
    exit 1
fi

if [ ! -f "${PARTITION_SRC}" ]; then
    echo "ha-partition.sh não encontrado ao lado deste script (${PARTITION_SRC})." >&2
    exit 1
fi

echo "============================================================"
echo "  Configurando acesso do ha-dashboard para '${PANEL_USER}'"
echo "============================================================"

# ─── 1. Script de partição ────────────────────────────────────────────────────
echo "[1/3] Instalando ${PARTITION_BIN}..."
install -o root -g root -m 755 "${PARTITION_SRC}" "${PARTITION_BIN}"

# ─── 2. sudoers restrito ──────────────────────────────────────────────────────
echo "[2/3] Criando ${SUDOERS_FILE}..."
TMP="$(mktemp)"
cat > "${TMP}" << EOF
# Comandos liberados para o ha-dashboard (gerado por setup-vm-access.sh).
# Mantenha em sincronia com ha_dashboard/actions.py.
${PANEL_USER} ALL=(root) NOPASSWD: /usr/bin/systemctl start raft-ha-manager, \\
    /usr/bin/systemctl stop raft-ha-manager, \\
    /usr/bin/systemctl start postgresql, \\
    /usr/bin/systemctl stop postgresql, \\
    ${PARTITION_BIN}
EOF
# Valida antes de instalar: um sudoers inválido bloqueia o sudo da VM inteira
visudo -cf "${TMP}"
install -o root -g root -m 440 "${TMP}" "${SUDOERS_FILE}"
rm -f "${TMP}"

# ─── 3. Leitura do journal ────────────────────────────────────────────────────
echo "[3/3] Adicionando '${PANEL_USER}' ao grupo systemd-journal..."
usermod -aG systemd-journal "${PANEL_USER}"

echo ""
echo "  Estado atual da partição: $(${PARTITION_BIN} status)"
echo ""
echo "============================================================"
echo "  Pronto. O grupo novo vale a partir da próxima sessão SSH"
echo "  (o painel reconecta sozinho)."
echo "============================================================"
