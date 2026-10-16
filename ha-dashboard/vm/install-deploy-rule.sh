#!/bin/bash
# =============================================================================
# install-deploy-rule.sh — Libera UM deploy do agente sem senha, e só esse
#
# Instala /usr/local/sbin/ha-deploy-agent e uma regra de sudoers que permite ao
# usuário do painel executar apenas esse script. O script remove a própria regra
# depois do primeiro deploy bem-sucedido.
#
# Execute em cada VM (pede a senha do sudo uma única vez):
#   scp ha-deploy-agent.sh install-deploy-rule.sh ubuntu@<ip>:~/
#   ssh -t ubuntu@<ip> "sudo bash install-deploy-rule.sh"
#
# Para cancelar antes de usar:
#   sudo rm -f /etc/sudoers.d/ha-deploy /usr/local/sbin/ha-deploy-agent
# =============================================================================

set -euo pipefail

PANEL_USER="${1:-ubuntu}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${SCRIPT_DIR}/ha-deploy-agent.sh"
BIN="/usr/local/sbin/ha-deploy-agent"
SUDOERS_FILE="/etc/sudoers.d/ha-deploy"

[ "$(id -u)" -eq 0 ] || { echo "Execute como root: sudo bash install-deploy-rule.sh [usuario]" >&2; exit 1; }
id "$PANEL_USER" >/dev/null 2>&1 || { echo "Usuário '$PANEL_USER' não existe." >&2; exit 1; }
[ -f "$SRC" ] || { echo "ha-deploy-agent.sh não encontrado ao lado deste script." >&2; exit 1; }

echo "[1/2] Instalando ${BIN}..."
TMPBIN="$(mktemp)"
sed "s/__PANEL_USER__/${PANEL_USER}/" "$SRC" > "$TMPBIN"
install -o root -g root -m 755 "$TMPBIN" "$BIN"
rm -f "$TMPBIN"

echo "[2/2] Criando ${SUDOERS_FILE}..."
TMP="$(mktemp)"
cat > "$TMP" << EOF
# Deploy de uso único do raft-ha-manager (install-deploy-rule.sh).
# O próprio ha-deploy-agent remove este arquivo depois de instalar o JAR.
${PANEL_USER} ALL=(root) NOPASSWD: ${BIN}
EOF
visudo -cf "$TMP"
install -o root -g root -m 440 "$TMP" "$SUDOERS_FILE"
rm -f "$TMP"

echo ""
echo "Pronto. Regra de uso único instalada para '${PANEL_USER}'."
