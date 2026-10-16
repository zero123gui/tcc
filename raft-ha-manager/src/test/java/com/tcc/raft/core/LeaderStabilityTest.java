package com.tcc.raft.core;

import com.tcc.raft.grpc.proto.RaftProto;
import com.tcc.raft.postgres.PostgresHealthChecker;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static com.tcc.raft.core.ClusterHarness.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Item 1 do plano de aperfeiçoamento: um nó que volta ao cluster não pode derrubar
 * um líder saudável, e um líder isolado deve renunciar.
 *
 * <p>Os cenários principais rodam com a correção ligada (pre-vote e check-quorum) e
 * desligada. Sem a correção, os testes afirmam que o problema acontece: isso prova
 * que a montagem reproduz o defeito observado nas VMs.
 */
class LeaderStabilityTest {

    /** Várias janelas de eleição do nó que volta (timeout sorteado entre 300 e 500 ms). */
    private static final long SEVERAL_ELECTION_WINDOWS_MS = ELECTION_MAX_MS * 4;

    private static void assertNoViolations(ClusterHarness c) {
        assertTrue(c.violations().isEmpty(), "invariantes do Raft violadas: " + c.violations());
    }

    @Nested
    class ComCorrecao {

        @Test
        void t1_noQueVoltaSemReceberHeartbeatNaoDerrubaOLider() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.startCluster();
                long term = c.term("node-1");

                c.stop("node-2");
                Thread.sleep(ELECTION_MAX_MS * 3);
                // Caso das VMs: o node-2 volta, mas o canal do líder até ele ainda está em backoff
                c.block("node-1", "node-2");
                c.start("node-2");
                Thread.sleep(SEVERAL_ELECTION_WINDOWS_MS);
                c.unblock("node-1", "node-2");
                awaitTrue(() -> "node-1".equals(c.leaderSeenBy("node-2")), 2_000,
                    "node-2 deveria voltar a seguir o node-1");

                assertTrue(c.isLeader("node-1"), "node-1 deveria continuar líder");
                assertEquals(term, c.term("node-1"), "o mandato do líder não deveria mudar");
                assertEquals(term, c.term("node-2"), "o mandato do node-2 não deveria inflar");
                assertTrue(c.termsAbove(term).isEmpty(), "não deveria haver líder em mandato maior");
                assertTrue(c.failover("node-1").fences.isEmpty(), "o primary não pode sofrer fencing");
                assertNoViolations(c);
            }
        }

        @Test
        void t2_noIsoladoNaoInflaOMandatoNemDerrubaOLiderAoVoltar() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.startCluster();
                long term = c.term("node-1");

                c.isolate("node-2");
                Thread.sleep(SEVERAL_ELECTION_WINDOWS_MS);
                assertEquals(term, c.term("node-2"), "isolado, o node-2 não deveria subir o mandato");
                c.heal();
                Thread.sleep(SEVERAL_ELECTION_WINDOWS_MS);

                assertTrue(c.isLeader("node-1"));
                assertEquals(term, c.term("node-1"));
                assertTrue(c.termsAbove(term).isEmpty());
                assertNoViolations(c);
            }
        }

        @Test
        void t3_failoverContinuaFuncionandoQuandoOLiderMorre() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.startCluster();

                c.stop("node-1");
                awaitTrue(() -> c.isLeader("node-2"), 3_000, "node-2 deveria assumir");
                awaitTrue(() -> c.failover("node-2").promotions.size() == 1, 1_000,
                    "o PostgreSQL do node-2 deveria ser promovido uma vez");
                assertEquals(PostgresHealthChecker.Status.PRIMARY, c.health("node-2").getCurrentStatus());
                assertNoViolations(c);
            }
        }

        @Test
        void t4_liderIsoladoRenunciaEOutroLadoAssume() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.startCluster();

                c.isolate("node-1");
                // check-quorum: renuncia em ~election-timeout-min (300 ms) + 1 heartbeat
                awaitTrue(() -> !c.isLeader("node-1"), ELECTION_MIN_MS + 4 * HEARTBEAT_MS + 300,
                    "o líder isolado deveria renunciar");
                awaitTrue(() -> c.isLeader("node-2"), 3_000, "o lado majoritário deveria eleger o node-2");
                awaitTrue(() -> c.failover("node-2").promotions.size() == 1, 1_000,
                    "o node-2 deveria promover o PostgreSQL");

                c.heal();
                awaitTrue(() -> "node-2".equals(c.leaderSeenBy("node-1")), 2_000,
                    "ao reconectar, o node-1 deveria seguir o node-2");
                // O antigo primary é reconciliado pelo self-healing (Rogue Primary)
                awaitTrue(() -> !c.failover("node-1").fences.isEmpty(), 2_000,
                    "o antigo primary deveria passar pelo fencing/self-healing");
                assertTrue(c.isLeader("node-2"));
                assertNoViolations(c);
            }
        }

        @Test
        void t5_seguidorReiniciadoVoltaSemProvocarEleicao() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.startCluster();
                long term = c.term("node-1");

                c.stop("node-2");
                c.start("node-2");
                Thread.sleep(SEVERAL_ELECTION_WINDOWS_MS);

                assertTrue(c.isLeader("node-1"));
                assertEquals(term, c.term("node-1"));
                assertTrue(c.termsAbove(term).isEmpty());
                assertNoViolations(c);
            }
        }

        @Test
        void t6_arbitroReiniciadoVoltaSemEleicaoENuncaSeCandidata() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.startCluster();
                long term = c.term("node-1");

                c.stop("node-3");
                Thread.sleep(ELECTION_MAX_MS * 2);
                c.start("node-3");
                Thread.sleep(SEVERAL_ELECTION_WINDOWS_MS);

                assertTrue(c.isLeader("node-1"));
                assertEquals(term, c.term("node-1"));
                assertFalse(c.everCandidate().contains("node-3"), "árbitro nunca se candidata");
                assertNoViolations(c);
            }
        }

        @Test
        void t7_candidaturaComMandatoMaiorEhIgnoradaEnquantoOLiderEstaAtivo() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, true)) {
                c.startCluster();
                long term = c.term("node-2");

                RaftProto.RequestVoteResponse r = c.node("node-2").handleRequestVote(voteRequest(term + 5));

                assertFalse(r.getVoteGranted());
                assertEquals(term, c.term("node-2"), "o mandato não deveria ser atualizado");
                assertTrue(c.isLeader("node-1"));
            }
        }
    }

    /** Mesmos cenários sem a correção: os testes afirmam que o problema acontece. */
    @Nested
    class SemCorrecaoReproduzOProblema {

        @Test
        void t1_noQueVoltaDerrubaOLider() throws Exception {
            try (ClusterHarness c = new ClusterHarness(false, false)) {
                c.startCluster();
                long term = c.term("node-1");

                c.stop("node-2");
                Thread.sleep(ELECTION_MAX_MS * 3);
                c.block("node-1", "node-2");
                c.start("node-2");
                Thread.sleep(SEVERAL_ELECTION_WINDOWS_MS);

                assertTrue(c.term("node-1") > term,
                    "sem pré-votação, o node-2 deveria forçar um mandato novo no líder");
                assertNoViolations(c);
            }
        }

        @Test
        void t2_noIsoladoInflaOMandato() throws Exception {
            try (ClusterHarness c = new ClusterHarness(false, false)) {
                c.startCluster();
                long term = c.term("node-1");

                c.isolate("node-2");
                Thread.sleep(SEVERAL_ELECTION_WINDOWS_MS);

                assertTrue(c.term("node-2") > term + 1,
                    "sem pré-votação, o node-2 isolado deveria subir o mandato a cada eleição");
                assertNoViolations(c);
            }
        }

        @Test
        void t4_liderIsoladoContinuaSeDeclarandoLider() throws Exception {
            try (ClusterHarness c = new ClusterHarness(true, false)) {
                c.startCluster();

                c.isolate("node-1");
                awaitTrue(() -> c.isLeader("node-2"), 3_000, "o lado majoritário deveria eleger o node-2");
                Thread.sleep(ELECTION_MAX_MS * 2);

                assertTrue(c.isLeader("node-1"),
                    "sem check-quorum, o líder isolado deveria continuar se declarando líder");
                assertNoViolations(c);
            }
        }

        @Test
        void t7_candidaturaComMandatoMaiorAtualizaOMandato() throws Exception {
            try (ClusterHarness c = new ClusterHarness(false, false)) {
                c.startCluster();
                long term = c.term("node-2");

                c.node("node-2").handleRequestVote(voteRequest(term + 5));

                assertEquals(term + 5, c.term("node-2"));
            }
        }
    }

    private static RaftProto.RequestVoteRequest voteRequest(long term) {
        return RaftProto.RequestVoteRequest.newBuilder()
            .setTerm(term)
            .setCandidateId("node-3")
            .setLastLogIndex(1_000)
            .setLastLogTerm(term)
            .build();
    }
}
