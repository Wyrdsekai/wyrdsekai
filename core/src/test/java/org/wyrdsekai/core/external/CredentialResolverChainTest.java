package org.wyrdsekai.core.external;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.room.TheSafe;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W13 — resolver precedence: TheSafe slot →
 * WYRDSEKAI_CRED_* env → system property. Exercises the exact chain Main
 * wires ({@link CredentialResolver#chainedReader}) with fake env/property
 * lookups and a real file-backed TheSafe.
 */
class CredentialResolverChainTest {

    @TempDir
    Path tempDir;

    private final Map<String, String> fakeEnv = new HashMap<>();
    private final Map<String, String> fakeProps = new HashMap<>();

    @AfterEach
    void tearDown() {
        TheSafe.resetLocalForTests();
        CredentialResolver.get().resetForTests();
    }

    private Function<String, Optional<String>> chain(TheSafe safe) {
        return CredentialResolver.chainedReader(
            safe::readSlot, fakeEnv::get, fakeProps::get);
    }

    @Test
    void safeSlotWinsOverEnvAndProperty() {
        var safe = TheSafe.initLocal(tempDir.resolve("credentials.safe"),
            "seed".getBytes(StandardCharsets.UTF_8));
        safe.storeSlot("github.token", "from-safe");
        fakeEnv.put("WYRDSEKAI_CRED_GITHUB_TOKEN", "from-env");
        fakeProps.put("wyrdsekai.cred.github.token", "from-prop");

        assertEquals(Optional.of("from-safe"), chain(safe).apply("github.token"));
    }

    @Test
    void envWinsWhenSafeSlotEmpty() {
        var safe = TheSafe.initLocal(tempDir.resolve("credentials.safe"), null);
        fakeEnv.put("WYRDSEKAI_CRED_GOOGLEMAPS_API_KEY", "from-env");
        fakeProps.put("wyrdsekai.cred.googlemaps.api_key", "from-prop");

        assertEquals(Optional.of("from-env"), chain(safe).apply("googlemaps.api_key"));
    }

    @Test
    void propertyIsLastResort() {
        var safe = TheSafe.initLocal(tempDir.resolve("credentials.safe"), null);
        fakeProps.put("wyrdsekai.cred.weather.key", "from-prop");

        assertEquals(Optional.of("from-prop"), chain(safe).apply("weather.key"));
        assertEquals(Optional.empty(), chain(safe).apply("unpopulated.slot"));
        assertEquals(Optional.empty(), chain(safe).apply(null));
        assertEquals(Optional.empty(), chain(safe).apply("  "));
    }

    @Test
    void throwingSafeReaderDegradesToEnv() {
        fakeEnv.put("WYRDSEKAI_CRED_FLAKY_SLOT", "from-env");
        var reader = CredentialResolver.chainedReader(
            slot -> { throw new IllegalStateException("safe offline"); },
            fakeEnv::get, fakeProps::get);

        assertEquals(Optional.of("from-env"), reader.apply("flaky.slot"));
    }

    @Test
    void resolverEndToEndThroughSetSafeReader() {
        var safe = TheSafe.initLocal(tempDir.resolve("credentials.safe"),
            "seed".getBytes(StandardCharsets.UTF_8));
        safe.storeSlot("a2a.key", "resolved-value");
        CredentialResolver.get().setSafeReader(chain(safe));

        assertEquals(Optional.of("resolved-value"),
            CredentialResolver.get().resolve("a2a.key"));
        assertEquals(Optional.empty(), CredentialResolver.get().resolve("missing.slot"));
    }
}
