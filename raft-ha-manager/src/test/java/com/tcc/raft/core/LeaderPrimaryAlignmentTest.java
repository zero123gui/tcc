package com.tcc.raft.core;

import com.tcc.raft.postgres.PostgresHealthChecker;
import org.junit.jupiter.api.Test;

import static com.tcc.raft.core.ClusterHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Item 2 do plano de aperfeiçoamento: o líder do Raft deve ser o nó do PRIMARY do
 * PostgreSQL também na primeira eleição (cluster religado).
 *
 * <p>Antes, quem vencesse a primeira eleição virava líder sem promover nada. Se fosse o
 * standby, o primary virava seguidor, se via como "Rogue Primary" e se clonava do
 * líder: copiava um standby e o cluster ficava sem PRIMARY (incidente de 15/10).
 */
class LeaderPrimaryAlignmentTest {

    private static void assertNoViolations(ClusterHarness c) {
        assertTrue(c.violations().isEmpty(), "invariantes do Raft violadas: " + c.violations());
    }

    private static void assertPgUntouched(ClusterHarness c) {
        assertTrue(c.failover("node-1").fences.isEmpty(), "o primary não pode sofrer fencing");
        assertTrue(c.failover("node-1").promotions.isEmpty(), "nenhuma promoção é necessária");
        assertTrue(c.failover("node-2").promotions.isEmpty(), "o standby não pode ser promovido");
        assertEquals(PostgresHealthChecker.Status.PRIMARY, c.health("node-1").getCurrentStatus());
        assertEquals(PostgresHealthChecker.Status.STANDBY, c.health("node-2").getCurrentStatus());
    }

    @Test
    void a1_standbyNaoSeCandidataQuandoOPrimaryResponde() throws Exception {
        // O node-2 sobe antes, então o temporizador dele quase sempre dispara primeiro.
        // Várias rodadas porque o sorteio do timeout muda a ordem.
        for (int round = 0; round < 5; round++) {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.start("node-2");
                c.start("node-3");
                Thread.sleep(ELECTION_MIN_MS / 2);
                c.start("node-1");

                awaitTrue(() -> c.isLeader("node-1"), 5_000, "rodada " + round + ": node-1 deveria ser o líder");
                Thread.sleep(ELECTION_MAX_MS * 2);

                assertTrue(c.isLeader("node-1"), "rodada " + round + ": node-1 deveria continuar líder");
                assertFalse(c.everLeader().contains("node-2"), "rodada " + round + ": o standby não pode liderar");
                assertPgUntouched(c);
                assertNoViolations(c);
            }
        }
    }

    @Test
    void a2_liderDaPrimeiraEleicaoEntregaALiderancaAoPrimary() throws Exception {
        try (ClusterHarness c = new ClusterHarness(true, true)) {
            // O agente do primary está fora: o standby vence a primeira eleição sem promover
            c.start("node-2");
            c.start("node-3");
            awaitTrue(() -> c.isLeader("node-2"), 5_000, "node-2 deveria vencer a primeira eleição");

            c.start("node-1");
            awaitTrue(() -> c.isLeader("node-1"), 5_000, "a liderança deveria passar para o node-1 (primary)");
            Thread.sleep(ELECTION_MAX_MS * 2);

            assertTrue(c.isLeader("node-1"), "node-1 deveria continuar líder");
            assertEquals("node-1", c.leaderSeenBy("node-2"));
            assertPgUntouched(c);
            assertNoViolations(c);
        }
    }

    @Test
    void a3_roguePrimaryEsperaOLiderTerminarAPromocaoAntesDoFencing() throws Exception {
        try (ClusterHarness c = new ClusterHarness(true, true)) {
            c.startCluster();
            c.failover("node-2").promotionDelayMs = 1_000;

            // Para só o agente do líder: o PostgreSQL do node-1 continua PRIMARY
            c.stop("node-1");
            awaitTrue(() -> c.isLeader("node-2"), 3_000, "node-2 deveria assumir");
            // Volta enquanto o node-2 ainda está promovendo
            c.start("node-1");
            awaitTrue(() -> !c.failover("node-1").fences.isEmpty(), 5_000,
                "o antigo primary deveria passar pelo fencing depois da promoção");

            long promoted = c.failover("node-2").promotedAt.get(0);
            long fenced = c.failover("node-1").fencedAt.get(0);
            assertTrue(fenced >= promoted,
                "o fencing (clone do líder) só pode começar depois que o líder é PRIMARY");
            assertTrue(c.isLeader("node-2"), "o líder do failover não entrega a liderança");
            assertNoViolations(c);
        }
    }
}
