package com.tcc.raft.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Temporizador de eleição do Raft com jitter aleatório.
 *
 * <p>Quando o temporizador expira sem que um heartbeat válido tenha sido recebido,
 * ele invoca o callback de eleição, que inicia o processo de eleição no
 * {@link RaftNode}.
 *
 * <p>O jitter, ou seja, a variação aleatória no timeout, é essencial para que
 * dois seguidores que perderam o líder ao mesmo tempo não iniciem eleições
 * exatamente no mesmo instante. Sem jitter, ambos se tornariam candidatos,
 * votariam em si mesmos e nenhum conseguiria maioria, resultando em eleições
 * consecutivas que nunca terminam (split vote).
 *
 * <p>Esta classe é thread-safe: {@code reset()} e {@code stop()} podem ser
 * chamados de qualquer thread sem condições de corrida.
 */
public class ElectionTimer {

    private static final Logger log = LoggerFactory.getLogger(ElectionTimer.class);

    private final long minTimeoutMs;
    private final long maxTimeoutMs;
    private final Runnable onTimeout;
    private final Random random;

    // Executor de thread única para o temporizador
    private final ScheduledExecutorService scheduler;

    // Referência à tarefa agendada atual. Nula se o timer estiver parado.
    private volatile ScheduledFuture<?> currentTask;

    /**
     * @param minTimeoutMs Tempo mínimo de espera antes de acionar a eleição, em ms.
     * @param maxTimeoutMs Tempo máximo de espera antes de acionar a eleição, em ms.
     * @param onTimeout    Callback invocado quando o temporizador expira.
     *                     Deve ser thread-safe, pois é chamado pela thread do scheduler.
     */
    public ElectionTimer(long minTimeoutMs, long maxTimeoutMs, Runnable onTimeout) {
        this.minTimeoutMs = minTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
        this.onTimeout = onTimeout;
        this.random = new Random();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "election-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Reinicia o temporizador com um novo timeout aleatório.
     *
     * <p>Cancela a tarefa atual (se existir) e agenda uma nova com um timeout
     * aleatório dentro do intervalo [minTimeoutMs, maxTimeoutMs].
     *
     * <p>Este método deve ser chamado sempre que um heartbeat válido ou uma
     * resposta RequestVote válida for recebida.
     */
    public synchronized void reset() {
        cancel();
        long timeout = minTimeoutMs + (long) (random.nextDouble() * (maxTimeoutMs - minTimeoutMs));
        log.trace("Temporizador de eleição reiniciado. Timeout: {} ms.", timeout);
        currentTask = scheduler.schedule(this::fireTimeout, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Para o temporizador permanentemente.
     * Deve ser chamado quando o nó se torna líder (líderes não precisam do timer)
     * e no shutdown do sistema.
     */
    public synchronized void stop() {
        cancel();
        log.debug("Temporizador de eleição parado.");
    }

    /**
     * Para o temporizador temporariamente, sem agendar um novo ciclo.
     * Usado quando o nó se torna líder: o líder para o timer e inicia
     * o scheduler de heartbeats.
     */
    public synchronized void pause() {
        cancel();
    }

    private void cancel() {
        if (currentTask != null && !currentTask.isDone()) {
            currentTask.cancel(false); // false: não interrompe se já estiver em execução
        }
        currentTask = null;
    }

    private void fireTimeout() {
        log.debug("Temporizador de eleição expirou. Iniciando eleição.");
        try {
            onTimeout.run();
        } catch (Exception e) {
            log.error("Erro durante o callback de eleição.", e);
        }
    }

    /** Desliga o executor ao encerrar o processo. */
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }
}
