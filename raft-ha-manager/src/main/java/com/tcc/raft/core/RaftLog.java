package com.tcc.raft.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Log de entradas do Raft com persistência em disco.
 *
 * <p>O log é a estrutura de dados central do algoritmo Raft. Toda mudança de
 * estado do sistema, seja um failover ou uma mudança de configuração, passa
 * pelo log antes de ser executada. Isso garante que todos os nós apliquem
 * as mesmas operações na mesma ordem.
 *
 * <p>Persistência: as entradas são armazenadas em um arquivo JSON Lines
 * ({@code raft-log.jsonl}), onde cada linha é uma entrada serializada.
 * O estado persistido de Raft (term e votedFor) fica em {@code raft-state.json}.
 *
 * <p>O índice do log começa em 1. O índice 0 é um sentinela que representa
 * o estado inicial vazio (term=0, type=NO_OP), nunca armazenado em disco.
 *
 * <p>Esta classe não é thread-safe por si só. O {@link RaftNode} é responsável
 * por garantir que apenas uma thread acessa o log por vez, usando seu lock interno.
 */
public class RaftLog {

    private static final Logger log = LoggerFactory.getLogger(RaftLog.class);

    // Formato JSON para serialização das entradas em disco
    private static final Gson GSON = new GsonBuilder().create();

    // Lista em memória de todas as entradas do log (índice 0 é o sentinela)
    private final List<RaftLogEntry> entries;

    // Caminho do arquivo de log em disco
    private final Path logFilePath;

    // Writer para append ao arquivo de log (mantido aberto para eficiência)
    private BufferedWriter logWriter;

    /**
     * Inicializa o log. Se o arquivo existir em disco, carrega as entradas.
     * Se não existir, cria um log vazio.
     *
     * @param logDir Diretório onde o arquivo raft-log.jsonl será criado.
     * @throws IOException Se o diretório não puder ser criado ou o arquivo lido.
     */
    public RaftLog(String logDir) throws IOException {
        Path dir = Paths.get(logDir);
        Files.createDirectories(dir);
        this.logFilePath = dir.resolve("raft-log.jsonl");
        this.entries = new ArrayList<>();

        // Sentinela: entrada fictícia no índice 0 (nunca persistida)
        entries.add(new RaftLogEntry(0, 0, LogEntryType.NO_OP, new byte[0]));

        if (Files.exists(logFilePath)) {
            loadFromDisk();
        }

        // Abre o writer em modo append para novas entradas
        this.logWriter = new BufferedWriter(
            new FileWriter(logFilePath.toFile(), StandardCharsets.UTF_8, true)
        );

        log.info("Log Raft inicializado. Entradas carregadas: {}. Último índice: {}.",
            entries.size() - 1, lastIndex());
    }

    /**
     * Adiciona uma entrada ao log e a persiste em disco imediatamente.
     *
     * <p>A persistência é síncrona: o método só retorna após o flush para disco.
     * Isso garante que, em caso de crash logo após o retorno, a entrada já
     * esteja segura no armazenamento persistente.
     *
     * @param entry A entrada a ser adicionada. O índice deve ser {@code lastIndex() + 1}.
     * @throws IllegalArgumentException Se o índice da entrada for inválido.
     * @throws IOException Se a escrita em disco falhar.
     */
    public void append(RaftLogEntry entry) throws IOException {
        long expected = lastIndex() + 1;
        if (entry.getIndex() != expected) {
            throw new IllegalArgumentException(
                "Índice de entrada inválido. Esperado: " + expected + ", recebido: " + entry.getIndex());
        }
        entries.add(entry);
        persistEntry(entry);
        log.debug("Entrada adicionada ao log: {}", entry);
    }

    /**
     * Adiciona uma entrada ou remove o sufixo conflitante antes de adicioná-la.
     *
     * <p>Este método implementa a regra 3 do AppendEntries do Raft: se uma entrada
     * existente conflita com uma nova entrada do líder (mesmo índice, mandato diferente),
     * a entrada existente e todas as que vêm depois são removidas, e a nova entrada
     * é adicionada. Entradas idênticas (mesmo índice e mandato) são ignoradas.
     *
     * @param entry A entrada recebida do líder.
     * @throws IOException Se a reescrita do arquivo de log em disco falhar.
     */
    public void appendOrReplace(RaftLogEntry entry) throws IOException {
        long idx = entry.getIndex();
        if (idx <= lastIndex()) {
            RaftLogEntry existing = get(idx);
            if (existing.getTerm() == entry.getTerm()) {
                // Entrada idêntica já existe, não faz nada
                return;
            }
            // Conflito: remove o sufixo a partir deste índice
            log.warn("Conflito de log no índice {}. Mandato existente: {}, mandato recebido: {}. "
                + "Removendo sufixo do log.", idx, existing.getTerm(), entry.getTerm());
            truncateSuffix(idx);
        }
        append(entry);
    }

    /**
     * Remove todas as entradas do log a partir do índice especificado (inclusive).
     *
     * <p>Após o truncamento, o arquivo de log em disco é reescrito do zero para
     * garantir consistência. Esta operação é menos eficiente que um append simples,
     * mas ocorre apenas em casos de conflito de log, que são raros em operação normal.
     *
     * @param fromIndex Índice a partir do qual as entradas serão removidas.
     * @throws IOException Se a reescrita do arquivo em disco falhar.
     */
    public void truncateSuffix(long fromIndex) throws IOException {
        if (fromIndex <= 0) {
            throw new IllegalArgumentException("fromIndex deve ser >= 1.");
        }
        int listIndex = (int) fromIndex; // O índice Raft coincide com o índice da lista (sentinela no 0)
        if (listIndex < entries.size()) {
            entries.subList(listIndex, entries.size()).clear();
            rewriteToDisk();
            log.info("Sufixo do log removido a partir do índice {}. Último índice agora: {}.",
                fromIndex, lastIndex());
        }
    }

    /**
     * Verifica se o log é consistente com a entrada anterior informada pelo líder.
     *
     * <p>Esta verificação implementa a condição de consistência de log do Raft:
     * o seguidor só aceita novas entradas se a entrada imediatamente anterior
     * (prevLogIndex, prevLogTerm) bater com o que está no seu log local.
     *
     * @param prevLogIndex Índice da entrada anterior informada pelo líder.
     * @param prevLogTerm  Mandato da entrada anterior informada pelo líder.
     * @return true se o log é consistente com os valores informados.
     */
    public boolean isConsistentWith(long prevLogIndex, long prevLogTerm) {
        if (prevLogIndex == 0) {
            // Ponto de partida: não há entrada anterior, sempre consistente
            return true;
        }
        if (prevLogIndex > lastIndex()) {
            // O seguidor não tem a entrada anterior ainda
            return false;
        }
        return get(prevLogIndex).getTerm() == prevLogTerm;
    }

    /**
     * Retorna a entrada no índice especificado.
     *
     * @param index Índice da entrada (começa em 1; 0 retorna o sentinela).
     * @throws IndexOutOfBoundsException Se o índice não existir no log.
     */
    public RaftLogEntry get(long index) {
        int i = (int) index;
        if (i < 0 || i >= entries.size()) {
            throw new IndexOutOfBoundsException(
                "Índice " + index + " fora dos limites. Último índice: " + lastIndex());
        }
        return entries.get(i);
    }

    /**
     * Retorna todas as entradas a partir do índice especificado (inclusive).
     * A lista retornada é uma cópia e pode ser modificada com segurança.
     */
    public List<RaftLogEntry> getFrom(long fromIndex) {
        int i = (int) fromIndex;
        if (i >= entries.size()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(entries.subList(i, entries.size()));
    }

    /** Índice da última entrada no log (0 se o log estiver vazio). */
    public long lastIndex() {
        return entries.size() - 1;
    }

    /** Mandato da última entrada no log (0 se o log estiver vazio). */
    public long lastTerm() {
        return entries.get(entries.size() - 1).getTerm();
    }

    /** Fecha o writer de arquivo. Deve ser chamado ao desligar o nó. */
    public void close() {
        try {
            if (logWriter != null) {
                logWriter.close();
            }
        } catch (IOException e) {
            log.error("Erro ao fechar writer do log Raft.", e);
        }
    }

    // =========================================================================
    // Persistência em disco
    // =========================================================================

    private void persistEntry(RaftLogEntry entry) throws IOException {
        String json = serializeEntry(entry);
        logWriter.write(json);
        logWriter.newLine();
        logWriter.flush(); // Flush síncrono para garantir durabilidade
    }

    private void rewriteToDisk() throws IOException {
        // Fecha o writer atual antes de sobrescrever o arquivo
        if (logWriter != null) {
            logWriter.close();
        }
        try (BufferedWriter writer = new BufferedWriter(
            new FileWriter(logFilePath.toFile(), StandardCharsets.UTF_8, false))) {
            // Pula o sentinela (índice 0), que nunca é persistido
            for (int i = 1; i < entries.size(); i++) {
                writer.write(serializeEntry(entries.get(i)));
                writer.newLine();
            }
            writer.flush();
        }
        // Reabre em modo append para futuras adições
        this.logWriter = new BufferedWriter(
            new FileWriter(logFilePath.toFile(), StandardCharsets.UTF_8, true)
        );
    }

    private void loadFromDisk() throws IOException {
        log.info("Carregando log Raft de {}.", logFilePath);
        try (BufferedReader reader = new BufferedReader(
            new FileReader(logFilePath.toFile(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    entries.add(deserializeEntry(line));
                }
            }
        }
    }

    private String serializeEntry(RaftLogEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("index", entry.getIndex());
        obj.addProperty("term", entry.getTerm());
        obj.addProperty("type", entry.getType().name());
        // Serializa os dados binários como Base64 para compatibilidade JSON
        obj.addProperty("data", Base64.getEncoder().encodeToString(entry.getData()));
        return GSON.toJson(obj);
    }

    private RaftLogEntry deserializeEntry(String json) {
        JsonObject obj = GSON.fromJson(json, JsonObject.class);
        long index = obj.get("index").getAsLong();
        long term = obj.get("term").getAsLong();
        LogEntryType type = LogEntryType.valueOf(obj.get("type").getAsString());
        byte[] data = Base64.getDecoder().decode(obj.get("data").getAsString());
        return new RaftLogEntry(index, term, type, data);
    }
}
