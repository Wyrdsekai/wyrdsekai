package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memory of attackers. What the body has had to act against, kept in the record: a tool's
 * reach for the keys, a door shut, a visitor turned away at the dock, a capability the
 * librarian blocklisted. Each entry says what was seen, why, when, from where, how often, and
 * when it expires, a year unless the steward says otherwise. It is consulted when a foreign
 * limb attaches, so a remembered source lands in quarantine on sight, and it is the steward's
 * to read and to forget.
 *
 * <p>Entries expire because a blocklist that never forgets rots: a source that has changed
 * deserves a second look, and the steward can always forget sooner.</p>
 */
public final class ImmuneMemory {

    private static final Logger log = LoggerFactory.getLogger(ImmuneMemory.class);
    public static final Duration DEFAULT_TTL = Duration.ofDays(365);

    public record Entry(String id, String kind, String subject, String reason, String source,
                        Instant firstSeen, Instant lastSeen, long count, Instant expiresAt) {
        public boolean expired(Instant now) { return expiresAt != null && !now.isBefore(expiresAt); }
    }

    private static volatile ImmuneMemory instance;
    public static ImmuneMemory get() { return instance; }
    public static void install(ImmuneMemory m) { instance = m; }
    public static void resetForTests() { instance = null; }

    private final String jdbcUrl;
    private final Map<String, Entry> inMemory;   // only when there is no record

    private ImmuneMemory(String jdbcUrl, boolean memoryOnly) {
        this.jdbcUrl = jdbcUrl;
        this.inMemory = memoryOnly ? new ConcurrentHashMap<>() : null;
    }

    public static ImmuneMemory onRecord(String jdbcUrl) { return new ImmuneMemory(jdbcUrl, false); }
    public static ImmuneMemory inMemory() { return new ImmuneMemory(null, true); }

    private static String key(String kind, String subject) { return kind + "|" + subject; }

    /** Remember, or count again if already remembered; the expiry moves with the last sighting. */
    public synchronized Entry remember(String kind, String subject, String reason, String source, Duration ttl) {
        var now = Instant.now();
        var expires = ttl == null ? now.plus(DEFAULT_TTL) : now.plus(ttl);
        var existing = recall(kind, subject).orElse(null);
        var e = existing == null
            ? new Entry(UUID.randomUUID().toString(), kind, subject, reason, source, now, now, 1, expires)
            : new Entry(existing.id(), kind, subject, reason == null ? existing.reason() : reason, source == null ? existing.source() : source,
                existing.firstSeen(), now, existing.count() + 1, expires);
        if (inMemory != null) { inMemory.put(key(kind, subject), e); return e; }
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var up = conn.prepareStatement("""
                 INSERT INTO immune_memory (id, kind, subject, reason, source, first_seen, last_seen, count, expires_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                 ON CONFLICT(kind, subject) DO UPDATE SET reason = excluded.reason, source = excluded.source,
                   last_seen = excluded.last_seen, count = excluded.count, expires_at = excluded.expires_at""")) {
            up.setString(1, e.id()); up.setString(2, e.kind()); up.setString(3, e.subject()); up.setString(4, e.reason());
            up.setString(5, e.source()); up.setLong(6, e.firstSeen().toEpochMilli()); up.setLong(7, e.lastSeen().toEpochMilli());
            up.setLong(8, e.count()); up.setLong(9, e.expiresAt().toEpochMilli());
            up.executeUpdate();
        } catch (SQLException ex) {
            log.warn("immune memory: not kept ({} {}): {}", kind, subject, ex.getMessage());
        }
        return e;
    }

    /** What is remembered about this, if it has not expired. */
    public synchronized Optional<Entry> recall(String kind, String subject) {
        var now = Instant.now();
        if (inMemory != null) return Optional.ofNullable(inMemory.get(key(kind, subject))).filter(e -> !e.expired(now));
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var q = conn.prepareStatement("SELECT * FROM immune_memory WHERE kind = ? AND subject = ?")) {
            q.setString(1, kind); q.setString(2, subject);
            try (var rs = q.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                var e = new Entry(rs.getString("id"), rs.getString("kind"), rs.getString("subject"), rs.getString("reason"), rs.getString("source"),
                    Instant.ofEpochMilli(rs.getLong("first_seen")), Instant.ofEpochMilli(rs.getLong("last_seen")), rs.getLong("count"),
                    rs.getObject("expires_at") == null ? null : Instant.ofEpochMilli(rs.getLong("expires_at")));
                return e.expired(now) ? Optional.empty() : Optional.of(e);
            }
        } catch (SQLException ex) {
            log.warn("immune memory: not read: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /** Whether any remembered, unexpired entry names this subject, whatever its kind. */
    public synchronized Optional<Entry> recallAny(String subject) {
        return list().stream().filter(e -> e.subject().equals(subject)).findFirst();
    }

    /** Everything remembered and not expired, most recent first. */
    public synchronized List<Entry> list() {
        var now = Instant.now();
        var out = new ArrayList<Entry>();
        if (inMemory != null) { inMemory.values().stream().filter(e -> !e.expired(now)).forEach(out::add); }
        else {
            try (var conn = DriverManager.getConnection(jdbcUrl);
                 var q = conn.prepareStatement("SELECT * FROM immune_memory ORDER BY last_seen DESC"); var rs = q.executeQuery()) {
                while (rs.next()) {
                    var e = new Entry(rs.getString("id"), rs.getString("kind"), rs.getString("subject"), rs.getString("reason"), rs.getString("source"),
                        Instant.ofEpochMilli(rs.getLong("first_seen")), Instant.ofEpochMilli(rs.getLong("last_seen")), rs.getLong("count"),
                        rs.getObject("expires_at") == null ? null : Instant.ofEpochMilli(rs.getLong("expires_at")));
                    if (!e.expired(now)) out.add(e);
                }
            } catch (SQLException ex) {
                log.warn("immune memory: not listed: {}", ex.getMessage());
            }
        }
        out.sort((a, b) -> b.lastSeen().compareTo(a.lastSeen()));
        return out;
    }

    /** The steward forgets one entry by id. */
    public synchronized boolean forget(String id) {
        if (inMemory != null) return inMemory.values().removeIf(e -> e.id().equals(id));
        try (var conn = DriverManager.getConnection(jdbcUrl); var d = conn.prepareStatement("DELETE FROM immune_memory WHERE id = ?")) {
            d.setString(1, id);
            return d.executeUpdate() > 0;
        } catch (SQLException ex) {
            log.warn("immune memory: not forgotten: {}", ex.getMessage());
            return false;
        }
    }

    /** Drop what has expired. Returns how many. */
    public synchronized int expire() {
        var now = Instant.now();
        if (inMemory != null) { int before = inMemory.size(); inMemory.values().removeIf(e -> e.expired(now)); return before - inMemory.size(); }
        try (var conn = DriverManager.getConnection(jdbcUrl); var d = conn.prepareStatement("DELETE FROM immune_memory WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            d.setLong(1, now.toEpochMilli());
            return d.executeUpdate();
        } catch (SQLException ex) {
            return 0;
        }
    }
}
