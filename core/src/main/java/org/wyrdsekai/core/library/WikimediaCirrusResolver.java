package org.wyrdsekai.core.library;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import org.wyrdsekai.common.model.AppVersion;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * resolves download URLs for Wikimedia CirrusSearch index
 * dumps at <b>install time</b>.
 *
 * <p>Wikimedia deprecated the old {@code other/cirrussearch/current/...} dumps (the registry's
 * former static simple-wikipedia URL went 404). The replacement at
 * {@code other/cirrus_search_index/} publishes <b>weekly dated directories that rotate out after
 * ~5 weeks</b>, so any static URL structurally rots. A pack whose registry entry carries
 * {@code "urlResolver": "wikimedia-cirrus"} + {@code "resolverArgs": {"index": "simplewiki_content"}}
 * resolves its shard URLs here when the steward actually installs it.</p>
 *
 * <p>Layout (HTML directory listings):
 * {@code <base>/<YYYYMMDD>/index_name%3D<index>/<index>-<YYYYMMDD>-<NNNNN>.json.bz2}</p>
 */
public final class WikimediaCirrusResolver {

    private static final Logger log = LoggerFactory.getLogger(WikimediaCirrusResolver.class);
    static final String DEFAULT_BASE = "https://dumps.wikimedia.org/other/cirrus_search_index/";

    private static final Pattern DATED_DIR = Pattern.compile("href=\"(\\d{8})/\"");
    private static final Pattern SHARD = Pattern.compile("href=\"([^\"]+\\.json\\.bz2)\"");

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private WikimediaCirrusResolver() {}

    /**
     * Resolve the shard URLs for one cirrus index (e.g. {@code simplewiki_content}) from the
     * latest published weekly dump.
     *
     * @throws IOException if the listing can't be fetched or the index has no shards
     */
    public static List<String> resolve(String index) throws IOException {
        return resolve(DEFAULT_BASE, index, WikimediaCirrusResolver::fetch);
    }

    /** Seam for tests: injectable listing fetcher. */
    interface Lister { String list(String url) throws IOException; }

    static List<String> resolve(String base, String index, Lister lister) throws IOException {
        String latest = latestDatedDir(lister.list(base));
        if (latest == null) {
            throw new IOException("No dated dump directories found at " + base);
        }
        String indexDir = base + latest + "/index_name%3D" + index + "/";
        List<String> shards = shardUrls(lister.list(indexDir), indexDir);
        if (shards.isEmpty()) {
            throw new IOException("No .json.bz2 shards found for index '" + index + "' at " + indexDir);
        }
        log.info("[Library] Resolved cirrus index '{}' → {} shard(s) from dump {}", index, shards.size(), latest);
        return shards;
    }

    /** Latest YYYYMMDD directory in a listing, or null. Pure — unit-testable. */
    static String latestDatedDir(String listingHtml) {
        String latest = null;
        var m = DATED_DIR.matcher(listingHtml);
        while (m.find()) {
            String d = m.group(1);
            if (latest == null || d.compareTo(latest) > 0) latest = d;
        }
        return latest;
    }

    /** All shard file URLs in an index-dir listing, sorted. Pure — unit-testable. */
    static List<String> shardUrls(String listingHtml, String indexDirUrl) {
        var urls = new ArrayList<String>();
        var m = SHARD.matcher(listingHtml);
        while (m.find()) {
            String name = m.group(1);
            if (!name.contains("/")) urls.add(indexDirUrl + name);
        }
        urls.sort(String::compareTo);
        return urls;
    }

    /**
     * Wikimedia enforces a User-Agent policy: requests with an absent or generic
     * UA (including the JDK default) are refused with <b>403</b>, which is what
     * broke the simple-wikipedia pack install on every boot — the listing fetch
     * sent no UA at all. Verified 2026-08-05: no UA → 403, {@code Java/25} → 403,
     * a descriptive UA → 200. Must identify the tool and give a contact URL.
     * See {@code WikipediaTrendingFeed}, which sets one and has always worked.
     */
    private static final String USER_AGENT =
        "wyrdsekai/" + AppVersion.get().version() + " (https://wyrdsekai.org; library pack fetch)";

    /** One identifying UA for the whole pack subsystem — the resolver had one
     *  and the DOWNLOADER didn't, so resolution succeeded and every download
     *  403'd (second-node, every boot since install; found 2026-08-30). */
    static String userAgent() {
        return USER_AGENT;
    }

    private static String fetch(String url) throws IOException {
        var request = HttpRequest.newBuilder().uri(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<String> resp = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " listing " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Listing fetch interrupted", e);
        }
    }
}
