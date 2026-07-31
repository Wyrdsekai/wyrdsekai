package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Set;

/**
 * Audible.
 *
 * <p><b>Stub.</b> Audible has no public API; the spec's recommended path
 * for self-hosted audiobook listening is {@code world.audiobookshelf.*}
 * (which lives in §4.46 and ships in a later wave). This adapter
 * captures the surface so items declaring {@code audible.read} resolve
 * to {@code not_yet_wired} cleanly until an Audible client is reverse-
 * engineered or an official partner API arrives.</p>
 */
public final class AudibleAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "audible";

    private final HttpAdapterSupport http;

    public AudibleAdapter() { this(new HttpAdapterSupport()); }

    public AudibleAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("library_list", "listening_history");
    }

    @Override public String credentialSlot() { return "audible.session"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "library_list", "listening_history" ->
                http.notYetWired(NAMESPACE, "Audible has no public API. Use "
                    + "world.audiobookshelf.* for self-hosted audio libraries, or "
                    + "world.spotify.* for paid audiobook streaming.");
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }
}
