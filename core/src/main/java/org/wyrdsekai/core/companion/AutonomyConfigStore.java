package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Per-agent JSON-on-disk store for {@link AutonomyConfig} — the Autonomy
 * Console furnishing in the Hearth. Mirrors
 * {@link PersonalProjectStore}'s pattern: agent-scoped under
 * {@code $WYRDSEKAI_DATA_DIR/agents/<did-slug>/autonomy.json}, full-rewrite
 * on every mutation (config payload is tiny, simpler than delta writes).
 */
public final class AutonomyConfigStore {

    private static final Logger log = LoggerFactory.getLogger(AutonomyConfigStore.class);
    private static final String FILENAME = "autonomy.json";

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        var m = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
        m.findAndRegisterModules();
        return m;
    }

    private final String agentDid;
    private final Path file;
    private AutonomyConfig current;

    public AutonomyConfigStore(String agentDid, Path root) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        this.agentDid = agentDid;
        var base = root != null ? root : HearthJournal.defaultRoot(agentDid);
        this.file = base.resolve(FILENAME);
        load();
    }

    public AutonomyConfigStore(String agentDid) {
        this(agentDid, null);
    }

    private void load() {
        if (!Files.exists(file)) {
            current = AutonomyConfig.defaults();
            return;
        }
        try {
            current = MAPPER.readValue(Files.readAllBytes(file), AutonomyConfig.class);
        } catch (IOException e) {
            log.warn("Failed to load autonomy config for {}: {}", agentDid, e.getMessage());
            current = AutonomyConfig.defaults();
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MAPPER.writeValueAsString(current));
        } catch (IOException e) {
            log.warn("Failed to save autonomy config for {}: {}", agentDid, e.getMessage());
        }
    }

    public synchronized AutonomyConfig get() {
        return current;
    }

    public synchronized AutonomyConfig set(String key, String value) {
        current = current.with(key, value);
        save();
        return current;
    }

    public synchronized AutonomyConfig replace(AutonomyConfig next) {
        current = next == null ? AutonomyConfig.defaults() : next;
        save();
        return current;
    }
}
