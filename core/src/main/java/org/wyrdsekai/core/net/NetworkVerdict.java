package org.wyrdsekai.core.net;

/**
 * the result of a {@link NetworkGate} check.
 *
 * <p>A denial is a normal in-world outcome the agent narrates, NOT an error to
 * swallow (cf. the classifier-swallow lesson). When allowed via an allowlist
 * entry, {@link #entry()} carries the {@code keyRef} the capability resolves to
 * a credential; when allowed by a permissive default (http), {@code entry} is
 * null.</p>
 *
 * @param allowed whether the call may proceed
 * @param reason  short machine/human reason ({@code allow:default},
 *                {@code allow:allowlist}, {@code deny:not-allowlisted},
 *                {@code deny:scheme}, {@code deny:command-prefix})
 * @param entry   the matched allowlist entry (credential handle), or null
 */
public record NetworkVerdict(boolean allowed, String reason, NetworkAllowEntry entry) {

    public static NetworkVerdict allowDefault() {
        return new NetworkVerdict(true, "allow:default", null);
    }

    public static NetworkVerdict allowFrom(NetworkAllowEntry entry) {
        return new NetworkVerdict(true, "allow:allowlist", entry);
    }

    public static NetworkVerdict deny(String reason) {
        return new NetworkVerdict(false, reason, null);
    }
}
