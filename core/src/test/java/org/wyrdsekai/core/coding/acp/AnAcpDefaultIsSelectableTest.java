package org.wyrdsekai.core.coding.acp;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A backend that can be registered must also be selectable.
 *
 * <h2>What went wrong</h2>
 * 2026-08-24, first live attempt to run staging on ACP: AcpRuntimeConfig has
 * working defaults for every key, the bootstrap registers the backend — and
 * `default-backend = acp` boot-looped the server, because ConfigValidator
 * derives the selectable set from the DECLARED backends.* blocks and
 * reference.conf never declared one for acp.
 */
class AnAcpDefaultIsSelectableTest {

    @Test
    @DisplayName("reference.conf declares the backends.acp block the validator needs")
    void referenceConfDeclaresAcp() {
        var ref = ConfigFactory.defaultReference();
        assertThat(ref.hasPath("wyrdsekai.coding.backends.acp.enabled"))
            .as("backends.acp must be declared, or default-backend=acp is "
                + "rejected as unknown and the server cannot boot")
            .isTrue();
    }
}
