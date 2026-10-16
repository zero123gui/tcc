#!/bin/bash
# =============================================================================
# ha-deploy-agent — Instala um novo JAR do raft-ha-manager (ou volta o anterior)
#
# Instalado em /usr/local/sbin/ha-deploy-agent por install-deploy-rule.sh e chamado
# via "sudo -n". Faz só isto:
#   1. valida o JAR em /home/<usuario>/raft-ha-manager-1.0-SNAPSHOT.jar
#   2. guarda o JAR em uso como .prev (para rollback)
#   3. copia o novo para /opt/raft-ha-manager/ (troca atômica), dono raft:raft, modo 640
#   4. imprime o SHA-256 instalado
#   5. no modo de uso único, remove a regra de sudoers que permitia este comando
#
# NÃO reinicia o serviço: quem decide quando reiniciar é o operador, porque
# reiniciar um agente no meio do cluster pode derrubar o líder.
#
# Uso:
#   sudo -n /usr/local/sbin/ha-deploy-agent              # instala o JAR novo
#   sudo -n /usr/local/sbin/ha-deploy-agent --rollback   # volta o JAR anterior (.prev)
# =============================================================================

set -euo pipefail

# Preenchidos por install-deploy-rule.sh
PANEL_USER="__PANEL_USER__"
ONE_SHOT="__ONE_SHOT__"

SRC="/home/${PANEL_USER}/raft-ha-manager-1.0-SNAPSHOT.jar"
DEST_DIR="/opt/raft-ha-manager"
DEST="${DEST_DIR}/raft-ha-manager-1.0-SNAPSHOT.jar"
PREV="${DEST}.prev"
SUDOERS_FILE="/etc/sudoers.d/ha-deploy"

[ "$(id -u)" -eq 0 ] || { echo "Execute via sudo." >&2; exit 1; }
[ -d "$DEST_DIR" ] || { echo "$DEST_DIR não existe (rode 04-deploy-raft.sh antes)." >&2; exit 3; }

install_atomic() {  # $1 = arquivo de origem
    local tmp
    tmp="$(mktemp "${DEST_DIR}/.deploy.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    cp "$1" "$tmp"
    chown raft:raft "$tmp"
    chmod 640 "$tmp"
    mv -f "$tmp" "$DEST"
    trap - EXIT
}

case "${1:-}" in
    --rollback)
        [ -f "$PREV" ] || { echo "Não há JAR anterior ($PREV) para voltar." >&2; exit 4; }
        CURRENT_COPY="$(mktemp)"
        cp -p "$DEST" "$CURRENT_COPY"
        install_atomic "$PREV"
        install -o raft -g raft -m 640 "$CURRENT_COPY" "$PREV"   # o que estava em uso vira o .prev
        rm -f "$CURRENT_COPY"
        echo "rollback: JAR anterior reinstalado em $DEST"
        ;;
    "")
        # Arquivo comum (não link simbólico), do próprio usuário, com tamanho de um JAR de verdade
        [ -f "$SRC" ] && [ ! -L "$SRC" ] || { echo "JAR não encontrado em $SRC" >&2; exit 2; }
        [ "$(stat -c %U "$SRC")" = "$PANEL_USER" ] || { echo "$SRC não pertence a $PANEL_USER" >&2; exit 2; }
        SIZE="$(stat -c %s "$SRC")"
        [ "$SIZE" -gt 1000000 ] || { echo "JAR pequeno demais ($SIZE bytes)" >&2; exit 2; }
        [ "$(head -c 2 "$SRC")" = "PK" ] || { echo "$SRC não é um arquivo zip/JAR" >&2; exit 2; }

        if [ -f "$DEST" ]; then
            install -o raft -g raft -m 640 "$DEST" "$PREV"
        fi
        install_atomic "$SRC"
        echo "instalado: $DEST (anterior guardado em $PREV)"
        ;;
    *)
        echo "Uso: ha-deploy-agent [--rollback]" >&2
        exit 2
        ;;
esac

sha256sum "$DEST" | cut -d' ' -f1

if [ "$ONE_SHOT" = "true" ]; then
    rm -f "$SUDOERS_FILE"
    echo "regra de sudoers ${SUDOERS_FILE} removida (uso único)"
fi
echo "o agente em execução continua com o JAR antigo até ser reiniciado"
