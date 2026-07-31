package org.wyrdsekai.core.nostr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Chain multiple {@link NostrAdapter.SeedResolver}s. Returns the first
 * non-null seed; falls through to the next on null. Returns null if every
 * resolver returns null.
 *
 * <p>Standard wiring for Phase 2c:
 * <pre>{@code
 * var resolver = CompositeSeedResolver.of(
 *     new CompanionSeedResolver(soulStore, householdSecret),  // per-companion
 *     did -> nodeDid.equals(did) ? nodeSeed.clone() : null     // node fallback
 * );
 * NostrAdapterBootstrap.setSeedResolver(resolver);
 * }</pre>
 *
 * <p>The order matters — earlier resolvers win. Put higher-specificity
 * resolvers (e.g. companion-specific) first, generic fallbacks (e.g. node
 * identity) last.
 *
 * <p>Note: the Safe-override path
 * ({@code nostr.keypairs.<did>}) is handled separately inside
 * {@link NostrAdapter#resolveKeyForDid} and always takes precedence over
 * this chain. The chain is only consulted when there's no Safe override.
 */
public final class CompositeSeedResolver implements NostrAdapter.SeedResolver {

    private final List<NostrAdapter.SeedResolver> resolvers;

    public CompositeSeedResolver(List<NostrAdapter.SeedResolver> resolvers) {
        // Filter null entries — List.copyOf rejects them and the doc-stated
        // contract is "skip null resolvers in chain."
        var filtered = new ArrayList<NostrAdapter.SeedResolver>(resolvers.size());
        for (var r : resolvers) if (r != null) filtered.add(r);
        this.resolvers = List.copyOf(filtered);
    }

    public static CompositeSeedResolver of(NostrAdapter.SeedResolver... resolvers) {
        return new CompositeSeedResolver(Arrays.asList(resolvers));
    }

    @Override public byte[] seedForDid(String did) {
        for (var r : resolvers) {
            if (r == null) continue;
            try {
                var seed = r.seedForDid(did);
                if (seed != null && seed.length == 32) return seed;
            } catch (Exception e) {
                // Any single resolver failing is non-fatal — fall through.
                // We deliberately don't log here because resolvers may fail
                // for benign reasons (DID not in their domain).
            }
        }
        return null;
    }

    public int size() { return resolvers.size(); }
}
