package com.tcc.raft;

import com.tcc.raft.config.NodeConfig;
import com.tcc.raft.config.PeerConfig;
import com.tcc.raft.core.RaftLog;
import com.tcc.raft.core.RaftNode;
import com.tcc.raft.grpc.RaftGrpcClient;
import com.tcc.raft.grpc.RaftGrpcServer;
import com.tcc.raft.postgres.FailoverExecutor;
import com.tcc.raft.postgres.PostgresHealthChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Ponto de entrada do raft-ha-manager.
 *
 * <p>Sequência de inicialização:
 * <ol>
 *   <li>Lê e valida o arquivo de configuração YAML.
 *   <li>Inicializa o log Raft (carrega do disco se existir).
 *   <li>Cria os clientes gRPC para cada par do cluster.
 *   <li>Inicializa o monitoramento do PostgreSQL local (se não for árbitro).
 *   <li>Cria e inicia o {@link RaftNode} (máquina de estados principal).
 *   <li>Inicia o servidor gRPC para receber chamadas dos pares.
 *   <li>Registra o shutdown hook para encerramento limpo.
 * </ol>
 *
 * <p>Uso:
 * <pre>
 *   java -jar raft-ha-manager.jar /etc/raft-ha-manager/node.yaml
 * </pre>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Uso: raft-ha-manager <caminho-para-node.yaml>");
            System.err.println("Exemplo: java -jar raft-ha-manager.jar /etc/raft-ha-manager/node.yaml");
            System.exit(1);
        }

        // Define o ID do nó como propriedade do sistema para o Logback
        // (usado no padrão de log: [${NODE_ID}])
        // Isso deve ser feito ANTES do primeiro uso do logger
        String configPath = args[0];

        log.info("==========================================================");
        log.info("  raft-ha-manager v1.0 - TCC Ciência da Computação");
        log.info("  Carregando configuração: {}", configPath);
        log.info("==========================================================");

        // =====================================================================
        // 1. Configuração
        // =====================================================================
        NodeConfig config = NodeConfig.load(configPath);
        System.setProperty("node.id", config.getNodeId());
        log.info("Configuração carregada: {}", config);

        // =====================================================================
        // 2. Log Raft persistido em disco
        // =====================================================================
        RaftLog raftLog = new RaftLog(config.getStorage().getLogDir());

        // =====================================================================
        // 3. Clientes gRPC para os pares (todos exceto este nó)
        // =====================================================================
        Map<String, RaftGrpcClient> peerClients = new HashMap<>();
        for (PeerConfig peer : config.getPeers()) {
            if (!peer.getId().equals(config.getNodeId())) {
                RaftGrpcClient client = new RaftGrpcClient(
                    peer.getId(),
                    peer.getHost(),
                    peer.getPort(),
                    config.getRaft().getRpcTimeoutMs()
                );
                peerClients.put(peer.getId(), client);
                log.info("Cliente gRPC criado para o par: {}.", peer.getId());
            }
        }

        // =====================================================================
        // 4. Monitoramento e failover do PostgreSQL (apenas nós não-árbitros)
        // =====================================================================
        PostgresHealthChecker healthChecker;
        FailoverExecutor failoverExecutor;

        if (config.isArbiter()) {
            log.info("Este nó é um ÁRBITRO. Monitoramento do PostgreSQL desativado.");
            // Árbitros precisam de implementações "no-op" para não quebrar a injeção
            healthChecker = new PostgresHealthChecker(null) {
                @Override public void start() {}
                @Override public void stop() {}
                @Override public Status getCurrentStatus() { return Status.UNKNOWN; }
            };
            failoverExecutor = new FailoverExecutor(null);
        } else {
            healthChecker = new PostgresHealthChecker(config.getPostgres());
            failoverExecutor = new FailoverExecutor(config.getPostgres());
        }

        // =====================================================================
        // 5. Nó Raft (máquina de estados principal)
        // =====================================================================
        RaftNode raftNode = new RaftNode(config, raftLog, peerClients, healthChecker, failoverExecutor);
        raftNode.start();

        // =====================================================================
        // 6. Servidor gRPC (aceita chamadas dos pares)
        // =====================================================================
        RaftGrpcServer grpcServer = new RaftGrpcServer(config, raftNode);
        grpcServer.start();

        log.info("Nó {} pronto. Aguardando eventos do cluster.", config.getNodeId());

        // =====================================================================
        // 7. Shutdown hook para encerramento limpo
        // =====================================================================
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Sinal de encerramento recebido. Iniciando parada do nó {}...",
                config.getNodeId());
            grpcServer.stop();
            raftNode.stop();
            for (RaftGrpcClient client : peerClients.values()) {
                client.shutdown();
            }
            log.info("Nó {} encerrado com sucesso.", config.getNodeId());
        }, "shutdown-hook"));

        // Bloqueia a thread principal até o servidor ser encerrado (ex: SIGTERM)
        grpcServer.awaitTermination();
    }
}
