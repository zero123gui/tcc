#!/bin/bash
# =============================================================================
# ha-partition — Simula uma partição de rede isolando este nó dos seus pares
#
# Instalado em /usr/local/sbin/ha-partition por setup-vm-access.sh e chamado
# pelo ha-dashboard via "sudo -n".
#
# Usa iptables (e não ufw deny) porque o UFW aceita conexões já estabelecidas
# antes de avaliar as regras do usuário: os canais gRPC entre os nós, mantidos
# abertos com keep-alive, continuariam funcionando. As cadeias deste script são
# inseridas na posição 1 de INPUT/OUTPUT e descartam inclusive esse tráfego.
#
# Só os IPs informados são bloqueados. O host Windows (192.168.56.1) continua
# alcançando o nó, então o painel segue vendo os logs da VM isolada.
#
# As regras não são persistidas: um reboot da VM desfaz a partição.
#
# Uso:
#   ha-partition isolate <ip-par> [<ip-par> ...]
#   ha-partition heal
#   ha-partition status      → "connected" ou "isolated <ip> <ip> ..."
# =============================================================================

set -euo pipefail

IN_CHAIN="HA_PARTITION_IN"
OUT_CHAIN="HA_PARTITION_OUT"

usage() {
    echo "Uso: ha-partition isolate <ip>... | heal | status" >&2
    exit 2
}

valid_ip() {
    local ip="$1" part
    [[ "$ip" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
    for part in "${BASH_REMATCH[@]:1}"; do
        (( 10#$part <= 255 )) || return 1
    done
}

chains_exist() {
    iptables -S "$IN_CHAIN" >/dev/null 2>&1
}

ensure_chains() {
    iptables -N "$IN_CHAIN" 2>/dev/null || true
    iptables -N "$OUT_CHAIN" 2>/dev/null || true
    iptables -C INPUT -j "$IN_CHAIN" 2>/dev/null || iptables -I INPUT 1 -j "$IN_CHAIN"
    iptables -C OUTPUT -j "$OUT_CHAIN" 2>/dev/null || iptables -I OUTPUT 1 -j "$OUT_CHAIN"
}

blocked_ips() {
    chains_exist || return 0
    iptables -S "$IN_CHAIN" | sed -n 's/^-A .* -s \([0-9.]*\)\/32 -j DROP$/\1/p'
}

case "${1:-}" in
    isolate)
        shift
        [ $# -ge 1 ] || usage
        for ip in "$@"; do
            valid_ip "$ip" || { echo "IP inválido: $ip" >&2; exit 2; }
        done
        ensure_chains
        iptables -F "$IN_CHAIN"
        iptables -F "$OUT_CHAIN"
        for ip in "$@"; do
            iptables -A "$IN_CHAIN" -s "$ip" -j DROP
            iptables -A "$OUT_CHAIN" -d "$ip" -j DROP
        done
        echo "isolated $*"
        ;;
    heal)
        [ $# -eq 1 ] || usage
        if chains_exist; then
            iptables -F "$IN_CHAIN"
            iptables -F "$OUT_CHAIN"
        fi
        echo "connected"
        ;;
    status)
        [ $# -eq 1 ] || usage
        ips="$(blocked_ips | tr '\n' ' ' | sed 's/ *$//')"
        if [ -n "$ips" ]; then
            echo "isolated $ips"
        else
            echo "connected"
        fi
        ;;
    *)
        usage
        ;;
esac
