package org.wyrdsekai.between.discovery;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /: directory-authority
 * pubkeys baked into the wyrd binary at build time.
 *
 * <p>Why baked at build, not loaded at runtime: the public-relay trust
 * chain anchors at "wyrd binary trusts these authorities." If the
 * authority list were runtime-configurable from a file or env var,
 * an attacker who could write that file could swap the authorities
 * for their own — defeating the entire chain. Hardcoding the
 * authorities (or generating them at compile time from a signed
 * source list) means the trust root flows from "did you get a real
 * wyrd binary?" — which is a different question, addressed by F2.2
 * item 4 (binary signing) when binaries flow through public
 * distribution channels.
 *
 * <p><b>Today (2026-04-27).</b> The list is empty. Wyrdsekai is
 * private-mesh / hand-distributed; there are no production directory
 * authorities yet. Households use {@link RelayConsensus#singleAuthority}
 * with a self-signed dev key in test setups, or skip the consensus path
 * entirely and use the F2.1 invite-URL flow (TOFU with embedded
 * fingerprint).
 *
 * <p><b>When the first public authority quorum stands up:</b>
 * <ol>
 *   <li>Generate Ed25519 keypair on hardware-backed key storage
 *       (yubikey / hsm).</li>
 *   <li>Add the base64-encoded public key to {@link #PUBKEYS} below
 *       (or, preferably, to a generated resource — see
 * for the build-time generation pattern).</li>
 *   <li>Cut a release. Sign the binaries (item 4).</li>
 *   <li>Document the authority's identity + operator + custody.</li>
 * </ol>
 *
 * <p><b>Runtime override.</b> {@link #effective} respects
 * {@code WYRDSEKAI_AUTHORITY_KEYS} (comma-separated base64) for
 * dev/test setups. The override <i>extends</i> the baked list rather
 * than replacing it — so a malicious env mutation can add an attacker
 * key but can't remove the legitimate ones. (For pure dev where you
 * want only your single dev key, set {@code WYRDSEKAI_AUTHORITY_KEYS_REPLACE=true}
 * — explicit opt-out, not the default.)
 */
public final class BuiltinAuthorities {

    private BuiltinAuthorities() {}

    /**
     * Hardcoded production directory-authority pubkeys (Ed25519, base64).
     *
     * <p>Empty until the first public authority quorum is stood up —
     * see class javadoc for the procedure. Until then,
     * {@link #effective()} falls back to the runtime override or
     * returns an empty list (callers should detect and disable the
     * authority-quorum path, falling back to the F2.1 invite flow).
     */
    public static final List<String> PUBKEYS = List.of(
        // Add production authority pubkeys here, one per line.
        // Example (DO NOT use — placeholder format):
        // "MCowBQYDK2VwAyEAabcdef..."  // authority-1, operator: <name>, generated: <date>
    );

    /** Required-vote threshold for a 2-of-3 (or N-of-M) quorum. */
    public static final int REQUIRED_VOTES_DEFAULT = 2;

    /**
     * Effective authority list = baked + runtime override.
     * Returns an immutable view.
     */
    public static List<String> effective() {
        var override = System.getenv("WYRDSEKAI_AUTHORITY_KEYS");
        boolean replace = "true".equalsIgnoreCase(
            System.getenv("WYRDSEKAI_AUTHORITY_KEYS_REPLACE"));
        if (override == null || override.isBlank()) {
            return PUBKEYS;
        }
        var extra = Arrays.stream(override.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (replace) return List.copyOf(extra);
        var combined = new ArrayList<String>(PUBKEYS.size() + extra.size());
        combined.addAll(PUBKEYS);
        combined.addAll(extra);
        return List.copyOf(combined);
    }

    /**
     * Build an {@link RelayConsensus.AuthorityConfig} from the effective
     * key list. Threshold is {@code min(REQUIRED_VOTES_DEFAULT, size)}
     * so a single-authority dev setup degrades to 1-of-1 instead of
     * silently failing.
     */
    public static RelayConsensus.AuthorityConfig defaultConfig() {
        var keys = effective();
        if (keys.isEmpty()) {
            // No quorum available — caller must fall back to invite flow.
            // We still return an empty-but-valid-shape config so callers
            // can call .isValid() and decide.
            return new RelayConsensus.AuthorityConfig(List.of(), 0);
        }
        int threshold = Math.min(REQUIRED_VOTES_DEFAULT, keys.size());
        return new RelayConsensus.AuthorityConfig(keys, threshold);
    }
}
