package org.wyrdsekai.core.coding;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The documented way to choose a backend has to choose it.
 *
 * <h2>What went wrong</h2>
 * {@code reference.conf} binds {@code WYRDSEKAI_CODING_DEFAULT_BACKEND} to
 * {@code wyrdsekai.coding.default-backend} (hyphen). {@link CodingBackendPreference} read
 * {@code wyrdsekai.coding.default_backend} (underscore). Two HOCON keys. Setting the env
 * var — the one documented knob, and the one the installer would expose — wrote a key
 * nobody consulted, and the chain stayed {@code [goose, pi]}. Found 2026-08-23 wiring
 * CodeZaiku onto the staging node: it had never been selectable from config at all.
 */
class TheChosenBackendIsActuallyChosenTest {

    @Test
    @DisplayName("the hyphenated key reference.conf binds the env var to is honoured")
    void theHyphenatedKeyIsRead() {
        var cfg = ConfigFactory.parseString(
            "wyrdsekai.coding.default-backend = codezaiku");
        assertThat(CodingBackendPreference.chain(cfg).getFirst()).isEqualTo("codezaiku");
    }

    @Test
    @DisplayName("the underscored key the code and docs name is still honoured")
    void theUnderscoredKeyIsRead() {
        var cfg = ConfigFactory.parseString(
            "wyrdsekai.coding.default_backend = codezaiku");
        assertThat(CodingBackendPreference.chain(cfg).getFirst()).isEqualTo("codezaiku");
    }

    @Test
    @DisplayName("the shipped default plus the env override resolves to the override")
    void theEnvOverrideWinsOverTheShippedDefault() {
        // Exactly what reference.conf does: a literal, then ${?ENV} on top of it.
        var cfg = ConfigFactory.parseString(
            "wyrdsekai.coding.default-backend = goose\n"
            + "wyrdsekai.coding.default-backend = codezaiku");
        assertThat(CodingBackendPreference.chain(cfg).getFirst()).isEqualTo("codezaiku");
    }

    @Test
    @DisplayName("with nothing chosen the built-in chain stands")
    void nothingChosenIsTheBuiltInChain() {
        assertThat(CodingBackendPreference.chain(ConfigFactory.empty()))
            .isEqualTo(CodingBackendPreference.BUILT_IN_CHAIN);
    }
}
