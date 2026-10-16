package com.tcc.raft.grpc;

import com.tcc.raft.core.RaftNode;
import com.tcc.raft.grpc.proto.RaftProto;
import com.tcc.raft.grpc.proto.RaftServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementação do serviço gRPC do Raft.
 *
 * <p>Esta classe recebe as chamadas gRPC dos pares ({@code RequestVote} e
 * {@code AppendEntries}) e as delega ao {@link RaftNode}, que contém toda
 * a lógica do algoritmo.
 *
 * <p>A separação entre a camada gRPC e o núcleo Raft é intencional: o
 * {@link RaftNode} não conhece gRPC e pode ser testado de forma isolada.
 */
public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(RaftServiceImpl.class);

    private final RaftNode raftNode;

    public RaftServiceImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    /**
     * Recebe uma requisição de voto de um candidato e retorna a resposta.
     *
     * <p>O processamento é delegado ao {@link RaftNode#handleRequestVote}.
     * Erros internos são logados e uma resposta de rejeição é enviada,
     * evitando que o stream gRPC fique em estado indefinido.
     */
    @Override
    public void requestVote(RaftProto.RequestVoteRequest request,
                            StreamObserver<RaftProto.RequestVoteResponse> responseObserver) {
        try {
            RaftProto.RequestVoteResponse response = raftNode.handleRequestVote(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Erro ao processar RequestVote de {}.", request.getCandidateId(), e);
            responseObserver.onError(e);
        }
    }

    /**
     * Recebe uma requisição AppendEntries do líder e retorna a resposta.
     *
     * <p>O processamento é delegado ao {@link RaftNode#handleAppendEntries}.
     */
    @Override
    public void appendEntries(RaftProto.AppendEntriesRequest request,
                              StreamObserver<RaftProto.AppendEntriesResponse> responseObserver) {
        try {
            RaftProto.AppendEntriesResponse response = raftNode.handleAppendEntries(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Erro ao processar AppendEntries do líder {}.", request.getLeaderId(), e);
            responseObserver.onError(e);
        }
    }

    /**
     * Recebe uma pré-votação e retorna a resposta, sem alterar o estado do nó.
     *
     * <p>O processamento é delegado ao {@link RaftNode#handlePreVote}.
     */
    @Override
    public void preVote(RaftProto.RequestVoteRequest request,
                        StreamObserver<RaftProto.RequestVoteResponse> responseObserver) {
        try {
            RaftProto.RequestVoteResponse response = raftNode.handlePreVote(request);
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Erro ao processar PreVote de {}.", request.getCandidateId(), e);
            responseObserver.onError(e);
        }
    }
}
