package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B integration coverage: verify that the scheduled chronicle
 * synthesis is wired into {@link CompanionActor#completeSleep} so a
 * DAY-scale chronicle snapshot is written on each sleep cycle.
 *
 * <p>Source-text checks for the wiring + atomic-write semantics. The
 * actual ChronicleService.build() logic has its own unit tests; this
 * test only confirms the runtime path invokes it during sleep.
 */
class CompanionActorChronicleSnapshotWiringTest {

    private static final Path SRC = Path.of(
        "src/main/java/org/wyrdsekai/core/agent/CompanionActor.java");

    private String sourceText() throws Exception {
        return Files.readString(SRC);
    }

    private String completeSleepBody(String src) {
        int sleepStart = src.indexOf("private void completeSleep(");
        int sleepEnd = src.indexOf("\n    private Behavior<Command> onRegisterRoomImprints",
            sleepStart + 100);
        return src.substring(sleepStart, sleepEnd > 0 ? sleepEnd : src.length());
    }

    @Test
    void completeSleep_calls_writeDailyChronicleSnapshot() throws Exception {
        var body = completeSleepBody(sourceText());
        assertThat(body)
            .as("completeSleep must invoke writeDailyChronicleSnapshot() — the "
                + "fresh-at-wake artifact stewards read without rebuilding")
            .contains("writeDailyChronicleSnapshot()");
    }

    @Test
    void chronicle_snapshot_wrapped_in_try_catch() throws Exception {
        var body = completeSleepBody(sourceText());
        int snapshotIdx = body.indexOf("writeDailyChronicleSnapshot()");
        assertThat(snapshotIdx).isGreaterThan(0);
        int windowStart = Math.max(0, snapshotIdx - 200);
        int windowEnd = Math.min(body.length(), snapshotIdx + 200);
        var window = body.substring(windowStart, windowEnd);
        assertThat(window)
            .as("snapshot write must be wrapped in try/catch so write failure "
                + "never blocks sleep completion")
            .contains("try {")
            .contains("catch (Exception");
    }

    @Test
    void writeDailyChronicleSnapshot_method_defined() throws Exception {
        var src = sourceText();
        assertThat(src.indexOf("private void writeDailyChronicleSnapshot()"))
            .as("writeDailyChronicleSnapshot method must be defined")
            .isGreaterThan(0);
    }

    @Test
    void snapshot_uses_chronicle_service_build() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void writeDailyChronicleSnapshot()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx, methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("synthesis must come from ChronicleService.build* — not "
                + "reimplemented or stubbed")
            .contains("ChronicleService(")
            // Accept build( or the 4B-voiced buildVoiced( variant (the testimony
            // path, SPEC chronicle-voicing) — both delegate synthesis to the service.
            .contains("service.build")
            .contains("Scale.DAY");
    }

    @Test
    void snapshot_delegates_to_chronicle_snapshot_writer() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void writeDailyChronicleSnapshot()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx, methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("atomic-write + JSON shape semantics are in ChronicleSnapshotWriter "
                + "(extracted to be testable without instantiating a full actor) — "
                + "CompanionActor delegates")
            .contains("ChronicleSnapshotWriter")
            .contains(".write(");
    }

    @Test
    void snapshot_passes_did_slug_to_writer() throws Exception {
        var src = sourceText();
        int methodIdx = src.indexOf("private void writeDailyChronicleSnapshot()");
        int methodEnd = src.indexOf("\n    private void handleSeekSanctuary",
            methodIdx);
        var methodBody = src.substring(methodIdx, methodEnd > 0 ? methodEnd : src.length());
        assertThat(methodBody)
            .as("DID slug protects against path-injection from DID format changes")
            .contains("didSlug")
            .contains("replaceAll");
    }

    @Test
    void snapshot_runs_after_auto_escalation() throws Exception {
        var body = completeSleepBody(sourceText());
        int escalateIdx = body.indexOf("autoEscalateIfWarranted()");
        int snapshotIdx = body.indexOf("writeDailyChronicleSnapshot()");
        assertThat(escalateIdx).isGreaterThan(0);
        assertThat(snapshotIdx).isGreaterThan(escalateIdx)
            .as("chronicle synthesis runs AFTER auto-escalation so any "
                + "AttendantSession opened by escalation appears in the "
                + "same-night snapshot");
    }
}
