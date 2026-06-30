package com.tcc.raft.grpc;

import com.tcc.raft.config.NodeConfig;
import com.tcc.raft.core.RaftNode;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Servidor gRPC do nó Raft.
 *
 * <p>Expõe o {@link RaftServiceImpl} e o {@link ClusterManagementServiceImpl}
 * na porta configurada em {@code node.yaml}. Todos os pares do cluster
 * se conectam a este servidor para enviar RPCs Raft.
 */
public class RaftGrpcServer {

    private static final Logger log = LoggerFactory.getLogger(RaftGrpcServer.class);

    private final Server server;
    private final int port;

    public RaftGrpcServer(NodeConfig config, RaftNode raftNode) {
        this.port = config.getGrpcPort();
        this.server = ServerBuilder.forPort(port)
            .addService(new RaftServiceImpl(raftNode))
            .addService(new ClusterManagementServiceImpl(raftNode))
            // Limites de tamanho de mensagem (entradas de log podem crescer)
            .maxInboundMessageSize(16 * 1024 * 1024) // 16 MB
            .build();
    }

    /**
     * Inicia o servidor gRPC e começa a aceitar conexões.
     *
     * @throws IOException Se a porta já estiver em uso ou não puder ser aberta.
     */
    public void start() throws IOException {
        server.start();
        log.info("Servidor gRPC iniciado na porta {}.", port);
    }

    /**
     * Para o servidor de forma limpa, aguardando até 5 segundos para que
     * chamadas em andamento sejam concluídas antes de fechar.
     */
    public void stop() {
        log.info("Parando servidor gRPC...");
        try {
            server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("Servidor gRPC não foi encerrado dentro do timeout. Forçando parada.");
            server.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Bloqueia a thread atual até o servidor ser encerrado. Útil no Main. */
    public void awaitTermination() throws InterruptedException {
        server.awaitTermination();
    }
}
