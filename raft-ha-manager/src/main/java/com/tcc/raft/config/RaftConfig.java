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

    public static RaftConfig withDefaults() {
        RaftConfig cfg = new RaftConfig();
        cfg.heartbeatIntervalMs = 5000;
        cfg.electionTimeoutMinMs = 15000;
        cfg.electionTimeoutMaxMs = 30000;
        cfg.rpcTimeoutMs = 3000;
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

    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public long getElectionTimeoutMinMs() { return electionTimeoutMinMs; }
    public long getElectionTimeoutMaxMs() { return electionTimeoutMaxMs; }
    public long getRpcTimeoutMs() { return rpcTimeoutMs; }
}
