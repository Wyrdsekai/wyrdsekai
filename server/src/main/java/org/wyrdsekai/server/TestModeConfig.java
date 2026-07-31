package org.wyrdsekai.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test mode configuration for E2E testing.
 *
 * When enabled (WYRDSEKAI_TEST_MODE=true), the server:
 * - Auto-creates a test user (e2e_test / testpass123)
 * - Generates a known household key for pairing bypass
 * - Disables rate limiting
 * - Logs a large warning on startup
 *
 * NEVER enable in production.
 */
public final class TestModeConfig {

    private static final Logger log = LoggerFactory.getLogger(TestModeConfig.class);

    private static final boolean ENABLED = Boolean.parseBoolean(
        System.getenv().getOrDefault("WYRDSEKAI_TEST_MODE",
            System.getProperty("wyrdsekai.test-mode", "false")));

    private TestModeConfig() {}

    public static boolean isTestMode() {
        return ENABLED;
    }

    public static String testUsername() { return "e2e_test"; }
    public static String testPassword() { return "testpass123"; }
    public static String testDisplayName() { return "E2E Tester"; }

    public static void logWarningIfEnabled() {
        if (!ENABLED) return;
        log.warn("╔══════════════════════════════════════════╗");
        log.warn("║       ⚠  TEST MODE ACTIVE  ⚠            ║");
        log.warn("║   DO NOT USE IN PRODUCTION               ║");
        log.warn("║   Rate limiting disabled                  ║");
        log.warn("║   Test user auto-provisioned              ║");
        log.warn("╚══════════════════════════════════════════╝");
    }
}
