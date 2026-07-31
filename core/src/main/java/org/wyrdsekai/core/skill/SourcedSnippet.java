package org.wyrdsekai.core.skill;

import org.wyrdsekai.core.library.Provenance;

/**
 * a chunk of open-world content paired with the {@link Provenance.Source}
 * it came from, handed to an {@link AnchorMiner} as the only material it is permitted to ground
 * anchors in.
 *
 * <p>This is the retrieval unit: the Library / research-pack / web-acquire pipeline returns these
 * (text + source + tier), and the miner may mine an anchor ONLY from a snippet it was given. That
 * is the leakage barrier expressed as a data flow — the model never sees the eval, only sourced
 * evidence.</p>
 */
public record SourcedSnippet(
    String text,
    Provenance.Source source,
    Provenance.TrustTier trustTier
) {
    public SourcedSnippet {
        if (trustTier == null) trustTier = Provenance.TrustTier.UNKNOWN;
    }
}
