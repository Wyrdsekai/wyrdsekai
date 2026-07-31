package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Set;

/**
 * Apple HomeKit (deferred).
 *
 * <p>HomeKit access requires native macOS / iOS application context (the
 * HomeKit framework, MFi authorization, and a paired Apple ID). There is no
 * public REST surface a JVM-side adapter can call directly. This adapter
 * exists so item manifests can declare {@code apple_home.*} caps today and
 * have the install-prompt warn correctly; every method returns a structured
 * {@code not_yet_wired} response.</p>
 */
public final class AppleHomeAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "apple_home";

    private final HttpAdapterSupport http;

    public AppleHomeAdapter() { this(new HttpAdapterSupport()); }

    public AppleHomeAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("list_accessories", "get_state", "set_state");
    }

    @Override public String credentialSlot() { return "apple_home.bridge"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "list_accessories", "get_state", "set_state" ->
                http.notYetWired(NAMESPACE,
                    request.method() + " requires Apple HomeKit native bridge (macOS/iOS only)");
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }
}
