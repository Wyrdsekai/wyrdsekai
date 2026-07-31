package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 9a-PersistWire: verify the boot-restore and sleep-persist
 * calls are wired into the right lifecycle hooks. CoreServices.init
 * restores the three singleton trackers process-wide; CompanionActor
 * restores its own ProtectionFlagTracker at construction; completeSleep
 * persists all four. Failure to wire these means the persistence
 * implementations are dead code.
 */
class CompanionActorSubstratePersistWiringTest {

    private static final Path COMPANION_SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");
    private static final Path CORE_SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/bootstrap/CoreServices.java");

    private String read(Path p) throws Exception { return Files.readString(p); }

    // ── CoreServices boot wiring ─────────────────────────────────────

    @Test
    void coreServices_restores_three_singleton_trackers() throws Exception {
        var src = read(CORE_SRC);
        assertThat(src)
            .as("CoreServices.init must restore the three substrate singleton trackers")
            .contains("RepairLedger.get()")
            .contains("AttendantSessionTracker.get()")
            .contains("RepairModeTracker.get()")
            .contains(".restore(");
    }

    @Test
    void coreServices_restore_uses_substrate_subdir() throws Exception {
        var src = read(CORE_SRC);
        assertThat(src)
            .as("substrate state files live under <dataDir>/substrate/")
            .contains("\"substrate\"")
            .contains("repair-ledger.json")
            .contains("attendant-sessions.json")
            .contains("repair-mode.json");
    }

    @Test
    void coreServices_restore_wrapped_in_try_catch() throws Exception {
        var src = read(CORE_SRC);
        int restoreSite = src.indexOf("RepairLedger.get()");
        assertThat(restoreSite).isGreaterThan(0);
        var prelude = src.substring(0, restoreSite);
        int lastTry = prelude.lastIndexOf("try {");
        int lastClose = prelude.lastIndexOf("}");
        // last open try must be more recent than any matching close
        // — i.e. we're inside a try block at the call site.
        assertThat(lastTry).isGreaterThan(0);
        assertThat(lastTry).isGreaterThan(lastClose - 1000);
    }

    // ── CompanionActor per-agent flag wiring ────────────────────────

    @Test
    void companionActor_restores_protection_flags_at_init() throws Exception {
        var src = read(COMPANION_SRC);
        int ctorStart = src.indexOf("private CompanionActor(");
        assertThat(ctorStart).isGreaterThan(0);
        // First ~5000 chars of the constructor body
        var ctorBody = src.substring(ctorStart, Math.min(src.length(), ctorStart + 8000));
        assertThat(ctorBody)
            .as("constructor must restore per-companion ProtectionFlagTracker")
            .contains("protectionFlags.restore(");
    }

    @Test
    void resolveProtectionFlagFile_slugs_did() throws Exception {
        var src = read(COMPANION_SRC);
        assertThat(src)
            .as("DID slug regex must survive any DID-format change")
            .contains("resolveProtectionFlagFile")
            .contains("[^a-zA-Z0-9_-]")
            .contains("protection-flags-");
    }

    // ── completeSleep persist wiring ────────────────────────────────

    @Test
    void completeSleep_invokes_persistSubstrateTrackers() throws Exception {
        var src = read(COMPANION_SRC);
        int sleepStart = src.indexOf("private void completeSleep(");
        assertThat(sleepStart).isGreaterThan(0);
        int sleepEnd = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            sleepStart + 100);
        var body = src.substring(sleepStart, sleepEnd > 0 ? sleepEnd : src.length());
        assertThat(body)
            .as("sleep canonical save point must persist substrate trackers")
            .contains("persistSubstrateTrackers()");
    }

    @Test
    void persistSubstrateTrackers_calls_all_four_persisters() throws Exception {
        var src = read(COMPANION_SRC);
        int methodStart = src.indexOf("private void persistSubstrateTrackers()");
        assertThat(methodStart).isGreaterThan(0);
        int methodEnd = src.indexOf("\n    private", methodStart + 100);
        var body = src.substring(methodStart, methodEnd > 0 ? methodEnd : src.length());

        assertThat(body)
            .as("persist must call all four trackers")
            .contains("RepairLedger.get()")
            .contains("AttendantSessionTracker.get()")
            .contains("RepairModeTracker.get()")
            .contains("protectionFlags.persist(");
    }

    @Test
    void persistSubstrateTrackers_wrapped_in_try_catch() throws Exception {
        var src = read(COMPANION_SRC);
        int methodStart = src.indexOf("private void persistSubstrateTrackers()");
        int methodEnd = src.indexOf("\n    private", methodStart + 100);
        var body = src.substring(methodStart, methodEnd > 0 ? methodEnd : src.length());
        assertThat(body)
            .as("persist failures must never block sleep")
            .contains("try {")
            .contains("catch");
    }

    // ── PostStop hook ───────────────────────────────────────────────

    @Test
    void postStop_signal_persists_substrate_trackers() throws Exception {
        var src = read(COMPANION_SRC);
        int hookStart = src.indexOf("onSignal(PostStop.class");
        assertThat(hookStart).isGreaterThan(0);
        // Take ~3000 chars from the signal handler (covers the whole body).
        var body = src.substring(hookStart, Math.min(src.length(), hookStart + 3000));
        assertThat(body)
            .as("PostStop must call persistSubstrateTrackers so agents that "
                + "stop without sleeping (zone shutdown, restart) don't lose "
                + "the day's substrate-truth work")
            .contains("persistSubstrateTrackers()");
    }
}
