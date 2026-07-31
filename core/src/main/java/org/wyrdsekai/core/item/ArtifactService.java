package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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
 * JDBC-backed artifact store.
 *
 * <p>An <b>artifact</b> is any binary or structured output an item produces
 * that's worth surfacing in the world: a PNG, an SVG, a JSON dataset, a
 * generated text document, a chart spec. Artifacts carry a {@code mime} type
 * and a {@code payload} (text/JSON), plus owner+kind metadata for filtering.</p>
 *
 * <p>Storage: a single {@code item_artifacts} table. Payload is held inline
 * as TEXT — the contract is that artifacts within the inline-payload size cap
 * (~1MB) live here; larger binary blobs (PDFs, audio) would route to a
 * filesystem-backed store in a future phase, but the API surface is the same.</p>
 *
 * <p>Owner-scoping: every API takes an {@code agentId}. List/get/revoke check
 * ownership; only the owner may revoke. Room-attached artifacts are visible
 * to room occupants (the runtime layer handles that — this service just
 * records the {@code attached_room_id}).</p>
 *
 * <p>Singleton via {@link #get(String)}; tests can call {@link #resetForTesting()}
 * between cases.</p>
 */
public final class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private static volatile ArtifactService INSTANCE;

    /** Stored artifact record. */
    public record Artifact(
        String id,
        String ownerAgentId,
        String kind,
        String mime,
        String title,
        String payload,         // serialized payload (JSON/text)
        String attachedRoomId,  // null when not attached
        Instant createdAt,
        boolean revoked
    ) {}

    private final String jdbcUrl;
    // In-memory mirror — tests without JDBC still get full service behaviour.
    private final Map<String, Artifact> mem = new ConcurrentHashMap<>();

    private ArtifactService(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        if (hasJdbc()) {
            initSchema();
            loadFromDisk();
        }
    }

    public static ArtifactService get(String jdbcUrl) {
        if (INSTANCE == null) {
            synchronized (ArtifactService.class) {
                if (INSTANCE == null) INSTANCE = new ArtifactService(jdbcUrl);
            }
        }
        return INSTANCE;
    }

    /** Test-only — release the singleton. */
    public static void resetForTesting() {
        synchronized (ArtifactService.class) {
            if (INSTANCE != null) {
                INSTANCE.mem.clear();
                INSTANCE = null;
            }
        }
    }

    // ─── Public API ─────────────────────────────────────────────

    /**
     * §4.36 {@code world.artifact.create}. Returns
     * {@code {ok, id, mime, sizeBytes, createdAt}}.
     */
    public Map<String, Object> create(String agentId, String kind, String mime,
                                        Object payload, Map<String, Object> opts) {
        if (agentId == null || agentId.isBlank()) {
            return Map.of("ok", false, "error", "agentId required");
        }
        if (kind == null || kind.isBlank()) {
            return Map.of("ok", false, "error", "kind required");
        }
        var safeMime = mime == null || mime.isBlank() ? "application/octet-stream" : mime;
        var serialized = serialize(payload);
        var title = opts == null ? null : asString(opts.get("title"));
        var id = "art_" + UUID.randomUUID().toString().substring(0, 12);
        var art = new Artifact(id, agentId, kind, safeMime,
            title == null ? "" : title, serialized, null, Instant.now(), false);
        mem.put(id, art);
        persist(art);
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("id", id);
        out.put("mime", safeMime);
        out.put("kind", kind);
        out.put("sizeBytes", serialized.getBytes(StandardCharsets.UTF_8).length);
        out.put("createdAt", art.createdAt.toEpochMilli());
        return out;
    }

    /** §4.36 {@code world.artifact.get}. Owner-scoped read. */
    public Map<String, Object> get(String agentId, String id) {
        var art = mem.get(id);
        if (art == null || art.revoked) {
            return Map.of("ok", false, "error", "not_found");
        }
        // Room-attached artifacts are readable by anyone in the room — that
        // policy lives at the room layer; here we just enforce owner.
        if (agentId != null && !agentId.equals(art.ownerAgentId) && art.attachedRoomId == null) {
            return Map.of("ok", false, "error", "not_owner");
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.put("id", art.id);
        out.put("kind", art.kind);
        out.put("mime", art.mime);
        out.put("title", art.title);
        out.put("payload", deserializePayload(art.payload, art.mime));
        out.put("createdAt", art.createdAt.toEpochMilli());
        if (art.attachedRoomId != null) out.put("attachedRoomId", art.attachedRoomId);
        return out;
    }

    /**
     * §4.36 {@code world.artifact.list}. {@code filter} may include
     * {@code kind, since (epochMs), limit}.
     */
    public List<Map<String, Object>> list(String agentId, Map<String, Object> filter) {
        var out = new ArrayList<Map<String, Object>>();
        String kind = filter == null ? null : asString(filter.get("kind"));
        long since = filter == null ? 0L
            : asLong(filter.get("since"), 0L);
        int limit = filter == null ? 50
            : (int) Math.max(1, asLong(filter.get("limit"), 50L));
        for (var art : mem.values()) {
            if (art.revoked) continue;
            if (agentId != null && !agentId.equals(art.ownerAgentId)) continue;
            if (kind != null && !kind.equals(art.kind)) continue;
            if (since > 0 && art.createdAt.toEpochMilli() < since) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("id", art.id);
            m.put("kind", art.kind);
            m.put("mime", art.mime);
            m.put("title", art.title);
            m.put("createdAt", art.createdAt.toEpochMilli());
            if (art.attachedRoomId != null) m.put("attachedRoomId", art.attachedRoomId);
            out.add(m);
            if (out.size() >= limit) break;
        }
        // Newest first.
        out.sort((a, b) -> Long.compare(
            asLong(b.get("createdAt"), 0L),
            asLong(a.get("createdAt"), 0L)));
        return out;
    }

    /**
     * §4.36 {@code world.artifact.attach}. Owner-only. Marks the artifact as
     * visible-in-room — the runtime broadcasts the attach event.
     */
    public Map<String, Object> attach(String agentId, String roomId, String artifactId) {
        var art = mem.get(artifactId);
        if (art == null || art.revoked) {
            return Map.of("ok", false, "error", "not_found");
        }
        if (agentId != null && !agentId.equals(art.ownerAgentId)) {
            return Map.of("ok", false, "error", "not_owner");
        }
        if (roomId == null || roomId.isBlank()) {
            return Map.of("ok", false, "error", "roomId required");
        }
        var updated = new Artifact(art.id, art.ownerAgentId, art.kind, art.mime,
            art.title, art.payload, roomId, art.createdAt, art.revoked);
        mem.put(artifactId, updated);
        persist(updated);
        return Map.of("ok", true, "id", artifactId, "attachedRoomId", roomId);
    }

    /** §4.36 {@code world.artifact.revoke}. Owner-only soft-delete. */
    public Map<String, Object> revoke(String agentId, String artifactId) {
        var art = mem.get(artifactId);
        if (art == null) return Map.of("ok", false, "error", "not_found");
        if (agentId != null && !agentId.equals(art.ownerAgentId)) {
            return Map.of("ok", false, "error", "not_owner");
        }
        if (art.revoked) return Map.of("ok", true, "id", artifactId, "alreadyRevoked", true);
        var updated = new Artifact(art.id, art.ownerAgentId, art.kind, art.mime,
            art.title, art.payload, art.attachedRoomId, art.createdAt, true);
        mem.put(artifactId, updated);
        persist(updated);
        return Map.of("ok", true, "id", artifactId);
    }

    /** Direct retrieval used internally by {@link ScrollService}. */
    public Artifact loadRaw(String artifactId) {
        var art = mem.get(artifactId);
        return (art == null || art.revoked) ? null : art;
    }

    /** Total registered (non-revoked) count — for tests. */
    public int size() {
        int n = 0;
        for (var art : mem.values()) if (!art.revoked) n++;
        return n;
    }

    // ─── Persistence ───────────────────────────────────────────

    private boolean hasJdbc() { return jdbcUrl != null && !jdbcUrl.isBlank(); }

    private void initSchema() {
        try (var c = DriverManager.getConnection(jdbcUrl);
             var st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS item_artifacts (
                  id TEXT PRIMARY KEY,
                  owner_agent_id TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  mime TEXT NOT NULL,
                  title TEXT,
                  payload TEXT,
                  attached_room_id TEXT,
                  created_at_ms BIGINT NOT NULL,
                  revoked INTEGER NOT NULL DEFAULT 0
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_item_artifacts_owner ON item_artifacts(owner_agent_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_item_artifacts_kind ON item_artifacts(kind)");
        } catch (SQLException e) {
            log.warn("ArtifactService.initSchema failed: {}", e.getMessage());
        }
    }

    private void persist(Artifact art) {
        if (!hasJdbc()) return;
        try (var c = DriverManager.getConnection(jdbcUrl)) {
            try (var del = c.prepareStatement("DELETE FROM item_artifacts WHERE id = ?")) {
                del.setString(1, art.id);
                del.executeUpdate();
            }
            try (var ps = c.prepareStatement(
                "INSERT INTO item_artifacts (id, owner_agent_id, kind, mime, title, payload, attached_room_id, created_at_ms, revoked) VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, art.id);
                ps.setString(2, art.ownerAgentId);
                ps.setString(3, art.kind);
                ps.setString(4, art.mime);
                ps.setString(5, art.title);
                ps.setString(6, art.payload);
                ps.setString(7, art.attachedRoomId);
                ps.setLong(8, art.createdAt.toEpochMilli());
                ps.setInt(9, art.revoked ? 1 : 0);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("ArtifactService.persist failed: {}", e.getMessage());
        }
    }

    private void loadFromDisk() {
        if (!hasJdbc()) return;
        try (var c = DriverManager.getConnection(jdbcUrl);
             var ps = c.prepareStatement(
                "SELECT id, owner_agent_id, kind, mime, title, payload, attached_room_id, created_at_ms, revoked FROM item_artifacts");
             var rs = ps.executeQuery()) {
            int count = 0;
            while (rs.next()) {
                var art = new Artifact(
                    rs.getString("id"),
                    rs.getString("owner_agent_id"),
                    rs.getString("kind"),
                    rs.getString("mime"),
                    rs.getString("title"),
                    rs.getString("payload"),
                    rs.getString("attached_room_id"),
                    Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                    rs.getInt("revoked") != 0
                );
                mem.put(art.id, art);
                count++;
            }
            if (count > 0) log.info("ArtifactService loaded {} artifacts from disk", count);
        } catch (SQLException e) {
            log.warn("ArtifactService.loadFromDisk failed: {}", e.getMessage());
        }
    }

    // ─── Helpers ────────────────────────────────────────────────

    private static String serialize(Object payload) {
        if (payload == null) return "";
        if (payload instanceof CharSequence cs) return cs.toString();
        return ItemJsonHelper.stringify(payload);
    }

    private static Object deserializePayload(String text, String mime) {
        if (text == null || text.isEmpty()) return "";
        if (mime != null && (mime.contains("json") || mime.contains("vega"))) {
            var parsed = ItemJsonHelper.parse(text);
            return parsed == null ? text : parsed;
        }
        return text;
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }

    private static long asLong(Object v, long def) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s); } catch (Exception _) { return def; }
        }
        return def;
    }
}
