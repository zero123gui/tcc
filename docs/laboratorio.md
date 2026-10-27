# Operação do laboratório

Como usar o cluster de 3 VMs do dia a dia. Para montar as VMs do zero, veja
[`raft-ha-manager/scripts/README.md`](../raft-ha-manager/scripts/README.md).

## VMs

| VM | IP | PostgreSQL | Recursos |
|---|---|---|---|
| `vm-node-1` | 192.168.56.101 | sim | 1 vCPU, 1280 MB |
| `vm-node-2` | 192.168.56.102 | sim | 1 vCPU, 1280 MB |
| `vm-node-3` | 192.168.56.103 | não (árbitro) | 1 vCPU, 768 MB |

- Acesso: `ssh ubuntu@192.168.56.10N`, com a chave `~/.ssh/id_ed25519` do Windows.
- O agente e o PostgreSQL sobem sozinhos no boot.
- As VMs `antiga-node-*` são de uma instalação anterior e usam os **mesmos IPs**: mantenha-as
  desligadas.

Os comandos `VBoxManage` abaixo ficam em `C:\Program Files\Oracle\VirtualBox\`.

## Ligar o cluster

1. Ligue primeiro o nó do PostgreSQL **primary** e o árbitro. Espere o primary virar líder.
2. Ligue o outro nó.
3. Espere **pelo menos 60 s** depois que o último nó entrou antes de considerar o cluster
   estável. Antes disso, uma eleição ainda pode acontecer.

```bash
VBoxManage startvm vm-node-2 --type headless
```

A ordem errada não causa mais estrago: se o standby vencer a primeira eleição, ele entrega a
liderança ao nó do primary (ver [arquitetura](arquitetura.md), §6.4). Só provoca uma troca de
líder a mais.

**Cluster estável** = um líder, o mesmo mandato nos 3 nós, um PRIMARY no mesmo nó do líder,
um STANDBY e nenhuma partição ativa:

```bash
ssh ubuntu@192.168.56.101 "sudo -n /usr/local/sbin/ha-partition status"
```

O esperado é `connected` nos 3 nós.

## Desligar o cluster

Desligue pelo botão ACPI, que encerra o agente e o PostgreSQL de forma limpa. Desligue o
líder **por último**, para não provocar um failover:

```bash
VBoxManage controlvm vm-node-1 acpipowerbutton
```

**Nunca** desligue o cluster logo depois de um failover, antes de o self-healing do antigo
primary terminar (cerca de 30 s): os dois bancos podem subir como PRIMARY.

## Painel

```powershell
ha-dashboard\run.ps1
```

O script pede a senha do usuário `raft_monitor` do PostgreSQL. Ela está na tabela de
credenciais de [`raft-ha-manager/scripts/README.md`](../raft-ha-manager/scripts/README.md).
Depois abra http://127.0.0.1:8000. Detalhes em [`ha-dashboard/README.md`](../ha-dashboard/README.md).

## Snapshots

| Snapshot | Conteúdo |
|---|---|
| `limpo-v4` | **Ponto de partida atual.** Agente da `main` com todas as proteções e o `recovery-standby.sh` novo. node-2 líder e primary |
| `limpo-v3` | Agente sem o alinhamento entre líder e primary. node-1 primary |
| `limpo` | Agente original, sem a regra de deploy |

Restaurar, com as 3 VMs **desligadas**:

```bash
VBoxManage snapshot vm-node-1 restore limpo-v4
```

Repita para `vm-node-2` e `vm-node-3`. Restaurar descarta tudo o que foi feito depois do
snapshot, inclusive os dados do banco. Tire snapshots novos com as VMs desligadas.

## Implantar uma nova versão do agente

Cada VM tem uma regra de sudo que permite instalar o JAR sem senha
(`ha-dashboard/vm/install-deploy-rule.sh --permanente`).

1. Compile e teste: `cd raft-ha-manager && mvn package`.
2. Copie o JAR para as 3 VMs:
   ```bash
   scp target/raft-ha-manager-1.0-SNAPSHOT.jar ubuntu@192.168.56.101:~/
   ```
3. Em cada VM, instale. O JAR anterior fica guardado como `.prev`:
   ```bash
   ssh ubuntu@192.168.56.101 "sudo -n /usr/local/sbin/ha-deploy-agent"
   ```
   Para voltar à versão anterior: `sudo -n /usr/local/sbin/ha-deploy-agent --rollback`.
4. Reinicie os agentes. O instalador **não** reinicia nada, para você escolher a ordem:
   pare os 3 (`sudo -n systemctl stop raft-ha-manager`), suba o nó do primary e o árbitro, espere
   o líder e só então suba o outro nó.

O `recovery-standby.sh` não entra nessa regra, porque ele roda como root. Para atualizá-lo, copie
o arquivo para a VM e instale com a senha do `ubuntu`:

```bash
ssh -t ubuntu@192.168.56.101 "sudo install -m 755 -o root -g root ~/recovery-standby.sh /opt/raft-ha-manager/recovery-standby.sh"
```

## Experimentos

- Pelo painel: cada ação disruptiva no líder abre um experimento, e os tempos vão para
  `ha-dashboard/results/experimentos.csv`.
- Em lote: scripts em `ha-dashboard/scripts/` (ver [`results/README.md`](../ha-dashboard/results/README.md)).
- **Rode um script de medição por vez.** Para interromper, pare a tarefa inteira e confira
  `ha-partition status` nos 3 nós antes de medir de novo: uma partição esquecida contamina as
  medições seguintes.

Para comparar com as proteções desligadas, acrescente as duas chaves na seção `raft:` do
`node.yaml` de cada VM e reinicie os agentes:

```bash
ssh -t ubuntu@192.168.56.101 "sudo sed -i -e '/^raft:/a\  check-quorum: false' -e '/^raft:/a\  pre-vote: false' /etc/raft-ha-manager/node.yaml"
```

Para voltar ao padrão:

```bash
ssh -t ubuntu@192.168.56.101 "sudo sed -i -e '/^  pre-vote: false$/d' -e '/^  check-quorum: false$/d' /etc/raft-ha-manager/node.yaml"
```

O log de cada agente mostra a configuração ao iniciar: `Pré-votação: ligada | Check-quorum: ligado`.

## Problemas comuns

| Sintoma | Causa e solução |
|---|---|
| VM parada em `Loading essential drivers` | Travamento no boot (host com pouca RAM e Hyper-V ativo). `VBoxManage controlvm <vm> reset`, repetindo se preciso |
| `systemctl is-active postgresql` diz `active` com o banco parado | No Ubuntu esse serviço é só um agrupador. Use `pg_lsclusters` |
| `REMOTE HOST IDENTIFICATION HAS CHANGED` no SSH | A VM foi recriada. `ssh-keygen -R 192.168.56.10N` |
| Painel mostra o PostgreSQL como "sem senha" ou "senha inválida" | Reinicie o `run.ps1` e informe a senha do `raft_monitor` |
| `run.ps1` diz que a porta 8000 está em uso | Outra instância do painel está aberta. Feche-a |
| Nó parece saudável, mas não fala com os outros | Partição esquecida: `sudo -n /usr/local/sbin/ha-partition heal` no nó |
| Ver o log de um agente | `ssh ubuntu@192.168.56.10N "journalctl -u raft-ha-manager -f"` |
