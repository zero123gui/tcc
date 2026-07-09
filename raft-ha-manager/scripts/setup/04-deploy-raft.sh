#!/bin/bash
# =============================================================================
# 04-deploy-raft.sh — Implanta o raft-ha-manager em um nó
#
# Execute em TODOS os nós (node-1, node-2, node-3) depois que os scripts
# anteriores tiverem sido executados.
#
# O que este script faz:
#   1. Copia o JAR para /opt/raft-ha-manager/
#   2. Copia o arquivo node.yaml para /etc/raft-ha-manager/
#   3. Cria um serviço systemd para o raft-ha-manager
#   4. Habilita o serviço (mas NÃO o inicia ainda)
#
# Você deve iniciar MANUALMENTE todos os nós ao mesmo tempo após
# o deploy para garantir que o cluster inicie corretamente.
#
# Uso (como root):
#   sudo bash 04-deploy-raft.sh <node-id>
#
# Pré-requisitos (devem estar no diretório atual):
#   - raft-ha-manager-1.0-SNAPSHOT.jar
#   - node.yaml (configuração específica do nó)
# =============================================================================

set -euo pipefail

# ─── Argumentos ───────────────────────────────────────────────────────────────
NODE_ID="${1:?'Erro: informe o ID do nó. Exemplo: sudo bash 04-deploy-raft.sh node-1'}"

# ─── Caminhos de instalação ───────────────────────────────────────────────────
JAR_NAME="raft-ha-manager-1.0-SNAPSHOT.jar"
INSTALL_DIR="/opt/raft-ha-manager"
CONF_DIR="/etc/raft-ha-manager"
DATA_DIR="/var/lib/raft-ha-manager"
LOG_DIR="${DATA_DIR}/logs"

echo "============================================================"
echo "  Implantando raft-ha-manager no nó: ${NODE_ID}"
echo "============================================================"

# ─── 1. Verificar pré-requisitos ──────────────────────────────────────────────
echo "[1/4] Verificando arquivos necessários..."

if [ ! -f "${JAR_NAME}" ]; then
    echo "  Erro: '${JAR_NAME}' não encontrado no diretório atual."
    echo "  Compile o projeto com: mvn package -DskipTests"
    echo "  Depois copie o JAR para esta VM:"
    echo "    scp target/${JAR_NAME} ubuntu@<ip-da-vm>:~/"
    exit 1
fi

if [ ! -f "node.yaml" ]; then
    echo "  Erro: 'node.yaml' não encontrado no diretório atual."
    echo "  Copie o arquivo de configuração correto para esta VM:"
    echo "    scp scripts/config/${NODE_ID}.yaml ubuntu@<ip-da-vm>:~/node.yaml"
    exit 1
fi

echo "  Arquivos encontrados."

# ─── 2. Instalar o JAR e a configuração ───────────────────────────────────────
echo "[2/4] Copiando arquivos para os diretórios de instalação..."

# Para o serviço se estiver rodando (para substituir o JAR com segurança)
systemctl stop raft-ha-manager 2>/dev/null || true

# Copia o JAR
cp ${JAR_NAME} ${INSTALL_DIR}/${JAR_NAME}
chown raft:raft ${INSTALL_DIR}/${JAR_NAME}
chmod 640 ${INSTALL_DIR}/${JAR_NAME}

# Copia a configuração
cp node.yaml ${CONF_DIR}/node.yaml
chown root:root ${CONF_DIR}/node.yaml
chmod 644 ${CONF_DIR}/node.yaml   # Leitura para todos (ambiente de simulação)
chmod 755 ${CONF_DIR}             # Diretório acessível para o usuário raft

# Copia o script de recuperação (se existir no diretório atual)
if [ -f "recovery-standby.sh" ]; then
    cp recovery-standby.sh ${INSTALL_DIR}/recovery-standby.sh
    chown root:root ${INSTALL_DIR}/recovery-standby.sh
    chmod 755 ${INSTALL_DIR}/recovery-standby.sh
fi

echo "  Arquivos instalados."

# ─── 3. Criar serviço systemd ─────────────────────────────────────────────────
echo "[3/4] Criando serviço systemd 'raft-ha-manager'..."

cat > /etc/systemd/system/raft-ha-manager.service << EOF
[Unit]
Description=Raft HA Manager — Alta Disponibilidade para SCADA com PostgreSQL
Documentation=https://github.com/zero123gui/tcc
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
User=raft
Group=raft

# Localização do JAR e da configuração
ExecStart=/usr/bin/java \\
    -Dnode.id=${NODE_ID} \\
    -Dlog.dir=${LOG_DIR} \\
    -jar ${INSTALL_DIR}/${JAR_NAME} \\
    ${CONF_DIR}/node.yaml

# Reinicia automaticamente em caso de falha não intencional
Restart=on-failure
RestartSec=10s

# Diretório de trabalho
WorkingDirectory=${DATA_DIR}

# Variáveis de ambiente
Environment=JAVA_OPTS="-Xms128m -Xmx512m"

# Redirecionamento de saída padrão para journald
StandardOutput=journal
StandardError=journal
SyslogIdentifier=raft-ha-manager

# Limites do processo
LimitNOFILE=65536
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload

echo "  Serviço systemd criado."

# ─── 4. Habilitar (mas não iniciar) ───────────────────────────────────────────
echo "[4/4] Habilitando serviço (não iniciado ainda)..."

# Habilita o serviço para iniciar automaticamente no boot
systemctl enable raft-ha-manager

echo "  Serviço habilitado."

# ─── Concluído ────────────────────────────────────────────────────────────────
echo ""
echo "============================================================"
echo "  raft-ha-manager implantado no nó ${NODE_ID}!"
echo ""
echo "  IMPORTANTE: Inicie o cluster em todos os nós ao mesmo tempo:"
echo ""
echo "    No node-1: sudo systemctl start raft-ha-manager"
echo "    No node-2: sudo systemctl start raft-ha-manager"
echo "    No node-3: sudo systemctl start raft-ha-manager"
echo ""
echo "  Comandos úteis:"
echo "    Ver logs:    sudo journalctl -u raft-ha-manager -f"
echo "    Status:      sudo systemctl status raft-ha-manager"
echo "    Parar:       sudo systemctl stop raft-ha-manager"
echo "    Reiniciar:   sudo systemctl restart raft-ha-manager"
echo "============================================================"
