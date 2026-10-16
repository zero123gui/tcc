package com.tcc.raft.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Os arquivos de configuração que vão para as VMs precisam carregar e ativar as proteções. */
class NodeConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "scripts/config/node-1.yaml",
        "scripts/config/node-2.yaml",
        "scripts/config/node-3.yaml",
        "src/main/resources/node.yaml.example",
    })
    void arquivosDoProjetoCarregamComPreVoteECheckQuorumLigados(String path) throws Exception {
        NodeConfig config = NodeConfig.load(path);
        assertTrue(config.getRaft().isPreVote(), path + ": pre-vote deveria estar ligado");
        assertTrue(config.getRaft().isCheckQuorum(), path + ": check-quorum deveria estar ligado");
    }

    @Test
    void protecoesLigadasPorPadraoQuandoAsChavesNaoExistem() {
        RaftConfig raft = RaftConfig.fromMap(Map.of());
        assertTrue(raft.isPreVote());
        assertTrue(raft.isCheckQuorum());
    }

    @Test
    void protecoesPodemSerDesligadasParaExperimentos() {
        RaftConfig raft = RaftConfig.fromMap(Map.of("pre-vote", false, "check-quorum", false));
        assertFalse(raft.isPreVote());
        assertFalse(raft.isCheckQuorum());
    }

    @Test
    void valorNaoBooleanoEhRecusado() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("pre-vote", "sim");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> RaftConfig.fromMap(raw));
        assertTrue(e.getMessage().contains("pre-vote"));
    }

    @Test
    void arbitroSemSecaoPostgresEhValido() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("node-id", "node-3");
        raw.put("grpc-address", "192.168.56.103:9090");
        raw.put("arbiter", true);
        raw.put("peers", List.of(Map.of("id", "node-3", "grpc-address", "192.168.56.103:9090")));
        assertTrue(NodeConfig.fromMap(raw).isArbiter());
    }
}
