package com.tcc.raft.core;

import com.tcc.raft.grpc.proto.RaftProto;

/**
 * Canal de comunicação do {@link RaftNode} com um par do cluster.
 *
 * <p>Em produção é implementado por {@code RaftGrpcClient}. Nos testes, por uma
 * rede em memória que permite cortar a comunicação entre nós e verificar o
 * algoritmo sem VMs.
 *
 * <p>Falhas de comunicação (par fora do ar, timeout) são sinalizadas com
 * qualquer {@link RuntimeException}.
 */
public interface PeerClient {

    /** Pede o voto do par para uma eleição real (Raft, seção 5.2). */
    RaftProto.RequestVoteResponse requestVote(RaftProto.RequestVoteRequest request);

    /**
     * Pré-votação (tese do Raft, seção 9.6): pergunta se o par votaria neste nó
     * no mandato {@code request.term}, sem que nenhum dos dois altere o próprio estado.
     */
    RaftProto.RequestVoteResponse preVote(RaftProto.RequestVoteRequest request);

    /** Heartbeat ou replicação de entradas de log (Raft, seção 5.3). */
    RaftProto.AppendEntriesResponse appendEntries(RaftProto.AppendEntriesRequest request);

    /** Libera os recursos de comunicação. */
    void shutdown();
}
