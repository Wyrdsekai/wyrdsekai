package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code wyrd coding chain} must report what THIS node will do.
 *
 * <h2>Why the command exists, and why it needed this test</h2>
 * Choosing a coding backend has failed silently once already: until
 * 2026-08-23 {@code reference.conf} bound {@code WYRDSEKAI_CODING_DEFAULT_BACKEND}
 * to the HOCON key {@code default-backend} while {@link CodingBackendPreference}
 * read only {@code default_backend} — two different keys, so the documented
 * setting wrote something nobody consulted and the chain stayed
 * {@code [goose, pi]}. {@code chain} was added so the setting is observable.
 *
 * <p>Its first version reported {@code [goose, pi]} on a node explicitly
 * configured for CodeZaiku, because {@link CodingBackendPreference#chain()}
 * with no argument reads a static that only a SERVER boot installs; in a CLI
 * process it is null and the call silently yields the built-in fallback. The
 * command written to catch a silently-ignored setting silently ignored the
 * setting. Hence a test that runs it against a configured value.</p>
 */
class TheChosenBackendIsVisibleTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("wyrdsekai.coding.default-backend");
        ConfigFactory.invalidateCaches();
    }

    private String runChain() {
        var buffer = new ByteArrayOutputStream();
        var stream = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        int code = new CodingCli(stream, stream).run(new String[]{"chain"});
        assertThat(code).as("chain reports; it never fails the caller").isZero();
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a configured backend appears, and appears first")
    void theConfiguredBackendIsReported() {
        System.setProperty("wyrdsekai.coding.default-backend", "codezaiku");
        ConfigFactory.invalidateCaches();

        var output = runChain();
        assertThat(output)
            .as("the node's actual choice — not the compiled-in fallback")
            .contains("codezaiku");
        assertThat(output.indexOf("codezaiku"))
            .as("and it is preferred over the fallbacks")
            .isLessThan(output.indexOf("goose"));
    }

    @Test
    @DisplayName("choosing one backend does not drop the fallbacks")
    void fallbacksSurvive() {
        System.setProperty("wyrdsekai.coding.default-backend", "codezaiku");
        ConfigFactory.invalidateCaches();

        assertThat(runChain())
            .as("a node whose first choice is unregistered still has somewhere to go")
            .contains("goose");
    }

    @Test
    @DisplayName("with nothing configured it says so honestly")
    void theBuiltInChainStillReports() {
        ConfigFactory.invalidateCaches();
        assertThat(runChain()).contains("goose");
    }
}
