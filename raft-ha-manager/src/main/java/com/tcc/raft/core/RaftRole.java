package com.tcc.raft.core;

/**
 * Papel atual de um nó no algoritmo Raft.
 *
 * <p>As transições válidas são:
 * <ul>
 *   <li>FOLLOWER  → CANDIDATE: quando o temporizador de eleição expira sem heartbeat do líder.
 *   <li>CANDIDATE → LEADER:    quando o candidato recebe votos da maioria dos nós.
 *   <li>CANDIDATE → FOLLOWER:  quando o candidato recebe um AppendEntries de um líder legítimo,
 *                              ou quando outro candidato vence a eleição.
 *   <li>LEADER    → FOLLOWER:  quando o líder descobre um mandato maior em qualquer resposta RPC.
 * </ul>
 *
 * <p>Não existe transição direta de FOLLOWER para LEADER,
 * nem de LEADER para CANDIDATE.
 */
public enum RaftRole {

    /**
     * Estado inicial de todos os nós e estado de qualquer nó que não seja
     * líder ou candidato. O seguidor responde a RPCs de candidatos e do líder,
     * e reinicia seu temporizador de eleição ao receber um heartbeat válido.
     */
    FOLLOWER,

    /**
     * Estado de um nó que está disputando uma eleição.
     * O candidato votou em si mesmo e está coletando votos dos outros nós.
     */
    CANDIDATE,

    /**
     * Estado do nó que ganhou a última eleição.
     * O líder é responsável por enviar heartbeats e replicar entradas de log.
     * No contexto deste sistema, o líder também é o nó cujo PostgreSQL
     * deve ser o Primary do cluster de banco de dados.
     */
    LEADER
}
