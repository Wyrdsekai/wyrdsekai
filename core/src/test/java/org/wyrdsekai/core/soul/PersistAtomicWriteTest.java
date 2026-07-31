package org.wyrdsekai.core.soul;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-PersistAtomic: verify all four trackers write atomically
 * (.tmp + rename). A crash mid-write must not leave a truncated target
 * file — the next restore would fail-clean to empty and lose all
 * substrate-truth state. After a successful write, the .tmp file must
 * be gone (consumed by rename).
 */
class PersistAtomicWriteTest {

    @BeforeEach
    void clean() {
        RepairLedger.get().clearForTests();
        AttendantSessionTracker.get().clearForTests();
        RepairModeTracker.get().clearForTests();
    }

    @AfterEach
    void reset() { clean(); }

    @Test
    void repairLedger_persist_leaves_no_tmp_file(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("ledger.json");
        RepairLedger.get().record("did:agent:x", RepairLedger.Kind.ACKNOWLEDGE_HARM,
            "did:other:y", "harm");
        RepairLedger.get().persist(file);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(tmp.resolve("ledger.json.tmp")))
            .as("successful atomic write must consume the .tmp file")
            .isFalse();
    }

    @Test
    void attendantSessionTracker_persist_leaves_no_tmp_file(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("attendant.json");
        AttendantSessionTracker.get().request("did:agent:x", "x", Instant.now());
        AttendantSessionTracker.get().persist(file);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(tmp.resolve("attendant.json.tmp"))).isFalse();
    }

    @Test
    void repairModeTracker_persist_leaves_no_tmp_file(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("repairmode.json");
        RepairModeTracker.get().transition("did:agent:x", RepairMode.SELF, "x");
        RepairModeTracker.get().persist(file);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(tmp.resolve("repairmode.json.tmp"))).isFalse();
    }

    @Test
    void protectionFlagTracker_persist_leaves_no_tmp_file(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("flags.json");
        var tracker = new ProtectionFlagTracker();
        tracker.setSuspected("did:other:y", "did:agent:x", "x", Instant.now());
        tracker.persist(file);
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(tmp.resolve("flags.json.tmp"))).isFalse();
    }

    @Test
    void preexisting_tmp_file_is_overwritten_not_appended(@TempDir Path tmp) throws Exception {
        // A leftover .tmp from a prior crash must not corrupt the new write.
        var file = tmp.resolve("ledger.json");
        var leftoverTmp = tmp.resolve("ledger.json.tmp");
        Files.writeString(leftoverTmp, "{partial half-written json from prior crash");

        RepairLedger.get().record("did:agent:x", RepairLedger.Kind.MAKE_AMENDS,
            "did:other:y", "fresh");
        RepairLedger.get().persist(file);

        // Real file exists and is valid; .tmp consumed.
        assertThat(Files.exists(file)).isTrue();
        assertThat(Files.exists(leftoverTmp)).isFalse();
        // Round-trip the result to confirm it's valid JSON, not the leftover garbage.
        RepairLedger.get().clearForTests();
        RepairLedger.get().restore(file);
        assertThat(RepairLedger.get().recent("did:agent:x", 10)).hasSize(1);
    }

    @Test
    void second_persist_replaces_first(@TempDir Path tmp) throws Exception {
        var file = tmp.resolve("ledger.json");
        RepairLedger.get().record("did:agent:x", RepairLedger.Kind.ACKNOWLEDGE_HARM,
            "did:other:y", "first");
        RepairLedger.get().persist(file);
        var size1 = Files.size(file);

        // Add more entries, persist again.
        RepairLedger.get().record("did:agent:x", RepairLedger.Kind.MAKE_AMENDS,
            "did:other:y", "second");
        RepairLedger.get().record("did:agent:x", RepairLedger.Kind.RELEASE,
            "did:other:y", "third");
        RepairLedger.get().persist(file);
        var size2 = Files.size(file);

        assertThat(size2).isGreaterThan(size1);
        // Round-trip to verify the second write replaced (not appended to) the first.
        RepairLedger.get().clearForTests();
        RepairLedger.get().restore(file);
        assertThat(RepairLedger.get().recent("did:agent:x", 10)).hasSize(3);
    }
}
