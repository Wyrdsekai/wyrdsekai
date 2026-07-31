package org.wyrdsekai.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.AppVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Data-directory version marker — {@code data-version.json} at the data-dir root.
 *
 * <p>Pre-OSS data-durability (2026-07-09): once releases ship, a node's data dir will be
 * opened by many binary versions over its life. This marker records who created it and who
 * touched it last, and provides the <b>downgrade guard</b>: an older binary refusing to open
 * a data dir whose schema is newer than anything it understands, instead of silently running
 * ad-hoc DDL against tables it doesn't know. Override with
 * {@code WYRDSEKAI_ALLOW_DOWNGRADE=true} (after taking a backup).</p>
 */
public final class DataVersion {

    private static final Logger log = LoggerFactory.getLogger(DataVersion.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String FILE_NAME = "data-version.json";

    private DataVersion() {}

    /**
     * Stamp the data dir and guard against downgrades. Call ONCE at boot, before any
     * schema initialization. Never throws for IO problems (a broken marker must not
     * brick a node); throws {@link IllegalStateException} only for the downgrade case.
     */
    public static void stampAndGuard(Path dataDir) {
        var file = dataDir.resolve(FILE_NAME);
        var now = Instant.now().toString();
        var appVersion = AppVersion.get().version();
        try {
            Files.createDirectories(dataDir);
            Map<String, Object> info;
            if (Files.exists(file)) {
                info = MAPPER.readValue(file.toFile(), Map.class);
                int storedSchema = info.get("schema_version") instanceof Number n ? n.intValue() : 0;
                if (storedSchema > SchemaInitializer.SCHEMA_VERSION) {
                    boolean allow = "true".equalsIgnoreCase(
                        System.getenv().getOrDefault("WYRDSEKAI_ALLOW_DOWNGRADE", "false"));
                    var msg = "Data dir " + dataDir + " has schema v" + storedSchema
                        + " (written by " + info.getOrDefault("last_opened_by", "?")
                        + ") but this binary (" + appVersion + ") only understands v"
                        + SchemaInitializer.SCHEMA_VERSION
                        + ". Opening it could corrupt newer tables. Upgrade the binary, or set "
                        + "WYRDSEKAI_ALLOW_DOWNGRADE=true to proceed anyway (take a `wyrd backup` first).";
                    if (!allow) throw new IllegalStateException(msg);
                    log.warn("DOWNGRADE OVERRIDE ACTIVE: {}", msg);
                }
            } else {
                info = new LinkedHashMap<>();
                // If databases already exist, this data dir predates versioning — say so
                // honestly rather than claiming this binary created it.
                boolean preexisting = Files.exists(dataDir.resolve("world.db"));
                info.put("created_at", now);
                info.put("created_by", preexisting ? "pre-versioning (unknown)" : appVersion);
            }
            info.put("schema_version", SchemaInitializer.SCHEMA_VERSION);
            info.put("last_opened_by", appVersion);
            info.put("last_opened_at", now);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), info);
            log.info("Data-version stamp: schema v{}, opened by {} ({})",
                SchemaInitializer.SCHEMA_VERSION, appVersion, file);
        } catch (IllegalStateException downgrade) {
            throw downgrade;
        } catch (Exception e) {
            log.warn("Data-version stamp failed (non-fatal): {}", e.getMessage());
        }
    }
}
