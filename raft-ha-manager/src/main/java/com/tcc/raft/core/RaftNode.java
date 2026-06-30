package com.tcc.raft.core;

import com.tcc.raft.config.NodeConfig;
import com.tcc.raft.grpc.RaftGrpcClient;
import com.tcc.raft.postgres.FailoverExecutor;
import com.tcc.raft.postgres.PostgresHealthChecker;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Máquina de estados do algoritmo Raft.
 *
 * <p>Esta classe implementa o núcleo do protocolo Raft conforme descrito em:
 * Diego Ongaro e John Ousterhout, "In Search of an Understandable Consensus
 * Algorithm", USENIX ATC 2014.
 *
 * <p>Responsabilidades:
 * <ul>
 *   <li>Gerenciar as transições de papel (FOLLOWER, CANDIDATE, LEADER).
 *   <li>Processar as RPCs {@code RequestVote} e {@code AppendEntries}.
 *   <li>Iniciar eleições quando o temporizador expira.
 *   <li>Replicar entradas de log para os seguidores quando líder.
 *   <li>Aplicar entradas confirmadas à máquina de estados (failover, config change).
 *   <li>Persistir o estado volátil (currentTerm, votedFor) para sobreviver a crashes.
 * </ul>
 *
 * <p>Modelo de concorrência: toda modificação de estado é protegida por
 * {@code stateLock}. As chamadas gRPC vindas de outros nós são recebidas
 * nas threads do servidor Netty e adquirem o lock antes de modificar estado.
 * As tarefas agendadas (eleição, heartbeat) também adquirem o lock.
 * Chamadas gRPC de saída (para outros nós) são feitas fora do lock para
 * evitar deadlock e permitir chamadas paralelas.
 */
public class RaftNode {

    private static final Logger log = LoggerFactory.getLogger(RaftNode.class);
    private static final Gson GSON = new GsonBuilder().create();

    // =========================================================================
    // Estado persistido (deve sobreviver a reinicializações)
    // Seção 5.4 do paper: "Persistent state on all servers"
    // =========================================================================

    /** Último mandato que este nó viu. Aumenta monotonicamente. */
    private long currentTerm = 0;

    /**
     * ID do candidato para quem votou no mandato atual.
     * Null se não votou em ninguém neste mandato.
     */
    private String votedFor = null;

    // =========================================================================
    // Estado volátil (pode ser reconstruído após um restart via log)
    // =========================================================================

    /** Índice da maior entrada de log confirmada (committed). */
    private long commitIndex = 0;

    /** Índice da maior entrada de log já aplicada à máquina de estados. */
    private long lastApplied = 0;

    // =========================================================================
    // Estado volátil do líder (reinicializado após cada eleição)
    // =========================================================================

    /**
     * Para cada par: índice da próxima entrada de log a enviar.
     * Inicializado como lastIndex() + 1 quando o nó se torna líder.
     */
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();

    /**
     * Para cada par: índice da maior entrada de log confirmada como replicada.
     * Inicializado como 0 quando o nó se torna líder.
     */
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    // =========================================================================
    // Papel e identidade
    // =========================================================================

    private volatile RaftRole role = RaftRole.FOLLOWER;
    private volatile String currentLeaderId = null;

    // =========================================================================
    // Dependências
    // =========================================================================

    private final NodeConfig config;
    private final RaftLog raftLog;
    private final ElectionTimer electionTimer;
    private final Map<String, RaftGrpcClient> peerClients;
    private final PostgresHealthChecker healthChecker;
    private final FailoverExecutor failoverExecutor;

    // =========================================================================
    // Executors
    // =========================================================================

    /** Pool para enviar RequestVote em paralelo durante eleições. */
    private final ExecutorService electionExecutor;

    /** Pool para enviar AppendEntries em paralelo para todos os seguidores. */
    private final ExecutorService replicationExecutor;

    /** Scheduler para heartbeats periódicos quando líder. */
    private final ScheduledExecutorService heartbeatScheduler;
    private ScheduledFuture<?> heartbeatTask;

    // =========================================================================
    // Persistência do estado
    // =========================================================================

    private final Path stateFilePath;

    // Lock principal para todas as modificações de estado do Raft
    private final Object stateLock = new Object();

    // =========================================================================
    // Construtor
    // =========================================================================

    public RaftNode(NodeConfig config,
                    RaftLog raftLog,
                    Map<String, RaftGrpcClient> peerClients,
                    PostgresHealthChecker healthChecker,
                    FailoverExecutor failoverExecutor) {
        this.config = config;
        this.raftLog = raftLog;
        this.peerClients = peerClients;
        this.healthChecker = healthChecker;
        this.failoverExecutor = failoverExecutor;
        this.stateFilePath = Paths.get(config.getStorage().getLogDir(), "raft-state.json");

        this.electionExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "raft-election");
            t.setDaemon(true);
            return t;
        });
        this.replicationExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "raft-replication");
            t.setDaemon(true);
            return t;
        });
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-heartbeat");
            t.setDaemon(true);
            return t;
        });

        // O ElectionTimer chama startElection() quando expira
        this.electionTimer = new ElectionTimer(
            config.getRaft().getElectionTimeoutMinMs(),
            config.getRaft().getElectionTimeoutMaxMs(),
            this::startElection
        );
    }

    // =========================================================================
    // Ciclo de vida
    // =========================================================================

    /**
     * Inicializa o nó: carrega o estado persistido e começa como FOLLOWER.
     * O temporizador de eleição é iniciado imediatamente.
     */
    public void start() throws IOException {
        loadPersistedState();
        log.info("Nó {} iniciado como FOLLOWER no mandato {}.", config.getNodeId(), currentTerm);
        electionTimer.reset();

        // Inicia o monitoramento do PostgreSQL local (apenas nós não-árbitros)
        if (!config.isArbiter()) {
            healthChecker.start();
        }
    }

    /** Para o nó de forma limpa. Cancela timers e fecha conexões. */
    public void stop() {
        electionTimer.shutdown();
        stopHeartbeatScheduler();
        electionExecutor.shutdownNow();
        replicationExecutor.shutdownNow();
        heartbeatScheduler.shutdownNow();
        healthChecker.stop();
        raftLog.close();
        log.info("Nó {} parado.", config.getNodeId());
    }

    // =========================================================================
    // Processamento de RPCs (chamado pela camada gRPC)
    // =========================================================================

    /**
     * Processa uma requisição de voto de um candidato.
     *
     * <p>Regras (seção 5.2 do paper):
     * <ol>
     *   <li>Se term do candidato < currentTerm: nega o voto.
     *   <li>Se term do candidato > currentTerm: atualiza o termo e vira FOLLOWER.
     *   <li>Concede voto se: (votedFor é null ou é o próprio candidato) E o log
     *       do candidato é pelo menos tão atualizado quanto o nosso.
     * </ol>
     */
    public RaftProto.RequestVoteResponse handleRequestVote(RaftProto.RequestVoteRequest request) {
        synchronized (stateLock) {
            log.debug("RequestVote recebido de {} para o mandato {}.",
                request.getCandidateId(), request.getTerm());

            // Regra 1: Rejeita se o mandato do candidato for menor
            if (request.getTerm() < currentTerm) {
                log.debug("Voto negado para {}: mandato desatualizado ({} < {}).",
                    request.getCandidateId(), request.getTerm(), currentTerm);
                return buildVoteResponse(false);
            }

            // Regra 2: Atualiza o mandato se o do candidato for maior
            if (request.getTerm() > currentTerm) {
                becomeFollower(request.getTerm(), null);
            }

            // Regra 3: Concede voto se as condições forem satisfeitas
            boolean candidateLogIsUpToDate = isCandidateLogUpToDate(
                request.getLastLogIndex(), request.getLastLogTerm());
            boolean canVote = (votedFor == null || votedFor.equals(request.getCandidateId()));

            if (canVote && candidateLogIsUpToDate) {
                votedFor = request.getCandidateId();
                persistState(); // DEVE persistir antes de responder
                electionTimer.reset(); // Reseta o timer ao conceder voto
                log.info("Voto concedido para {} no mandato {}.",
                    request.getCandidateId(), currentTerm);
                return buildVoteResponse(true);
            }

            log.debug("Voto negado para {}: canVote={}, logUpToDate={}.",
                request.getCandidateId(), canVote, candidateLogIsUpToDate);
            return buildVoteResponse(false);
        }
    }

    /**
     * Processa uma requisição AppendEntries do líder.
     *
     * <p>Funciona tanto como heartbeat (entries vazio) quanto como replicação de log.
     * Esta implementação segue as regras da Figura 2 do paper do Raft.
     */
    public RaftProto.AppendEntriesResponse handleAppendEntries(RaftProto.AppendEntriesRequest request) {
        synchronized (stateLock) {
            // Regra 1: Rejeita se o mandato do líder for menor que o nosso
            if (request.getTerm() < currentTerm) {
                log.debug("AppendEntries rejeitado de {}: mandato desatualizado ({} < {}).",
                    request.getLeaderId(), request.getTerm(), currentTerm);
                return buildAppendResponse(false);
            }

            // Reconhece o líder e atualiza o mandato se necessário
            if (request.getTerm() >= currentTerm) {
                becomeFollower(request.getTerm(), request.getLeaderId());
            }

            // Reinicia o temporizador de eleição: recebemos heartbeat de um líder válido
            electionTimer.reset();

            // Regra 2: Verificação de consistência de log
            // Rejeita se a entrada anterior não bater com o nosso log
            if (!raftLog.isConsistentWith(request.getPrevLogIndex(), request.getPrevLogTerm())) {
                log.warn("AppendEntries rejeitado: inconsistência de log no índice {} (mandato esperado: {}).",
                    request.getPrevLogIndex(), request.getPrevLogTerm());
                return buildAppendResponse(false);
            }

            // Regra 3 e 4: Processa as entradas (se houver)
            if (!request.getEntriesList().isEmpty()) {
                try {
                    for (RaftProto.LogEntry protoEntry : request.getEntriesList()) {
                        RaftLogEntry entry = convertFromProto(protoEntry);
                        raftLog.appendOrReplace(entry);
                    }
                } catch (IOException e) {
                    log.error("Erro ao gravar entradas no log Raft.", e);
                    return buildAppendResponse(false);
                }
            }

            // Regra 5: Avança o commitIndex se o líder confirmar novas entradas
            advanceCommitIndex(request.getLeaderCommit());

            return RaftProto.AppendEntriesResponse.newBuilder()
                .setTerm(currentTerm)
                .setSuccess(true)
                .setMatchIndex(raftLog.lastIndex())
                .build();
        }
    }

    // =========================================================================
    // Eleição
    // =========================================================================

    /**
     * Inicia uma eleição: incrementa o mandato, se auto-candidata e solicita
     * votos de todos os pares em paralelo.
     *
     * <p>Chamado pelo ElectionTimer quando o timeout expira sem heartbeat.
     * Também pode ser chamado recursivamente se a eleição não produzir resultado
     * (o timer é resetado e pode disparar novamente).
     */
    private void startElection() {
        final long electionTerm;
        final long lastLogIndex;
        final long lastLogTerm;

        synchronized (stateLock) {
            // Transição para CANDIDATE
            currentTerm++;
            role = RaftRole.CANDIDATE;
            votedFor = config.getNodeId(); // Voto em si mesmo
            persistState();

            electionTerm = currentTerm;
            lastLogIndex = raftLog.lastIndex();
            lastLogTerm = raftLog.lastTerm();

            log.info("Nó {} iniciou eleição para o mandato {}.", config.getNodeId(), currentTerm);

            // Reseta o timer caso esta eleição não produza um líder
            electionTimer.reset();
        }

        // Voto próprio já está contado
        AtomicInteger votesReceived = new AtomicInteger(1);
        int quorum = quorumSize();

        RaftProto.RequestVoteRequest voteRequest = RaftProto.RequestVoteRequest.newBuilder()
            .setTerm(electionTerm)
            .setCandidateId(config.getNodeId())
            .setLastLogIndex(lastLogIndex)
            .setLastLogTerm(lastLogTerm)
            .build();

        // Envia RequestVote para todos os pares em paralelo (fora do lock)
        for (Map.Entry<String, RaftGrpcClient> entry : peerClients.entrySet()) {
            final String peerId = entry.getKey();
            final RaftGrpcClient client = entry.getValue();

            electionExecutor.submit(() -> {
                try {
                    RaftProto.RequestVoteResponse response = client.requestVote(voteRequest);

                    synchronized (stateLock) {
                        // Verifica se ainda somos candidatos no mesmo mandato
                        if (role != RaftRole.CANDIDATE || currentTerm != electionTerm) {
                            return;
                        }

                        // Se o respondente tem mandato maior, reverte para FOLLOWER
                        if (response.getTerm() > currentTerm) {
                            log.info("Mandato mais alto ({}) detectado na resposta de {}. Revertendo para FOLLOWER.",
                                response.getTerm(), peerId);
                            becomeFollower(response.getTerm(), null);
                            return;
                        }

                        if (response.getVoteGranted()) {
                            int total = votesReceived.incrementAndGet();
                            log.debug("Voto recebido de {} ({}/{}). Quorum necessário: {}.",
                                peerId, total, peerClients.size() + 1, quorum);

                            if (total >= quorum) {
                                becomeLeader();
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Erro ao solicitar voto de {}: {}.", peerId, e.getMessage());
                }
            });
        }
    }

    // =========================================================================
    // Transições de papel
    // =========================================================================

    /**
     * Transição para FOLLOWER. Atualiza o mandato e reseta o líder conhecido.
     * Deve ser chamado dentro do stateLock.
     */
    private void becomeFollower(long newTerm, String leaderId) {
        if (role == RaftRole.LEADER) {
            log.info("Nó {} deixou de ser LEADER. Revertendo para FOLLOWER no mandato {}.",
                config.getNodeId(), newTerm);
            stopHeartbeatScheduler();
        } else if (role == RaftRole.CANDIDATE) {
            log.info("Nó {} desistiu da candidatura. Revertendo para FOLLOWER no mandato {}.",
                config.getNodeId(), newTerm);
        }

        if (newTerm > currentTerm) {
            currentTerm = newTerm;
            votedFor = null;
            persistState();
        }

        role = RaftRole.FOLLOWER;
        currentLeaderId = leaderId;
        electionTimer.reset();
    }

    /**
     * Transição para LEADER após vencer a eleição.
     * Deve ser chamado dentro do stateLock.
     */
    private void becomeLeader() {
        if (role != RaftRole.CANDIDATE) {
            return; // Corrida: outro evento mudou o papel antes
        }

        log.info("Nó {} eleito LÍDER para o mandato {}.", config.getNodeId(), currentTerm);
        role = RaftRole.LEADER;
        currentLeaderId = config.getNodeId();

        // Para o temporizador de eleição: líderes não precisam dele
        electionTimer.pause();

        // Inicializa nextIndex e matchIndex para todos os seguidores
        long nextIdx = raftLog.lastIndex() + 1;
        for (String peerId : peerClients.keySet()) {
            nextIndex.put(peerId, nextIdx);
            matchIndex.put(peerId, 0L);
        }

        // Insere entrada NO_OP: força o avanço do commitIndex sobre
        // entradas de mandatos anteriores que ainda não foram confirmadas.
        // Esta é uma exigência de segurança do Raft (seção 5.4.2 do paper).
        try {
            RaftLogEntry noOp = new RaftLogEntry(
                raftLog.lastIndex() + 1, currentTerm, LogEntryType.NO_OP, new byte[0]);
            raftLog.append(noOp);
        } catch (IOException e) {
            log.error("Falha ao gravar entrada NO_OP após eleição. O nó pode não confirmar " +
                "entradas de mandatos anteriores.", e);
        }

        // Inicia o envio periódico de heartbeats
        startHeartbeatScheduler();

        // Verifica se o PostgreSQL local precisa ser promovido
        if (!config.isArbiter()) {
            replicationExecutor.submit(this::checkAndExecutePostgresPromotion);
        }
    }

    // =========================================================================
    // Heartbeats e replicação de log
    // =========================================================================

    private void startHeartbeatScheduler() {
        long intervalMs = config.getRaft().getHeartbeatIntervalMs();
        heartbeatTask = heartbeatScheduler.scheduleAtFixedRate(
            this::sendHeartbeatsToAll,
            0, intervalMs, TimeUnit.MILLISECONDS
        );
        log.debug("Scheduler de heartbeats iniciado (intervalo: {} ms).", intervalMs);
    }

    private void stopHeartbeatScheduler() {
        if (heartbeatTask != null && !heartbeatTask.isDone()) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
            log.debug("Scheduler de heartbeats parado.");
        }
    }

    /**
     * Envia AppendEntries para todos os seguidores.
     * Em um heartbeat puro, entries estará vazio.
     * Em uma replicação, entries conterá as entradas pendentes para cada seguidor.
     *
     * <p>Chamado periodicamente pelo scheduler de heartbeats.
     */
    private void sendHeartbeatsToAll() {
        // Captura o estado necessário dentro do lock, depois envia fora do lock
        final long term;
        final long commitIdx;
        final long lastIdx;

        synchronized (stateLock) {
            if (role != RaftRole.LEADER) return;
            term = currentTerm;
            commitIdx = commitIndex;
            lastIdx = raftLog.lastIndex();
        }

        for (Map.Entry<String, RaftGrpcClient> entry : peerClients.entrySet()) {
            final String peerId = entry.getKey();
            final RaftGrpcClient client = entry.getValue();

            replicationExecutor.submit(() -> sendAppendEntriesToPeer(peerId, client, term, commitIdx));
        }
    }

    /**
     * Envia AppendEntries para um único seguidor.
     * Se o seguidor estiver desatualizado, envia as entradas pendentes.
     * Se estiver atualizado, envia um heartbeat vazio.
     */
    private void sendAppendEntriesToPeer(String peerId, RaftGrpcClient client,
                                          long term, long leaderCommit) {
        final long prevLogIndex;
        final long prevLogTerm;
        final List<RaftProto.LogEntry> entriesToSend;

        synchronized (stateLock) {
            if (role != RaftRole.LEADER || currentTerm != term) return;

            long next = nextIndex.getOrDefault(peerId, raftLog.lastIndex() + 1);
            prevLogIndex = next - 1;
            prevLogTerm = (prevLogIndex > 0 && prevLogIndex <= raftLog.lastIndex())
                ? raftLog.get(prevLogIndex).getTerm() : 0;

            List<RaftLogEntry> pending = raftLog.getFrom(next);
            entriesToSend = new ArrayList<>();
            for (RaftLogEntry e : pending) {
                entriesToSend.add(convertToProto(e));
            }
        }

        RaftProto.AppendEntriesRequest request = RaftProto.AppendEntriesRequest.newBuilder()
            .setTerm(term)
            .setLeaderId(config.getNodeId())
            .setPrevLogIndex(prevLogIndex)
            .setPrevLogTerm(prevLogTerm)
            .addAllEntries(entriesToSend)
            .setLeaderCommit(leaderCommit)
            .build();

        try {
            RaftProto.AppendEntriesResponse response = client.appendEntries(request);

            synchronized (stateLock) {
                if (role != RaftRole.LEADER || currentTerm != term) return;

                if (response.getTerm() > currentTerm) {
                    log.info("Mandato mais alto ({}) detectado na resposta de {}. Revertendo para FOLLOWER.",
                        response.getTerm(), peerId);
                    becomeFollower(response.getTerm(), null);
                    return;
                }

                if (response.getSuccess()) {
                    // Atualiza matchIndex e nextIndex com base no que o seguidor confirmou
                    long matched = response.getMatchIndex();
                    matchIndex.put(peerId, matched);
                    nextIndex.put(peerId, matched + 1);

                    // Verifica se alguma nova entrada pode ser confirmada
                    tryAdvanceCommitIndex();
                } else {
                    // Seguidor rejeitou: recua nextIndex para tentar na próxima rodada
                    long current = nextIndex.getOrDefault(peerId, 1L);
                    if (current > 1) {
                        nextIndex.put(peerId, current - 1);
                    }
                    log.debug("Seguidor {} rejeitou AppendEntries. nextIndex recuado para {}.",
                        peerId, nextIndex.get(peerId));
                }
            }
        } catch (Exception e) {
            log.warn("Erro ao enviar AppendEntries para {}: {}.", peerId, e.getMessage());
        }
    }

    // =========================================================================
    // Confirmação de entradas (commit)
    // =========================================================================

    /**
     * Tenta avançar o commitIndex quando há entradas replicadas pela maioria.
     *
     * <p>Regra do Raft (seção 5.3): o líder só pode confirmar entradas do
     * mandato atual. Entradas de mandatos anteriores só são confirmadas
     * indiretamente, quando uma entrada do mandato atual é confirmada.
     * A entrada NO_OP inserida em becomeLeader() serve exatamente para isso.
     *
     * <p>Deve ser chamado dentro do stateLock.
     */
    private void tryAdvanceCommitIndex() {
        long lastIdx = raftLog.lastIndex();
        for (long n = lastIdx; n > commitIndex; n--) {
            // Só confirma entradas do mandato atual
            if (raftLog.get(n).getTerm() != currentTerm) continue;

            // Conta quantos nós replicaram até o índice n (incluindo o próprio líder)
            int replicatedCount = 1;
            for (long matched : matchIndex.values()) {
                if (matched >= n) replicatedCount++;
            }

            if (replicatedCount >= quorumSize()) {
                log.info("Confirmando entradas até o índice {} (replicado em {}/{} nós).",
                    n, replicatedCount, peerClients.size() + 1);
                commitIndex = n;
                applyCommittedEntries();
                break;
            }
        }
    }

    /**
     * Avança o commitIndex a partir de um valor informado pelo líder.
     * Usado por seguidores ao receber AppendEntries.
     * Deve ser chamado dentro do stateLock.
     */
    private void advanceCommitIndex(long leaderCommit) {
        if (leaderCommit > commitIndex) {
            commitIndex = Math.min(leaderCommit, raftLog.lastIndex());
            applyCommittedEntries();
        }
    }

    /**
     * Aplica à máquina de estados todas as entradas confirmadas que ainda não
     * foram aplicadas (lastApplied < commitIndex).
     * Deve ser chamado dentro do stateLock.
     */
    private void applyCommittedEntries() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = raftLog.get(lastApplied);
            applyEntry(entry);
        }
    }

    /**
     * Aplica uma única entrada à máquina de estados.
     * Deve ser chamado dentro do stateLock.
     */
    private void applyEntry(RaftLogEntry entry) {
        log.info("Aplicando entrada confirmada: {}", entry);
        switch (entry.getType()) {
            case NO_OP:
                // Não há nada a fazer; a entrada existe para forçar confirmação
                break;
            case FAILOVER:
                handleFailoverEntry(entry);
                break;
            case CONFIG_CHANGE:
                handleConfigChangeEntry(entry);
                break;
            default:
                log.warn("Tipo de entrada desconhecido: {}. Ignorando.", entry.getType());
        }
    }

    // =========================================================================
    // Aplicação de entradas específicas
    // =========================================================================

    private void handleFailoverEntry(RaftLogEntry entry) {
        // O failover real é executado em checkAndExecutePostgresPromotion()
        // após a eleição. Esta entrada serve como registro auditável no log.
        log.info("FAILOVER registrado no log: {}", entry.getDataAsString());
    }

    private void handleConfigChangeEntry(RaftLogEntry entry) {
        // TODO: Fase 3 - Implementar atualização da lista de pares
        log.info("CONFIG_CHANGE confirmado: {}", entry.getDataAsString());
    }

    // =========================================================================
    // Integração com PostgreSQL
    // =========================================================================

    /**
     * Verifica se o PostgreSQL local está como standby e, se estiver,
     * executa a promoção para primary.
     *
     * <p>Chamado logo após o nó se tornar líder. Se o PostgreSQL local já
     * for primary, nenhuma ação é necessária. Se for standby, precisa ser
     * promovido para que o cluster de banco de dados esteja alinhado com
     * o líder do Raft.
     */
    private void checkAndExecutePostgresPromotion() {
        PostgresHealthChecker.Status pgStatus = healthChecker.getCurrentStatus();

        if (pgStatus == PostgresHealthChecker.Status.PRIMARY) {
            log.info("PostgreSQL local já é PRIMARY. Nenhuma promoção necessária.");
            return;
        }

        if (pgStatus == PostgresHealthChecker.Status.STANDBY) {
            log.info("PostgreSQL local é STANDBY. Iniciando promoção para PRIMARY.");
            try {
                // Registra o evento de failover no log Raft antes de executar
                String failoverJson = buildFailoverJson("unknown", config.getNodeId(), "leader_elected");
                synchronized (stateLock) {
                    if (role == RaftRole.LEADER) {
                        RaftLogEntry failoverEntry = new RaftLogEntry(
                            raftLog.lastIndex() + 1, currentTerm, LogEntryType.FAILOVER,
                            failoverJson.getBytes(StandardCharsets.UTF_8));
                        raftLog.append(failoverEntry);
                    }
                }
                // Executa a promoção no PostgreSQL
                failoverExecutor.promote();
            } catch (Exception e) {
                log.error("Falha ao promover PostgreSQL para PRIMARY.", e);
            }
        } else {
            log.warn("PostgreSQL local está UNHEALTHY. Não é possível promover. " +
                "O cluster pode ficar sem PRIMARY.", pgStatus);
        }
    }

    // =========================================================================
    // Utilitários
    // =========================================================================

    /**
     * Verifica se o log do candidato é pelo menos tão atualizado quanto o nosso.
     *
     * <p>Regra do Raft (seção 5.4.1): o log do candidato é mais atualizado se:
     * (1) seu último mandato é maior, ou
     * (2) o último mandato é igual e o log é pelo menos tão longo quanto o nosso.
     */
    private boolean isCandidateLogUpToDate(long candidateLastIndex, long candidateLastTerm) {
        long ourLastTerm = raftLog.lastTerm();
        long ourLastIndex = raftLog.lastIndex();
        if (candidateLastTerm != ourLastTerm) {
            return candidateLastTerm > ourLastTerm;
        }
        return candidateLastIndex >= ourLastIndex;
    }

    /**
     * Calcula o quorum necessário para este cluster.
     * Quorum = floor(N/2) + 1, onde N é o número total de nós (incluindo este).
     */
    private int quorumSize() {
        int totalNodes = peerClients.size() + 1;
        return (totalNodes / 2) + 1;
    }

    private RaftProto.RequestVoteResponse buildVoteResponse(boolean granted) {
        return RaftProto.RequestVoteResponse.newBuilder()
            .setTerm(currentTerm)
            .setVoteGranted(granted)
            .build();
    }

    private RaftProto.AppendEntriesResponse buildAppendResponse(boolean success) {
        return RaftProto.AppendEntriesResponse.newBuilder()
            .setTerm(currentTerm)
            .setSuccess(success)
            .setMatchIndex(raftLog.lastIndex())
            .build();
    }

    private String buildFailoverJson(String oldLeader, String newLeader, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("old_leader_id", oldLeader);
        data.put("new_leader_id", newLeader);
        data.put("reason", reason);
        data.put("initiated_at_ms", System.currentTimeMillis());
        return GSON.toJson(data);
    }

    // =========================================================================
    // Conversão entre modelos interno e Protobuf
    // =========================================================================

    private RaftLogEntry convertFromProto(RaftProto.LogEntry proto) {
        LogEntryType type = switch (proto.getType()) {
            case NO_OP -> LogEntryType.NO_OP;
            case FAILOVER -> LogEntryType.FAILOVER;
            case CONFIG_CHANGE -> LogEntryType.CONFIG_CHANGE;
            default -> throw new IllegalArgumentException("Tipo de entrada desconhecido: " + proto.getType());
        };
        return new RaftLogEntry(proto.getIndex(), proto.getTerm(), type, proto.getData().toByteArray());
    }

    private RaftProto.LogEntry convertToProto(RaftLogEntry entry) {
        RaftProto.LogEntry.EntryType protoType = switch (entry.getType()) {
            case NO_OP -> RaftProto.LogEntry.EntryType.NO_OP;
            case FAILOVER -> RaftProto.LogEntry.EntryType.FAILOVER;
            case CONFIG_CHANGE -> RaftProto.LogEntry.EntryType.CONFIG_CHANGE;
        };
        return RaftProto.LogEntry.newBuilder()
            .setIndex(entry.getIndex())
            .setTerm(entry.getTerm())
            .setType(protoType)
            .setData(com.google.protobuf.ByteString.copyFrom(entry.getData()))
            .build();
    }

    // =========================================================================
    // Persistência do estado Raft (currentTerm e votedFor)
    // =========================================================================

    /**
     * Persiste currentTerm e votedFor em disco.
     *
     * <p>Esta chamada é SÍNCRONA e deve ocorrer antes de responder a qualquer RPC
     * que modifica esses valores. O Raft garante a segurança apenas se o estado
     * persistido for escrito antes da resposta ser enviada.
     *
     * <p>Deve ser chamado dentro do stateLock.
     */
    private void persistState() {
        try {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("currentTerm", currentTerm);
            state.put("votedFor", votedFor);
            String json = GSON.toJson(state);
            Files.writeString(stateFilePath, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Em um sistema de produção, uma falha aqui deveria causar o crash do processo
            // para evitar violar as garantias de segurança do Raft.
            log.error("FALHA CRÍTICA: não foi possível persistir o estado do Raft. " +
                "Isso pode violar as garantias de segurança do algoritmo.", e);
            throw new RuntimeException("Falha ao persistir estado Raft.", e);
        }
    }

    private void loadPersistedState() {
        if (!Files.exists(stateFilePath)) {
            log.info("Nenhum estado persistido encontrado. Iniciando com term=0.");
            return;
        }
        try {
            String json = Files.readString(stateFilePath, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> state = GSON.fromJson(json, Map.class);
            currentTerm = ((Number) state.get("currentTerm")).longValue();
            votedFor = (String) state.get("votedFor");
            log.info("Estado persistido carregado: term={}, votedFor={}.", currentTerm, votedFor);
        } catch (IOException e) {
            log.error("Erro ao carregar estado persistido. Iniciando com valores padrão.", e);
        }
    }

    // =========================================================================
    // Getters para consulta de estado (usados pelo ClusterManagementService)
    // =========================================================================

    public RaftRole getRole() { return role; }
    public long getCurrentTerm() { return currentTerm; }
    public String getCurrentLeaderId() { return currentLeaderId; }
    public long getCommitIndex() { return commitIndex; }
    public String getNodeId() { return config.getNodeId(); }
}
