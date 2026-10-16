package com.tcc.raft.grpc;

import com.tcc.raft.grpc.proto.RaftProto;
import com.tcc.raft.grpc.proto.RaftServiceGrpc;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8: depois de um par ficar fora do ar por algum tempo, o cliente gRPC tem de
 * voltar a alcançá-lo em até um heartbeat, e não ao fim do backoff exponencial
 * do gRPC (que chega a minutos). Era isso que impedia o líder de enviar heartbeat
 * ao node-2 quando ele voltava, levando-o a iniciar uma eleição.
 */
class RaftGrpcClientReconnectTest {

    /** Serviço mínimo: responde a qualquer AppendEntries com sucesso. */
    private static final class OkService extends RaftServiceGrpc.RaftServiceImplBase {
        @Override
        public void appendEntries(RaftProto.AppendEntriesRequest request,
                                  StreamObserver<RaftProto.AppendEntriesResponse> obs) {
            obs.onNext(RaftProto.AppendEntriesResponse.newBuilder().setSuccess(true).build());
            obs.onCompleted();
        }
    }

    private static Server startServer(int port) throws Exception {
        return NettyServerBuilder.forPort(port)
            .addService(new OkService())
            .permitKeepAliveTime(5, TimeUnit.SECONDS)
            .permitKeepAliveWithoutCalls(true)
            .build()
            .start();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static boolean tryCall(RaftGrpcClient client) {
        try {
            return client.appendEntries(RaftProto.AppendEntriesRequest.getDefaultInstance()).getSuccess();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void reconectaEmAteUmHeartbeatDepoisDeOParFicarForaDoAr() throws Exception {
        int port = freePort();
        Server server = startServer(port);
        RaftGrpcClient client = new RaftGrpcClient("par", "127.0.0.1", port, 300);
        try {
            assertTrue(tryCall(client), "chamada inicial deveria funcionar");

            // Par fora do ar por 8 s, com "heartbeats" a cada 100 ms falhando:
            // o suficiente para o backoff do gRPC passar de vários segundos.
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            long downUntil = System.currentTimeMillis() + 8_000;
            while (System.currentTimeMillis() < downUntil) {
                tryCall(client);
                Thread.sleep(100);
            }

            server = startServer(port);
            long back = System.currentTimeMillis();
            boolean ok = false;
            while (!ok && System.currentTimeMillis() - back < 10_000) {
                ok = tryCall(client);
                if (!ok) Thread.sleep(50);
            }
            long reconnectMs = System.currentTimeMillis() - back;

            assertTrue(ok, "o cliente deveria reconectar");
            // Medido no notebook do laboratório: ~55 ms com o backoff zerado e 1,6–2,1 s
            // sem (após só 8 s fora do ar; nas VMs, minutos fora levavam a dezenas de segundos).
            assertTrue(reconnectMs < 500,
                "reconexão levou " + reconnectMs + " ms; deveria ser imediata (backoff zerado)");
        } finally {
            client.shutdown();
            server.shutdownNow();
        }
    }
}
