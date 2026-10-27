# Roteiro da apresentação prática

Duração estimada: **12 a 15 minutos** de demonstração. Todos os cenários abaixo foram
ensaiados em 25/10/2026 (3 repetições cada, 24 de 24 sem falha); os números estão em
`ha-dashboard/results/roteiro-resultados-2026-10-25.md`.

## Antes da banca (30 min antes)

1. Notebook na tomada, suspensão desligada.
2. Restaurar o ponto de partida, com as VMs **desligadas**: snapshot `limpo-v4` nas 3 VMs
   (VirtualBox: botão direito no snapshot → Restaurar).
3. Ligar na ordem: **node-2 e node-3**, esperar o líder aparecer, depois **node-1**. Se uma VM
   travar em `Loading essential drivers`:
   `VBoxManage controlvm <vm> reset`.
4. Abrir o painel: `ha-dashboard\run.ps1` (pede a senha do `raft_monitor`; está na tabela de
   credenciais de `raft-ha-manager/scripts/README.md`). Abrir `http://127.0.0.1:8000`.
5. Conferir, **esperando pelo menos 60 s depois que o último nó entrou**:
   - painel com 1 líder, mesmo mandato nos 3 nós, 1 PRIMARY e 1 STANDBY no mesmo nó do líder;
   - sem alertas;
   - `sudo -n /usr/local/sbin/ha-partition status` = `connected` nos 3 nós.
6. Deixar visíveis: cartões dos nós, **Eventos**, **Logs** com "só eventos" marcado, e o
   quadro **Experimentos de failover** (o cronômetro é o que preenche a espera).

## Roteiro

### 0. Situação inicial (1 min)
Mostrar no painel: node-1 e node-2 com PostgreSQL (primary e standby em replicação), node-3
árbitro sem banco, só desempata a votação. O líder do Raft é o nó do PostgreSQL primary.
Explicar: "o Raft decide quem manda; o agente faz o PostgreSQL seguir essa decisão".

### 1. Um nó secundário cai e volta: nada acontece (2 min)
Ação: **Parar raft-ha-manager** no standby, esperar ~1 min, **Iniciar**.
Esperado: mesmo líder, mesmo mandato, nenhuma eleição. Nos logs: nenhum evento de eleição.
Mensagem: falha de quem não é líder não perturba o cluster.

### 2. O PostgreSQL do líder falha (2 min)
Ação: **Parar PostgreSQL** no líder (confirmar). Depois **Iniciar PostgreSQL**.
Esperado: o líder renuncia em ~8 s ("PG FALHOU", "RENÚNCIA"); novo líder eleito em ~25–32 s;
standby promovido ("PROMOVIDO"); cronômetro mostra os marcos. Ao religar o banco do antigo
líder, o self-healing o refaz como standby em ~16–19 s.
Falar durante a espera (25–35 s): tempo de eleição aleatório entre 15 e 30 s evita empates;
a promoção em si leva ~2 s.

### 3. O agente do líder para (2 min)
Ação: **Parar raft-ha-manager** no líder, esperar o novo líder, **Iniciar**.
Esperado: novo líder em ~24–30 s. O antigo líder ainda tem o PostgreSQL como PRIMARY: ao voltar
é detectado como "Rogue Primary", sofre fencing e é refeito como standby (**SPLIT-BRAIN**,
depois **SELF-HEALING**, ~30 s). Mostrar que acontece **uma vez só** e que o
`recovery-standby.sh` confere que a origem é PRIMARY antes de apagar qualquer coisa.

### 4. Partição de rede no líder (3 min)
Ação: **Isolar da rede do cluster** no líder (confirmar). Esperar o failover, depois
**Reconectar**.
Esperado: o líder isolado renuncia sozinho em ~18 s ("SEM QUÓRUM", check-quorum); o outro lado
elege e promove em ~25 s; ao reconectar, o antigo líder segue o novo e é reconciliado. Mensagem:
nunca há dois líderes aceitos pela maioria, e o líder sem maioria não continua se declarando líder.

### 5. O destaque: nó que volta não derruba o líder (3 min)
Ação: **Isolar da rede** o **standby** por 60 s, **Reconectar**.
Esperado: mandato igual antes e depois; nos outros nós aparece **PRÉ-VOTO NEGADO**; nenhuma
eleição. Mostrar a tabela do comparativo (slide, não ao vivo): sem pré-votação e check-quorum,
o líder foi derrubado em 5 de 5 repetições (`seguidor-isolado-sem-correcao.csv`); com eles, em
0 de 8 (5 repetições de 19/10 em `seguidor-isolado-com-correcao.csv` e 3 de 25/10 em
`roteiro-seguidor-isolado.csv`).

### 6. Fechamento (1 min)
Voltar ao cartão geral: um líder, um primary, um standby. Resumir: eleição por maioria,
pré-votação, check-quorum, fencing e self-healing.

## Se algo sair do esperado
- **Cluster ficou estranho** (dois primaries, nó DOWN): não improvisar. Desligar as VMs pelo
  VirtualBox (ACPI), restaurar `limpo-v4` e subir de novo. Leva ~5 min; ter isso decidido evita
  pânico.
- **Painel sem estado do PostgreSQL**: faltou a senha do `raft_monitor`; reiniciar o painel.
- **Falha demorou mais que 40 s**: é a eleição sorteada; esperar, não repetir a ação.
- **Nunca** desligar o cluster inteiro logo depois de um failover, antes do self-healing terminar
  (~30 s): pode subir com dois primaries.

## Perguntas prováveis da banca
- **Por que 3 nós e um deles sem banco?** Maioria de 2 em 3 com só 2 bancos; o árbitro desempata
  sem custo de replicação.
- **E se o líder cair no meio de uma escrita?** O Raft garante um único líder por mandato; a
  replicação do PostgreSQL é assíncrona, então escritas ainda não replicadas podem ser perdidas
  no failover (limitação do modo de replicação, não do Raft).
- **Por que o failover leva ~30 s?** Tempo de eleição de 15 a 30 s (configuração da monografia),
  escolhido para evitar falsos positivos; a promoção leva ~2 s.
- **Durabilidade do estado do Raft em queda de energia?** O `fsync` e a escrita atômica do
  estado são trabalho futuro (já indicado na apresentação).
- **O `reconfigureStandby` não é usado?** Na topologia de 2 bancos mais árbitro o único outro
  nó com banco volta pelo self-healing; só faria falta com 3 ou mais bancos.
- **Dois primaries ao religar tudo?** Só se o cluster inteiro for desligado depois de um failover
  e antes do self-healing; hoje vence a eleição e o outro se clona. Critério pela *timeline* do
  PostgreSQL fica como trabalho futuro.
