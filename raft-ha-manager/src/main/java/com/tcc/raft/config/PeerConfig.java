package com.tcc.raft.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuração de um par (peer) conhecido no cluster.
 *
 * <p>
 * Cada entrada na lista 'peers' do node.yaml é mapeada para uma instância
 * desta classe. A lista inclui o próprio nó, o que facilita a verificação de
 * consistência na validação da configuração.
 */
public class PeerConfig {

    private String id;
    private String grpcAddress;
    private boolean arbiter;

    // Construtor padrão necessário para o SnakeYAML
    public PeerConfig() {
    }

    public PeerConfig(String id, String grpcAddress, boolean arbiter) {
        this.id = id;
        this.grpcAddress = grpcAddress;
        this.arbiter = arbiter;
    }

    public static List<PeerConfig> listFromRaw(List<Map<String, Object>> rawList) {
        List<PeerConfig> result = new ArrayList<>();
        for (Map<String, Object> raw : rawList) {
            PeerConfig peer = new PeerConfig();
            peer.id = (String) raw.get("id");
            peer.grpcAddress = (String) raw.get("grpc-address");
            peer.arbiter = Boolean.TRUE.equals(raw.get("arbiter"));

            if (peer.id == null || peer.grpcAddress == null) {
                throw new IllegalStateException(
                        "Peer inválido na configuração: 'id' e 'grpc-address' são obrigatórios.");
            }
            result.add(peer);
        }
        return result;
    }

    // Retorna o host extraído de grpcAddress (parte antes do ':')
    public String getHost() {
        return grpcAddress.split(":")[0];
    }

    // Retorna a porta extraída de grpcAddress (parte depois do ':')
    public int getPort() {
        return Integer.parseInt(grpcAddress.split(":")[1]);
    }

    public String getId() {
        return id;
    }

    public String getGrpcAddress() {
        return grpcAddress;
    }

    public boolean isArbiter() {
        return arbiter;
    }

    @Override
    public String toString() {
        return "PeerConfig{id='" + id + "', address='" + grpcAddress
                + "', arbiter=" + arbiter + "}";
    }
}
