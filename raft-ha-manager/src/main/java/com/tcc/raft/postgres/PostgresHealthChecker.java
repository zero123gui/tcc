package com.tcc.raft.postgres;

import com.tcc.raft.config.PostgresConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Monitora a saúde do PostgreSQL local em intervalos regulares.
 *
 * <p>Executa dois tipos de verificação:
 * <ol>
 *   <li>Conectividade: tenta abrir uma conexão JDBC dentro do timeout configurado.
 *   <li>Papel do nó: executa {@code SELECT pg_is_in_recovery()} para determinar
 *       se o PostgreSQL local é PRIMARY (não está em recovery) ou STANDBY.
 * </ol>
 *
 * <p>O status é atualizado atomicamente para que o {@link com.tcc.raft.core.RaftNode}
 * possa consultá-lo a qualquer momento sem necessidade de sincronização adicional.
 *
 * <p>O limite de falhas consecutivas ({@code healthCheckFailureThreshold}) evita
 * que uma falha transitória de rede dispare um failover desnecessário.
 */
public class PostgresHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(PostgresHealthChecker.class);

    /** Resultado de uma verificação de saúde do PostgreSQL local. */
    public enum Status {
        /** O PostgreSQL está acessível e é o nó primário (pg_is_in_recovery = false). */
        PRIMARY,
        /** O PostgreSQL está acessível e é um standby (pg_is_in_recovery = true). */
        STANDBY,
        /** O PostgreSQL não respondeu dentro do timeout ou retornou um erro. */
        UNHEALTHY,
        /** Verificação ainda não foi executada. */
        UNKNOWN
    }

    public interface StatusListener {
        void onStatusChanged(Status oldStatus, Status newStatus);
    }

    private final PostgresConfig config;
    private final ScheduledExecutorService scheduler;
    private StatusListener listener;

    /** Status atual, atualizado atomicamente após cada verificação. */
    private final AtomicReference<Status> currentStatus = new AtomicReference<>(Status.UNKNOWN);

    /** Contador de falhas consecutivas. Resetado ao primeiro sucesso. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    public PostgresHealthChecker(PostgresConfig config) {
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pg-health-checker");
            t.setDaemon(true);
            return t;
        });
    }

    public void setListener(StatusListener listener) {
        this.listener = listener;
    }

    /** Inicia as verificações periódicas de saúde. */
    public void start() {
        long intervalMs = config.getHealthCheckIntervalMs();
        scheduler.scheduleAtFixedRate(
            this::checkHealth,
            0, intervalMs, TimeUnit.MILLISECONDS
        );
        log.info("Monitoramento do PostgreSQL iniciado (intervalo: {} ms, threshold: {} falhas).",
            intervalMs, config.getHealthCheckFailureThreshold());
    }

    /** Para as verificações. Deve ser chamado no shutdown do nó. */
    public void stop() {
        scheduler.shutdownNow();
    }

    /** Retorna o status mais recente do PostgreSQL local. Thread-safe. */
    public Status getCurrentStatus() {
        return currentStatus.get();
    }

    /** Retorna true se o PostgreSQL local está saudável (PRIMARY ou STANDBY). */
    public boolean isHealthy() {
        Status s = currentStatus.get();
        return s == Status.PRIMARY || s == Status.STANDBY;
    }

    // =========================================================================
    // Execução da verificação
    // =========================================================================

    private void checkHealth() {
        try (Connection conn = DriverManager.getConnection(
                config.buildJdbcUrl(), config.getUser(), config.getPassword());
             PreparedStatement stmt = conn.prepareStatement("SELECT pg_is_in_recovery()");
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                boolean isInRecovery = rs.getBoolean(1);
                Status newStatus = isInRecovery ? Status.STANDBY : Status.PRIMARY;
                Status previousStatus = currentStatus.getAndSet(newStatus);

                consecutiveFailures.set(0);

                if (previousStatus != newStatus) {
                    log.info("Status do PostgreSQL mudou: {} → {}.", previousStatus, newStatus);
                    if (listener != null) {
                        listener.onStatusChanged(previousStatus, newStatus);
                    }
                } else {
                    log.trace("PostgreSQL saudável como {}.", newStatus);
                }
            }
        } catch (SQLException e) {
            int failures = consecutiveFailures.incrementAndGet();
            int threshold = config.getHealthCheckFailureThreshold();

            log.warn("Falha #{} na verificação do PostgreSQL: {}.", failures, e.getMessage());

            if (failures >= threshold) {
                Status previousStatus = currentStatus.getAndSet(Status.UNHEALTHY);
                if (previousStatus != Status.UNHEALTHY) {
                    log.error("PostgreSQL declarado UNHEALTHY após {} falhas consecutivas. "
                        + "O cluster Raft será notificado.", failures);
                    if (listener != null) {
                        listener.onStatusChanged(previousStatus, Status.UNHEALTHY);
                    }
                }
            }
        }
    }
}
