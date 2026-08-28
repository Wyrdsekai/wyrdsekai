package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Set;

/**
 * Duolingo user progress.
 *
 * <p><b>Stub.</b> Duolingo has no public API. The brief explicitly calls
 * for a stub returning {@code not_yet_wired} until either Duolingo opens an
 * official API or a household-specific scraping integration ships. The
 * surface is captured so items can declare {@code duolingo.read} and have
 * a clean failure path now.</p>
 */
public final class DuolingoAdapter implements ExternalAdapter {
    /**
     * Scaffolding: this adapter declares a surface it does not yet reach. Advertising it
     * to an item author builds tools on vapor — see {@code ExternalAdapter#wiredCapabilities}.
     */
    @Override public Set<String> wiredCapabilities() { return Set.of(); }


    public static final String NAMESPACE = "duolingo";

    private final HttpAdapterSupport http;

    public DuolingoAdapter() { this(new HttpAdapterSupport()); }

    public DuolingoAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("user_progress", "list_courses", "progress");
    }

    @Override public String credentialSlot() { return "duolingo.token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "user_progress", "progress", "list_courses" ->
                http.notYetWired(NAMESPACE, "Duolingo has no public API as of 2026; "
                    + "method '" + request.method() + "' will return not_yet_wired "
                    + "until an official API ships or a household-specific scraping "
                    + "integration is registered.");
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }
}
