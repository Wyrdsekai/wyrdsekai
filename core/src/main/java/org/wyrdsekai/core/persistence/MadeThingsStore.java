package org.wyrdsekai.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the house keeps about the rooms and items its companions make: who a thing was made for,
 * when anyone was last in it or used it, whether a person has been, and whether it is closed up or
 * put away.
 *
 * <p>Until 0.5.4 a made room had a maker's name and a date and nothing else. The household node held
 * 149 made rooms (49 of them from two companions in six days) and 90 crafted items; of 128 rooms
 * anyone had entered, 57 were entered on one day only; nothing recorded when, and only a steward
 * could take a room down. A room is never deleted by the house: it is <em>closed up</em> (its
 * doorways close, it leaves the map, its record and everything in it stay) and opened again by
 * going to it. An item is <em>put away</em> and can be taken out again.
 *
 * <p>{@code made_for}: {@code null} = made before this was recorded (nobody knows who asked);
 * {@link #OWN} = made on her own time; anything else = the person the turn that made it answered.
 */
public final class MadeThingsStore {

    private static final Logger log = LoggerFactory.getLogger(MadeThingsStore.class);
    public static final String OWN = "own";

    private static volatile MadeThingsStore SHARED;

    /** Register the node's store at boot. */
    public static void install(MadeThingsStore s) { SHARED = s; }
    /** The registered store, or {@code null} when this process has none (tests, relays). */
    public static MadeThingsStore shared() { return SHARED; }

    /** A made room as the house holds it. Times are epoch milliseconds; {@code null} = never / unknown. */
    public record RoomRecord(String roomId, String name, String zone, String createdBy, long createdAtMs,
                             String madeFor, Long lastEnteredAt, Long lastPersonAt, Long closedAt,
                             String closedExits, Integer baselineObjects, String closedSummary, Long coldAt) {
        public RoomRecord(String roomId, String name, String zone, String createdBy, long createdAtMs,
                          String madeFor, Long lastEnteredAt, Long lastPersonAt, Long closedAt,
                          String closedExits, Integer baselineObjects) {
            this(roomId, name, zone, createdBy, createdAtMs, madeFor, lastEnteredAt, lastPersonAt, closedAt,
                closedExits, baselineObjects, null, null);
        }
        public boolean closed() { return closedAt != null; }
        /** Closed up so long that it has gone to cold storage: kept, out of the everyday lists. */
        public boolean cold() { return closedAt != null && coldAt != null; }
        /** Made because a person asked for it. */
        public boolean askedFor() { return madeFor != null && !madeFor.isBlank() && !OWN.equals(madeFor); }
        /** A person has been in it since it was made. */
        public boolean aPersonHasBeenIn() { return lastPersonAt != null; }
    }

    /** A made item as the house holds it. */
    public record ItemRecord(String itemId, String kind, String madeBy, String madeFor, long madeAtMs,
                             Long lastUsedAt, Long lastPersonAt, Long putAwayAt, String name, String summary, Long coldAt) {
        public ItemRecord(String itemId, String kind, String madeBy, String madeFor, long madeAtMs,
                          Long lastUsedAt, Long lastPersonAt, Long putAwayAt) {
            this(itemId, kind, madeBy, madeFor, madeAtMs, lastUsedAt, lastPersonAt, putAwayAt, null, null, null);
        }
        public boolean putAway() { return putAwayAt != null; }
        public boolean cold() { return putAwayAt != null && coldAt != null; }
        /** The name it goes by: the one recorded, else the last part of its id. */
        public String shownName() {
            return name != null && !name.isBlank() ? name : itemId.substring(itemId.indexOf('/') + 1);
        }
        public boolean askedFor() { return madeFor != null && !madeFor.isBlank() && !OWN.equals(madeFor); }
        public boolean aPersonHasUsed() { return lastPersonAt != null; }
    }

    private final String jdbcUrl;

    public MadeThingsStore(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    // ── Schema (migration 20) ─────────────────────────────────────────────────────────

    /**
     * The columns on {@code rooms} and the {@code made_items} table. Rooms already there are given
     * "last entered: now": for a room made before the house kept this, the count of quiet days
     * starts at the upgrade, never before it.
     */
    public static void ensure(Connection conn) throws SQLException {
        for (var col : List.of(new String[]{"made_for", "TEXT"}, new String[]{"last_entered_at", "BIGINT"},
                new String[]{"last_person_at", "BIGINT"}, new String[]{"closed_at", "BIGINT"},
                new String[]{"closed_exits", "TEXT"}, new String[]{"baseline_objects", "INTEGER"})) {
            if (!hasColumn(conn, "rooms", col[0])) {
                try (var st = conn.createStatement()) {
                    st.execute("ALTER TABLE rooms ADD COLUMN " + col[0] + " " + col[1]);
                }
            }
        }
        try (var ps = conn.prepareStatement("UPDATE rooms SET last_entered_at = ? WHERE last_entered_at IS NULL")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS made_items ("
                + "item_id TEXT PRIMARY KEY, kind TEXT NOT NULL, made_by TEXT NOT NULL, made_for TEXT, "
                + "made_at BIGINT NOT NULL, last_used_at BIGINT, last_person_at BIGINT, put_away_at BIGINT)");
        }
    }

    /**
     * Migration 21: what makes the archive findable, and its second tier. A closed-up room keeps a
     * note of what it was and what was in it; an item keeps its name and description; and either can
     * go to cold storage after a long while in the archive.
     */
    public static void ensureArchive(Connection conn) throws SQLException {
        for (var col : List.of(new String[]{"rooms", "closed_summary", "TEXT"}, new String[]{"rooms", "cold_at", "BIGINT"},
                new String[]{"made_items", "name", "TEXT"}, new String[]{"made_items", "summary", "TEXT"},
                new String[]{"made_items", "cold_at", "BIGINT"})) {
            if (!hasColumn(conn, col[0], col[1])) {
                try (var st = conn.createStatement()) {
                    st.execute("ALTER TABLE " + col[0] + " ADD COLUMN " + col[1] + " " + col[2]);
                }
            }
        }
    }

    private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
        try (var rs = conn.getMetaData().getColumns(null, null, table, column)) {
            if (rs.next()) return true;
        }
        try (var rs = conn.getMetaData().getColumns(null, null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }

    // ── Rooms ─────────────────────────────────────────────────────────────────────────

    /**
     * Record who a newly made room was made for and what it held when made. The room's own row is
     * written by its actor a moment after it is created, so this writes the row if it is not there
     * yet; the actor's later registration then finds it and changes nothing.
     */
    public void stampRoomMade(String roomId, String name, String zone, String createdBy, String madeFor,
                              int baselineObjects) {
        long now = System.currentTimeMillis();
        var sql = "INSERT INTO rooms (room_id, name, zone, created_by, made_for, baseline_objects, last_entered_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (room_id) DO UPDATE SET made_for = excluded.made_for, "
            + "baseline_objects = excluded.baseline_objects, last_entered_at = excluded.last_entered_at";
        try (var conn = DriverManager.getConnection(jdbcUrl); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomId);
            ps.setString(2, name);
            ps.setString(3, zone);
            ps.setString(4, createdBy);
            ps.setString(5, madeFor);
            ps.setInt(6, baselineObjects);
            ps.setLong(7, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("made room not stamped ({}): {}", roomId, e.getMessage());
        }
    }

    /** Someone entered the room. A person's entry is kept separately: it holds the room. */
    public void roomEntered(String roomId, long atMs, boolean person) {
        var sql = person
            ? "UPDATE rooms SET last_entered_at = ?, last_person_at = ? WHERE room_id = ?"
            : "UPDATE rooms SET last_entered_at = ? WHERE room_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl); var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, atMs);
            if (person) { ps.setLong(2, atMs); ps.setString(3, roomId); } else { ps.setString(2, roomId); }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("room entry not recorded ({}): {}", roomId, e.getMessage());
        }
    }

    /**
     * A person says this room is theirs to keep: it is held from then on, as one they asked for.
     * The trail cannot know everything (a room she built later, on her own time, because someone had
     * asked days before reads as her own). True when a row was changed.
     */
    public boolean keepRoom(String roomId, String personId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("UPDATE rooms SET made_for = ? WHERE room_id = ?")) {
            ps.setString(1, personId == null || personId.isBlank() ? "steward" : personId);
            ps.setString(2, roomId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("room not kept ({}): {}", roomId, e.getMessage());
            return false;
        }
    }

    public void setBaseline(String roomId, int objects) {
        update("UPDATE rooms SET baseline_objects = ? WHERE room_id = ? AND baseline_objects IS NULL", objects, roomId);
    }

    public void roomClosed(String roomId, long atMs, String closedExits) {
        roomClosed(roomId, atMs, closedExits, null);
    }

    /** Closed up, with the note that makes it findable later: what it was and what was in it. */
    public void roomClosed(String roomId, long atMs, String closedExits, String summary) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "UPDATE rooms SET closed_at = ?, closed_exits = ?, closed_summary = ?, cold_at = NULL WHERE room_id = ?")) {
            ps.setLong(1, atMs);
            ps.setString(2, closedExits);
            ps.setString(3, summary);
            ps.setString(4, roomId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("room not marked closed ({}): {}", roomId, e.getMessage());
        }
    }

    public void roomOpened(String roomId, long atMs) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "UPDATE rooms SET closed_at = NULL, closed_exits = NULL, cold_at = NULL, last_entered_at = ? WHERE room_id = ?")) {
            ps.setLong(1, atMs);
            ps.setString(2, roomId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("room not marked open ({}): {}", roomId, e.getMessage());
        }
    }

    public List<RoomRecord> rooms() {
        var out = new ArrayList<RoomRecord>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT * FROM rooms ORDER BY name")) {
            while (rs.next()) out.add(room(rs));
        } catch (SQLException e) {
            log.warn("made rooms not listed: {}", e.getMessage());
        }
        return out;
    }

    public Optional<RoomRecord> room(String roomId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM rooms WHERE room_id = ?")) {
            ps.setString(1, roomId);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(room(rs));
            }
        } catch (SQLException e) {
            log.debug("made room not read ({}): {}", roomId, e.getMessage());
        }
        return Optional.empty();
    }

    /** Room ids that are closed up: the zone does not start them. */
    public java.util.Set<String> closedRoomIds() {
        var out = new java.util.HashSet<String>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT room_id FROM rooms WHERE closed_at IS NOT NULL")) {
            while (rs.next()) out.add(rs.getString(1));
        } catch (SQLException e) {
            log.debug("closed rooms not read: {}", e.getMessage());
        }
        return out;
    }

    private static RoomRecord room(ResultSet rs) throws SQLException {
        long createdSec = rs.getLong("created_at");
        return new RoomRecord(rs.getString("room_id"), rs.getString("name"), rs.getString("zone"),
            rs.getString("created_by"), createdSec * 1000L, rs.getString("made_for"),
            nullableLong(rs, "last_entered_at"), nullableLong(rs, "last_person_at"), nullableLong(rs, "closed_at"),
            rs.getString("closed_exits"), nullableInt(rs, "baseline_objects"), rs.getString("closed_summary"),
            nullableLong(rs, "cold_at"));
    }

    // ── Items ─────────────────────────────────────────────────────────────────────────

    public void itemMade(String itemId, String kind, String madeBy, String madeFor) {
        itemMade(itemId, kind, madeBy, madeFor, null, null);
    }

    /** A made item, with the name and description it can be found by once it is put away. */
    public void itemMade(String itemId, String kind, String madeBy, String madeFor, String name, String summary) {
        var sql = "INSERT INTO made_items (item_id, kind, made_by, made_for, made_at, last_used_at, name, summary) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (item_id) DO UPDATE SET put_away_at = NULL, cold_at = NULL, last_used_at = excluded.last_used_at, "
            + "name = excluded.name, summary = excluded.summary";
        long now = System.currentTimeMillis();
        try (var conn = DriverManager.getConnection(jdbcUrl); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            ps.setString(2, kind);
            ps.setString(3, madeBy);
            ps.setString(4, madeFor);
            ps.setLong(5, now);
            ps.setLong(6, now);
            ps.setString(7, name);
            ps.setString(8, summary);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("made item not recorded ({}): {}", itemId, e.getMessage());
        }
    }

    /**
     * An item made before the house kept this: recorded once, with nobody known to have asked, its
     * unused days counted from now. Does nothing when the item is already recorded.
     */
    public void enrollItem(String itemId, String kind, String madeBy) {
        enrollItem(itemId, kind, madeBy, null, null);
    }

    /** As above, with the name and description it can be found by; these are filled in if they were missing. */
    public void enrollItem(String itemId, String kind, String madeBy, String name, String summary) {
        long now = System.currentTimeMillis();
        var sql = "INSERT INTO made_items (item_id, kind, made_by, made_for, made_at, last_used_at, name, summary) "
            + "VALUES (?, ?, ?, NULL, ?, ?, ?, ?) "
            + "ON CONFLICT (item_id) DO UPDATE SET name = COALESCE(made_items.name, excluded.name), "
            + "summary = COALESCE(made_items.summary, excluded.summary)";
        try (var conn = DriverManager.getConnection(jdbcUrl); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, itemId);
            ps.setString(2, kind);
            ps.setString(3, madeBy);
            ps.setLong(4, now);
            ps.setLong(5, now);
            ps.setString(6, name);
            ps.setString(7, summary);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("made item not enrolled ({}): {}", itemId, e.getMessage());
        }
    }

    /** The item was used. Only items the house knows were made are tracked; others are not touched. */
    public void itemUsed(String itemId, long atMs, boolean person) {
        var sql = person
            ? "UPDATE made_items SET last_used_at = ?, last_person_at = ? WHERE item_id = ?"
            : "UPDATE made_items SET last_used_at = ? WHERE item_id = ?";
        try (var conn = DriverManager.getConnection(jdbcUrl); var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, atMs);
            if (person) { ps.setLong(2, atMs); ps.setString(3, itemId); } else { ps.setString(2, itemId); }
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("item use not recorded ({}): {}", itemId, e.getMessage());
        }
    }

    /** A closed-up room that has been in the archive past the setting goes to cold storage. */
    public void roomCold(String roomId, long atMs) {
        update("UPDATE rooms SET cold_at = ? WHERE room_id = ? AND closed_at IS NOT NULL", atMs, roomId);
    }

    /** The same for a put-away item. */
    public void itemCold(String itemId, long atMs) {
        update("UPDATE made_items SET cold_at = ? WHERE item_id = ? AND put_away_at IS NOT NULL", atMs, itemId);
    }

    public void itemPutAway(String itemId, long atMs) {
        update("UPDATE made_items SET put_away_at = ? WHERE item_id = ?", atMs, itemId);
    }

    public void itemTakenOut(String itemId, long atMs) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("UPDATE made_items SET put_away_at = NULL, cold_at = NULL, last_used_at = ? WHERE item_id = ?")) {
            ps.setLong(1, atMs);
            ps.setString(2, itemId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("item not marked out ({}): {}", itemId, e.getMessage());
        }
    }

    public List<ItemRecord> items() {
        var out = new ArrayList<ItemRecord>();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var st = conn.createStatement();
             var rs = st.executeQuery("SELECT * FROM made_items ORDER BY made_at")) {
            while (rs.next()) {
                out.add(new ItemRecord(rs.getString("item_id"), rs.getString("kind"), rs.getString("made_by"),
                    rs.getString("made_for"), rs.getLong("made_at"), nullableLong(rs, "last_used_at"),
                    nullableLong(rs, "last_person_at"), nullableLong(rs, "put_away_at"), rs.getString("name"),
                    rs.getString("summary"), nullableLong(rs, "cold_at")));
            }
        } catch (SQLException e) {
            log.warn("made items not listed: {}", e.getMessage());
        }
        return out;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────

    private void update(String sql, long value, String id) {
        try (var conn = DriverManager.getConnection(jdbcUrl); var ps = conn.prepareStatement(sql)) {
            ps.setLong(1, value);
            ps.setString(2, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.debug("made things update failed ({}): {}", id, e.getMessage());
        }
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
