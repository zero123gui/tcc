package com.tcc.raft.postgres;

import com.tcc.raft.config.PostgresConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * Executa o failover do PostgreSQL quando o nó Raft vence uma eleição e
 * o PostgreSQL local precisa ser promovido de STANDBY para PRIMARY.
 *
 * <p>A promoção é realizada via {@code pg_promote()}, função disponível
 * no PostgreSQL 12 ou superior. Se a função falhar, tenta via
 * {@code pg_ctl promote} como alternativa de linha de comando.
 *
 * <p>Após a promoção, outros nós do cluster precisam reconfigurar sua
 * replicação para apontar para o novo PRIMARY. Isso é feito pelo
 * {@link #reconfigureStandby(String, int, String, String)} chamado
 * remotamente pelo líder após confirmar a sua própria promoção.
 */
public class FailoverExecutor {

    private static final Logger log = LoggerFactory.getLogger(FailoverExecutor.class);

    private final PostgresConfig config;

    public FailoverExecutor(PostgresConfig config) {
        this.config = config;
    }

    /**
     * Promove o PostgreSQL local de STANDBY para PRIMARY.
     *
     * <p>Tenta primeiro via SQL ({@code SELECT pg_promote()}), que é mais limpo
     * e não requer privilégios de sistema operacional além do usuário do banco.
     * Se falhar, tenta via {@code pg_ctl promote} como fallback.
     *
     * @throws FailoverException Se nenhum dos métodos de promoção tiver sucesso.
     */
    public void promote() throws FailoverException {
        log.info("Iniciando promoção do PostgreSQL local para PRIMARY.");

        // Tentativa 1: pg_promote() via JDBC (preferido, PostgreSQL 12+)
        try {
            promoteViaSql();
            log.info("PostgreSQL promovido com sucesso via pg_promote().");
            waitForPromotion();
            return;
        } catch (Exception e) {
            log.warn("Promoção via SQL falhou: {}. Tentando via pg_ctl.", e.getMessage());
        }

        // Tentativa 2: pg_ctl promote via linha de comando
        try {
            promoteViaPgCtl();
            log.info("PostgreSQL promovido com sucesso via pg_ctl promote.");
            waitForPromotion();
        } catch (Exception e) {
            throw new FailoverException("Falha em ambos os métodos de promoção do PostgreSQL.", e);
        }
    }

    /**
     * Reconfigura este nó como STANDBY do novo PRIMARY.
     *
     * <p>Usado quando este nó (que estava como standby do antigo primary) precisa
     * reconectar como standby do novo primary após um failover.
     *
     * <p>Usa {@code ALTER SYSTEM SET primary_conninfo} seguido de
     * {@code SELECT pg_reload_conf()} para aplicar a mudança sem reiniciar.
     * Em versões antigas do PostgreSQL, pode ser necessário reiniciar o serviço.
     *
     * @param newPrimaryHost Host do novo PRIMARY.
     * @param newPrimaryPort Porta do PostgreSQL no novo PRIMARY.
     * @param replicationUser Usuário de replicação.
     * @param replicationPassword Senha do usuário de replicação.
     */
    public void reconfigureStandby(String newPrimaryHost, int newPrimaryPort,
                                    String replicationUser, String replicationPassword)
            throws FailoverException {
        log.info("Reconfigurando standby para replicar do novo PRIMARY em {}:{}.",
            newPrimaryHost, newPrimaryPort);

        String primaryConninfo = String.format(
            "host=%s port=%d user=%s password=%s application_name=%s",
            newPrimaryHost, newPrimaryPort, replicationUser, replicationPassword,
            "raft-ha-standby"
        );

        try (Connection conn = DriverManager.getConnection(
                config.buildJdbcUrl(), config.getUser(), config.getPassword())) {

            // Atualiza primary_conninfo sem reiniciar o PostgreSQL
            try (PreparedStatement stmt = conn.prepareStatement(
                    "ALTER SYSTEM SET primary_conninfo = ?")) {
                stmt.setString(1, primaryConninfo);
                stmt.execute();
            }

            // Recarrega a configuração para aplicar a mudança
            try (PreparedStatement stmt = conn.prepareStatement("SELECT pg_reload_conf()");
                 ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) {
                    log.info("Configuração do standby recarregada com sucesso.");
                } else {
                    log.warn("pg_reload_conf() não confirmou o recarregamento.");
                }
            }
        } catch (SQLException e) {
            throw new FailoverException("Erro ao reconfigurar standby.", e);
        }
    }

    // =========================================================================
    // Implementações de promoção
    // =========================================================================

    private void promoteViaSql() throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                config.buildJdbcUrl(), config.getUser(), config.getPassword());
             PreparedStatement stmt = conn.prepareStatement("SELECT pg_promote(wait => true, wait_seconds => 60)");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next() && !rs.getBoolean(1)) {
                throw new SQLException("pg_promote() retornou false.");
            }
        }
    }

    private void promoteViaPgCtl() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "pg_ctl", "promote", "-D", config.getDataDir()
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Lê a saída do processo para logging
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            reader.lines().forEach(line -> log.debug("pg_ctl: {}", line));
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("pg_ctl promote não terminou dentro de 30 segundos.");
        }
        if (process.exitValue() != 0) {
            throw new IOException("pg_ctl promote falhou com código de saída: " + process.exitValue());
        }
    }

    /**
     * Aguarda até 60 segundos para que o PostgreSQL saia do modo de recovery
     * e comece a aceitar escritas como PRIMARY.
     *
     * @throws FailoverException Se o PostgreSQL não promover dentro do timeout.
     */
    private void waitForPromotion() throws FailoverException {
        log.info("Aguardando PostgreSQL confirmar promoção para PRIMARY...");
        int maxAttempts = 60;
        for (int i = 0; i < maxAttempts; i++) {
            try {
                Thread.sleep(1000);
                try (Connection conn = DriverManager.getConnection(
                        config.buildJdbcUrl(), config.getUser(), config.getPassword());
                     PreparedStatement stmt = conn.prepareStatement("SELECT pg_is_in_recovery()");
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next() && !rs.getBoolean(1)) {
                        log.info("PostgreSQL confirmou papel de PRIMARY após {} segundos.", i + 1);
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FailoverException("Interrompido durante aguardo de promoção.", e);
            } catch (SQLException e) {
                log.debug("Aguardando PostgreSQL ficar disponível... tentativa {}/{}.", i + 1, maxAttempts);
            }
        }
        throw new FailoverException(
            "PostgreSQL não confirmou promoção para PRIMARY em " + maxAttempts + " segundos.");
    }

    // =========================================================================
    // Exceção específica de failover
    // =========================================================================

    public static class FailoverException extends Exception {
        public FailoverException(String message, Throwable cause) {
            super(message, cause);
        }
        public FailoverException(String message) {
            super(message);
        }
    }
}
