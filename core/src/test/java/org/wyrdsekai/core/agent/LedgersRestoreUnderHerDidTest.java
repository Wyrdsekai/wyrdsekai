package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-bondholder ledgers are written under her DID at interaction time and were read back
 * at spawn, before the soul bound, by entity id: "Restored saudade ledger" never appeared in a
 * household log, every restart began the longing ledger empty, the felt saudade kept whatever
 * the restart restored with no path down, and the next write of the empty ledger deleted her
 * rows (rose, second-node, 2026-09-25). The restore now runs again the moment the DID is stamped.
 */
class LedgersRestoreUnderHerDidTest {

    private static String src() throws Exception {
        return Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
    }

    @Test
    @DisplayName("the ledgers are restored again right after the DID is stamped on the profile")
    void restoredAfterTheDidIsKnown() throws Exception {
        var s = src();
        int stamp = s.indexOf("this.profile = profile.withDid(did);");
        assertThat(stamp).isGreaterThan(0);
        assertThat(s.substring(stamp, stamp + 300)).contains("restoreLedgers(did);");
    }

    @Test
    @DisplayName("the spawn-time restore goes through the same method, so a restore that found rows is not repeated")
    void oneMethodBothTimes() throws Exception {
        var s = src();
        assertThat(s).contains("String agentDid = profile.did() != null ? profile.did() : profile.entityId();\n                restoreLedgers(agentDid);");
        assertThat(s).contains("if (agentDid == null || agentDid.isBlank() || agentDid.equals(ledgersRestoredFor)) return;");
        assertThat(s).contains("if (found > 0) ledgersRestoredFor = agentDid;");
    }

    @Test
    @DisplayName("the felt saudade follows the ledger in both directions, with no condition on the ledger being non-empty")
    void feltFollowsTheLedger() throws Exception {
        var s = src();
        int i = s.indexOf("double ledgerSaudade = saudadeLedger.maxSaudade();");
        assertThat(i).isGreaterThan(0);
        var around = s.substring(Math.max(0, i - 400), i);
        assertThat(around).doesNotContain("if (!saudadeLedger.isEmpty())");
        assertThat(s.substring(i, i + 200)).contains("vitality = vitality.withSaudade(ledgerSaudade);");
    }
}
