package org.wyrdsekai.core.external.q;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.CredentialResolver;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test helpers shared across Phase Q adapter tests. Provides a fake
 * CredentialResolver wiring + a recording {@link AbstractHttpAdapter.Transport}
 * so unit tests never hit the network.
 */
final class PhaseQTestSupport {

    private PhaseQTestSupport() {}

    /** Wire a static credential value for the given slot. */
    static void wireCred(String slot, String value) {
        CredentialResolver.get().setSafeReader(s -> s.equals(slot) ? Optional.of(value) : Optional.empty());
    }

    /** Wire no credentials at all. */
    static void wireNoCreds() {
        CredentialResolver.get().setSafeReader(s -> Optional.empty());
    }

    static AdapterRequest req(String namespace, String method, Map<String, Object> args) {
        return new AdapterRequest(namespace, method,
            args == null ? Map.of() : args,
            ItemCapabilitySet.UNRESTRICTED, "test_item");
    }

    /** Captures the last call so tests can assert URL + headers + body. */
    static final class Recorder implements AbstractHttpAdapter.Transport {
        final AtomicReference<String> method = new AtomicReference<>();
        final AtomicReference<String> url = new AtomicReference<>();
        final AtomicReference<Map<String, String>> headers = new AtomicReference<>();
        final AtomicReference<Object> body = new AtomicReference<>();
        AdapterResponse response = AdapterResponse.ok(Map.of("recorded", true));

        @Override
        public AdapterResponse send(String m, String u, Map<String, String> h, Object b) {
            method.set(m);
            url.set(u);
            headers.set(h);
            body.set(b);
            return response;
        }
    }
}
