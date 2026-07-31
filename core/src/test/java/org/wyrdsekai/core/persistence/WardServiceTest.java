package org.wyrdsekai.core.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.test.TestDb;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class WardServiceTest {

    private WardService service;

    @BeforeEach void setUp() {
        var jdbcUrl = TestDb.createInMemory();
        service = new WardService(jdbcUrl);
    }

    @Test void open_room_allows_all() {
        // Room with no wards is open
        assertThat(service.isAllowed("open-room", "alice", "enter")).isTrue();
        assertThat(service.isAllowed("open-room", "alice", "speak")).isTrue();
        assertThat(service.isAllowed("open-room", "alice", "take")).isTrue();
    }

    @Test void grant_and_check() {
        service.grant("room1", "alice", "enter", "system");
        // Room now has wards — only explicit grants
        assertThat(service.isAllowed("room1", "alice", "enter")).isTrue();
        assertThat(service.isAllowed("room1", "alice", "speak")).isFalse();
        assertThat(service.isAllowed("room1", "bob", "enter")).isFalse();
    }

    @Test void look_always_allowed() {
        service.grant("room1", "alice", "enter", "system");
        // look is always allowed, even on warded rooms
        assertThat(service.isAllowed("room1", "bob", "look")).isTrue();
    }

    @Test void wildcard_grants_all_principals() {
        service.grant("room1", WardService.WILDCARD, "enter", "system");
        assertThat(service.isAllowed("room1", "alice", "enter")).isTrue();
        assertThat(service.isAllowed("room1", "bob", "enter")).isTrue();
    }

    @Test void admin_implies_all_permissions() {
        service.grant("room1", "alice", "admin", "system");
        assertThat(service.isAllowed("room1", "alice", "enter")).isTrue();
        assertThat(service.isAllowed("room1", "alice", "speak")).isTrue();
        assertThat(service.isAllowed("room1", "alice", "build")).isTrue();
    }

    @Test void revoke_removes_access() {
        service.grant("room1", "alice", "enter", "system");
        assertThat(service.isAllowed("room1", "alice", "enter")).isTrue();

        service.revoke("room1", "alice", "enter");
        // All wards removed → room is open again
        assertThat(service.isAllowed("room1", "alice", "enter")).isTrue();
    }

    @Test void revoke_with_remaining_wards() {
        service.grant("room1", "alice", "enter", "system");
        service.grant("room1", "alice", "speak", "system");
        service.revoke("room1", "alice", "enter");
        // Room still warded (speak grant remains), enter no longer granted
        assertThat(service.isAllowed("room1", "alice", "enter")).isFalse();
        assertThat(service.isAllowed("room1", "alice", "speak")).isTrue();
    }

    @Test void listWards() {
        service.grant("room1", "alice", "enter", "system");
        service.grant("room1", "bob", "speak", "admin");
        var wards = service.listWards("room1");
        assertThat(wards).hasSize(2);
    }

    @Test void grant_idempotent() {
        assertThat(service.grant("room1", "alice", "enter", "system")).isTrue();
        assertThat(service.grant("room1", "alice", "enter", "system")).isFalse();
        assertThat(service.listWards("room1")).hasSize(1);
    }

    @Test void seedFoundationWards() {
        service.seedFoundationWards("nexus");
        var wards = service.listWards("nexus");
        assertThat(wards).hasSizeGreaterThanOrEqualTo(4);
        assertThat(service.isAllowed("nexus", "anyone", "enter")).isTrue();
        assertThat(service.isAllowed("nexus", "anyone", "speak")).isTrue();
    }
}
