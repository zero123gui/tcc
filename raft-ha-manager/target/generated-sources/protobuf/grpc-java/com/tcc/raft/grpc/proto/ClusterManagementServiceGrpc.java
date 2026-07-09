package com.tcc.raft.grpc.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ============================================================================
 * Serviço de gerenciamento do cluster
 * Exposto pelo líder para receber operações administrativas.
 * Seguidores que receberem estas chamadas devem retornar o ID do líder.
 * ============================================================================
 * </pre>
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.63.0)",
    comments = "Source: cluster.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ClusterManagementServiceGrpc {

  private ClusterManagementServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "cluster.ClusterManagementService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest,
      com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse> getSubmitConfigChangeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SubmitConfigChange",
      requestType = com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest.class,
      responseType = com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest,
      com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse> getSubmitConfigChangeMethod() {
    io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest, com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse> getSubmitConfigChangeMethod;
    if ((getSubmitConfigChangeMethod = ClusterManagementServiceGrpc.getSubmitConfigChangeMethod) == null) {
      synchronized (ClusterManagementServiceGrpc.class) {
        if ((getSubmitConfigChangeMethod = ClusterManagementServiceGrpc.getSubmitConfigChangeMethod) == null) {
          ClusterManagementServiceGrpc.getSubmitConfigChangeMethod = getSubmitConfigChangeMethod =
              io.grpc.MethodDescriptor.<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest, com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SubmitConfigChange"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ClusterManagementServiceMethodDescriptorSupplier("SubmitConfigChange"))
              .build();
        }
      }
    }
    return getSubmitConfigChangeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest,
      com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse> getGetClusterStatusMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "GetClusterStatus",
      requestType = com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest.class,
      responseType = com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest,
      com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse> getGetClusterStatusMethod() {
    io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest, com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse> getGetClusterStatusMethod;
    if ((getGetClusterStatusMethod = ClusterManagementServiceGrpc.getGetClusterStatusMethod) == null) {
      synchronized (ClusterManagementServiceGrpc.class) {
        if ((getGetClusterStatusMethod = ClusterManagementServiceGrpc.getGetClusterStatusMethod) == null) {
          ClusterManagementServiceGrpc.getGetClusterStatusMethod = getGetClusterStatusMethod =
              io.grpc.MethodDescriptor.<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest, com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "GetClusterStatus"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ClusterManagementServiceMethodDescriptorSupplier("GetClusterStatus"))
              .build();
        }
      }
    }
    return getGetClusterStatusMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ClusterManagementServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClusterManagementServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClusterManagementServiceStub>() {
        @java.lang.Override
        public ClusterManagementServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClusterManagementServiceStub(channel, callOptions);
        }
      };
    return ClusterManagementServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ClusterManagementServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClusterManagementServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClusterManagementServiceBlockingStub>() {
        @java.lang.Override
        public ClusterManagementServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClusterManagementServiceBlockingStub(channel, callOptions);
        }
      };
    return ClusterManagementServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ClusterManagementServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ClusterManagementServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ClusterManagementServiceFutureStub>() {
        @java.lang.Override
        public ClusterManagementServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ClusterManagementServiceFutureStub(channel, callOptions);
        }
      };
    return ClusterManagementServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ============================================================================
   * Serviço de gerenciamento do cluster
   * Exposto pelo líder para receber operações administrativas.
   * Seguidores que receberem estas chamadas devem retornar o ID do líder.
   * ============================================================================
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Submete uma mudança de configuração (add/remove nó) ao líder.
     * </pre>
     */
    default void submitConfigChange(com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSubmitConfigChangeMethod(), responseObserver);
    }

    /**
     * <pre>
     * Retorna o estado atual do cluster: papéis, saúde do PostgreSQL, etc.
     * </pre>
     */
    default void getClusterStatus(com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getGetClusterStatusMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ClusterManagementService.
   * <pre>
   * ============================================================================
   * Serviço de gerenciamento do cluster
   * Exposto pelo líder para receber operações administrativas.
   * Seguidores que receberem estas chamadas devem retornar o ID do líder.
   * ============================================================================
   * </pre>
   */
  public static abstract class ClusterManagementServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ClusterManagementServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ClusterManagementService.
   * <pre>
   * ============================================================================
   * Serviço de gerenciamento do cluster
   * Exposto pelo líder para receber operações administrativas.
   * Seguidores que receberem estas chamadas devem retornar o ID do líder.
   * ============================================================================
   * </pre>
   */
  public static final class ClusterManagementServiceStub
      extends io.grpc.stub.AbstractAsyncStub<ClusterManagementServiceStub> {
    private ClusterManagementServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClusterManagementServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClusterManagementServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Submete uma mudança de configuração (add/remove nó) ao líder.
     * </pre>
     */
    public void submitConfigChange(com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSubmitConfigChangeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Retorna o estado atual do cluster: papéis, saúde do PostgreSQL, etc.
     * </pre>
     */
    public void getClusterStatus(com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getGetClusterStatusMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ClusterManagementService.
   * <pre>
   * ============================================================================
   * Serviço de gerenciamento do cluster
   * Exposto pelo líder para receber operações administrativas.
   * Seguidores que receberem estas chamadas devem retornar o ID do líder.
   * ============================================================================
   * </pre>
   */
  public static final class ClusterManagementServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ClusterManagementServiceBlockingStub> {
    private ClusterManagementServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClusterManagementServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClusterManagementServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Submete uma mudança de configuração (add/remove nó) ao líder.
     * </pre>
     */
    public com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse submitConfigChange(com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSubmitConfigChangeMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Retorna o estado atual do cluster: papéis, saúde do PostgreSQL, etc.
     * </pre>
     */
    public com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse getClusterStatus(com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getGetClusterStatusMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ClusterManagementService.
   * <pre>
   * ============================================================================
   * Serviço de gerenciamento do cluster
   * Exposto pelo líder para receber operações administrativas.
   * Seguidores que receberem estas chamadas devem retornar o ID do líder.
   * ============================================================================
   * </pre>
   */
  public static final class ClusterManagementServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<ClusterManagementServiceFutureStub> {
    private ClusterManagementServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ClusterManagementServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ClusterManagementServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Submete uma mudança de configuração (add/remove nó) ao líder.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse> submitConfigChange(
        com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSubmitConfigChangeMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Retorna o estado atual do cluster: papéis, saúde do PostgreSQL, etc.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse> getClusterStatus(
        com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getGetClusterStatusMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SUBMIT_CONFIG_CHANGE = 0;
  private static final int METHODID_GET_CLUSTER_STATUS = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SUBMIT_CONFIG_CHANGE:
          serviceImpl.submitConfigChange((com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest) request,
              (io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse>) responseObserver);
          break;
        case METHODID_GET_CLUSTER_STATUS:
          serviceImpl.getClusterStatus((com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest) request,
              (io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getSubmitConfigChangeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeRequest,
              com.tcc.raft.grpc.proto.ClusterProto.ConfigChangeResponse>(
                service, METHODID_SUBMIT_CONFIG_CHANGE)))
        .addMethod(
          getGetClusterStatusMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusRequest,
              com.tcc.raft.grpc.proto.ClusterProto.ClusterStatusResponse>(
                service, METHODID_GET_CLUSTER_STATUS)))
        .build();
  }

  private static abstract class ClusterManagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ClusterManagementServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.tcc.raft.grpc.proto.ClusterProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ClusterManagementService");
    }
  }

  private static final class ClusterManagementServiceFileDescriptorSupplier
      extends ClusterManagementServiceBaseDescriptorSupplier {
    ClusterManagementServiceFileDescriptorSupplier() {}
  }

  private static final class ClusterManagementServiceMethodDescriptorSupplier
      extends ClusterManagementServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ClusterManagementServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (ClusterManagementServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ClusterManagementServiceFileDescriptorSupplier())
              .addMethod(getSubmitConfigChangeMethod())
              .addMethod(getGetClusterStatusMethod())
              .build();
        }
      }
    }
    return result;
  }
}
