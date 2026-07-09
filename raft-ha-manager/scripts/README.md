# Scripts de Configuração: raft-ha-manager

Este diretório contém todos os scripts necessários para criar e configurar o ambiente
de simulação do raft-ha-manager no VirtualBox.

---

## Pré-requisitos

- VirtualBox instalado no Windows
- ISO do Ubuntu Server 22.04 LTS baixada
  - Download: https://ubuntu.com/download/server
  - Arquivo: `ubuntu-22.04.x-live-server-amd64.iso`
- JAR do projeto compilado: `mvn package -DskipTests` gera `target/raft-ha-manager-1.0-SNAPSHOT.jar`

---

## Topologia de Validação (Notebook — 3 nós)

| VM       | IP              | RAM    | PostgreSQL | Papel Raft |
|----------|-----------------|--------|------------|------------|
| vm-node-1 | 192.168.56.101 | 1.5 GB | Primary    | Líder inicial |
| vm-node-2 | 192.168.56.102 | 1.5 GB | Standby    | Seguidor |
| vm-node-3 | 192.168.56.103 | 512 MB | Nenhum     | Árbitro |

---

## Passo a Passo

### Etapa 1 — Criar as VMs (Windows, PowerShell como Administrador)

```powershell
# Ajuste o caminho da ISO antes de executar
$isoPath = "C:\Users\guimi\Downloads\ubuntu-22.04.5-live-server-amd64.iso"
.\vm-create.ps1 -IsoPath $isoPath -Nodes 3
```

O script cria as 3 VMs e configura a rede Host-Only (192.168.56.0/24).
Após isso, inicie cada VM e siga o instalador do Ubuntu Server.

**Durante a instalação do Ubuntu, configure:**
- Nome do servidor: `node-1`, `node-2` ou `node-3`
- Usuário: `ubuntu` (senha à sua escolha)
- Marque: **Install OpenSSH server**
- Deixe o restante como padrão

### Etapa 2 — Descobrir os IPs temporários das VMs

Após a instalação, faça login no console de cada VM e anote o IP da interface NAT (enp0s3).
Você vai usar esses IPs para se conectar via SSH do host Windows.

```bash
ip addr show enp0s3
```

### Etapa 3 — Configurar IPs estáticos e sistema base

Conecte via SSH em cada VM e execute os scripts na ordem:

```bash
# Em CADA nó (node-1, node-2, node-3):
# Copie o script para a VM
scp scripts/setup/01-base.sh ubuntu@<IP_TEMPORARIO>:~/

# Acesse a VM via SSH
ssh ubuntu@<IP_TEMPORARIO>

# Execute o script de base (informe o ID e o IP estático do nó)
# node-1: sudo bash 01-base.sh node-1 192.168.56.101
# node-2: sudo bash 01-base.sh node-2 192.168.56.102
# node-3: sudo bash 01-base.sh node-3 192.168.56.103
sudo bash 01-base.sh <node-id> <ip-estatico>
```

Após o reboot, use o IP estático para se conectar: `ssh ubuntu@192.168.56.101`

### Etapa 4 — Configurar o PostgreSQL Primary (apenas node-1)

```bash
scp scripts/setup/02-postgres-primary.sh ubuntu@192.168.56.101:~/
ssh ubuntu@192.168.56.101
sudo bash 02-postgres-primary.sh
```

### Etapa 5 — Configurar os PostgreSQL Standbys (node-2)

```bash
scp scripts/setup/03-postgres-standby.sh ubuntu@192.168.56.102:~/
ssh ubuntu@192.168.56.102
# Informe o IP do primary como argumento
sudo bash 03-postgres-standby.sh 192.168.56.101
```

### Etapa 6 — Implantar o raft-ha-manager (TODOS os nós)

```bash
# Primeiro, compile o JAR no seu computador:
# mvn package -DskipTests

# Copie o JAR e o script para cada VM:
for NODE_IP in 192.168.56.101 192.168.56.102 192.168.56.103; do
  scp target/raft-ha-manager-1.0-SNAPSHOT.jar ubuntu@$NODE_IP:~/
  scp scripts/setup/04-deploy-raft.sh ubuntu@$NODE_IP:~/
done

# Em node-1:
scp scripts/config/node-1.yaml ubuntu@192.168.56.101:~/node.yaml
ssh ubuntu@192.168.56.101 "sudo bash 04-deploy-raft.sh node-1"

# Em node-2:
scp scripts/config/node-2.yaml ubuntu@192.168.56.102:~/node.yaml
ssh ubuntu@192.168.56.102 "sudo bash 04-deploy-raft.sh node-2"

# Em node-3 (árbitro):
scp scripts/config/node-3.yaml ubuntu@192.168.56.103:~/node.yaml
ssh ubuntu@192.168.56.103 "sudo bash 04-deploy-raft.sh node-3"
```

### Etapa 7 — Iniciar o cluster

```bash
# Em cada nó (pode abrir 3 terminais SSH em paralelo):
ssh ubuntu@192.168.56.101 "sudo systemctl start raft-ha-manager"
ssh ubuntu@192.168.56.102 "sudo systemctl start raft-ha-manager"
ssh ubuntu@192.168.56.103 "sudo systemctl start raft-ha-manager"
```

### Verificar logs

```bash
# Ver logs em tempo real de um nó
ssh ubuntu@192.168.56.101 "sudo journalctl -u raft-ha-manager -f"
```

---

## Credenciais padrão dos scripts

| Recurso               | Usuário      | Senha          |
|-----------------------|--------------|----------------|
| PostgreSQL admin      | postgres     | (sem senha local) |
| Usuário de replicação | replicator   | `repl_pass_123` |
| Usuário JDBC (raft)   | raft_monitor | `raft_mon_123`  |
| SSH das VMs           | ubuntu       | (definida na instalação) |

> **Atenção:** essas credenciais são para o ambiente de simulação do TCC.
> Não use em ambiente de produção.
