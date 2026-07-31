package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Set;

/**
 * Coursera (a.k.a. "Coursa" in the brief)
 * course search and enrollment.
 *
 * <p><b>Stub.</b> Coursera's catalogue API is partner-only — public search
 * endpoints exist but require a partner agreement. The brief calls for a
 * stub returning {@code not_yet_wired} for both methods. The surface is
 * captured so items can declare {@code coursa.read} / {@code coursa.write}
 * and have a clean failure path now.</p>
 */
public final class CoursaAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "coursa";

    private final HttpAdapterSupport http;

    public CoursaAdapter() { this(new HttpAdapterSupport()); }

    public CoursaAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("course_search", "search", "enroll");
    }

    @Override public String credentialSlot() { return "coursa.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "course_search", "search", "enroll" ->
                http.notYetWired(NAMESPACE, "Coursera's public API is partner-only as "
                    + "of 2026; method '" + request.method() + "' will return "
                    + "not_yet_wired until a partner credential is configured.");
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }
}
