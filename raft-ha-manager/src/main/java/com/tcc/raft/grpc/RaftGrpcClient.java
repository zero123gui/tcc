package com.tcc.raft.grpc;

import com.tcc.raft.core.PeerClient;
import com.tcc.raft.grpc.proto.RaftProto;
import com.tcc.raft.grpc.proto.RaftServiceGrpc;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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
 *
 * <p>Reconexão: depois de várias falhas o gRPC espaça as tentativas de conexão
 * (backoff exponencial de até 2 minutos). Um par que ficou fora do ar por alguns
 * minutos deixaria de receber heartbeats por esse tempo depois de voltar, e
 * acabaria iniciando uma eleição. Por isso o backoff é zerado a cada chamada que
 * encontra o canal em falha: o líder volta a alcançar o par no heartbeat seguinte.
 */
public class RaftGrpcClient implements PeerClient {

    private static final Logger log = LoggerFactory.getLogger(RaftGrpcClient.class);

    private final String peerId;
    private final ManagedChannel channel;
    private final RaftServiceGrpc.RaftServiceBlockingStub stub;
    private final long rpcTimeoutMs;
    private final AtomicBoolean warnedPreVoteUnsupported = new AtomicBoolean(false);

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
     * @throws StatusRuntimeException Se o par não responder dentro do timeout
     *                                ou se houver erro de rede.
     */
    @Override
    public RaftProto.RequestVoteResponse requestVote(RaftProto.RequestVoteRequest request)
            throws StatusRuntimeException {
        return call(s -> s.requestVote(request));
    }

    /**
     * Envia uma pré-votação. Um par com uma versão antiga do agente não conhece este
     * RPC (UNIMPLEMENTED); nesse caso a pré-votação é considerada concedida, que é o
     * que esse par faria na eleição real de qualquer forma.
     */
    @Override
    public RaftProto.RequestVoteResponse preVote(RaftProto.RequestVoteRequest request)
            throws StatusRuntimeException {
        try {
            return call(s -> s.preVote(request));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.UNIMPLEMENTED) {
                throw e;
            }
            if (warnedPreVoteUnsupported.compareAndSet(false, true)) {
                log.warn("O par {} não suporta pré-votação (versão antiga do agente). "
                    + "Pré-votos dele serão considerados concedidos.", peerId);
            }
            return RaftProto.RequestVoteResponse.newBuilder()
                .setTerm(request.getTerm())
                .setVoteGranted(true)
                .build();
        }
    }

    /**
     * Envia uma requisição AppendEntries para o par e aguarda a resposta.
     *
     * @throws StatusRuntimeException Se o par não responder dentro do timeout.
     */
    @Override
    public RaftProto.AppendEntriesResponse appendEntries(RaftProto.AppendEntriesRequest request)
            throws StatusRuntimeException {
        return call(s -> s.appendEntries(request));
    }

    private <T> T call(Function<RaftServiceGrpc.RaftServiceBlockingStub, T> rpc) {
        // Canal em falha: tenta conectar agora em vez de esperar o fim do backoff
        if (channel.getState(false) == ConnectivityState.TRANSIENT_FAILURE) {
            channel.resetConnectBackoff();
        }
        try {
            return rpc.apply(stub.withDeadlineAfter(rpcTimeoutMs, TimeUnit.MILLISECONDS));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
                channel.resetConnectBackoff();
            }
            throw e;
        }
    }

    /** Fecha o canal gRPC. Deve ser chamado ao desligar o nó. */
    @Override
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
