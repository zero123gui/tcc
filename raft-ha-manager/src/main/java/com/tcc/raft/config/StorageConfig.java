package com.tcc.raft.config;

import java.util.Map;

/**
 * Configuração de armazenamento em disco para o estado persistido do Raft.
 *
 * <p>O algoritmo Raft exige que o estado persistido, composto por
 * {@code currentTerm} e {@code votedFor}, sobreviva a reinicializações do processo.
 * Sem essa persistência, um nó poderia votar duas vezes no mesmo mandato após
 * uma reinicialização, violando a garantia de segurança do Raft.
 */
public class StorageConfig {

    private String logDir;

    public static StorageConfig withDefaults() {
        StorageConfig cfg = new StorageConfig();
        cfg.logDir = "/var/lib/raft-ha-manager";
        return cfg;
    }

    public static StorageConfig fromMap(Map<String, Object> raw) {
        StorageConfig cfg = new StorageConfig();
        cfg.logDir = (String) raw.getOrDefault("log-dir", "/var/lib/raft-ha-manager");
        return cfg;
    }

    public String getLogDir() { return logDir; }
}
