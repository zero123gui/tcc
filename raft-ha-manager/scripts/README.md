# Montar o cluster do zero

Passo a passo para criar as 3 VMs no VirtualBox e instalar o PostgreSQL, o agente e o acesso do
painel. Para o uso do dia a dia (ligar, desligar, snapshots, nova versão), veja
[`docs/laboratorio.md`](../../docs/laboratorio.md).

Os comandos de cópia partem da pasta `raft-ha-manager/` do repositório, no Windows.

## Conteúdo

| Arquivo | Onde roda | Para quê |
|---|---|---|
| `vm-create.ps1` | Windows | Cria as VMs e a rede Host-Only |
| `setup/01-base.sh` | Todos os nós | IP fixo, Java 21, usuário `raft`, firewall, `/etc/hosts`, sudo restrito |
| `setup/02-postgres-primary.sh` | node-1 | Configura o PostgreSQL primary e cria os usuários do banco |
| `setup/03-postgres-standby.sh` | node-2 | Clona o primary e configura o standby |
| `setup/04-deploy-raft.sh` | Todos os nós | Instala o JAR, o `node.yaml` e o serviço systemd |
| `setup/recovery-standby.sh` | node-1 e node-2 | Self-healing, chamado pelo agente (instalado pelo `04`) |
| `config/node-{1,2,3}.yaml` | — | Configuração de cada nó |

Scripts de acesso do painel (`ha-dashboard/vm/`): `setup-vm-access.sh`, `ha-partition.sh`,
`install-deploy-rule.sh` e `ha-deploy-agent.sh`.

## Topologia

| VM | IP | PostgreSQL | Papel no Raft | RAM usada no laboratório |
|---|---|---|---|---|
| `vm-node-1` | 192.168.56.101 | primary | líder inicial | 1280 MB |
| `vm-node-2` | 192.168.56.102 | standby | seguidor | 1280 MB |
| `vm-node-3` | 192.168.56.103 | nenhum | árbitro | 768 MB |

Todas com 1 vCPU, Ubuntu Server 22.04 LTS e PostgreSQL 16.

## 1. Criar as VMs (Windows, PowerShell como administrador)

Baixe a ISO do Ubuntu Server 22.04 em https://ubuntu.com/download/server e rode:

```powershell
.\scripts\vm-create.ps1 -IsoPath "C:\caminho\ubuntu-22.04.5-live-server-amd64.iso" -Nodes 3
```

Cada VM recebe dois adaptadores: NAT (internet) e Host-Only (rede do cluster, 192.168.56.0/24,
com o Windows em 192.168.56.1).

O script cria as VMs com 1536 MB (node-1 e node-2) e 512 MB (node-3). Com 512 MB a instalação
do Ubuntu pode travar: aumente para 2048 MB durante a instalação e reduza depois, no VirtualBox
(*Configurações → Sistema*).

## 2. Instalar o Ubuntu em cada VM

No instalador:
- nome do servidor: `node-1`, `node-2` ou `node-3`;
- usuário: `ubuntu`, com uma senha à sua escolha;
- marque **Install OpenSSH server**;
- o resto pode ficar no padrão.

Evite que o notebook suspenda durante a instalação: ela trava.

## 3. Primeiro acesso por SSH

O IP do adaptador NAT não é acessível a partir do Windows. No console de cada VM, coloque o IP
definitivo na interface Host-Only (troque `N` pelo número do nó):

```bash
sudo ip addr add 192.168.56.10N/24 dev enp0s8
sudo ip link set enp0s8 up
```

No Windows, crie uma chave SSH (uma vez) e copie-a para as 3 VMs:

```powershell
ssh-keygen -t ed25519
foreach ($ip in "192.168.56.101", "192.168.56.102", "192.168.56.103") {
  Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh ubuntu@$ip "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys"
}
```

Se o SSH reclamar de `REMOTE HOST IDENTIFICATION HAS CHANGED` (VM recriada com o mesmo IP),
remova a chave antiga com `ssh-keygen -R 192.168.56.10N`.

## 4. Base (todos os nós)

```powershell
scp scripts/setup/01-base.sh ubuntu@192.168.56.101:~/
ssh -t ubuntu@192.168.56.101 "sudo bash 01-base.sh node-1 192.168.56.101"
```

Repita para node-2 (`192.168.56.102`) e node-3 (`192.168.56.103`). O script torna o IP fixo,
atualiza o sistema, instala o Java 21, cria o usuário `raft` e libera no firewall as portas 22,
5432 e 9090.

## 5. PostgreSQL 16 (node-1 e node-2)

Os scripts `02` e `03` **não instalam** o PostgreSQL (as linhas estão comentadas). Instale antes,
pelo repositório oficial (PGDG), nos dois nós:

```bash
sudo apt-get install -y curl ca-certificates
sudo install -d /usr/share/postgresql-common/pgdg
sudo curl -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc --fail https://www.postgresql.org/media/keys/ACCC4CF8.asc
echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main" | sudo tee /etc/apt/sources.list.d/pgdg.list
sudo apt-get update && sudo apt-get install -y postgresql-16
```

Depois configure o primary e o standby:

```powershell
scp scripts/setup/02-postgres-primary.sh ubuntu@192.168.56.101:~/
ssh -t ubuntu@192.168.56.101 "sudo bash 02-postgres-primary.sh"

scp scripts/setup/03-postgres-standby.sh ubuntu@192.168.56.102:~/
ssh -t ubuntu@192.168.56.102 "sudo bash 03-postgres-standby.sh 192.168.56.101"
```

Para conferir a replicação, no node-1:
`sudo -u postgres psql -c "SELECT client_addr, state FROM pg_stat_replication;"`.

## 6. Agente (todos os nós)

Compile o JAR (`mvn package`) e copie para cada nó o JAR, o `04-deploy-raft.sh` e o `node.yaml`
daquele nó. Nos nós com PostgreSQL, copie também o `recovery-standby.sh`: sem ele, o
self-healing não funciona.

```powershell
foreach ($n in 1, 2, 3) {
  $ip = "192.168.56.10$n"
  scp target/raft-ha-manager-1.0-SNAPSHOT.jar scripts/setup/04-deploy-raft.sh ubuntu@${ip}:~/
  scp scripts/config/node-$n.yaml ubuntu@${ip}:~/node.yaml
}
scp scripts/setup/recovery-standby.sh ubuntu@192.168.56.101:~/
scp scripts/setup/recovery-standby.sh ubuntu@192.168.56.102:~/
```

Em cada nó (troque `N`):

```bash
ssh -t ubuntu@192.168.56.10N "sudo bash 04-deploy-raft.sh node-N"
```

O script instala o serviço `raft-ha-manager`, habilitado para subir no boot.

## 7. Acesso do painel (todos os nós)

A partir da pasta `ha-dashboard/`:

```powershell
foreach ($ip in "192.168.56.101", "192.168.56.102", "192.168.56.103") {
  scp vm/ha-partition.sh vm/setup-vm-access.sh vm/ha-deploy-agent.sh vm/install-deploy-rule.sh ubuntu@${ip}:~/
  ssh -t ubuntu@$ip "sudo bash setup-vm-access.sh && sudo bash install-deploy-rule.sh --permanente"
}
```

- `setup-vm-access.sh` libera sem senha só os comandos que o painel executa e coloca o `ubuntu`
  no grupo que lê o journal.
- `install-deploy-rule.sh --permanente` libera sem senha só a instalação de um novo JAR (ver
  [`docs/laboratorio.md`](../../docs/laboratorio.md)).

## 8. Subir o cluster

Ligue primeiro o agente do node-1 e do node-3, espere o node-1 virar líder e depois ligue o do
node-2:

```bash
ssh ubuntu@192.168.56.101 "sudo systemctl start raft-ha-manager"
ssh ubuntu@192.168.56.101 "journalctl -u raft-ha-manager -f"
```

O log mostra `Nó node-1 eleito LÍDER para o mandato N`. Depois disso, abra o painel
(`ha-dashboard\run.ps1`) e tire um snapshot das 3 VMs desligadas, para ter um ponto de partida.

## Credenciais do ambiente de simulação

| Recurso | Usuário | Senha |
|---|---|---|
| PostgreSQL (administrador) | `postgres` | sem senha, só acesso local |
| Replicação | `replicator` | `repl_pass_123` |
| Monitoramento (agente e painel) | `raft_monitor` | `raft_mon_123` |
| SSH das VMs | `ubuntu` | definida na instalação |

> Essas credenciais existem só para o laboratório do TCC. Não use em produção.
