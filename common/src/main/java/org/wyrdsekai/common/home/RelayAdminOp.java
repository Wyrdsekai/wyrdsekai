package org.wyrdsekai.common.home;

import java.util.Locale;

/**
 * Relay admin operations and the {@link RelayAdminScope} each requires
 * ( operations / §6 scope mapping). This is the
 * operation→scope map that the {@code RelayGovernance} predicate consults; the
 * P3 signed admin API resolves each incoming {@code op} string to one of these.
 *
 * <p>Mapping (operation → minimum required scope):
 * <ul>
 *   <li>{@code invite} → {@link RelayAdminScope#INVITE_ONLY}+</li>
 *   <li>{@code list}/{@code remove}/{@code promote}/{@code demote}/{@code vouch}/
 *       {@code report-queue}/{@code resolve-report} → {@link RelayAdminScope#MODERATION}+</li>
 *   <li>{@code set-mode}/{@code set-policy}/{@code grant-admin}/{@code revoke-admin}/
 *       {@code audit} → {@link RelayAdminScope#FULL}</li>
 * </ul>
 *
 * <p><b>{@code report} (file a report) is special.</b>
 * Its enum {@code requiredScope()} is {@link RelayAdminScope#MODERATION} only to
 * keep a single, stable op→scope vocabulary that the Python relay's
 * {@code _OP_REQUIRED_SCOPE} mirrors. But <em>filing</em> a report is open to
 * <em>any registered DID</em> — a user reporting a node — exactly like
 * {@code deregister}: the Ed25519 signature proves identity and no relay-admin
 * grant is required. The relay enforces this via its {@code _OPEN_TO_ANY_SIGNER}
 * exemption (the signature is the whole bar); zone-side, {@code report} is filed
 * directly (not through the grant-scope {@code RelayGovernance.authorize} gate),
 * while {@code report-queue}/{@code resolve-report} stay moderator-gated. See
 * {@link #isOpenToAnySigner()}.</p>
 */
public enum RelayAdminOp {

    INVITE("invite", RelayAdminScope.INVITE_ONLY),

    LIST("list", RelayAdminScope.MODERATION),
    REMOVE("remove", RelayAdminScope.MODERATION),
    PROMOTE("promote", RelayAdminScope.MODERATION),
    DEMOTE("demote", RelayAdminScope.MODERATION),
    VOUCH("vouch", RelayAdminScope.MODERATION),
    REPORT_QUEUE("report-queue", RelayAdminScope.MODERATION),
    RESOLVE_REPORT("resolve-report", RelayAdminScope.MODERATION),
    /** File an abuse report — open to any valid signer; see {@link #isOpenToAnySigner()}. */
    REPORT("report", RelayAdminScope.MODERATION),

    SET_MODE("set-mode", RelayAdminScope.FULL),
    SET_POLICY("set-policy", RelayAdminScope.FULL),
    GRANT_ADMIN("grant-admin", RelayAdminScope.FULL),
    REVOKE_ADMIN("revoke-admin", RelayAdminScope.FULL),
    AUDIT("audit", RelayAdminScope.FULL);

    private final String wire;
    private final RelayAdminScope requiredScope;

    RelayAdminOp(String wire, RelayAdminScope requiredScope) {
        this.wire = wire;
        this.requiredScope = requiredScope;
    }

    /** The on-the-wire op name (e.g. {@code "set-mode"}). */
    public String wire() { return wire; }

    /** The minimum scope a caller must hold to perform this operation. */
    public RelayAdminScope requiredScope() { return requiredScope; }

    /**
     * True if this op is open to any valid signer —
     * authorized by a verified signature alone, no relay-admin grant required.
     * Only {@link #REPORT} (filing an abuse report) qualifies; viewing
     * ({@link #REPORT_QUEUE}) and resolving ({@link #RESOLVE_REPORT}) stay
     * moderator-gated. Mirrors the relay's {@code _OPEN_TO_ANY_SIGNER} set.
     */
    public boolean isOpenToAnySigner() { return this == REPORT; }

    /** Parse an op name; case-insensitive, tolerates underscores for hyphens. Returns {@code null} on miss. */
    public static RelayAdminOp parse(String name) {
        if (name == null) return null;
        var n = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (var op : values()) {
            if (op.wire.equals(n)) return op;
        }
        return null;
    }
}
