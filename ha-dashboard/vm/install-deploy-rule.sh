#!/bin/bash
# =============================================================================
# install-deploy-rule.sh — Libera o deploy do agente sem senha, e só ele
#
# Instala /usr/local/sbin/ha-deploy-agent e uma regra de sudoers que permite ao
# usuário do painel executar apenas esse script (instalar o JAR novo ou voltar o
# anterior). O script não reinicia nada.
#
# Dois modos:
#   uso único (padrão)  a regra se remove sozinha depois do primeiro deploy
#   --permanente        a regra fica até ser removida à mão (ciclo de desenvolvimento)
#
# Execute em cada VM (pede a senha do sudo uma única vez):
#   scp ha-deploy-agent.sh install-deploy-rule.sh ubuntu@<ip>:~/
#   ssh -t ubuntu@<ip> "sudo bash install-deploy-rule.sh --permanente"
#
# Para remover a regra:
#   sudo rm -f /etc/sudoers.d/ha-deploy /usr/local/sbin/ha-deploy-agent
# =============================================================================

set -euo pipefail

ONE_SHOT="true"
if [ "${1:-}" = "--permanente" ]; then
    ONE_SHOT="false"
    shift
fi
PANEL_USER="${1:-ubuntu}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${SCRIPT_DIR}/ha-deploy-agent.sh"
BIN="/usr/local/sbin/ha-deploy-agent"
SUDOERS_FILE="/etc/sudoers.d/ha-deploy"

[ "$(id -u)" -eq 0 ] || { echo "Execute como root: sudo bash install-deploy-rule.sh [--permanente] [usuario]" >&2; exit 1; }
id "$PANEL_USER" >/dev/null 2>&1 || { echo "Usuário '$PANEL_USER' não existe." >&2; exit 1; }
[ -f "$SRC" ] || { echo "ha-deploy-agent.sh não encontrado ao lado deste script." >&2; exit 1; }

echo "[1/2] Instalando ${BIN}..."
TMPBIN="$(mktemp)"
sed -e "s/__PANEL_USER__/${PANEL_USER}/" -e "s/__ONE_SHOT__/${ONE_SHOT}/" "$SRC" > "$TMPBIN"
install -o root -g root -m 755 "$TMPBIN" "$BIN"
rm -f "$TMPBIN"

echo "[2/2] Criando ${SUDOERS_FILE}..."
if [ "$ONE_SHOT" = "true" ]; then
    MODE_NOTE="Uso único: o próprio ha-deploy-agent remove este arquivo depois de instalar o JAR."
else
    MODE_NOTE="Permanente: remova à mão quando o ciclo de desenvolvimento terminar."
fi
TMP="$(mktemp)"
cat > "$TMP" << EOF
# Deploy do raft-ha-manager (install-deploy-rule.sh).
# ${MODE_NOTE}
${PANEL_USER} ALL=(root) NOPASSWD: ${BIN}
EOF
visudo -cf "$TMP"
install -o root -g root -m 440 "$TMP" "$SUDOERS_FILE"
rm -f "$TMP"

echo ""
if [ "$ONE_SHOT" = "true" ]; then
    echo "Pronto. Regra de uso único instalada para '${PANEL_USER}'."
else
    echo "Pronto. Regra permanente instalada para '${PANEL_USER}'."
fi
