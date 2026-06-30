package com.tcc.raft.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Modelo de configuração de um nó do cluster raft-ha-manager.
 *
 * <p>Lido a partir do arquivo node.yaml na inicialização. Todos os campos
 * são validados no momento da carga para garantir que erros de configuração
 * sejam detectados antes de qualquer comunicação de rede ocorrer.
 */
public class NodeConfig {

    private String nodeId;
    private String grpcAddress;
    private boolean arbiter;
    private PostgresConfig postgres;
    private RaftConfig raft;
    private StorageConfig storage;
    private List<PeerConfig> peers;

    // Construtor padrão necessário para o SnakeYAML
    public NodeConfig() {}

    /**
     * Lê e valida o arquivo de configuração YAML.
     *
     * @param path Caminho absoluto ou relativo para o arquivo node.yaml.
     * @return Instância de NodeConfig com os valores do arquivo.
     * @throws IOException          Se o arquivo não puder ser lido.
     * @throws IllegalStateException Se algum campo obrigatório estiver ausente.
     */
    public static NodeConfig load(String path) throws IOException {
        Yaml yaml = new Yaml();
        try (InputStream in = new FileInputStream(path)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = yaml.load(in);
            NodeConfig config = parseFromMap(raw);
            config.validate();
            return config;
        }
    }

    @SuppressWarnings("unchecked")
    private static NodeConfig parseFromMap(Map<String, Object> raw) {
        NodeConfig config = new NodeConfig();
        config.nodeId = (String) raw.get("node-id");
        config.grpcAddress = (String) raw.get("grpc-address");
        config.arbiter = Boolean.TRUE.equals(raw.get("arbiter"));

        if (raw.containsKey("postgres")) {
            config.postgres = PostgresConfig.fromMap((Map<String, Object>) raw.get("postgres"));
        }

        if (raw.containsKey("raft")) {
            config.raft = RaftConfig.fromMap((Map<String, Object>) raw.get("raft"));
        } else {
            config.raft = RaftConfig.withDefaults();
        }

        if (raw.containsKey("storage")) {
            config.storage = StorageConfig.fromMap((Map<String, Object>) raw.get("storage"));
        } else {
            config.storage = StorageConfig.withDefaults();
        }

        if (raw.containsKey("peers")) {
            config.peers = PeerConfig.listFromRaw((List<Map<String, Object>>) raw.get("peers"));
        }

        return config;
    }

    private void validate() {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalStateException("Configuração inválida: 'node-id' é obrigatório.");
        }
        if (grpcAddress == null || !grpcAddress.contains(":")) {
            throw new IllegalStateException(
                "Configuração inválida: 'grpc-address' deve estar no formato host:porta.");
        }
        if (!arbiter && postgres == null) {
            throw new IllegalStateException(
                "Configuração inválida: nós não-árbitros precisam da seção 'postgres'.");
        }
        if (peers == null || peers.isEmpty()) {
            throw new IllegalStateException("Configuração inválida: 'peers' não pode ser vazio.");
        }
        boolean selfFound = peers.stream().anyMatch(p -> p.getId().equals(nodeId));
        if (!selfFound) {
            throw new IllegalStateException(
                "Configuração inválida: este nó (" + nodeId + ") não aparece na lista de peers.");
        }
    }

    // =========================================================================
    // Getters
    // =========================================================================

    public String getNodeId() { return nodeId; }
    public String getGrpcAddress() { return grpcAddress; }
    public boolean isArbiter() { return arbiter; }
    public PostgresConfig getPostgres() { return postgres; }
    public RaftConfig getRaft() { return raft; }
    public StorageConfig getStorage() { return storage; }
    public List<PeerConfig> getPeers() { return peers; }

    // Retorna o host extraído de grpcAddress (parte antes do ':')
    public String getGrpcHost() {
        return grpcAddress.split(":")[0];
    }

    // Retorna a porta extraída de grpcAddress (parte depois do ':')
    public int getGrpcPort() {
        return Integer.parseInt(grpcAddress.split(":")[1]);
    }

    @Override
    public String toString() {
        return "NodeConfig{nodeId='" + nodeId + "', grpcAddress='" + grpcAddress
            + "', arbiter=" + arbiter + ", peers=" + peers.size() + "}";
    }
}
