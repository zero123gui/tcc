package com.tcc.raft.config;

import java.util.Map;

/**
 * Parâmetros de temporização do algoritmo Raft.
 *
 * <p>A escolha dos valores de timeout é crítica para o comportamento do sistema:
 * <ul>
 *   <li>Timeouts muito curtos causam eleições desnecessárias por falhas transitórias de rede.
 *   <li>Timeouts muito longos aumentam o tempo de detecção de falhas reais.
 * </ul>
 *
 * <p>A regra fundamental do Raft é: heartbeat-interval {@code <<} election-timeout.
 * Recomenda-se que o election-timeout seja pelo menos 3x o heartbeat-interval.
 */
public class RaftConfig {

    // Intervalo de envio de heartbeats pelo líder, em milissegundos
    private long heartbeatIntervalMs;

    // Limite inferior do jitter de eleição, em milissegundos
    private long electionTimeoutMinMs;

    // Limite superior do jitter de eleição, em milissegundos
    private long electionTimeoutMaxMs;

    // Timeout para chamadas gRPC entre pares, em milissegundos
    private long rpcTimeoutMs;

    /**
     * Pré-votação (tese do Raft, seção 9.6) e proteção contra servidores disruptivos
     * ("leader stickiness", seção 4.2.3): um nó só inicia eleição se a maioria confirmar
     * que não há líder ativo, e quem ainda ouve o líder ignora candidaturas. Impede que um
     * nó que volta ao cluster derrube um líder saudável. Desligável para experimentos
     * comparativos.
     */
    private boolean preVote;

    /**
     * Check-quorum (tese do Raft, seção 6.2): o líder renuncia se ficar sem resposta da
     * maioria por mais de election-timeout-min-ms. Impede um líder isolado de continuar
     * se considerando líder. Desligável para experimentos comparativos.
     */
    private boolean checkQuorum;

    public static RaftConfig withDefaults() {
        RaftConfig cfg = new RaftConfig();
        cfg.heartbeatIntervalMs = 5000;
        cfg.electionTimeoutMinMs = 15000;
        cfg.electionTimeoutMaxMs = 30000;
        cfg.rpcTimeoutMs = 3000;
        cfg.preVote = true;
        cfg.checkQuorum = true;
        return cfg;
    }

    public static RaftConfig fromMap(Map<String, Object> raw) {
        RaftConfig cfg = withDefaults();
        if (raw.containsKey("heartbeat-interval-ms")) {
            cfg.heartbeatIntervalMs = toLong(raw.get("heartbeat-interval-ms"));
        }
        if (raw.containsKey("election-timeout-min-ms")) {
            cfg.electionTimeoutMinMs = toLong(raw.get("election-timeout-min-ms"));
        }
        if (raw.containsKey("election-timeout-max-ms")) {
            cfg.electionTimeoutMaxMs = toLong(raw.get("election-timeout-max-ms"));
        }
        if (raw.containsKey("rpc-timeout-ms")) {
            cfg.rpcTimeoutMs = toLong(raw.get("rpc-timeout-ms"));
        }
        if (raw.containsKey("pre-vote")) {
            cfg.preVote = toBoolean("pre-vote", raw.get("pre-vote"));
        }
        if (raw.containsKey("check-quorum")) {
            cfg.checkQuorum = toBoolean("check-quorum", raw.get("check-quorum"));
        }
        cfg.validate();
        return cfg;
    }

    private void validate() {
        if (electionTimeoutMinMs >= electionTimeoutMaxMs) {
            throw new IllegalStateException(
                "election-timeout-min-ms deve ser menor que election-timeout-max-ms.");
        }
        if (heartbeatIntervalMs >= electionTimeoutMinMs) {
            throw new IllegalStateException(
                "heartbeat-interval-ms deve ser menor que election-timeout-min-ms. " +
                "O líder precisa enviar heartbeats antes que seguidores iniciem eleições.");
        }
        if (rpcTimeoutMs >= heartbeatIntervalMs) {
            throw new IllegalStateException(
                "rpc-timeout-ms deve ser menor que heartbeat-interval-ms para que " +
                "chamadas gRPC falhem antes do próximo ciclo de heartbeat.");
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Long) return (Long) value;
        throw new IllegalStateException("Valor numérico esperado, recebido: " + value);
    }

    private static boolean toBoolean(String key, Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        throw new IllegalStateException(key + " deve ser true ou false, recebido: " + value);
    }

    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public long getElectionTimeoutMinMs() { return electionTimeoutMinMs; }
    public long getElectionTimeoutMaxMs() { return electionTimeoutMaxMs; }
    public long getRpcTimeoutMs() { return rpcTimeoutMs; }
    public boolean isPreVote() { return preVote; }
    public boolean isCheckQuorum() { return checkQuorum; }
}
