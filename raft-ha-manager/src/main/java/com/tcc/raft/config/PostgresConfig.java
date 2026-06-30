package com.tcc.raft.config;

import java.util.Map;

/**
 * Configuração de conexão com o PostgreSQL local.
 *
 * <p>Usada pelo {@code PostgresHealthChecker} para verificar a saúde do banco
 * e pelo {@code FailoverExecutor} para executar a promoção do nó.
 *
 * <p>Esta configuração é ignorada em nós árbitros ({@code arbiter: true}).
 */
public class PostgresConfig {

    private String host;
    private int port;
    private String database;
    private String user;
    private String password;
    private String dataDir;
    private long healthCheckIntervalMs;
    private int healthCheckFailureThreshold;

    public static PostgresConfig fromMap(Map<String, Object> raw) {
        PostgresConfig cfg = new PostgresConfig();
        cfg.host = (String) raw.getOrDefault("host", "127.0.0.1");
        cfg.port = (int) raw.getOrDefault("port", 5432);
        cfg.database = (String) raw.getOrDefault("database", "postgres");
        cfg.user = (String) raw.get("user");
        cfg.password = (String) raw.get("password");
        cfg.dataDir = (String) raw.get("data-dir");
        cfg.healthCheckIntervalMs = toLong(raw.getOrDefault("health-check-interval-ms", 3000));
        cfg.healthCheckFailureThreshold = (int) raw.getOrDefault("health-check-failure-threshold", 3);

        if (cfg.user == null) {
            throw new IllegalStateException(
                "Configuração postgres inválida: 'user' é obrigatório.");
        }
        if (cfg.dataDir == null) {
            throw new IllegalStateException(
                "Configuração postgres inválida: 'data-dir' é obrigatório para executar o failover.");
        }
        return cfg;
    }

    /**
     * Monta a URL de conexão JDBC para o PostgreSQL local.
     * O timeout de login (loginTimeout) é definido como 5 segundos para evitar
     * que a verificação de saúde bloqueie por tempo indeterminado.
     */
    public String buildJdbcUrl() {
        return String.format(
            "jdbc:postgresql://%s:%d/%s?loginTimeout=5&connectTimeout=5&socketTimeout=5",
            host, port, database
        );
    }

    private static long toLong(Object value) {
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Long) return (Long) value;
        throw new IllegalStateException("Valor numérico esperado, recebido: " + value);
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabase() { return database; }
    public String getUser() { return user; }
    public String getPassword() { return password; }
    public String getDataDir() { return dataDir; }
    public long getHealthCheckIntervalMs() { return healthCheckIntervalMs; }
    public int getHealthCheckFailureThreshold() { return healthCheckFailureThreshold; }
}
