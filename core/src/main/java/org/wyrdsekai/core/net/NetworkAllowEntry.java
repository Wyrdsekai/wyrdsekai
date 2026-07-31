package org.wyrdsekai.core.net;

import java.util.List;
import java.util.Set;

/**
 * one steward-configured allowlist entry.
 *
 * <p>Grants an agent (through a permission-fixed network item) reach to
 * {@code host} for the given {@code kinds}. Credentials never live here — the
 * {@code keyRef} is a HANDLE ({@code household:<nodeId>} or {@code chest:<slot>})
 * the {@link NetworkCapability} resolves at call time via an injected resolver.
 * A steward edits these from the Study's Scroll of Settings ({@code scroll net
 * allow …}), which validates the {@code keyRef} resolves before persisting.</p>
 *
 * @param host          hostname or wildcard pattern (e.g. {@code second-node},
 *                      {@code *.example.com})
 * @param kinds         which protocol kinds this entry grants — subset of
 *                      {@code {ssh, scp, http, https}}
 * @param keyRef        credential handle ({@code household:<nodeId>} /
 *                      {@code chest:<slot>}); may be null for http/https
 * @param schemes       for http/https: permitted URL schemes; null/empty = any
 * @param commandPrefix optional ssh command-family constraint (far-hand); null
 *                      = any command permitted on this host
 */
public record NetworkAllowEntry(
        String host,
        Set<String> kinds,
        String keyRef,
        List<String> schemes,
        String commandPrefix) {

    public NetworkAllowEntry {
        host = host == null ? "" : host.trim();
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        schemes = schemes == null ? List.of() : List.copyOf(schemes);
    }

    /** True if this entry grants {@code kind}. */
    public boolean grants(String kind) {
        return kind != null && kinds.contains(kind.toLowerCase());
    }

    /** True if this entry's host pattern matches {@code candidate}. */
    public boolean matchesHost(String candidate) {
        return NetworkGate.hostMatches(candidate, host);
    }

    /** True if this entry permits {@code scheme} (empty schemes = any). */
    public boolean permitsScheme(String scheme) {
        if (schemes.isEmpty()) return true;
        if (scheme == null) return false;
        return schemes.stream().anyMatch(s -> s.equalsIgnoreCase(scheme));
    }
}
