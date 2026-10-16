package com.tcc.raft.core;

import com.tcc.raft.config.NodeConfig;
import com.tcc.raft.grpc.proto.RaftProto;
import com.tcc.raft.postgres.FailoverExecutor;
import com.tcc.raft.postgres.PostgresHealthChecker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Cluster de três {@link RaftNode} reais ligados por uma rede em memória.
 *
 * <p>Topologia igual à das VMs: node-1 (PostgreSQL PRIMARY), node-2 (STANDBY) e
 * node-3 (árbitro). O PostgreSQL e o failover são simulados: a promoção muda o
 * status do nó para PRIMARY e o fencing só é registrado.
 *
 * <p>A rede permite cortar a comunicação em um sentido só ({@link #block}), o que
 * reproduz o que acontecia nas VMs: um nó que volta consegue falar com os outros,
 * mas o líder ainda não o alcança porque o canal gRPC está em backoff.
 *
 * <p>Um amostrador verifica continuamente as invariantes do Raft: no máximo um
 * líder por mandato e mandatos nunca decrescentes em cada nó.
 */
final class ClusterHarness implements AutoCloseable {

    static final long HEARTBEAT_MS = 50;
    static final long ELECTION_MIN_MS = 300;
    static final long ELECTION_MAX_MS = 500;
    static final long RPC_TIMEOUT_MS = 40;
    static final List<String> IDS = List.of("node-1", "node-2", "node-3");

    private final boolean preVote;
    private final boolean checkQuorum;
    private final Path baseDir;

    private final Map<String, RaftNode> live = new ConcurrentHashMap<>();
    private final Map<String, FakeHealth> health = new ConcurrentHashMap<>();
    private final Map<String, FakeFailover> failover = new ConcurrentHashMap<>();
    /** Ligações cortadas, no formato "origem->destino". */
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();

    // Invariantes observadas pelo amostrador
    private final Map<Long, Set<String>> leadersByTerm = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenTerm = new ConcurrentHashMap<>();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final Set<String> everCandidate = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean sampling = new AtomicBoolean(true);
    private final Thread sampler;

    ClusterHarness(boolean preVote, boolean checkQuorum) throws IOException {
        this.preVote = preVote;
        this.checkQuorum = checkQuorum;
        this.baseDir = Files.createTempDirectory("raft-harness");
        health.put("node-1", new FakeHealth(PostgresHealthChecker.Status.PRIMARY));
        health.put("node-2", new FakeHealth(PostgresHealthChecker.Status.STANDBY));
        health.put("node-3", new FakeHealth(PostgresHealthChecker.Status.UNKNOWN));
        for (String id : IDS) {
            failover.put(id, new FakeFailover(health.get(id)));
        }
        sampler = new Thread(this::sampleLoop, "harness-sampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    // =========================================================================
    // Ciclo de vida dos nós
    // =========================================================================

    void start(String id) throws IOException {
        NodeConfig config = NodeConfig.fromMap(configFor(id));
        RaftLog raftLog = new RaftLog(config.getStorage().getLogDir());
        Map<String, PeerClient> clients = new HashMap<>();
        for (String peer : IDS) {
            if (!peer.equals(id)) {
                clients.put(peer, new InMemoryPeer(id, peer));
            }
        }
        RaftNode node = new RaftNode(config, raftLog, clients, health.get(id), failover.get(id));
        node.start();
        live.put(id, node);
    }

    /** Mata o nó (como um crash ou "systemctl stop"): o estado em disco é mantido. */
    void stop(String id) {
        RaftNode node = live.remove(id);
        if (node != null) {
            node.stop();
        }
    }

    /** Sobe o cluster na ordem segura: node-1 e o árbitro, espera o líder, depois node-2. */
    void startCluster() throws Exception {
        start("node-1");
        start("node-3");
        awaitTrue(() -> isLeader("node-1"), 5_000, "node-1 deveria ser eleito líder");
        start("node-2");
        awaitTrue(() -> "node-1".equals(leaderSeenBy("node-2")), 5_000, "node-2 deveria seguir o node-1");
    }

    // =========================================================================
    // Rede
    // =========================================================================

    void block(String from, String to) { blocked.add(from + "->" + to); }
    void unblock(String from, String to) { blocked.remove(from + "->" + to); }

    /** Isola o nó de todos os outros, nos dois sentidos. */
    void isolate(String id) {
        for (String other : IDS) {
            if (!other.equals(id)) { block(id, other); block(other, id); }
        }
    }

    void heal() { blocked.clear(); }

    private boolean reachable(String from, String to) {
        return live.containsKey(to) && !blocked.contains(from + "->" + to);
    }

    /** PeerClient que entrega a chamada diretamente ao RaftNode de destino. */
    private final class InMemoryPeer implements PeerClient {
        private final String from;
        private final String to;

        InMemoryPeer(String from, String to) { this.from = from; this.to = to; }

        private RaftNode target() {
            // O bloqueio vale para o canal de quem chama (a resposta volta pela mesma
            // conexão). Para isolar um nó por completo, bloqueie os dois sentidos.
            RaftNode node = live.get(to);
            if (node == null || !reachable(from, to)) {
                throw new IllegalStateException("UNAVAILABLE: " + from + " -> " + to);
            }
            return node;
        }

        @Override public RaftProto.RequestVoteResponse requestVote(RaftProto.RequestVoteRequest r) {
            return target().handleRequestVote(r);
        }
        @Override public RaftProto.RequestVoteResponse preVote(RaftProto.RequestVoteRequest r) {
            return target().handlePreVote(r);
        }
        @Override public RaftProto.AppendEntriesResponse appendEntries(RaftProto.AppendEntriesRequest r) {
            return target().handleAppendEntries(r);
        }
        @Override public void shutdown() { }
    }

    // =========================================================================
    // Consultas
    // =========================================================================

    RaftNode node(String id) { return live.get(id); }

    boolean isLeader(String id) {
        RaftNode n = live.get(id);
        return n != null && n.getRole() == RaftRole.LEADER;
    }

    String leaderSeenBy(String id) {
        RaftNode n = live.get(id);
        return n == null ? null : n.getCurrentLeaderId();
    }

    long term(String id) {
        RaftNode n = live.get(id);
        return n == null ? -1 : n.getCurrentTerm();
    }

    /** Líderes vistos em algum momento num mandato maior que {@code term}. */
    Set<Long> termsAbove(long term) {
        Set<Long> result = new TreeSet<>();
        for (Long t : leadersByTerm.keySet()) {
            if (t > term) result.add(t);
        }
        return result;
    }

    FakeFailover failover(String id) { return failover.get(id); }
    FakeHealth health(String id) { return health.get(id); }
    Set<String> everCandidate() { return everCandidate; }

    List<String> violations() { return violations; }

    // =========================================================================
    // Espera e amostragem
    // =========================================================================

    static void awaitTrue(BooleanSupplier condition, long timeoutMs, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError(message + " (aguardado " + timeoutMs + " ms)");
    }

    private void sampleLoop() {
        while (sampling.get()) {
            for (Map.Entry<String, RaftNode> e : live.entrySet()) {
                String id = e.getKey();
                // Papel e mandato precisam ser lidos juntos: lidos separadamente, um líder
                // que está renunciando aparece com o mandato novo e o papel antigo.
                RaftNode.StatusSnapshot s = e.getValue().snapshot();
                long t = s.term();
                RaftRole role = s.role();
                Long previous = lastSeenTerm.put(id, t);
                if (previous != null && t < previous) {
                    violations.add(id + ": mandato diminuiu de " + previous + " para " + t);
                }
                if (role == RaftRole.LEADER) {
                    Set<String> leaders = leadersByTerm.computeIfAbsent(t, k -> ConcurrentHashMap.newKeySet());
                    leaders.add(id);
                    if (leaders.size() > 1) {
                        violations.add("mais de um líder no mandato " + t + ": " + leaders);
                    }
                }
                if (role == RaftRole.CANDIDATE) {
                    everCandidate.add(id);
                }
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException ex) {
                return;
            }
        }
    }

    // =========================================================================
    // Configuração
    // =========================================================================

    private Map<String, Object> configFor(String id) {
        Map<String, Object> raft = new HashMap<>();
        raft.put("heartbeat-interval-ms", (int) HEARTBEAT_MS);
        raft.put("election-timeout-min-ms", (int) ELECTION_MIN_MS);
        raft.put("election-timeout-max-ms", (int) ELECTION_MAX_MS);
        raft.put("rpc-timeout-ms", (int) RPC_TIMEOUT_MS);
        raft.put("pre-vote", preVote);
        raft.put("check-quorum", checkQuorum);

        List<Map<String, Object>> peers = new ArrayList<>();
        for (int i = 0; i < IDS.size(); i++) {
            Map<String, Object> p = new HashMap<>();
            p.put("id", IDS.get(i));
            p.put("grpc-address", "192.168.56.10" + (i + 1) + ":9090");
            p.put("arbiter", "node-3".equals(IDS.get(i)));
            peers.add(p);
        }

        Map<String, Object> raw = new HashMap<>();
        raw.put("node-id", id);
        raw.put("grpc-address", "192.168.56.10" + (IDS.indexOf(id) + 1) + ":9090");
        raw.put("arbiter", "node-3".equals(id));
        if (!"node-3".equals(id)) {
            Map<String, Object> pg = new HashMap<>();
            pg.put("user", "teste");
            pg.put("data-dir", "/tmp/pg");
            raw.put("postgres", pg);
        }
        raw.put("raft", raft);
        raw.put("storage", Map.of("log-dir", baseDir.resolve(id).toString()));
        raw.put("peers", peers);
        return raw;
    }

    @Override
    public void close() {
        sampling.set(false);
        for (String id : new ArrayList<>(live.keySet())) {
            stop(id);
        }
    }

    // =========================================================================
    // PostgreSQL e failover simulados
    // =========================================================================

    static final class FakeHealth extends PostgresHealthChecker {
        private volatile Status status;

        FakeHealth(Status initial) {
            super(null);
            this.status = initial;
        }

        void set(Status s) { status = s; }

        @Override public void start() { }
        @Override public void stop() { }
        @Override public Status getCurrentStatus() { return status; }
        @Override public boolean isHealthy() { return status == Status.PRIMARY || status == Status.STANDBY; }
    }

    static final class FakeFailover extends FailoverExecutor {
        private final FakeHealth health;
        final List<Long> promotions = new CopyOnWriteArrayList<>();
        final List<String> fences = new CopyOnWriteArrayList<>();

        FakeFailover(FakeHealth health) {
            super(null);
            this.health = health;
        }

        @Override
        public void promote() {
            promotions.add(System.currentTimeMillis());
            health.set(PostgresHealthChecker.Status.PRIMARY);
        }

        @Override
        public void fenceAndRecover(String leaderIp) {
            fences.add(leaderIp);
            health.set(PostgresHealthChecker.Status.STANDBY);
        }
    }
}
