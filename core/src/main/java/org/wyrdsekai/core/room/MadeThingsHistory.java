package org.wyrdsekai.core.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.persistence.MadeThingsStore;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The history of the rooms made before the house kept one, read once from what the node already
 * holds: the companions' trail and the rooms' own journals.
 *
 * <p>A room made before 0.5.4 had a maker's name (since late September) and nothing else. Treating
 * "nobody recorded who asked" as "she made it on her own" would have closed up rooms a person had
 * asked for; leaving every older room alone would have left the clutter where it was. The record
 * does say: the trail has, beside each making, whether she was on her own time (a tick of her own
 * time just before) or answering someone (a line of hers addressed to a person); on the household
 * node that told 48 of 50 rooms as her own and 2 as made while talking with her person, none
 * unknown. For rooms older than the maker's name, the trail has her line saying she made it. And the
 * rooms' journals have every entry, a person's among them, so quiet days are counted from when
 * someone was really last there, not from the day of the upgrade.
 *
 * <p>What the record cannot tell stays unrecorded, and an unrecorded room is held: the house does
 * not close it and does not count it. Runs once; nothing of a companion's is read but her trail,
 * and nothing is written but the house's own list of rooms.
 */
public final class MadeThingsHistory {

    private static final Logger log = LoggerFactory.getLogger(MadeThingsHistory.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String MARK = "made_things_history";
    private static final long BEFORE_MS = 6 * 60_000L;
    private static final long ASKED_BEFORE_MS = 3 * 60_000L;
    private static final long AFTER_MS = 30_000L;
    private static final long MAKER_BEFORE_MS = 2 * 60_000L;
    private static final long MAKER_AFTER_MS = 4 * 60_000L;
    private static final Pattern MAKING = Pattern.compile("(?i)\\b(creat|made|make|making|built|build)");

    private MadeThingsHistory() {}

    public record Outcome(int rooms, int own, int asked, int unrecorded, int makersNamed, int entriesFound) {}

    private record Row(long at, String type, String room, String toId, String to, String text) {}
    private record Made(String roomId, String name, String createdBy, long createdAtMs) {}

    /** Whether this node has already read its history. */
    public static boolean done(String jdbcUrl) {
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            try (var st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS maintenance_state(key TEXT PRIMARY KEY, value TEXT)");
            }
            try (var ps = conn.prepareStatement("SELECT value FROM maintenance_state WHERE key = ?")) {
                ps.setString(1, MARK);
                try (var rs = ps.executeQuery()) { return rs.next(); }
            }
        } catch (SQLException e) {
            log.debug("made things history: mark not read: {}", e.getMessage());
            return false;
        }
    }

    /** Read the history once. A node that has done so returns {@code null}. */
    public static Outcome runOnce(String jdbcUrl, Path trail, Set<String> companions, UnaryOperator<String> canonicalPerson) {
        if (done(jdbcUrl)) return null;
        var outcome = run(jdbcUrl, trail, companions, canonicalPerson);
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("INSERT INTO maintenance_state(key, value) VALUES (?, ?) "
                 + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, MARK);
            ps.setString(2, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("made things history: mark not written: {}", e.getMessage());
        }
        return outcome;
    }

    static Outcome run(String jdbcUrl, Path trail, Set<String> companions, UnaryOperator<String> canonicalPerson) {
        var names = new HashMap<String, String>();   // lower → as written
        if (companions != null) for (var c : companions) if (c != null) names.put(c.toLowerCase(Locale.ROOT), c);
        var made = new ArrayList<Made>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT room_id, name, created_by, created_at FROM rooms "
                 + "WHERE zone IN ('companion-created', 'player-created') AND made_for IS NULL")) {
            while (rs.next()) {
                made.add(new Made(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4) * 1000L));
            }
        } catch (SQLException e) {
            log.warn("made things history: rooms not read: {}", e.getMessage());
            return new Outcome(0, 0, 0, 0, 0, 0);
        }
        var byAgent = readTrail(trail, names.keySet());
        var lastEntered = new HashMap<String, Long>();
        var lastPerson = new HashMap<String, Long>();
        var furnishedWith = new HashMap<String, Integer>();
        int entries = readJournal(jdbcUrl, lastEntered, lastPerson, furnishedWith);
        // The trail knows where each companion walked, and where a person was when she spoke to them.
        for (var rows : byAgent.values()) {
            for (var r : rows) {
                if (r.room() == null) continue;
                if ("move".equals(r.type())) lastEntered.merge(r.room(), r.at(), Math::max);
                if ("speak".equals(r.type()) && isPerson(r.toId(), r.to(), names.keySet())) {
                    lastPerson.merge(r.room(), r.at(), Math::max);
                }
            }
        }
        int own = 0, asked = 0, unrecorded = 0, named = 0;
        try (var conn = DriverManager.getConnection(jdbcUrl)) {
            for (var m : made) {
                String maker = m.createdBy() == null ? null : names.get(m.createdBy().toLowerCase(Locale.ROOT));
                if (maker == null) {
                    maker = makerFromTrail(m, byAgent, names);
                    if (maker != null) {
                        try (var ps = conn.prepareStatement("UPDATE rooms SET created_by = ? WHERE room_id = ?")) {
                            ps.setString(1, maker);
                            ps.setString(2, m.roomId());
                            ps.executeUpdate();
                        }
                        named++;
                    }
                }
                String madeFor = maker == null ? null
                    : madeFor(m, byAgent.getOrDefault(maker.toLowerCase(Locale.ROOT), List.of()), names.keySet(), canonicalPerson);
                if (madeFor == null) unrecorded++;
                else if (MadeThingsStore.OWN.equals(madeFor)) own++;
                else asked++;
                long entered = Math.max(m.createdAtMs(), lastEntered.getOrDefault(m.roomId(), 0L));
                Long person = lastPerson.get(m.roomId());
                // What it was furnished with when made (the objects of its first seconds), so that
                // "holds something she put there" can be told for an older room too.
                Integer furnished = furnishedWith.get(m.roomId());
                try (var ps = conn.prepareStatement(
                        "UPDATE rooms SET made_for = ?, last_entered_at = ?, last_person_at = ?, "
                        + "baseline_objects = COALESCE(baseline_objects, ?) WHERE room_id = ?")) {
                    ps.setString(1, madeFor);
                    ps.setLong(2, entered);
                    if (person == null) ps.setNull(3, java.sql.Types.BIGINT); else ps.setLong(3, person);
                    if (furnished == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, furnished);
                    ps.setString(5, m.roomId());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            log.warn("made things history: not written whole: {}", e.getMessage());
        }
        log.info("Made rooms, history read once: {} room(s) made before a record was kept — {} on her own time, "
            + "{} because a person asked, {} the record cannot tell (held); {} maker(s) named from the trail; "
            + "{} entries read from the rooms' journals", made.size(), own, asked, unrecorded, named, entries);
        return new Outcome(made.size(), own, asked, unrecorded, named, entries);
    }

    /** A person asked (her line just before was addressed to them), or it was her own time, or the record cannot say. */
    static String madeFor(Made m, List<Row> makerRows, Set<String> companionNames, UnaryOperator<String> canonicalPerson) {
        boolean ownTime = false;
        Row toPerson = null;
        for (var r : makerRows) {
            if (r.at() < m.createdAtMs() - BEFORE_MS || r.at() > m.createdAtMs() + AFTER_MS) continue;
            if ("tick".equals(r.type()) || "autonomy".equals(r.type())) ownTime = true;
            if ("speak".equals(r.type()) && r.at() >= m.createdAtMs() - ASKED_BEFORE_MS
                    && isPerson(r.toId(), r.to(), companionNames)) toPerson = r;
        }
        // A person in the conversation decides it: holding a room she made on her own costs clutter,
        // closing one a person asked for breaks the rule.
        if (toPerson != null) {
            var id = toPerson.toId() != null && !toPerson.toId().isBlank() ? toPerson.toId() : "person:" + toPerson.to();
            return canonicalPerson == null ? id : canonicalPerson.apply(id);
        }
        return ownTime ? MadeThingsStore.OWN : null;
    }

    /** The one companion whose line, around the making, names the room and says she made it. */
    static String makerFromTrail(Made m, Map<String, List<Row>> byAgent, Map<String, String> names) {
        if (m.name() == null || m.name().isBlank()) return null;
        var needle = m.name().toLowerCase(Locale.ROOT);
        String found = null;
        for (var e : byAgent.entrySet()) {
            for (var r : e.getValue()) {
                if (!"speak".equals(r.type()) || r.text() == null) continue;
                if (r.at() < m.createdAtMs() - MAKER_BEFORE_MS || r.at() > m.createdAtMs() + MAKER_AFTER_MS) continue;
                var text = r.text().toLowerCase(Locale.ROOT);
                if (text.contains(needle) && MAKING.matcher(text).find()) {
                    if (found != null && !found.equals(e.getKey())) return null;   // two of them: nobody is named
                    found = e.getKey();
                }
            }
        }
        return found == null ? null : names.get(found);
    }

    static boolean isPerson(String toId, String to, Set<String> companionNamesLower) {
        if (toId != null && !toId.isBlank()) {
            return !toId.startsWith("companion-") && !toId.startsWith("agent-");
        }
        return to != null && !to.isBlank() && !companionNamesLower.contains(to.toLowerCase(Locale.ROOT));
    }

    private static Map<String, List<Row>> readTrail(Path trail, Set<String> companionNamesLower) {
        var out = new HashMap<String, List<Row>>();
        if (trail == null || !Files.isRegularFile(trail)) return out;
        try (BufferedReader in = Files.newBufferedReader(trail, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!(line.contains("\"type\":\"speak\"") || line.contains("\"type\":\"move\"")
                        || line.contains("\"type\":\"tick\"") || line.contains("\"type\":\"autonomy\""))) continue;
                try {
                    JsonNode n = JSON.readTree(line);
                    var agent = n.path("agent").asText("").toLowerCase(Locale.ROOT);
                    if (!companionNamesLower.contains(agent)) continue;
                    var type = n.path("type").asText("");
                    long at = Instant.parse(n.path("ts").asText()).toEpochMilli();
                    var text = "speak".equals(type) ? n.path("text").asText("") : null;
                    if (text != null && text.length() > 400) text = text.substring(0, 400);
                    var room = "move".equals(type) ? text(n, "to") : text(n, "room");
                    out.computeIfAbsent(agent, k -> new ArrayList<>()).add(new Row(at, type, room,
                        "speak".equals(type) ? text(n, "toId") : null, "speak".equals(type) ? text(n, "to") : null, text));
                } catch (Exception ignored) {
                    // A line that does not parse is not history.
                }
            }
        } catch (Exception e) {
            log.warn("made things history: the trail was not read whole: {}", e.toString());
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        var v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    /** How long after a room's first event an object still counts as what it was furnished with. */
    private static final long FURNISHING_MS = 10_000L;

    private static int readJournal(String jdbcUrl, Map<String, Long> lastEntered, Map<String, Long> lastPerson,
                                   Map<String, Integer> furnishedWith) {
        int n = 0;
        var firstEvent = new HashMap<String, Long>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT event_payload FROM event_journal WHERE persistence_id LIKE 'Room|%' ORDER BY ordering")) {
            while (rs.next()) {
                try {
                    var bytes = rs.getBytes(1);
                    if (bytes == null) continue;
                    var ev = JSON.readTree(bytes).path("event");
                    var room = ev.path("roomId").asText(null);
                    if (room == null) continue;
                    long at = Instant.parse(ev.path("timestamp").asText()).toEpochMilli();
                    firstEvent.putIfAbsent(room, at);
                    var kind = ev.path("type").asText();
                    if ("object_added".equals(kind) && at - firstEvent.get(room) <= FURNISHING_MS) {
                        furnishedWith.merge(room, 1, Integer::sum);
                    }
                    if (!"entity_entered".equals(kind)) continue;
                    lastEntered.merge(room, at, Math::max);
                    var who = ev.path("entityType").asText("");
                    if ("player".equals(who) || "visitor".equals(who)) lastPerson.merge(room, at, Math::max);
                    n++;
                } catch (Exception ignored) {
                    // An event in another shape is not an entry.
                }
            }
        } catch (SQLException e) {
            log.debug("made things history: no room journals here: {}", e.getMessage());
        }
        return n;
    }
}
