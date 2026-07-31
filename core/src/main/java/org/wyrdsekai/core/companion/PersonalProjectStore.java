package org.wyrdsekai.core.companion;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-agent JSON-on-disk store for {@link PersonalProject} records.
 *
 * <p>: the Hearth's Personal Project Board. The
 * companion's first-class storage for work she's doing on her own time.
 * Mirrors the {@code FamiliarPersistenceStore} pattern: agent-scoped under
 * {@code $WYRDSEKAI_DATA_DIR/agents/<did-slug>/projects.json}, full-rewrite
 * on every mutation (small payloads, simpler than delta writes).</p>
 *
 * <p>Privacy is operational: the file is plaintext on disk and the user
 * (or anyone with host root) can read it. The boundary is at the Home
 * grant layer — this store does not enforce read access on its own.</p>
 */
public final class PersonalProjectStore {

    private static final Logger log = LoggerFactory.getLogger(PersonalProjectStore.class);
    private static final String FILENAME = "projects.json";

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
    private final Map<String, PersonalProject> projects = new ConcurrentHashMap<>();

    public PersonalProjectStore(String agentDid, Path root) {
        if (agentDid == null || agentDid.isBlank()) {
            throw new IllegalArgumentException("agentDid required");
        }
        this.agentDid = agentDid;
        var base = root != null ? root : defaultRoot(agentDid);
        this.file = base.resolve(FILENAME);
        load();
    }

    public PersonalProjectStore(String agentDid) {
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

    private void load() {
        if (!Files.exists(file)) return;
        try {
            var bytes = Files.readAllBytes(file);
            if (bytes.length == 0) return;
            List<PersonalProject> loaded = MAPPER.readValue(
                bytes, new TypeReference<List<PersonalProject>>() {});
            for (var p : loaded) {
                if (p != null && p.id() != null) projects.put(p.id(), p);
            }
            log.info("Loaded {} personal projects for {}", projects.size(), agentDid);
        } catch (IOException e) {
            log.warn("Failed to load personal projects for {}: {}",
                agentDid, e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            var snapshot = list();
            Files.writeString(file, MAPPER.writeValueAsString(snapshot));
        } catch (IOException e) {
            log.warn("Failed to save personal projects for {}: {}",
                agentDid, e.getMessage());
        }
    }

    /** Add or update a project; returns the stored value. */
    public PersonalProject put(PersonalProject project) {
        if (project == null || project.id() == null) {
            throw new IllegalArgumentException("project + id required");
        }
        projects.put(project.id(), project);
        save();
        return project;
    }

    public PersonalProject create(String title, String description, List<String> tags) {
        var p = PersonalProject.create(title, description, tags);
        return put(p);
    }

    public Optional<PersonalProject> addEntry(String projectId, String text) {
        var existing = projects.get(projectId);
        if (existing == null) return Optional.empty();
        var updated = existing.withEntry(text);
        projects.put(projectId, updated);
        save();
        return Optional.of(updated);
    }

    public Optional<PersonalProject> setStatus(String projectId, String status) {
        var existing = projects.get(projectId);
        if (existing == null) return Optional.empty();
        var updated = existing.withStatus(status);
        projects.put(projectId, updated);
        save();
        return Optional.of(updated);
    }

    public Optional<PersonalProject> get(String projectId) {
        return Optional.ofNullable(projects.get(projectId));
    }

    /** All projects, newest-touched first. */
    public List<PersonalProject> list() {
        var snap = new ArrayList<>(projects.values());
        snap.sort(Comparator.comparing(PersonalProject::lastTouched).reversed());
        return List.copyOf(snap);
    }

    /** Active-only projects, newest-touched first. */
    public List<PersonalProject> active() {
        return list().stream()
            .filter(p -> "active".equalsIgnoreCase(p.status()))
            .toList();
    }

    public boolean remove(String projectId) {
        var removed = projects.remove(projectId) != null;
        if (removed) save();
        return removed;
    }

    public int size() {
        return projects.size();
    }
}
