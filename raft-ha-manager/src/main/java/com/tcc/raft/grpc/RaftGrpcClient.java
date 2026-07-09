package com.tcc.raft.grpc;

import com.tcc.raft.grpc.proto.RaftProto;
import com.tcc.raft.grpc.proto.RaftServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Cliente gRPC para comunicação com um único par do cluster.
 *
 * <p>Cada par conhecido recebe sua própria instância deste cliente. O canal
 * gRPC é mantido aberto e reconectado automaticamente pelo Netty em caso de
 * falha de rede.
 *
 * <p>Todas as chamadas usam um deadline (timeout) configurável para evitar
 * que uma chamada bloqueie indefinidamente quando o par não responde. Isso é
 * crítico para o progresso do algoritmo Raft: um líder não pode esperar para
 * sempre pela resposta de um seguidor que caiu.
 */
public class RaftGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(RaftGrpcClient.class);

    private final String peerId;
    private final ManagedChannel channel;
    private final RaftServiceGrpc.RaftServiceBlockingStub stub;
    private final long rpcTimeoutMs;

    /**
     * @param peerId      Identificador do nó par (para logging).
     * @param host        Host do par.
     * @param port        Porta gRPC do par.
     * @param rpcTimeoutMs Timeout em milissegundos para cada chamada RPC.
     */
    public RaftGrpcClient(String peerId, String host, int port, long rpcTimeoutMs) {
        this.peerId = peerId;
        this.rpcTimeoutMs = rpcTimeoutMs;

        // usePlaintext(): sem TLS. Em um ambiente de produção real, seria necessário
        // configurar TLS mútuo (mTLS) para autenticar os nós entre si.
        // Para o ambiente de simulação do TCC, plaintext é aceitável.
        this.channel = ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(10, TimeUnit.SECONDS)   // Detecta conexões mortas
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)            // Mantém o canal vivo mesmo sem chamadas
            .build();

        this.stub = RaftServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Envia uma requisição de voto para o par e aguarda a resposta.
     *
     * @param request A requisição RequestVote a ser enviada.
     * @return A resposta do par.
     * @throws StatusRuntimeException Se o par não responder dentro do timeout
     *                                ou se houver erro de rede.
     */
    public RaftProto.RequestVoteResponse requestVote(RaftProto.RequestVoteRequest request)
            throws StatusRuntimeException {
        return stub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS)
                   .requestVote(request);
    }

    /**
     * Envia uma requisição AppendEntries para o par e aguarda a resposta.
     *
     * @param request A requisição AppendEntries (heartbeat ou replicação).
     * @return A resposta do par.
     * @throws StatusRuntimeException Se o par não responder dentro do timeout.
     */
    public RaftProto.AppendEntriesResponse appendEntries(RaftProto.AppendEntriesRequest request)
            throws StatusRuntimeException {
        return stub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS)
                   .appendEntries(request);
    }

    /** Fecha o canal gRPC. Deve ser chamado ao desligar o nó. */
    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.debug("Canal gRPC para {} fechado.", peerId);
    }

    public String getPeerId() { return peerId; }
}
