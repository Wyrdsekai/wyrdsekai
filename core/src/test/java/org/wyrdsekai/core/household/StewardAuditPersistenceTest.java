package org.wyrdsekai.core.household;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.persistence.SqlDialect;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The steward audit ledger must survive restarts — an audit log that forgets
 * on reboot is no audit log. A second {@link StewardAuditLog} instance over
 * the same file DB stands in for "restart the server".
 */
class StewardAuditPersistenceTest {

    private static String fileJdbc(Path dir) {
        return "jdbc:sqlite:" + dir.resolve("audit-test.db");
    }

    @Test
    void events_survive_a_restart(@TempDir Path dir) {
        var url = fileJdbc(dir);
        var dialect = SqlDialect.fromJdbcUrl(url);

        var before = new StewardAuditLog(url, dialect);
        before.log("did:steward:1", "operator", StewardAuditLog.ActionType.MEMBER_ADD,
            "did:steward:1", "operator joined as steward", true);
        before.log("did:steward:1", "operator", StewardAuditLog.ActionType.MEMBER_PROMOTE,
            "did:member:2", "promoted mia to steward", true);

        // "Restart": a brand-new instance, same DB file, no shared memory.
        var after = new StewardAuditLog(url, dialect);
        var recent = after.recent(10);

        assertThat(recent).hasSize(2);
        // Newest first.
        assertThat(recent.get(0).type())
            .isEqualTo(StewardAuditLog.ActionType.MEMBER_PROMOTE);
        assertThat(recent.get(1).description()).contains("joined as steward");
        assertThat(recent.get(1).actorName()).isEqualTo("operator");
    }

    @Test
    void filters_and_denied_query_the_store(@TempDir Path dir) {
        var url = fileJdbc(dir);
        var dialect = SqlDialect.fromJdbcUrl(url);
        var audit = new StewardAuditLog(url, dialect);

        audit.log("did:a", "Alice", StewardAuditLog.ActionType.MEMBER_ADD, "t1", "add bob", true);
        audit.log("did:b", "Bob", StewardAuditLog.ActionType.MEMBER_REMOVE, "t2", "remove eve", false);
        audit.log("did:a", "Alice", StewardAuditLog.ActionType.BUDGET_CHANGE, "t3", "raise cap", true);

        assertThat(audit.byActor("did:a", 10)).hasSize(2);
        assertThat(audit.byTarget("t2", 10)).hasSize(1);
        var denied = audit.denied(10);
        assertThat(denied).hasSize(1);
        assertThat(denied.get(0).approved()).isFalse();
        assertThat(audit.entryCount()).isEqualTo(3);
    }

    @Test
    void in_memory_constructor_still_works() {
        var audit = new StewardAuditLog(); // legacy no-arg path
        audit.log("did:x", "X", StewardAuditLog.ActionType.MEMBER_ADD, "t", "d", true);
        assertThat(audit.recent(5)).hasSize(1);
    }
}
