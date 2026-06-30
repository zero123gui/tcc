package com.tcc.raft.grpc;

import com.tcc.raft.core.RaftNode;
import com.tcc.raft.core.RaftRole;
import com.tcc.raft.grpc.proto.ClusterProto;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementação do serviço de gerenciamento do cluster.
 *
 * <p>Permite que um operador consulte o status do cluster e submeta
 * mudanças de configuração. Operações de escrita (como CONFIG_CHANGE)
 * só são aceitas pelo líder atual.
 *
 * <p>TODO (Fase 3): Implementar SubmitConfigChange completo com criação
 * da entrada no log Raft e replicação para os seguidores.
 */
public class ClusterManagementServiceImpl
        extends ClusterProto.ClusterManagementServiceGrpc.ClusterManagementServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ClusterManagementServiceImpl.class);

    private final RaftNode raftNode;

    public ClusterManagementServiceImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    /**
     * Retorna o estado atual do cluster: papel de cada nó, líder atual,
     * mandato e índice de commit.
     *
     * <p>Esta chamada pode ser feita em qualquer nó, não apenas no líder.
     */
    @Override
    public void getClusterStatus(ClusterProto.ClusterStatusRequest request,
                                 StreamObserver<ClusterProto.ClusterStatusResponse> responseObserver) {
        ClusterProto.NodeInfo selfInfo = ClusterProto.NodeInfo.newBuilder()
            .setNodeId(raftNode.getNodeId())
            .setRaftRole(raftNode.getRole().name())
            .setReachable(true)
            .build();

        ClusterProto.ClusterStatusResponse response = ClusterProto.ClusterStatusResponse.newBuilder()
            .setLeaderId(raftNode.getCurrentLeaderId() != null ? raftNode.getCurrentLeaderId() : "")
            .setCurrentTerm(raftNode.getCurrentTerm())
            .setCommitIndex(raftNode.getCommitIndex())
            .addNodes(selfInfo)
            .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    /**
     * Submete uma mudança de configuração ao cluster.
     *
     * <p>Somente o líder pode aceitar esta operação. Se este nó não for o líder,
     * retorna o ID do líder para que o cliente redirecione a requisição.
     */
    @Override
    public void submitConfigChange(ClusterProto.ConfigChangeRequest request,
                                   StreamObserver<ClusterProto.ConfigChangeResponse> responseObserver) {
        if (raftNode.getRole() != RaftRole.LEADER) {
            ClusterProto.ConfigChangeResponse response = ClusterProto.ConfigChangeResponse.newBuilder()
                .setAccepted(false)
                .setMessage("Este nó não é o líder. Envie a requisição para: "
                    + (raftNode.getCurrentLeaderId() != null ? raftNode.getCurrentLeaderId() : "líder desconhecido"))
                .setLeaderId(raftNode.getCurrentLeaderId() != null ? raftNode.getCurrentLeaderId() : "")
                .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
            return;
        }

        // TODO (Fase 3): Criar entrada CONFIG_CHANGE no log e replicar
        log.info("CONFIG_CHANGE recebido (ainda não implementado): {}", request.getChange());
        ClusterProto.ConfigChangeResponse response = ClusterProto.ConfigChangeResponse.newBuilder()
            .setAccepted(false)
            .setMessage("CONFIG_CHANGE ainda não implementado nesta versão.")
            .setLeaderId(raftNode.getNodeId())
            .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
