#!/bin/bash
# =============================================================================
# 01-base.sh — Configuração base para todos os nós do cluster
#
# O que este script faz:
#   1. Configura IP estático na interface Host-Only (enp0s8)
#   2. Atualiza o sistema operacional
#   3. Instala o Java 21 JRE
#   4. Cria o usuário de sistema 'raft' e os diretórios necessários
#   5. Configura o firewall (UFW) para liberar as portas do cluster
#   6. Configura /etc/hosts com os endereços de todos os nós
#
# Uso (como root):
#   sudo bash 01-base.sh <node-id> <ip-estatico>
#
# Exemplos:
#   sudo bash 01-base.sh node-1 192.168.56.101
#   sudo bash 01-base.sh node-2 192.168.56.102
#   sudo bash 01-base.sh node-3 192.168.56.103
# =============================================================================

set -euo pipefail

# ─── Argumentos ───────────────────────────────────────────────────────────────
NODE_ID="${1:?'Erro: informe o ID do nó. Exemplo: sudo bash 01-base.sh node-1 192.168.56.101'}"
STATIC_IP="${2:?'Erro: informe o IP estático. Exemplo: sudo bash 01-base.sh node-1 192.168.56.101'}"

# ─── Constantes ───────────────────────────────────────────────────────────────
GATEWAY="192.168.56.1"
HOST_ONLY_IFACE="enp0s8"   # Segunda interface no VirtualBox (Host-Only)
GRPC_PORT=9090
PG_PORT=5432

echo "============================================================"
echo "  Configurando nó: ${NODE_ID} | IP: ${STATIC_IP}"
echo "============================================================"

# ─── 1. IP Estático via Netplan ───────────────────────────────────────────────
echo "[1/6] Configurando IP estático ${STATIC_IP} em ${HOST_ONLY_IFACE}..."

cat > /etc/netplan/99-host-only.yaml << EOF
network:
  version: 2
  ethernets:
    ${HOST_ONLY_IFACE}:
      addresses:
        - ${STATIC_IP}/24
      routes:
        - to: 192.168.56.0/24
          via: ${GATEWAY}
EOF

chmod 600 /etc/netplan/99-host-only.yaml
netplan apply
echo "  IP estático configurado."

# ─── 2. Atualizar o sistema ───────────────────────────────────────────────────
echo "[2/6] Atualizando o sistema..."
apt-get update -y -q
apt-get upgrade -y -q
echo "  Sistema atualizado."

# ─── 3. Instalar Java 21 JRE ──────────────────────────────────────────────────
echo "[3/6] Instalando Java 21 JRE..."
apt-get install -y -q openjdk-21-jre-headless
java -version
echo "  Java 21 instalado."

# ─── 4. Criar usuário e diretórios do raft-ha-manager ────────────────────────
echo "[4/6] Criando usuário 'raft' e estrutura de diretórios..."

# Cria o usuário de sistema (sem shell de login, sem home directory interativo)
useradd -r -s /usr/sbin/nologin -d /var/lib/raft-ha-manager raft 2>/dev/null || \
    echo "  Usuário 'raft' já existe."

# Diretórios de dados, logs e configuração
mkdir -p /var/lib/raft-ha-manager/logs
mkdir -p /etc/raft-ha-manager
mkdir -p /opt/raft-ha-manager

chown -R raft:raft /var/lib/raft-ha-manager
chown -R raft:raft /opt/raft-ha-manager
chmod 750 /etc/raft-ha-manager

# Configura o sudoers para permitir Fencing e Auto-Healing pelo usuário raft
cat > /etc/sudoers.d/raft-ha << EOF
raft ALL=(ALL) NOPASSWD: /bin/systemctl stop postgresql
raft ALL=(ALL) NOPASSWD: /bin/systemctl start postgresql
raft ALL=(ALL) NOPASSWD: /usr/bin/bash /opt/raft-ha-manager/recovery-standby.sh *
EOF
chmod 440 /etc/sudoers.d/raft-ha

echo "  Diretórios criados e sudoers configurado."

# ─── 5. Configurar UFW (firewall) ─────────────────────────────────────────────
echo "[5/6] Configurando firewall (UFW)..."

# Habilita UFW sem confirmar
ufw --force enable

# SSH (para administração remota)
ufw allow 22/tcp comment 'SSH'

# gRPC do raft-ha-manager (comunicação entre nós do cluster)
ufw allow ${GRPC_PORT}/tcp comment 'raft-ha-manager gRPC'

# PostgreSQL (apenas para conexões da rede do cluster, não exposição geral)
ufw allow from 192.168.56.0/24 to any port ${PG_PORT} proto tcp comment 'PostgreSQL cluster'

ufw status verbose
echo "  Firewall configurado."

# ─── 6. Configurar /etc/hosts ────────────────────────────────────────────────
echo "[6/6] Configurando resolução de nomes em /etc/hosts..."

# Remove entradas anteriores do cluster (evita duplicatas em re-execuções)
sed -i '/# raft-ha-manager cluster/,/# fim-cluster/d' /etc/hosts

cat >> /etc/hosts << 'EOF'
# raft-ha-manager cluster
192.168.56.101  node-1
192.168.56.102  node-2
192.168.56.103  node-3
192.168.56.104  node-4
192.168.56.105  node-5
# fim-cluster
EOF

echo "  /etc/hosts atualizado."

# ─── Hostname ─────────────────────────────────────────────────────────────────
hostnamectl set-hostname "${NODE_ID}"

# ─── Concluído ────────────────────────────────────────────────────────────────
echo ""
echo "============================================================"
echo "  Nó ${NODE_ID} configurado com sucesso!"
echo "  IP estático: ${STATIC_IP}"
echo ""
echo "  ATENÇÃO: Reconecte via SSH usando o novo IP:"
echo "    ssh ubuntu@${STATIC_IP}"
echo "============================================================"
