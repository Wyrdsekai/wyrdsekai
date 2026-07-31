package org.wyrdsekai.core.app.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.app.family.FamilyHubService.*;
import org.wyrdsekai.core.persistence.SchemaInitializer;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FamilyPersistenceTest {

    private FamilyPersistence persistence;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db");
        var jdbcUrl = SchemaInitializer.initialize(dbPath);
        persistence = new FamilyPersistence(jdbcUrl);
    }

    // --- Calendar Events ---

    @Test void save_and_load_event() {
        var event = new CalendarEvent("e-1", "Dentist", "Checkup",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700003600),
            "alice", Set.of("alice", "bob"), false, EventType.APPOINTMENT);
        persistence.saveEvent(event);

        var loaded = persistence.loadEvent("e-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().title()).isEqualTo("Dentist");
        assertThat(loaded.get().description()).isEqualTo("Checkup");
        assertThat(loaded.get().createdBy()).isEqualTo("alice");
        assertThat(loaded.get().participants()).containsExactlyInAnyOrder("alice", "bob");
        assertThat(loaded.get().type()).isEqualTo(EventType.APPOINTMENT);
    }

    @Test void event_not_found() {
        assertThat(persistence.loadEvent("ghost")).isEmpty();
    }

    @Test void events_for_date() {
        // Use a specific day: 2023-11-15
        var dayStart = Instant.ofEpochSecond(1700006400); // 2023-11-15 00:00 UTC
        var dayMid = Instant.ofEpochSecond(1700049600);   // 2023-11-15 12:00 UTC
        var nextDay = Instant.ofEpochSecond(1700092800);   // 2023-11-16 00:00 UTC

        persistence.saveEvent(new CalendarEvent("e-1", "Morning", "",
            dayStart, dayMid, "alice", Set.of(), false, EventType.CUSTOM));
        persistence.saveEvent(new CalendarEvent("e-2", "Afternoon", "",
            dayMid, nextDay, "alice", Set.of(), false, EventType.CUSTOM));
        persistence.saveEvent(new CalendarEvent("e-3", "Tomorrow", "",
            nextDay, Instant.ofEpochSecond(nextDay.getEpochSecond() + 3600),
            "alice", Set.of(), false, EventType.CUSTOM));

        // eventsForDate uses LocalDate → system timezone boundaries
        // Just test that the query works and returns events
        var date = LocalDate.of(2023, 11, 15);
        var events = persistence.eventsForDate(date);
        // Number depends on timezone, but should be >= 1
        assertThat(events).isNotEmpty();
    }

    @Test void event_count() {
        assertThat(persistence.eventCount()).isEqualTo(0);
        persistence.saveEvent(new CalendarEvent("e-1", "Test", "",
            Instant.ofEpochSecond(1700000000), null, "alice",
            Set.of(), false, EventType.CUSTOM));
        assertThat(persistence.eventCount()).isEqualTo(1);
    }

    @Test void event_upsert() {
        persistence.saveEvent(new CalendarEvent("e-1", "Old Title", "",
            Instant.ofEpochSecond(1700000000), null, "alice",
            Set.of(), false, EventType.CUSTOM));
        persistence.saveEvent(new CalendarEvent("e-1", "New Title", "Updated",
            Instant.ofEpochSecond(1700000000), Instant.ofEpochSecond(1700003600),
            "alice", Set.of(), false, EventType.APPOINTMENT));

        var loaded = persistence.loadEvent("e-1");
        assertThat(loaded.get().title()).isEqualTo("New Title");
        assertThat(persistence.eventCount()).isEqualTo(1);
    }

    // --- Chores ---

    @Test void save_and_query_chores() {
        persistence.saveChore(new Chore("c-1", "Dishes", "alice",
            ChoreStatus.PENDING, Instant.ofEpochSecond(1700000000), null, 5));
        persistence.saveChore(new Chore("c-2", "Laundry", "bob",
            ChoreStatus.PENDING, Instant.ofEpochSecond(1700001000), null, 10));
        persistence.saveChore(new Chore("c-3", "Vacuum", "alice",
            ChoreStatus.COMPLETED, Instant.ofEpochSecond(1700002000),
            Instant.ofEpochSecond(1700001500), 8));

        var aliceChores = persistence.choresForAssignee("alice");
        assertThat(aliceChores).hasSize(1); // Only non-COMPLETED
        assertThat(aliceChores.get(0).title()).isEqualTo("Dishes");

        var pending = persistence.pendingChores();
        assertThat(pending).hasSize(2);
    }

    @Test void chore_status_update() {
        persistence.saveChore(new Chore("c-1", "Dishes", "alice",
            ChoreStatus.PENDING, Instant.ofEpochSecond(1700000000), null, 5));
        persistence.saveChore(new Chore("c-1", "Dishes", "alice",
            ChoreStatus.COMPLETED, Instant.ofEpochSecond(1700000000),
            Instant.ofEpochSecond(1700001000), 5));

        // Should not appear in pending
        assertThat(persistence.pendingChores()).isEmpty();
    }

    // --- Notices ---

    @Test void save_and_list_notices() {
        persistence.saveNotice(new Notice("n-1", "Welcome", "Hello family!",
            "alice", Instant.ofEpochSecond(1700000000), NoticePriority.NORMAL, false));
        persistence.saveNotice(new Notice("n-2", "Important", "Read this!",
            "bob", Instant.ofEpochSecond(1700001000), NoticePriority.HIGH, true));

        var notices = persistence.allNotices();
        assertThat(notices).hasSize(2);
        // Pinned first
        assertThat(notices.get(0).title()).isEqualTo("Important");
        assertThat(notices.get(0).pinned()).isTrue();
    }

    @Test void notice_upsert() {
        persistence.saveNotice(new Notice("n-1", "Old", "old content",
            "alice", Instant.ofEpochSecond(1700000000), NoticePriority.LOW, false));
        persistence.saveNotice(new Notice("n-1", "New", "new content",
            "alice", Instant.ofEpochSecond(1700000000), NoticePriority.HIGH, true));

        var notices = persistence.allNotices();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).title()).isEqualTo("New");
        assertThat(notices.get(0).priority()).isEqualTo(NoticePriority.HIGH);
    }
}
