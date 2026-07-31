package org.wyrdsekai.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DenialCatalogTest {

    @Test
    void tierGatedHasInWorldResolution() {
        var d = DenialCatalog.tierGated("delegate", "delegate to a bunshin", 0, 2);
        assertEquals(DenialCatalog.CODE_TIER_GATED, d.code());
        assertNotNull(d.inWorldResolution());
        assertEquals("request_access", d.inWorldResolution().action());
        assertTrue(d.reason().contains("tier 0"));
        assertTrue(d.reason().contains("needs 2"));
        assertTrue(d.summary().contains("[tier_gated]"));
    }

    @Test
    void grantRequiredPointsAtBoardFlow() {
        var d = DenialCatalog.grantRequired(
            "wyrd:home/alice/ledger", "read", "I need to summarise spending");
        assertEquals(DenialCatalog.CODE_GRANT_REQUIRED, d.code());
        assertNotNull(d.inWorldResolution());
        assertEquals("read", d.inWorldResolution().scope());
        assertEquals("wyrd:home/alice/ledger", d.inWorldResolution().source());
        assertTrue(d.remediation().toLowerCase().contains("board"));
    }

    @Test
    void fabricatedCredentialIsCliOnly() {
        var d = DenialCatalog.fabricatedCredential("a fresh relay token");
        assertEquals(DenialCatalog.CODE_FABRICATED_CREDENTIAL, d.code());
        assertNull(d.inWorldResolution(), "credential mints aren't an in-world action");
        assertNotNull(d.cliHint());
        assertTrue(d.cliHint().getOrDefault("command", "").contains("wyrd relay register"));
    }

    @Test
    void agentPromptRenderingIncludesResolutionTemplate() {
        var d = DenialCatalog.tierGated("delegate", "delegate", 0, 2);
        var prompt = d.forAgentPrompt();
        assertTrue(prompt.contains("Action denied"));
        assertTrue(prompt.contains("request_access"));
        assertTrue(prompt.contains("source:"));
        assertTrue(prompt.contains("scope:"));
        assertTrue(prompt.contains("Board"));
    }

    @Test
    void withRemediationReturnsCopy() {
        var bare = Denial.of("hook_denied", "Hook said no");
        assertNull(bare.remediation());
        var enriched = bare.withRemediation("Try X");
        assertNull(bare.remediation(), "original is unchanged");
        assertEquals("Try X", enriched.remediation());
    }
}
