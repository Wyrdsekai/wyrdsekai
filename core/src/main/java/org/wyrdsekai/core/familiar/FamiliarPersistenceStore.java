package org.wyrdsekai.core.familiar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.soul.FamilyLocker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON-to-disk persistence for per-agent familiar-system state.
 *
 * <p>What it persists, per agent DID:</p>
 * <ul>
 *   <li><b>thought-forms.json</b> — active forms + per-form history + retired list.</li>
 *   <li><b>named-familiars.json</b> — named-familiar records with counters, bond, self-context.</li>
 *   <li><b>imprints.json</b> — imprint manager state (all imprints).</li>
 *   <li><b>summon-keys.json</b> — issued keys + revoked key ids + usage counters.</li>
 * </ul>
 *
 * <p>Storage root defaults to {@code $WYRDSEKAI_DATA_DIR/agents/<did-slug>/}.
 * Falls back to a writable tmpdir when the env var is absent (typical in
 * tests). Writes are best-effort: a failure logs and continues so the live
 * runtime is never held back by disk issues.</p>
 *
 * <p>The store is agent-scoped — construct one per CompanionActor. Mutations
 * in the CompanionActor trigger {@code saveAll(...)} which re-serializes the
 * affected namespace. JSON is small enough ({@literal <}100KB typical) that a
 * full-rewrite model is simpler and safer than delta writes.</p>
 */
public final class FamiliarPersistenceStore {

    private static final Logger log = LoggerFactory.getLogger(FamiliarPersistenceStore.class);

    private static final ObjectMapper MAPPER = buildMapper();

    private static ObjectMapper buildMapper() {
        var m = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(SerializationFeature.INDENT_OUTPUT);
        // Pick up records / parameter-names modules if on classpath.
        m.findAndRegisterModules();
        return m;
    }

    private final String agentDid;
    private final Path root;

    public FamiliarPersistenceStore(String agentDid, Path root) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        this.agentDid = agentDid;
        this.root = root == null ? defaultRoot(agentDid) : root;
    }

    public FamiliarPersistenceStore(String agentDid) {
        this(agentDid, null);
    }

    public static Path defaultRoot(String agentDid) {
        var base = WyrdConfig.get().dataDir();
        var home = base != null && !base.isBlank()
            ? Path.of(base)
            : Path.of(System.getProperty("java.io.tmpdir"), "wyrdsekai");
        return home.resolve("agents").resolve(slug(agentDid));
    }

    private static String slug(String did) {
        return did.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    public Path root() { return root; }

    // ── Forge cursor (§12 + backlog "since-last-forge") ────────────────────
    // Tracks the timestamp of the most recent ingestion so the next pass can
    // filter out forms + named familiars that haven't changed. Stored as a
    // single-line JSON map so reads + writes are cheap.

    record ForgeCursor(Instant lastForgeAt) {}

    /** Read the last forge timestamp. Returns {@link java.time.Instant#EPOCH} on first call. */
    public Instant loadForgeCursor() {
        try {
            ensureRoot();
            var in = root.resolve("forge-cursor.json");
            if (!Files.exists(in)) return Instant.EPOCH;
            var cursor = MAPPER.readValue(in.toFile(), ForgeCursor.class);
            return cursor.lastForgeAt() == null ? Instant.EPOCH : cursor.lastForgeAt();
        } catch (Exception e) {
            log.debug("Forge cursor read failed for {}: {} — treating as EPOCH",
                agentDid, e.getMessage());
            return Instant.EPOCH;
        }
    }

    /** Update the forge cursor after a successful ingestion. */
    public void saveForgeCursor(Instant at) {
        if (at == null) return;
        try {
            ensureRoot();
            var out = root.resolve("forge-cursor.json");
            MAPPER.writeValue(out.toFile(), new ForgeCursor(at));
        } catch (Exception e) {
            log.warn("Forge cursor write failed for {}: {}", agentDid, e.getMessage());
        }
    }

    // ── Agent-adjustable deviation thresholds (§21) ────────────────────────

    record ThresholdsBlob(double patchCeiling, double minorCeiling) {}

    /**
     * Load this agent's {@link FormEvolutionClassifier.Thresholds}, or null if
     * unset (caller should fall back to defaults). §21 — per-agent override
     * within user-configured bounds.
     */
    public FormEvolutionClassifier.Thresholds loadDeviationThresholds() {
        try {
            ensureRoot();
            var in = root.resolve("deviation-thresholds.json");
            if (!Files.exists(in)) return null;
            var blob = MAPPER.readValue(in.toFile(), ThresholdsBlob.class);
            return new FormEvolutionClassifier.Thresholds(
                blob.patchCeiling(), blob.minorCeiling());
        } catch (Exception e) {
            log.debug("deviation-thresholds read failed for {}: {}",
                agentDid, e.getMessage());
            return null;
        }
    }

    /** Persist this agent's deviation thresholds (pre-clamped by caller). */
    public void saveDeviationThresholds(FormEvolutionClassifier.Thresholds t) {
        if (t == null) return;
        try {
            ensureRoot();
            var out = root.resolve("deviation-thresholds.json");
            MAPPER.writeValue(out.toFile(),
                new ThresholdsBlob(t.patchCeiling(), t.minorCeiling()));
        } catch (Exception e) {
            log.warn("deviation-thresholds write failed for {}: {}",
                agentDid, e.getMessage());
        }
    }

    // ── Thought forms + named familiars ────────────────────────────────────

    record FamilyLockerSnapshot(
        List<ThoughtForm> forms,
        Map<String, List<ThoughtForm>> history,
        List<FamilyLocker.RetiredForm> retired,
        List<NamedFamiliar> named
    ) {}

    public void saveFamilyLocker(FamilyLocker locker) {
        if (locker == null) return;
        try {
            ensureRoot();
            var snapshot = new FamilyLockerSnapshot(
                new ArrayList<>(locker.thoughtFormsSnapshot()),
                new HashMap<>(locker.thoughtFormHistorySnapshot()),
                new ArrayList<>(locker.retiredThoughtForms()),
                new ArrayList<>(locker.namedFamiliarsSnapshot()));
            var out = root.resolve("thought-forms.json");
            MAPPER.writeValue(out.toFile(), snapshot);
        } catch (Exception e) {
            log.warn("FamiliarPersistenceStore[{}]: failed to save locker: {}",
                agentDid, e.getMessage());
        }
    }

    public void loadFamilyLocker(FamilyLocker locker) {
        if (locker == null) return;
        var in = root.resolve("thought-forms.json");
        if (!Files.exists(in)) return;
        try {
            var snapshot = MAPPER.readValue(in.toFile(), FamilyLockerSnapshot.class);
            if (snapshot.forms() != null) {
                for (var form : snapshot.forms()) {
                    var history = snapshot.history() != null
                        ? snapshot.history().get(form.id()) : null;
                    locker.loadThoughtForm(form, history);
                }
            }
            if (snapshot.retired() != null) {
                for (var r : snapshot.retired()) locker.loadRetiredForm(r);
            }
            if (snapshot.named() != null) {
                for (var n : snapshot.named()) locker.loadNamedFamiliar(n);
            }
            log.info("FamiliarPersistenceStore[{}]: loaded {} forms, {} retired, {} named",
                agentDid,
                snapshot.forms() == null ? 0 : snapshot.forms().size(),
                snapshot.retired() == null ? 0 : snapshot.retired().size(),
                snapshot.named() == null ? 0 : snapshot.named().size());
        } catch (Exception e) {
            log.warn("FamiliarPersistenceStore[{}]: failed to load locker: {}",
                agentDid, e.getMessage());
        }
    }

    // ── Imprints ───────────────────────────────────────────────────────────

    record ImprintsSnapshot(List<Imprint> imprints) {}

    public void saveImprints(ImprintManager manager) {
        if (manager == null) return;
        try {
            ensureRoot();
            var snapshot = new ImprintsSnapshot(new ArrayList<>(manager.listAll()));
            var out = root.resolve("imprints.json");
            MAPPER.writeValue(out.toFile(), snapshot);
        } catch (Exception e) {
            log.warn("FamiliarPersistenceStore[{}]: failed to save imprints: {}",
                agentDid, e.getMessage());
        }
    }

    public void loadImprints(ImprintManager manager) {
        if (manager == null) return;
        var in = root.resolve("imprints.json");
        if (!Files.exists(in)) return;
        try {
            var snapshot = MAPPER.readValue(in.toFile(), ImprintsSnapshot.class);
            if (snapshot.imprints() != null) {
                for (var imp : snapshot.imprints()) manager.loadImprint(imp);
                log.info("FamiliarPersistenceStore[{}]: loaded {} imprints",
                    agentDid, snapshot.imprints().size());
            }
        } catch (Exception e) {
            log.warn("FamiliarPersistenceStore[{}]: failed to load imprints: {}",
                agentDid, e.getMessage());
        }
    }

    // ── Summon keys ────────────────────────────────────────────────────────

    record SummonKeysSnapshot(
        List<SummonKey> issuedKeys,
        Map<String, Integer> usage,
        List<String> revoked
    ) {}

    public void saveSummonKeys(Map<String, SummonKey> issuedKeys,
                                SummonKeyRegistry registry) {
        if (issuedKeys == null && registry == null) return;
        try {
            ensureRoot();
            var snapshot = new SummonKeysSnapshot(
                issuedKeys == null ? List.of() : new ArrayList<>(issuedKeys.values()),
                registry == null ? Map.of() : new HashMap<>(registry.usageSnapshot()),
                registry == null ? List.of() : new ArrayList<>(registry.revokedSnapshot()));
            var out = root.resolve("summon-keys.json");
            MAPPER.writeValue(out.toFile(), snapshot);
        } catch (Exception e) {
            log.warn("FamiliarPersistenceStore[{}]: failed to save summon keys: {}",
                agentDid, e.getMessage());
        }
    }

    public void loadSummonKeys(Map<String, SummonKey> issuedKeys,
                                SummonKeyRegistry registry) {
        var in = root.resolve("summon-keys.json");
        if (!Files.exists(in)) return;
        try {
            var snapshot = MAPPER.readValue(in.toFile(), SummonKeysSnapshot.class);
            if (issuedKeys != null && snapshot.issuedKeys() != null) {
                for (var key : snapshot.issuedKeys()) issuedKeys.put(key.id(), key);
            }
            if (registry != null) {
                if (snapshot.usage() != null) {
                    for (var e : snapshot.usage().entrySet()) {
                        registry.loadUsage(e.getKey(), e.getValue());
                    }
                }
                if (snapshot.revoked() != null) {
                    for (var id : snapshot.revoked()) registry.loadRevoked(id);
                }
            }
            log.info("FamiliarPersistenceStore[{}]: loaded {} issued keys, {} revoked",
                agentDid,
                snapshot.issuedKeys() == null ? 0 : snapshot.issuedKeys().size(),
                snapshot.revoked() == null ? 0 : snapshot.revoked().size());
        } catch (Exception e) {
            log.warn("FamiliarPersistenceStore[{}]: failed to load summon keys: {}",
                agentDid, e.getMessage());
        }
    }

    // ── All-in-one ─────────────────────────────────────────────────────────

    public void saveAll(FamilyLocker locker, ImprintManager imprints,
                         Map<String, SummonKey> issuedKeys, SummonKeyRegistry registry) {
        saveFamilyLocker(locker);
        saveImprints(imprints);
        saveSummonKeys(issuedKeys, registry);
    }

    public void loadAll(FamilyLocker locker, ImprintManager imprints,
                         Map<String, SummonKey> issuedKeys, SummonKeyRegistry registry) {
        loadFamilyLocker(locker);
        loadImprints(imprints);
        loadSummonKeys(issuedKeys, registry);
    }

    private void ensureRoot() throws IOException {
        Files.createDirectories(root);
    }
}
