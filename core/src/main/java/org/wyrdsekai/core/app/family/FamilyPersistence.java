package org.wyrdsekai.core.app.family;

import org.wyrdsekai.core.persistence.SqlDialect;

import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * JDBC persistence for FamilyHubService (§15).
 * Stores calendar events, chores, and notices.
 */
public class FamilyPersistence {

    private final String jdbcUrl;
    private final SqlDialect dialect;

    public FamilyPersistence(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        this.dialect = SqlDialect.fromJdbcUrl(jdbcUrl);
    }

    // --- Calendar Events ---

    public void saveEvent(FamilyHubService.CalendarEvent event) {
        var sql = dialect.upsert("calendar_events",
            "event_id, title, description, start_time, end_time, created_by, participants, recurring, event_type",
            "?, ?, ?, ?, ?, ?, ?, ?, ?",
            "event_id",
            "title = EXCLUDED.title, description = EXCLUDED.description, start_time = EXCLUDED.start_time, end_time = EXCLUDED.end_time");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, event.eventId());
            ps.setString(2, event.title());
            ps.setString(3, event.description());
            ps.setLong(4, event.startTime().getEpochSecond());
            ps.setLong(5, event.endTime() != null ? event.endTime().getEpochSecond() : 0);
            ps.setString(6, event.createdBy());
            ps.setString(7, String.join(",", event.participants()));
            ps.setInt(8, event.recurring() ? 1 : 0);
            ps.setString(9, event.type().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save event: " + event.eventId(), e);
        }
    }

    public Optional<FamilyHubService.CalendarEvent> loadEvent(String eventId) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT * FROM calendar_events WHERE event_id = ?")) {
            ps.setString(1, eventId);
            var rs = ps.executeQuery();
            if (rs.next()) return Optional.of(mapEvent(rs));
            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load event: " + eventId, e);
        }
    }

    public List<FamilyHubService.CalendarEvent> eventsForDate(LocalDate date) {
        var startOfDay = date.atStartOfDay(ZoneId.systemDefault()).toInstant();
        var endOfDay = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT * FROM calendar_events WHERE start_time >= ? AND start_time < ? ORDER BY start_time")) {
            ps.setLong(1, startOfDay.getEpochSecond());
            ps.setLong(2, endOfDay.getEpochSecond());
            return mapEvents(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query events for date: " + date, e);
        }
    }

    public int eventCount() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM calendar_events")) {
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count events", e);
        }
    }

    // --- Chores ---

    public void saveChore(FamilyHubService.Chore chore) {
        var sql = dialect.upsert("chores",
            "chore_id, title, assignee, status, due_date, completed_at, points",
            "?, ?, ?, ?, ?, ?, ?",
            "chore_id",
            "title = EXCLUDED.title, assignee = EXCLUDED.assignee, status = EXCLUDED.status, completed_at = EXCLUDED.completed_at");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, chore.choreId());
            ps.setString(2, chore.title());
            ps.setString(3, chore.assignee());
            ps.setString(4, chore.status().name());
            ps.setLong(5, chore.dueDate() != null ? chore.dueDate().getEpochSecond() : 0);
            ps.setLong(6, chore.completedAt() != null ? chore.completedAt().getEpochSecond() : 0);
            ps.setInt(7, chore.points());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save chore: " + chore.choreId(), e);
        }
    }

    public List<FamilyHubService.Chore> choresForAssignee(String assignee) {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT * FROM chores WHERE assignee = ? AND status != 'COMPLETED' ORDER BY due_date")) {
            ps.setString(1, assignee);
            return mapChores(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query chores for: " + assignee, e);
        }
    }

    public List<FamilyHubService.Chore> pendingChores() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT * FROM chores WHERE status IN ('PENDING', 'IN_PROGRESS', 'OVERDUE') ORDER BY due_date")) {
            return mapChores(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query pending chores", e);
        }
    }

    // --- Notices ---

    public void saveNotice(FamilyHubService.Notice notice) {
        var sql = dialect.upsert("notices",
            "notice_id, title, content, posted_by, posted_at, priority, pinned",
            "?, ?, ?, ?, ?, ?, ?",
            "notice_id",
            "title = EXCLUDED.title, content = EXCLUDED.content, priority = EXCLUDED.priority, pinned = EXCLUDED.pinned");
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, notice.noticeId());
            ps.setString(2, notice.title());
            ps.setString(3, notice.content());
            ps.setString(4, notice.postedBy());
            ps.setLong(5, notice.postedAt().getEpochSecond());
            ps.setString(6, notice.priority().name());
            ps.setInt(7, notice.pinned() ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save notice: " + notice.noticeId(), e);
        }
    }

    public List<FamilyHubService.Notice> allNotices() {
        try (var conn = DriverManager.getConnection(jdbcUrl);
             var ps = conn.prepareStatement(
                 "SELECT * FROM notices ORDER BY pinned DESC, posted_at DESC")) {
            return mapNotices(ps.executeQuery());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to query notices", e);
        }
    }

    // --- Helpers ---

    private FamilyHubService.CalendarEvent mapEvent(ResultSet rs) throws SQLException {
        return new FamilyHubService.CalendarEvent(
            rs.getString("event_id"),
            rs.getString("title"),
            rs.getString("description"),
            Instant.ofEpochSecond(rs.getLong("start_time")),
            rs.getLong("end_time") > 0 ? Instant.ofEpochSecond(rs.getLong("end_time")) : null,
            rs.getString("created_by"),
            parseSet(rs.getString("participants")),
            rs.getInt("recurring") == 1,
            FamilyHubService.EventType.valueOf(rs.getString("event_type"))
        );
    }

    private List<FamilyHubService.CalendarEvent> mapEvents(ResultSet rs) throws SQLException {
        var events = new ArrayList<FamilyHubService.CalendarEvent>();
        while (rs.next()) events.add(mapEvent(rs));
        return events;
    }

    private List<FamilyHubService.Chore> mapChores(ResultSet rs) throws SQLException {
        var chores = new ArrayList<FamilyHubService.Chore>();
        while (rs.next()) {
            chores.add(new FamilyHubService.Chore(
                rs.getString("chore_id"),
                rs.getString("title"),
                rs.getString("assignee"),
                FamilyHubService.ChoreStatus.valueOf(rs.getString("status")),
                rs.getLong("due_date") > 0 ? Instant.ofEpochSecond(rs.getLong("due_date")) : null,
                rs.getLong("completed_at") > 0 ? Instant.ofEpochSecond(rs.getLong("completed_at")) : null,
                rs.getInt("points")
            ));
        }
        return chores;
    }

    private List<FamilyHubService.Notice> mapNotices(ResultSet rs) throws SQLException {
        var notices = new ArrayList<FamilyHubService.Notice>();
        while (rs.next()) {
            notices.add(new FamilyHubService.Notice(
                rs.getString("notice_id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("posted_by"),
                Instant.ofEpochSecond(rs.getLong("posted_at")),
                FamilyHubService.NoticePriority.valueOf(rs.getString("priority")),
                rs.getInt("pinned") == 1
            ));
        }
        return notices;
    }

    private static Set<String> parseSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return new HashSet<>(Arrays.asList(csv.split(",")));
    }
}
