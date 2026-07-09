package com.tcc.raft.core;

import java.util.Arrays;
import java.util.Objects;

/**
 * Representa uma entrada no log do Raft, conforme definido na seção 5.3 do paper.
 *
 * <p>Esta é a representação interna do sistema, independente do Protobuf.
 * A camada gRPC é responsável pela conversão entre esta classe e as mensagens
 * {@code LogEntry} definidas em {@code raft.proto}.
 *
 * <p>Instâncias desta classe são imutáveis após a criação. Uma vez adicionada
 * ao log, uma entrada nunca é modificada, apenas removida em caso de conflito
 * de log (truncamento do sufixo).
 */
public final class RaftLogEntry {

    /** Posição desta entrada no log. Começa em 1. O índice 0 é reservado como sentinela. */
    private final long index;

    /** Mandato em que o líder criou esta entrada. */
    private final long term;

    /** Tipo da entrada, define como o campo {@code data} é interpretado. */
    private final LogEntryType type;

    /**
     * Conteúdo da entrada serializado em JSON.
     * Vazio para NO_OP, FailoverData JSON para FAILOVER,
     * ConfigChangeData JSON para CONFIG_CHANGE.
     */
    private final byte[] data;

    public RaftLogEntry(long index, long term, LogEntryType type, byte[] data) {
        this.index = index;
        this.term = term;
        this.type = Objects.requireNonNull(type, "type não pode ser null");
        this.data = data != null ? Arrays.copyOf(data, data.length) : new byte[0];
    }

    public long getIndex() { return index; }
    public long getTerm() { return term; }
    public LogEntryType getType() { return type; }

    /** Retorna uma cópia defensiva dos dados para evitar mutação externa. */
    public byte[] getData() { return Arrays.copyOf(data, data.length); }

    /** Retorna os dados como String UTF-8 (conveniente para JSON). */
    public String getDataAsString() { return new String(data, java.nio.charset.StandardCharsets.UTF_8); }

    @Override
    public String toString() {
        return "RaftLogEntry{index=" + index + ", term=" + term + ", type=" + type + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RaftLogEntry)) return false;
        RaftLogEntry other = (RaftLogEntry) o;
        return index == other.index && term == other.term && type == other.type
            && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(index, term, type);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }
}
