package com.tcc.raft.postgres;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduz o problema observado nas VMs: o RaftNode dispara fenceAndRecover a cada
 * heartbeat enquanto o PostgreSQL ainda reporta PRIMARY, e as execuções concorrentes
 * apagavam o diretório de dados umas das outras no meio do pg_basebackup.
 */
class FailoverExecutorTest {

    /** Executor cuja recuperação só termina quando o teste libera, contando as execuções. */
    private static class RecordingExecutor extends FailoverExecutor {
        final AtomicInteger runs = new AtomicInteger();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final List<String> leaders = new ArrayList<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release;

        RecordingExecutor(long cooldownMs, AtomicLong clock, CountDownLatch release) {
            super(null, cooldownMs, clock::get);
            this.release = release;
        }

        @Override
        void runFenceAndRecover(String leaderIp) {
            runs.incrementAndGet();
            synchronized (leaders) {
                leaders.add(leaderIp);
            }
            int now = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
            }
        }
    }

    @Test
    void chamadasSimultaneasExecutamUmaUnicaRecuperacao() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        RecordingExecutor exec = new RecordingExecutor(30_000, new AtomicLong(1_000), release);

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                go.await();
                exec.fenceAndRecover("192.168.56.102");
                return null;
            });
        }
        go.countDown();
        assertTrue(exec.started.await(2, TimeUnit.SECONDS), "a recuperação deveria ter começado");
        // As demais chamadas chegam enquanto a primeira ainda roda
        Thread.sleep(150);
        release.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(1, exec.runs.get(), "só uma recuperação deve executar");
        assertEquals(1, exec.maxConcurrent.get());
    }

    @Test
    void chamadaLogoAposTerminarEhIgnoradaPeloPeriodoDeEspera() {
        AtomicLong clock = new AtomicLong(10_000);
        CountDownLatch release = new CountDownLatch(0); // termina na hora
        RecordingExecutor exec = new RecordingExecutor(30_000, clock, release);

        exec.fenceAndRecover("192.168.56.102");
        assertEquals(1, exec.runs.get());

        // O status do PostgreSQL ainda pode estar desatualizado logo depois do clone
        clock.addAndGet(5_000);
        exec.fenceAndRecover("192.168.56.102");
        assertEquals(1, exec.runs.get(), "dentro do período de espera não deve recuperar de novo");

        // Passado o período, uma nova detecção volta a recuperar
        clock.addAndGet(30_000);
        exec.fenceAndRecover("192.168.56.102");
        assertEquals(2, exec.runs.get());
    }

    @Test
    void falhaNaRecuperacaoLiberaATravaParaNovaTentativa() {
        AtomicLong clock = new AtomicLong(0);
        AtomicInteger runs = new AtomicInteger();
        FailoverExecutor exec = new FailoverExecutor(null, 1_000, clock::get) {
            @Override
            void runFenceAndRecover(String leaderIp) {
                runs.incrementAndGet();
                throw new IllegalStateException("script falhou");
            }
        };

        try {
            exec.fenceAndRecover("192.168.56.102");
        } catch (IllegalStateException expected) {
            // a exceção sobe, mas a trava precisa ser liberada
        }
        clock.addAndGet(5_000);
        try {
            exec.fenceAndRecover("192.168.56.102");
        } catch (IllegalStateException expected) {
            // idem
        }
        assertEquals(2, runs.get(), "após uma falha, outra tentativa deve ser possível");
    }
}
