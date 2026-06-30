package com.tcc.raft.grpc.proto;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 * <pre>
 * ============================================================================
 * Definição do serviço Raft
 * Cada nó expõe este serviço para receber chamadas dos seus pares.
 * ============================================================================
 * </pre>
 */
@javax.annotation.Generated(value = "by gRPC proto compiler (version 1.63.0)", comments = "Source: raft.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RaftServiceGrpc {

  private RaftServiceGrpc() {
  }

  public static final java.lang.String SERVICE_NAME = "raft.RaftService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest, com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse> getRequestVoteMethod;

  @io.grpc.stub.annotations.RpcMethod(fullMethodName = SERVICE_NAME + '/'
      + "RequestVote", requestType = com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest.class, responseType = com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse.class, methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest, com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse> getRequestVoteMethod() {
    io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest, com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse> getRequestVoteMethod;
    if ((getRequestVoteMethod = RaftServiceGrpc.getRequestVoteMethod) == null) {
      synchronized (RaftServiceGrpc.class) {
        if ((getRequestVoteMethod = RaftServiceGrpc.getRequestVoteMethod) == null) {
          RaftServiceGrpc.getRequestVoteMethod = getRequestVoteMethod = io.grpc.MethodDescriptor.<com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest, com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RequestVote"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RaftServiceMethodDescriptorSupplier("RequestVote"))
              .build();
        }
      }
    }
    return getRequestVoteMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest, com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse> getAppendEntriesMethod;

  @io.grpc.stub.annotations.RpcMethod(fullMethodName = SERVICE_NAME + '/'
      + "AppendEntries", requestType = com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest.class, responseType = com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse.class, methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest, com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse> getAppendEntriesMethod() {
    io.grpc.MethodDescriptor<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest, com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse> getAppendEntriesMethod;
    if ((getAppendEntriesMethod = RaftServiceGrpc.getAppendEntriesMethod) == null) {
      synchronized (RaftServiceGrpc.class) {
        if ((getAppendEntriesMethod = RaftServiceGrpc.getAppendEntriesMethod) == null) {
          RaftServiceGrpc.getAppendEntriesMethod = getAppendEntriesMethod = io.grpc.MethodDescriptor.<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest, com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "AppendEntries"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RaftServiceMethodDescriptorSupplier("AppendEntries"))
              .build();
        }
      }
    }
    return getAppendEntriesMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RaftServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RaftServiceStub> factory = new io.grpc.stub.AbstractStub.StubFactory<RaftServiceStub>() {
      @java.lang.Override
      public RaftServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
        return new RaftServiceStub(channel, callOptions);
      }
    };
    return RaftServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output
   * calls on the service
   */
  public static RaftServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RaftServiceBlockingStub> factory = new io.grpc.stub.AbstractStub.StubFactory<RaftServiceBlockingStub>() {
      @java.lang.Override
      public RaftServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
        return new RaftServiceBlockingStub(channel, callOptions);
      }
    };
    return RaftServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the
   * service
   */
  public static RaftServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RaftServiceFutureStub> factory = new io.grpc.stub.AbstractStub.StubFactory<RaftServiceFutureStub>() {
      @java.lang.Override
      public RaftServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
        return new RaftServiceFutureStub(channel, callOptions);
      }
    };
    return RaftServiceFutureStub.newStub(factory, channel);
  }

  /**
   * <pre>
   * ============================================================================
   * Definição do serviço Raft
   * Cada nó expõe este serviço para receber chamadas dos seus pares.
   * ============================================================================
   * </pre>
   */
  public interface AsyncService {

    /**
     * <pre>
     * Chamado por candidatos durante eleições para solicitar votos.
     * </pre>
     */
    default void requestVote(com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRequestVoteMethod(), responseObserver);
    }

    /**
     * <pre>
     * Chamado pelo líder para replicar entradas de log e enviar heartbeats.
     * </pre>
     */
    default void appendEntries(com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getAppendEntriesMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RaftService.
   * 
   * <pre>
   * ============================================================================
   * Definição do serviço Raft
   * Cada nó expõe este serviço para receber chamadas dos seus pares.
   * ============================================================================
   * </pre>
   */
  public static abstract class RaftServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override
    public final io.grpc.ServerServiceDefinition bindService() {
      return RaftServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RaftService.
   * 
   * <pre>
   * ============================================================================
   * Definição do serviço Raft
   * Cada nó expõe este serviço para receber chamadas dos seus pares.
   * ============================================================================
   * </pre>
   */
  public static final class RaftServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RaftServiceStub> {
    private RaftServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RaftServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RaftServiceStub(channel, callOptions);
    }

    /**
     * <pre>
     * Chamado por candidatos durante eleições para solicitar votos.
     * </pre>
     */
    public void requestVote(com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRequestVoteMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * Chamado pelo líder para replicar entradas de log e enviar heartbeats.
     * </pre>
     */
    public void appendEntries(com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest request,
        io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getAppendEntriesMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RaftService.
   * 
   * <pre>
   * ============================================================================
   * Definição do serviço Raft
   * Cada nó expõe este serviço para receber chamadas dos seus pares.
   * ============================================================================
   * </pre>
   */
  public static final class RaftServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RaftServiceBlockingStub> {
    private RaftServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RaftServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RaftServiceBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * Chamado por candidatos durante eleições para solicitar votos.
     * </pre>
     */
    public com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse requestVote(
        com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRequestVoteMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * Chamado pelo líder para replicar entradas de log e enviar heartbeats.
     * </pre>
     */
    public com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse appendEntries(
        com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getAppendEntriesMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service
   * RaftService.
   * 
   * <pre>
   * ============================================================================
   * Definição do serviço Raft
   * Cada nó expõe este serviço para receber chamadas dos seus pares.
   * ============================================================================
   * </pre>
   */
  public static final class RaftServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RaftServiceFutureStub> {
    private RaftServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RaftServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RaftServiceFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * Chamado por candidatos durante eleições para solicitar votos.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse> requestVote(
        com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRequestVoteMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * Chamado pelo líder para replicar entradas de log e enviar heartbeats.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse> appendEntries(
        com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getAppendEntriesMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REQUEST_VOTE = 0;
  private static final int METHODID_APPEND_ENTRIES = 1;

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
        case METHODID_REQUEST_VOTE:
          serviceImpl.requestVote((com.tcc.raft.grpc.proto.RaftProto.RequestVoteRequest) request,
              (io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse>) responseObserver);
          break;
        case METHODID_APPEND_ENTRIES:
          serviceImpl.appendEntries((com.tcc.raft.grpc.proto.RaftProto.AppendEntriesRequest) request,
              (io.grpc.stub.StreamObserver<com.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
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
          getReques
            io.grpc.stub.ServerCall
              new MethodHandlers<
                  com.tcc.raft.grpccom.tcc.raft.grpc.proto.RaftProto.RequestVoteResponse   service, METHODID_REQUEST_VOTE)))
                    od(
          getAppend
            io.grpc.stub.ServerCalls.
              new MethodHandlers<
                  com.tcc.raft.grpccom.tcc.raft.grpc.proto.RaftProto.AppendEntriesResponse   service, METHODID_APPEND_ENTRIES)))
                    ;
  }

  private static abstract class RaftServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RaftServiceBaseDescriptorSupplier() {}

    
    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.tcc.raft.grpc.proto.RaftProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RaftService");
    }
  }

  private static final class RaftServiceFileDescriptorSupplier
      extends RaftServiceBaseDescriptorSupplier {
    RaftServiceFileDescriptorSupplier() {}
  }
    

  private static final class RaftServiceMethodDescriptorSupplier
      extends RaftServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RaftServiceMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (RaftServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RaftServiceFileDescriptorSupplier())
              .addMethod(getRequestVoteMethod())
              .addMethod(getAppendEntriesMethod())
              .build();
        }
      }
    }
    return result;
  }
}
