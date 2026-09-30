package org.wyrdsekai.between;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ids for answers that come back over the relay (inference streams, recipe and zone-grant results).
 *
 * <p>The answer subject ends in the id ({@code federation.inference.stream.<id>},
 * {@code federation.recipe.result.<id>}, {@code federation.zonegrant.result.<id>}), and the relay lets a zone
 * read only answers under its own name. So the id starts with the asking zone: {@code <zone>.<uuid>}.</p>
 */
public final class RelayIds {

    /** A zone label the relay accepts (the same rule as deploy/relay/registration.py). */
    private static final Pattern ZONE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private RelayIds() {}

    /** {@code <zone>.<uuid>}, or a bare uuid when {@code zone} is not a plain label. */
    public static String scopedId(String zone) {
        var id = UUID.randomUUID().toString();
        return zone != null && ZONE_TOKEN.matcher(zone).matches() ? zone + "." + id : id;
    }
}
