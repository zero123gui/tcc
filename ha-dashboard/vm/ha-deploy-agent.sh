#!/bin/bash
# =============================================================================
# ha-deploy-agent — Instala um novo JAR do raft-ha-manager (deploy de uso único)
#
# Instalado em /usr/local/sbin/ha-deploy-agent por install-deploy-rule.sh e chamado
# via "sudo -n". Faz só isto:
#   1. valida o JAR em /home/<usuario>/raft-ha-manager-1.0-SNAPSHOT.jar
#   2. copia para /opt/raft-ha-manager/ (troca atômica), dono raft:raft, modo 640
#   3. imprime o SHA-256 instalado
#   4. remove a regra de sudoers que permitia este comando (uso único)
#
# NÃO reinicia o serviço: quem decide quando reiniciar é o operador, porque
# reiniciar um agente no meio do cluster pode derrubar o líder.
#
# Uso: sudo -n /usr/local/sbin/ha-deploy-agent
# =============================================================================

set -euo pipefail

# Preenchido por install-deploy-rule.sh
PANEL_USER="__PANEL_USER__"
SRC="/home/${PANEL_USER}/raft-ha-manager-1.0-SNAPSHOT.jar"
DEST_DIR="/opt/raft-ha-manager"
DEST="${DEST_DIR}/raft-ha-manager-1.0-SNAPSHOT.jar"
SUDOERS_FILE="/etc/sudoers.d/ha-deploy"

[ "$(id -u)" -eq 0 ] || { echo "Execute via sudo." >&2; exit 1; }

# Arquivo comum (não link simbólico), do próprio usuário, com tamanho de um JAR de verdade
[ -f "$SRC" ] && [ ! -L "$SRC" ] || { echo "JAR não encontrado em $SRC" >&2; exit 2; }
[ "$(stat -c %U "$SRC")" = "$PANEL_USER" ] || { echo "$SRC não pertence a $PANEL_USER" >&2; exit 2; }
SIZE="$(stat -c %s "$SRC")"
[ "$SIZE" -gt 1000000 ] || { echo "JAR pequeno demais ($SIZE bytes)" >&2; exit 2; }
[ "$(head -c 2 "$SRC")" = "PK" ] || { echo "$SRC não é um arquivo zip/JAR" >&2; exit 2; }

[ -d "$DEST_DIR" ] || { echo "$DEST_DIR não existe (rode 04-deploy-raft.sh antes)." >&2; exit 3; }

# Cópia para um nome temporário no mesmo diretório e troca atômica
TMP="$(mktemp "${DEST_DIR}/.deploy.XXXXXX")"
trap 'rm -f "$TMP"' EXIT
cp "$SRC" "$TMP"
chown raft:raft "$TMP"
chmod 640 "$TMP"
mv -f "$TMP" "$DEST"
trap - EXIT

echo "instalado: $DEST"
sha256sum "$DEST" | cut -d' ' -f1

# Uso único: a regra que permitia chamar este script é removida
rm -f "$SUDOERS_FILE"
echo "regra de sudoers ${SUDOERS_FILE} removida (uso único)"
echo "o agente atual continua rodando o JAR antigo até ser reiniciado"
