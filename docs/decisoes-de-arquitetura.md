# Decisões de arquitetura

Cada seção registra um problema, a decisão tomada e o motivo. O funcionamento detalhado está
em [arquitetura.md](arquitetura.md).

## 1. Raft para escolher o PRIMARY

**Problema:** decidir automaticamente qual nó hospeda o PostgreSQL PRIMARY, sem que dois nós se
considerem primary ao mesmo tempo, mesmo com falhas de nó e partições de rede.

**Decisão:** usar o algoritmo de consenso Raft. O líder do Raft é o nó do PRIMARY.

**Motivo:** o Raft garante no máximo um líder por mandato, aceito pela maioria. Foi escolhido
em vez do Paxos por ter sido projetado para ser mais compreensível, com resultado e eficiência
equivalentes.

A máquina de estados do Raft foi implementada no próprio agente, em vez de usar uma biblioteca
(Apache Ratis, etcd) ou uma ferramenta pronta (Patroni, repmgr, pg_auto_failover), por três
motivos, os mesmos da monografia: o aprofundamento acadêmico, a integração direta com o ciclo de
vida do PostgreSQL e o controle sobre o comportamento diante de falhas.

## 2. Três nós, um deles árbitro sem banco

**Problema:** o Raft precisa de maioria, então dois nós não bastam (a queda de um deixa o outro
sem maioria). Três cópias do banco custam mais disco, memória e replicação.

**Decisão:** dois nós com PostgreSQL e um **árbitro**, que só vota.

**Motivo:** a maioria é 2 de 3, então o cluster tolera a perda de qualquer um dos nós. O árbitro
é leve (768 MB de RAM no laboratório) e nunca se candidata, porque não tem banco para promover.

## 3. Fencing e self-healing contra split-brain

**Problema:** o Raft não controla o PostgreSQL diretamente. Depois de um failover, o antigo
primary pode voltar ainda como PRIMARY e aceitar gravações de clientes SCADA, enquanto o novo
primary aceita outras. Os dois bancos divergem (*split-brain*).

**Decisão:** um seguidor cujo PostgreSQL é PRIMARY:

1. para o próprio banco (**fencing**), para interromper as gravações;
2. refaz o banco como standby do líder (**self-healing**), com o `recovery-standby.sh`.

**Motivo:** em SCADA, perder temporariamente a disponibilidade de um nó é preferível a gravar
dados divergentes em dois bancos, que podem comandar equipamentos com informação errada.

**Salvaguardas** (acrescentadas depois de um incidente de perda de dados no laboratório,
em 15/10/2026):
- só uma recuperação por vez, e nenhuma nos 30 s seguintes a outra;
- o script confere que a origem é PRIMARY antes de parar ou apagar qualquer coisa;
- os dados antigos vão para `main.anterior` e voltam se o clone falhar;
- o fencing só acontece quando o PostgreSQL do líder já é PRIMARY (§6).

## 4. Pré-votação e proteção contra servidores disruptivos

**Problema:** um nó que volta de um isolamento costumava derrubar um líder saudável. Isolado,
ele não recebia heartbeats, iniciava eleições e incrementava o próprio mandato. Ao voltar, o
mandato maior forçava o líder a renunciar, provocando uma eleição e às vezes um failover do
PostgreSQL sem nenhuma falha real.

**Decisão:** implementar a **pré-votação** (tese do Raft, seção 9.6) e a **proteção contra
servidores disruptivos** (*leader stickiness*, seção 4.2.3).

**Motivo:** com as duas, o nó isolado não infla o mandato, e os seguidores que ainda ouvem o
líder ignoram candidaturas. Medido no laboratório: um standby isolado por 60 s derrubou o líder
em 5 de 5 repetições sem essas proteções, e em 0 de 8 com elas.

**Custo:** uma rodada a mais de mensagens antes de cada eleição. No cenário de partição, o
failover ficou cerca de 5 s mais lento, dentro da variação das medições.

## 5. Check-quorum

**Problema:** um líder isolado por uma partição continuava se considerando líder, enquanto o
outro lado elegia um novo. Havia dois nós se declarando líder, em mandatos diferentes.

**Decisão:** o líder que não recebe resposta da maioria por `election-timeout-min-ms` renuncia
(tese do Raft, seção 6.2).

**Motivo:** nas medições, o líder isolado renuncia por volta de 18 s, antes de o outro lado
eleger o novo líder. Sem o check-quorum, ele só renunciava quando a rede voltava.

## 6. Alinhamento entre o líder e o PRIMARY

**Problema:** ao religar o cluster inteiro, nenhum nó sabe quem era o líder, e quem vence a
primeira eleição vira líder sem promover nada. Se o vencedor fosse o standby, o primary virava
seguidor, se via como *Rogue Primary* e se clonava do líder, ou seja, de um standby. O cluster
ficava sem PRIMARY. Foi o que causou o incidente de 15/10.

**Decisão:** os nós informam, nas mensagens do Raft, se o próprio PostgreSQL é PRIMARY, e três
regras usam essa informação: o standby não se candidata se o primary responde, o líder sem banco
primário entrega a liderança ao primary e o fencing espera o líder ser PRIMARY.

**Alternativa descartada:** lembrar quem era o último líder. O último líder nem sempre é o
primary (o cluster pode ter sido desligado no meio de um failover), e no Raft a liderança é
de propósito descartável: só o mandato, o voto e o log são gravados em disco. O próprio
PostgreSQL sabe se é primary, e essa é a informação mais confiável.

## 7. Tempo de eleição entre 15 e 30 s

**Problema:** um tempo curto acelera o failover, mas aumenta os falsos positivos (eleições
causadas por atrasos momentâneos de rede ou de CPU nas VMs). Um tempo longo deixa o cluster mais
tempo sem primary.

**Decisão:** heartbeat de 5 s e eleição sorteada entre 15 e 30 s.

**Motivo:** o laboratório roda três VMs num notebook com pouca memória, onde pausas de alguns
segundos são comuns. O sorteio evita que dois nós se candidatem ao mesmo tempo. O failover
completo fica entre 25 e 35 s, e a promoção do PostgreSQL em si leva cerca de 2 s.
