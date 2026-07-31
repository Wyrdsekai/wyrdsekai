package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * multi-modal scrolls.
 *
 * <p>A <b>scroll</b> is a sequence of typed sections — text, chart, image,
 * embed, divider — composed into a single rich document an item can hand
 * to a person. Scrolls reference {@link ArtifactService} entries by id, so
 * the same chart artifact can appear in multiple scrolls without
 * duplication.</p>
 *
 * <p>State: title, sections (JSON-serialised), owner, lock flag, share-target
 * list, version. {@code revise} bumps the version; once {@code lock} is
 * called, further revisions are rejected.</p>
 *
 * <p>Singleton via {@link #get(String, ArtifactService)}; tests can call
 * {@link #resetForTesting()}.</p>
 */
public final class ScrollService {

    private static final Logger log = LoggerFactory.getLogger(ScrollService.class);

    private static volatile ScrollService INSTANCE;

    /** A scroll record. {@code sections} is a list of typed maps. */
    public record Scroll(
        String id,
        String ownerAgentId,
        String title,
        List<Map<String, Object>> sections,
        boolean locked,
        List<String> shareTargets,
        int version,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private final String jdbcUrl;
    private final ArtifactService artifactService;
    private final Map<String, Scroll> mem = new ConcurrentHashMap<>();

    private ScrollService(String jdbcUrl, ArtifactService artifactService) {
        this.jdbcUrl = jdbcUrl;
        this.artifactService = artifactService;
        if (hasJdbc()) {
            initSchema();
            loadFromDisk();
        }
    }

    public static ScrollService get(String jdbcUrl, ArtifactService artifactService) {
        if (INSTANCE == null) {
            synchronized (ScrollService.class) {
                if (INSTANCE == null) INSTANCE = new ScrollService(jdbcUrl, artifactService);
            }
        }
        return INSTANCE;
    }

    /** Test-only — drop the singleton. */
    public static void resetForTesting() {
        synchronized (ScrollService.class) {
            if (INSTANCE != null) {
                INSTANCE.mem.clear();
                INSTANCE = null;
            }
        }
    }

    // ─── Public API ─────────────────────────────────────────────

    /**
     * §4.37 {@code world.scroll.create}. {@code sections} is a list of
     * blocks: each block is a Map with at minimum a {@code type} key
     * (e.g. {@code "text"}, {@code "chart"}, {@code "embed"}). Validation
     * is shape-only — block-content schemas live in the spec.
     */
    public Map<String, Object> create(String agentId, String title,
                                        List<Map<String, Object>> sections) {
        if (agentId == null || agentId.isBlank()) {
            return Map.of("ok", false, "error", "agentId required");
        }
        var safeTitle = title == null ? "" : title;
        var safeSections = validateSections(sections);
        if (safeSections instanceof Map<?, ?> err) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>) err;
            return m;
        }
        @SuppressWarnings("unchecked")
        var blocks = (List<Map<String, Object>>) safeSections;
        var id = "scroll_" + UUID.randomUUID().toString().substring(0, 12);
        var now = Instant.now();
        var scroll = new Scroll(id, agentId, safeTitle, blocks, false,
            List.of(), 1, now, now);
        mem.put(id, scroll);
        persist(scroll);
        return Map.of("ok", true, "id", id, "version", 1, "createdAt", now.toEpochMilli());
    }

    /**
     * §4.37 {@code world.scroll.read}. Owner OR shared-with target may read.
     * Returns the scroll plus inline-resolved chart payloads.
     */
    public Map<String, Object> read(String agentId, String id) {
        var s = mem.get(id);
        if (s == null) return Map.of("ok", false, "error", "not_found");
        if (!canRead(s, agentId)) return Map.of("ok", false, "error", "not_authorized");
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("id", s.id);
        out.put("ownerAgentId", s.ownerAgentId);
        out.put("title", s.title);
        out.put("sections", resolveSections(s.sections));
        out.put("locked", s.locked);
        out.put("version", s.version);
        out.put("createdAt", s.createdAt.toEpochMilli());
        out.put("updatedAt", s.updatedAt.toEpochMilli());
        if (!s.shareTargets.isEmpty()) out.put("shareTargets", s.shareTargets);
        return out;
    }

    /**
     * §4.37 {@code world.scroll.list}. Returns scrolls owned by {@code agentId}
     * plus any shared-with the caller.
     */
    public List<Map<String, Object>> list(String agentId, Map<String, Object> filter) {
        int limit = filter == null ? 50
            : (int) Math.max(1, asLong(filter.get("limit"), 50L));
        boolean ownedOnly = filter != null && Boolean.TRUE.equals(filter.get("ownedOnly"));
        var out = new ArrayList<Map<String, Object>>();
        for (var s : mem.values()) {
            boolean owns = agentId == null || agentId.equals(s.ownerAgentId);
            boolean shared = !ownedOnly && agentId != null && s.shareTargets.contains(agentId);
            if (!owns && !shared) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("id", s.id);
            m.put("title", s.title);
            m.put("version", s.version);
            m.put("locked", s.locked);
            m.put("ownerAgentId", s.ownerAgentId);
            m.put("createdAt", s.createdAt.toEpochMilli());
            m.put("updatedAt", s.updatedAt.toEpochMilli());
            m.put("sectionCount", s.sections.size());
            out.add(m);
            if (out.size() >= limit) break;
        }
        out.sort((a, b) -> Long.compare(
            asLong(b.get("updatedAt"), 0L),
            asLong(a.get("updatedAt"), 0L)));
        return out;
    }

    /** §4.37 {@code world.scroll.revise} — owner-only; rejected if locked. */
    public Map<String, Object> revise(String agentId, String id,
                                        List<Map<String, Object>> sections) {
        var s = mem.get(id);
        if (s == null) return Map.of("ok", false, "error", "not_found");
        if (agentId != null && !agentId.equals(s.ownerAgentId)) {
            return Map.of("ok", false, "error", "not_owner");
        }
        if (s.locked) return Map.of("ok", false, "error", "locked");
        var validated = validateSections(sections);
        if (validated instanceof Map<?, ?> err) {
            @SuppressWarnings("unchecked")
            var m = (Map<String, Object>) err;
            return m;
        }
        @SuppressWarnings("unchecked")
        var blocks = (List<Map<String, Object>>) validated;
        var updated = new Scroll(s.id, s.ownerAgentId, s.title, blocks,
            s.locked, s.shareTargets, s.version + 1, s.createdAt, Instant.now());
        mem.put(id, updated);
        persist(updated);
        return Map.of("ok", true, "id", id, "version", updated.version);
    }

    /** §4.37 {@code world.scroll.lock} — owner-only; idempotent. */
    public Map<String, Object> lock(String agentId, String id) {
        var s = mem.get(id);
        if (s == null) return Map.of("ok", false, "error", "not_found");
        if (agentId != null && !agentId.equals(s.ownerAgentId)) {
            return Map.of("ok", false, "error", "not_owner");
        }
        if (s.locked) return Map.of("ok", true, "id", id, "alreadyLocked", true);
        var updated = new Scroll(s.id, s.ownerAgentId, s.title, s.sections,
            true, s.shareTargets, s.version, s.createdAt, Instant.now());
        mem.put(id, updated);
        persist(updated);
        return Map.of("ok", true, "id", id, "locked", true);
    }

    /**
     * §4.37 {@code world.scroll.share}. Owner-only. Adds {@code target} to
     * the share list — duplicate adds are no-ops.
     */
    public Map<String, Object> share(String agentId, String id, String target) {
        var s = mem.get(id);
        if (s == null) return Map.of("ok", false, "error", "not_found");
        if (agentId != null && !agentId.equals(s.ownerAgentId)) {
            return Map.of("ok", false, "error", "not_owner");
        }
        if (target == null || target.isBlank()) {
            return Map.of("ok", false, "error", "target required");
        }
        if (s.shareTargets.contains(target)) {
            return Map.of("ok", true, "id", id, "alreadyShared", true);
        }
        var newTargets = new ArrayList<>(s.shareTargets);
        newTargets.add(target);
        var updated = new Scroll(s.id, s.ownerAgentId, s.title, s.sections,
            s.locked, List.copyOf(newTargets), s.version, s.createdAt, Instant.now());
        mem.put(id, updated);
        persist(updated);
        return Map.of("ok", true, "id", id, "shareTargets", updated.shareTargets);
    }

    /** Total non-revoked count — for tests. */
    public int size() { return mem.size(); }

    // ─── Section validation + resolution ────────────────────────

    private Object validateSections(List<Map<String, Object>> sections) {
        if (sections == null) return List.<Map<String, Object>>of();
        if (sections.size() > 256) {
            return Map.of("ok", false, "error", "too many sections (max 256)");
        }
        var safe = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < sections.size(); i++) {
            var sec = sections.get(i);
            if (sec == null) {
                return Map.of("ok", false, "error", "section[" + i + "] is null");
            }
            var type = sec.get("type");
            if (type == null || type.toString().isBlank()) {
                return Map.of("ok", false, "error", "section[" + i + "] missing type");
            }
            // Defensive shallow copy so the caller can't mutate post-hoc.
            safe.add(new LinkedHashMap<>(sec));
        }
        return safe;
    }

    /**
     * Inline-resolve {@code embed}/{@code chart} sections that reference
     * artifact ids — read returns the resolved blob so the renderer doesn't
     * have to make a second hop.
     */
    private List<Map<String, Object>> resolveSections(List<Map<String, Object>> sections) {
        if (artifactService == null || sections == null) return sections;
        var out = new ArrayList<Map<String, Object>>();
        for (var sec : sections) {
            var type = String.valueOf(sec.get("type"));
            if (("embed".equals(type) || "chart".equals(type))
                    && sec.get("artifactId") instanceof String aid && !aid.isBlank()) {
                var art = artifactService.loadRaw(aid);
                if (art != null) {
                    var copy = new LinkedHashMap<>(sec);
                    copy.put("artifactMime", art.mime());
                    copy.put("artifactKind", art.kind());
                    copy.put("artifactTitle", art.title());
                    copy.put("artifactPayload",
                        deserializeFor(art.payload(), art.mime()));
                    out.add(copy);
                    continue;
                }
            }
            out.add(sec);
        }
        return out;
    }

    private static Object deserializeFor(String text, String mime) {
        if (text == null || text.isEmpty()) return "";
        if (mime != null && (mime.contains("json") || mime.contains("vega"))) {
            var parsed = ItemJsonHelper.parse(text);
            return parsed == null ? text : parsed;
        }
        return text;
    }

    private static boolean canRead(Scroll s, String agentId) {
        if (agentId == null) return true;  // service-level read for renderers
        if (agentId.equals(s.ownerAgentId)) return true;
        return s.shareTargets.contains(agentId);
    }

    // ─── Persistence ───────────────────────────────────────────

    private boolean hasJdbc() { return jdbcUrl != null && !jdbcUrl.isBlank(); }

    private void initSchema() {
        try (var c = DriverManager.getConnection(jdbcUrl);
             var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS item_scrolls (
                  id TEXT PRIMARY KEY,
                  owner_agent_id TEXT NOT NULL,
                  title TEXT,
                  sections_json TEXT NOT NULL,
                  locked INTEGER NOT NULL DEFAULT 0,
                  share_targets_json TEXT,
                  version INTEGER NOT NULL DEFAULT 1,
                  created_at_ms BIGINT NOT NULL,
                  updated_at_ms BIGINT NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_item_scrolls_owner ON item_scrolls(owner_agent_id)");
        } catch (SQLException e) {
            log.warn("ScrollService.initSchema failed: {}", e.getMessage());
        }
    }

    private void persist(Scroll s) {
        if (!hasJdbc()) return;
        try (var c = DriverManager.getConnection(jdbcUrl)) {
            try (var del = c.prepareStatement("DELETE FROM item_scrolls WHERE id = ?")) {
                del.setString(1, s.id);
                del.executeUpdate();
            }
            try (var ps = c.prepareStatement(
                "INSERT INTO item_scrolls (id, owner_agent_id, title, sections_json, locked, share_targets_json, version, created_at_ms, updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, s.id);
                ps.setString(2, s.ownerAgentId);
                ps.setString(3, s.title);
                ps.setString(4, ItemJsonHelper.stringify(s.sections));
                ps.setInt(5, s.locked ? 1 : 0);
                ps.setString(6, ItemJsonHelper.stringify(s.shareTargets));
                ps.setInt(7, s.version);
                ps.setLong(8, s.createdAt.toEpochMilli());
                ps.setLong(9, s.updatedAt.toEpochMilli());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("ScrollService.persist failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadFromDisk() {
        try (var c = DriverManager.getConnection(jdbcUrl);
             var ps = c.prepareStatement(
                "SELECT id, owner_agent_id, title, sections_json, locked, share_targets_json, version, created_at_ms, updated_at_ms FROM item_scrolls");
             var rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                var sectionsJson = rs.getString("sections_json");
                var sharedJson = rs.getString("share_targets_json");
                var sections = sectionsJson == null ? List.<Map<String, Object>>of()
                    : (List<Map<String, Object>>) ItemJsonHelper.parse(sectionsJson);
                if (sections == null) sections = List.of();
                var shared = sharedJson == null ? List.<String>of()
                    : (List<String>) ItemJsonHelper.parse(sharedJson);
                if (shared == null) shared = List.of();
                var s = new Scroll(
                    rs.getString("id"),
                    rs.getString("owner_agent_id"),
                    rs.getString("title"),
                    sections,
                    rs.getInt("locked") != 0,
                    shared,
                    rs.getInt("version"),
                    Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                    Instant.ofEpochMilli(rs.getLong("updated_at_ms"))
                );
                mem.put(s.id, s);
                count++;
            }
            if (count > 0) log.info("ScrollService loaded {} scrolls from disk", count);
        } catch (SQLException e) {
            log.warn("ScrollService.loadFromDisk failed: {}", e.getMessage());
        }
    }

    private static long asLong(Object v, long def) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (Exception _) { return def; }
        }
        return def;
    }
}
