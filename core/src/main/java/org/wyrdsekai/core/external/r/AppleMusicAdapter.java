package org.wyrdsekai.core.external.r;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Set;

/**
 * Apple Music (deferred).
 *
 * <p>Apple Music requires a developer-provided JWT signed with an Apple-issued
 * private key plus a per-user MusicKit token issued by an Apple-side flow.
 * Until the MusicKit JWT signer pipeline is wired (Phase R+1), this adapter
 * exposes the surface so manifests can declare {@code apple_music.*} caps and
 * have install-prompt warn correctly; every method returns
 * {@code not_yet_wired}.</p>
 */
public final class AppleMusicAdapter implements ExternalAdapter {
    /**
     * Scaffolding: this adapter declares a surface it does not yet reach. Advertising it
     * to an item author builds tools on vapor — see {@code ExternalAdapter#wiredCapabilities}.
     */
    @Override public Set<String> wiredCapabilities() { return Set.of(); }


    public static final String NAMESPACE = "apple_music";

    private final HttpAdapterSupport http;

    public AppleMusicAdapter() { this(new HttpAdapterSupport()); }

    public AppleMusicAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "play", "queue", "library");
    }

    @Override public String credentialSlot() { return "apple.music_user_token"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search", "play", "queue", "library" ->
                http.notYetWired(NAMESPACE,
                    request.method() + " requires Apple MusicKit JWT signer (deferred to Phase R+1)");
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }
}
