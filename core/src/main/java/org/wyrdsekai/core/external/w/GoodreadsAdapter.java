package org.wyrdsekai.core.external.w;

import org.wyrdsekai.core.external.AdapterRequest;
import org.wyrdsekai.core.external.AdapterResponse;
import org.wyrdsekai.core.external.ExternalAdapter;

import java.util.Set;

/**
 * Goodreads.
 *
 * <p><b>Stub.</b> Goodreads killed its public API in late 2020 and any
 * scraping wrapper would be fragile + ToS-violating. The brief explicitly
 * calls for {@code not_yet_wired} returns. Goodreads users are migrated
 * via {@code world.goodreads.import_csv} (a one-shot CSV importer that
 * lives in §4.46 but is not part of the realtime adapter surface).</p>
 */
public final class GoodreadsAdapter implements ExternalAdapter {

    public static final String NAMESPACE = "goodreads";

    private final HttpAdapterSupport http;

    public GoodreadsAdapter() { this(new HttpAdapterSupport()); }

    public GoodreadsAdapter(HttpAdapterSupport http) { this.http = http; }

    @Override public String namespace() { return NAMESPACE; }

    @Override public Set<String> capabilities() {
        return Set.of("search", "book_info", "list_reviews");
    }

    @Override public String credentialSlot() { return "goodreads.api_key"; }

    @Override
    public AdapterResponse invoke(AdapterRequest request) {
        return switch (request.method()) {
            case "search", "book_info", "list_reviews" ->
                http.notYetWired(NAMESPACE, "Goodreads killed its public API in 2020; "
                    + "method '" + request.method() + "' will return not_yet_wired. "
                    + "Use world.goodreads.import_csv for one-shot migration to "
                    + "StoryGraph or local Calibre.");
            default -> http.unknownMethod(NAMESPACE, request.method());
        };
    }
}
