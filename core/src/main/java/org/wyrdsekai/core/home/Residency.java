package org.wyrdsekai.core.home;

import java.time.Instant;

/**
 * A zone-local record of "this identity lives here as a member."
 *
 * <p>. Never replicated — granted only by deliberate action
 * of a zone's steward (invite redemption, admin {@code adduser}, birth
 * ritual, Isekai acceptance). Separates "who you are" (identity, portable)
 * from "where you live" (residency, local).</p>
 *
 * @param did           Identity of the resident ({@code did:wyrd:z6Mk…}) or
 *                      a legacy user-UUID during the §25.6 migration window.
 * @param zoneId        Zone label this residency applies to.
 * @param role          One of {@code member | steward | child | foreign-agent | guest}.
 * @param grantedAt     When residency was granted.
 * @param grantor       DID of the steward who granted, or
 *                      {@code "migration-v25.6"} for back-filled rows.
 * @param studyRoomId   The resident's Study roomId, or {@code null} if
 *                      StudyProvisioner hasn't run yet.
 */
public record Residency(
    String did,
    String zoneId,
    String role,
    Instant grantedAt,
    String grantor,
    String studyRoomId
) {
    public static final String ROLE_MEMBER = "member";
    public static final String ROLE_STEWARD = "steward";
    public static final String ROLE_CHILD = "child";
    public static final String ROLE_FOREIGN_AGENT = "foreign-agent";
    public static final String ROLE_GUEST = "guest";

    public static final String GRANTOR_MIGRATION = "migration-v25.6";

    public Residency {
        if (did == null || did.isBlank()) {
            throw new IllegalArgumentException("did required");
        }
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("zoneId required");
        }
        if (role == null || role.isBlank()) {
            role = ROLE_MEMBER;
        }
        if (grantedAt == null) grantedAt = Instant.now();
        if (grantor == null || grantor.isBlank()) {
            throw new IllegalArgumentException("grantor required");
        }
    }
}
