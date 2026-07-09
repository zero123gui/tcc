package com.tcc.raft.core;

/**
 * Tipo de uma entrada no log do Raft.
 *
 * <p>Cada entrada carrega um tipo que determina como o campo {@code data} será
 * interpretado quando a entrada for aplicada à máquina de estados.
 *
 * <p>O tipo {@code NO_OP} é inserido pelo líder imediatamente após vencer uma
 * eleição. Essa é uma exigência do algoritmo Raft para garantir que entradas
 * de mandatos anteriores, que ainda não foram confirmadas, sejam finalmente
 * replicadas e aplicadas antes que novas entradas do líder atual sejam
 * processadas.
 */
public enum LogEntryType {

    /**
     * Entrada vazia. Não altera o estado da máquina de estados.
     * Inserida pelo líder ao assumir o mandato para forçar o avanço
     * do commitIndex sobre entradas pendentes de mandatos anteriores.
     */
    NO_OP,

    /**
     * Registra a troca do Primary do PostgreSQL.
     * O campo {@code data} contém um {@code FailoverData} serializado em JSON.
     * Quando confirmada (committed), aciona a execução do failover no nó
     * que se tornou o novo líder.
     */
    FAILOVER,

    /**
     * Registra a adição ou remoção de um nó no cluster.
     * O campo {@code data} contém um {@code ConfigChangeData} serializado em JSON.
     * Quando confirmada, a nova configuração de cluster entra em vigor
     * em todos os nós que replicaram a entrada.
     *
     * <p>Este sistema implementa o mecanismo de "single-server changes" descrito
     * no paper do Raft, onde apenas uma mudança de configuração pode estar
     * pendente de confirmação por vez. Isso é mais simples e seguro do que
     * o "joint consensus" descrito na seção 6 do paper original.
     */
    CONFIG_CHANGE
}
