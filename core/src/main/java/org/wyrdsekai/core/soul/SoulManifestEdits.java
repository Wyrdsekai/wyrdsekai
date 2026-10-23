package org.wyrdsekai.core.soul;

import org.wyrdsekai.core.identity.AgentIdentityProvisioner;

/**
 * An edit made to her manifest from outside the forge, about to be stored.
 *
 * <p>The signature covers the version number and her identity text, so every edit that bumps the
 * version makes the old signature wrong. The store re-signs when this node holds her key; a command
 * run without the key in hand (the soul CLI on a stopped node, 2026-10-22) used to store the edit
 * with the previous version's signature still attached, and her next start read her as tampered.
 * An edit this node cannot sign goes in unsigned: a flagged, honest state the next forge mends,
 * never a signature over bytes it was not made for.
 */
public final class SoulManifestEdits {

    private SoulManifestEdits() {}

    /** The edited manifest as it should be stored: as given when this node can sign it, else unsigned. */
    public static SoulManifest unsignedUnlessThisNodeCanSign(SoulManifest edited) {
        if (edited == null || !edited.isSigned()) return edited;
        if (edited.did() != null && AgentIdentityProvisioner.canSign(edited.did())) return edited;
        return edited.signed(new byte[0]);
    }

    /** Whether storing this edit will leave her unsigned until the next forge. */
    public static boolean willBeUnsigned(SoulManifest edited) {
        return edited != null && edited.isSigned()
            && (edited.did() == null || !AgentIdentityProvisioner.canSign(edited.did()));
    }
}
