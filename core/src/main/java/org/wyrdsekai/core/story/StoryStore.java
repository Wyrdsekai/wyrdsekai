package org.wyrdsekai.core.story;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.wyrdsekai.core.soul.JsonAtomicWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * D.6 + §7 — persistence for scenes, beats, and arcs.
 *
 * <p>JSON files per day per focal entity:</p>
 * <pre>
 *   data/story/&lt;focalDID&gt;/&lt;YYYY-MM-DD&gt;.json   — scenes + beats for that day
 *   data/story/arcs.json                              — all arcs (declared + emergent)
 *   data/story/proposed.json                          — proposed-but-not-accepted arcs
 *   data/biography/&lt;focalDID&gt;/&lt;YYYY-MM-DD&gt;.md — human-readable journal
 * </pre>
 *
 * <p>Atomic writes via {@link JsonAtomicWriter}. Append-only at the data
 * layer: scene revisions create new rows with {@code replacesId} pointing at
 * the original; reads filter to the latest revision per sceneId. Journal
 * markdown is append-only at file level.</p>
 *
 * <p>Per-day file granularity gives natural rotation + cheap backup. The
 * "everything in arcs.json" choice is fine for v1 — arc count is small
 * (~10s) even on long-running households.</p>
 */
public final class StoryStore {

    /**
     * HTML-comment marker prefix stamped on every
     * journal scene block. The closing "{@code -->}" is appended by the
     * renderer immediately after the sceneId. Format:
     * {@code <!-- sceneId: <id> -->}. Invisible in rendered markdown,
     * grep-findable on disk, and the lookup key that pairs a human's
     * mirrored journal entry with the companion's {@link
     * org.wyrdsekai.core.soul.SoulFragment} of the same scene-cluster.
     */
    public static final String SCENE_ID_MARKER_PREFIX = "<!-- sceneId: ";

    private final Path storyRoot;
    private final Path biographyRoot;
    private final ObjectMapper mapper;
    private final Map<String, Object> ioLocks = new ConcurrentHashMap<>();

    public StoryStore(Path dataRoot) {
        if (dataRoot == null) throw new IllegalArgumentException("dataRoot required");
        this.storyRoot = dataRoot.resolve("story");
        this.biographyRoot = dataRoot.resolve("biography");
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        try {
            Files.createDirectories(storyRoot);
            Files.createDirectories(biographyRoot);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create story dirs at " + dataRoot, e);
        }
    }

    /** Container persisted in the per-day file. */
    public record DayFile(
        @JsonProperty("date") String date,
        @JsonProperty("focalEntityId") String focalEntityId,
        @JsonProperty("scenes") List<Scene> scenes
    ) {
        @JsonCreator
        public DayFile {
            scenes = scenes == null ? List.of() : List.copyOf(scenes);
        }
    }

    /** Container persisted in arcs.json. */
    public record ArcsFile(
        @JsonProperty("arcs") List<Arc> arcs
    ) {
        @JsonCreator
        public ArcsFile {
            arcs = arcs == null ? List.of() : List.copyOf(arcs);
        }
    }

    /** Persist a closed scene (append revision; never overwrite). */
    public void saveScene(Scene scene) {
        if (scene == null || scene.rangeEnd() == null) return;
        var date = LocalDate.ofInstant(scene.rangeEnd(), ZoneId.systemDefault()).toString();
        var path = scenePath(scene.focalEntityId(), date);
        var lock = ioLocks.computeIfAbsent(path.toString(), k -> new Object());
        synchronized (lock) {
            var existing = loadDayFile(path, scene.focalEntityId(), date);
            var combined = new ArrayList<>(existing.scenes());
            combined.add(scene);
            try {
                JsonAtomicWriter.write(path, new DayFile(date, scene.focalEntityId(), combined));
            } catch (IOException e) {
                throw new RuntimeException("Failed to persist scene " + scene.id(), e);
            }
        }
    }

    /** Load all scenes for a focal on a given date. Returns empty list if no file. */
    public List<Scene> loadScenes(String focalEntityId, LocalDate date) {
        if (focalEntityId == null || date == null) return List.of();
        return loadDayFile(scenePath(focalEntityId, date.toString()),
            focalEntityId, date.toString()).scenes();
    }

    /**
     * Load all closed scenes for a focal across the recent date range
     * (inclusive). Used by ArcDetector sleep-pass.
     */
    public List<Scene> loadScenesInWindow(String focalEntityId,
                                            LocalDate from, LocalDate to) {
        if (focalEntityId == null || from == null || to == null) return List.of();
        var all = new ArrayList<Scene>();
        var d = from;
        while (!d.isAfter(to)) {
            all.addAll(loadScenes(focalEntityId, d));
            d = d.plusDays(1);
        }
        return all;
    }

    /**
     * Replace a scene with a revised version. Adds the revised scene with
     * its own id, and stamps the prior scene's id into the revision's
     * {@code replacesId} field — readers filter to latest revision per
     * replacement chain. (Implementation-side: this v1 just appends; readers
     * apply the chain logic via {@link #latestRevisions}.)
     */
    public void replaceScene(Scene revised) {
        // Append-only: the revised scene is just another row.
        saveScene(revised);
    }

    /** Given a raw list of scenes, return only the latest revision per chain. */
    public static List<Scene> latestRevisions(List<Scene> scenes) {
        // v1: no replacesId field on Scene yet — scenes are already canonical.
        // Future: when revision support lands, group by chain and keep latest.
        return scenes;
    }

    // ─── Arcs ─────────────────────────────────────────────────────────────

    public synchronized void saveArcs(List<Arc> arcs) {
        try {
            JsonAtomicWriter.write(storyRoot.resolve("arcs.json"), new ArcsFile(arcs));
        } catch (IOException e) {
            throw new RuntimeException("Failed to persist arcs", e);
        }
    }

    public synchronized List<Arc> loadArcs() {
        var path = storyRoot.resolve("arcs.json");
        if (!Files.exists(path)) return List.of();
        try {
            var f = mapper.readValue(path.toFile(), ArcsFile.class);
            return f == null ? List.of() : f.arcs();
        } catch (IOException e) {
            return List.of();
        }
    }

    // ─── Journal markdown ─────────────────────────────────────────────────

    /**
     * Append a scene block to today's journal markdown file. Each file is
     * append-only at scene granularity; multiple scenes in one day stack
     * under the same {@code # YYYY-MM-DD} h1.
     *
     * <p>If the day file doesn't exist yet, creates it with the h1 header
     * + the scene block. Otherwise appends just the scene block.</p>
     *
     * @param focalName the focal entity's display name (for scene heading)
     * @param sceneTitle short summary ("Settling by the hearth")
     * @param scene the closed scene
     * @param archNames human-readable arc names from the scene's arcIds
     */
    public void appendJournalScene(String focalEntityId,
                                     String focalName,
                                     String sceneTitle,
                                     Scene scene,
                                     List<String> arcNames) {
        if (scene == null || scene.rangeEnd() == null) return;
        var date = LocalDate.ofInstant(scene.rangeEnd(), ZoneId.systemDefault());
        var path = biographyRoot.resolve(focalEntityId).resolve(date + ".md");
        var lock = ioLocks.computeIfAbsent(path.toString(), k -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(path.getParent());
                var sb = new StringBuilder();
                if (!Files.exists(path)) {
                    sb.append("# ").append(date).append("\n\n");
                }
                sb.append(renderSceneMarkdown(focalName, sceneTitle, scene, arcNames));
                Files.writeString(path, sb.toString(),
                    Files.exists(path)
                        ? StandardOpenOption.APPEND
                        : StandardOpenOption.CREATE);
            } catch (IOException e) {
                throw new RuntimeException("Failed to append journal for " + focalEntityId, e);
            }
        }
    }

    /** Render one scene block. Public for testability. */
    public static String renderSceneMarkdown(String focalName,
                                              String sceneTitle,
                                              Scene scene,
                                              List<String> arcNames) {
        var sb = new StringBuilder();
        var startTime = scene.rangeStart() == null ? "??:??"
            : LocalTime.ofInstant(scene.rangeStart(), ZoneId.systemDefault())
                .toString().substring(0, 5);
        var endTime = scene.rangeEnd() == null ? "??:??"
            : LocalTime.ofInstant(scene.rangeEnd(), ZoneId.systemDefault())
                .toString().substring(0, 5);
        var others = scene.participants().stream()
            .filter(p -> !p.equals(scene.focalEntityId()))
            .toList();
        var withClause = others.isEmpty() ? "" : " · with " + String.join(", ", others);

        // sceneId marker for cross-perspective lookup.
        // HTML comment so it's invisible in any markdown renderer but
        // grep-findable on disk: any SoulFragment carrying the same id
        // refers to the same closed scene-cluster as this journal entry.
        // Stable, never-elided format: SCENE_ID_MARKER_PREFIX + id.
        if (scene.id() != null && !scene.id().isBlank()) {
            sb.append(SCENE_ID_MARKER_PREFIX).append(scene.id()).append(" -->\n");
        }
        sb.append("## ").append(sceneTitle == null ? "Scene" : sceneTitle)
            .append(" · ").append(scene.roomId())
            .append(" · ").append(startTime).append("-").append(endTime)
            .append(withClause).append("\n");
        if (arcNames != null && !arcNames.isEmpty()) {
            sb.append("*Arcs: ").append(String.join(" · ", arcNames)).append("*\n");
        }
        sb.append("\n");
        for (var beat : scene.beats()) {
            var t = beat.rangeStart() == null ? "??:??"
                : LocalTime.ofInstant(beat.rangeStart(), ZoneId.systemDefault())
                    .toString().substring(0, 5);
            sb.append("### ").append(t).append(" — ")
                .append(beat.trigger().name().toLowerCase().replace('_', ' '))
                .append("\n")
                .append(beat.anchor() == null ? "" : beat.anchor())
                .append("\n\n");
        }
        if (scene.felt() != null && !scene.felt().isBlank()) {
            sb.append("> ").append(scene.felt().replace("\n", "\n> ")).append("\n\n");
        } else if (scene.needsRendering()) {
            sb.append("> _felt pending voice synthesis_\n\n");
        }
        return sb.toString();
    }

    /** Whether a journal file exists for the given focal + date. */
    public boolean journalExists(String focalEntityId, LocalDate date) {
        if (focalEntityId == null || date == null) return false;
        return Files.exists(biographyRoot.resolve(focalEntityId).resolve(date + ".md"));
    }

    /** Read the journal markdown for a day; returns empty string if absent. */
    public String readJournal(String focalEntityId, LocalDate date) {
        if (focalEntityId == null || date == null) return "";
        var path = biographyRoot.resolve(focalEntityId).resolve(date + ".md");
        if (!Files.exists(path)) return "";
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "";
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private DayFile loadDayFile(Path path, String focalEntityId, String date) {
        if (!Files.exists(path)) return new DayFile(date, focalEntityId, List.of());
        try {
            var f = mapper.readValue(path.toFile(), DayFile.class);
            return f == null ? new DayFile(date, focalEntityId, List.of()) : f;
        } catch (IOException e) {
            return new DayFile(date, focalEntityId, List.of());
        }
    }

    private Path scenePath(String focalEntityId, String date) {
        return storyRoot.resolve(focalEntityId).resolve(date + ".json");
    }

    /** Test seam: report root paths. */
    public Path storyRoot() { return storyRoot; }
    public Path biographyRoot() { return biographyRoot; }

    /**
     * find every focal whose journal file contains a
     * {@code <!-- sceneId: ... -->} marker for the given scene id. Walks
     * the biography directory; returns the focal entity ids (folder
     * names) that mirror this scene. Used to resolve "do you remember
     * that night by the fire" across perspectives — a SoulFragment
     * carrying the same {@code sceneId} maps to whatever focals appear
     * here. Empty list when no journal mirrors the scene yet.
     *
     * <p>Cheap implementation (read-and-scan); the biography tree is
     * typically dozens to low-hundreds of files per active household.
     * If that profile changes we can swap in an index without changing
     * the API.
     */
    public List<String> focalsWithJournalEntryForScene(String sceneId) {
        if (sceneId == null || sceneId.isBlank()) return List.of();
        var marker = SCENE_ID_MARKER_PREFIX + sceneId + " -->";
        if (!Files.isDirectory(biographyRoot)) return List.of();
        var out = new ArrayList<String>();
        try (var focalDirs = Files.list(biographyRoot)) {
            focalDirs.filter(Files::isDirectory).forEach(focalDir -> {
                try (var dayFiles = Files.list(focalDir)) {
                    boolean hit = dayFiles
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
                        .anyMatch(p -> fileContains(p, marker));
                    if (hit) out.add(focalDir.getFileName().toString());
                } catch (IOException ignored) {
                    // skip unreadable focal dir
                }
            });
        } catch (IOException ignored) {
            // biography root unreadable; return whatever we collected
        }
        return List.copyOf(out);
    }

    /** True iff {@code focalEntityId}'s journal carries a marker for {@code sceneId}. */
    public boolean journalEntryExistsForScene(String focalEntityId, String sceneId) {
        if (focalEntityId == null || focalEntityId.isBlank()) return false;
        if (sceneId == null || sceneId.isBlank()) return false;
        var focalDir = biographyRoot.resolve(focalEntityId);
        if (!Files.isDirectory(focalDir)) return false;
        var marker = SCENE_ID_MARKER_PREFIX + sceneId + " -->";
        try (var dayFiles = Files.list(focalDir)) {
            return dayFiles
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .anyMatch(p -> fileContains(p, marker));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean fileContains(Path p, String needle) {
        try {
            return Files.readString(p).contains(needle);
        } catch (IOException e) {
            return false;
        }
    }
}
