package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Set;

/**
 * Kobo personal library.
 *
 * <p><b>Stub.</b> Per the spec, "Kobo Books is also not a direct adapter —
 * Kobo has no public API. The household pattern for Kobo is Calibre with
 * the KoboTouchExtended plugin (sync Kobo device → Calibre →
 * {@code world.calibre.*})." This adapter exists so items can declare
 * {@code kobo.read} and surface {@code not_yet_wired} cleanly until either
 * a public API ships or a Calibre-mediated bridge is wired.</p>
 */
public final class KoboBooksAdapter implements ExternalAdapter {
    /**
     * Scaffolding: this adapter declares a surface it does not yet reach. Advertising it
     * to an item author builds tools on vapor — see {@code ExternalAdapter#wiredCapabilities}.
     */
    @Override public Set<String> wiredCapabilities() { return Set.of(); }


    public static final String NAMESPACE = "kobo";

    private final HttpAdapterSupport http;

    public KoboBooksAdapter() { this(new HttpAdapterSupport()); }

    public KoboBooksAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("library_list", "recent_purchases");
    }

    @Override public String credentialSlot() { return "kobo.session"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "library_list", "recent_purchases" ->
                http.notYetWired(NAMESPACE, "Kobo has no public API. Use Calibre + the "
                    + "KoboTouchExtended plugin to sync the device into Calibre, then "
                    + "use world.calibre.* for library access.");
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }
}
