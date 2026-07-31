package org.wyrdsekai.core.home;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.NotificationService;

/**
 * Pushes Home lifecycle events to the {@link NotificationService} so owners
 * and subjects learn about knocks, approvals, revocations, expiries, and
 * seals without polling their Board.
 *
 * <p>Priority mapping follows intuition: seal events are loud ("warning"),
 * approvals/denials are "normal", and passive expiries are "low". Events
 * are routed so the owner sees activity on their own Home and the subject
 * sees outcomes that affect them.</p>
 */
public final class NotificationHomeEventListener implements HomeEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationHomeEventListener.class);

    @Override
    public void onHomeEvent(Kind kind, String ownerDid, String actorDid,
                             String subjectDid, String resource, String detail) {
        var notifier = NotificationService.get();
        if (notifier == null) return;
        try {
            switch (kind) {
                case GRANT_REQUESTED -> {
                    // Owner gets: "X knocks on your Home: <reason>"
                    notifier.notify(ownerDid,
                        (subjectDid != null ? subjectDid : "someone")
                            + " knocks on your Home: " + safeDetail(detail)
                            + "\nTo respond: approve <id>  |  deny <id>",
                        "normal", subjectDid);
                }
                case GRANT_APPROVED -> {
                    // Subject gets: "Your request was approved."
                    if (subjectDid != null) {
                        notifier.notify(subjectDid,
                            "Your request on " + resource + " was approved"
                                + (detail != null ? " (" + detail + ")" : ""),
                            "normal", actorDid);
                    }
                }
                case GRANT_DENIED -> {
                    if (subjectDid != null) {
                        notifier.notify(subjectDid,
                            "Your request on " + resource + " was denied"
                                + (detail != null ? " (" + detail + ")" : ""),
                            "normal", actorDid);
                    }
                }
                case GRANT_ISSUED -> {
                    // Subject learns they now hold a grant.
                    if (subjectDid != null && !subjectDid.equals(ownerDid)) {
                        notifier.notify(subjectDid,
                            "You now hold " + safeDetail(detail) + " on " + resource,
                            "low", actorDid);
                    }
                }
                case GRANT_REVOKED -> {
                    if (subjectDid != null && !subjectDid.equals(ownerDid)) {
                        notifier.notify(subjectDid,
                            "Your grant on " + resource + " was revoked"
                                + (detail != null ? " (" + detail + ")" : ""),
                            "normal", actorDid);
                    }
                }
                case GRANT_EXPIRED -> {
                    // Owner + subject both informed — the grant is quietly gone.
                    notifier.notify(ownerDid,
                        "Grant on " + resource + " expired",
                        "low", null);
                    if (subjectDid != null && !subjectDid.equals(ownerDid)) {
                        notifier.notify(subjectDid,
                            "Your grant on " + resource + " expired",
                            "low", null);
                    }
                }
                case HOME_SEALED -> {
                    notifier.notify(ownerDid,
                        "Your Home is sealed"
                            + (detail != null ? ": " + detail : ""),
                        "warning", null);
                }
                case HOME_UNSEALED -> {
                    notifier.notify(ownerDid,
                        "Your Home is open again.",
                        "normal", null);
                }
            }
        } catch (Exception e) {
            log.debug("notification dispatch failed for {}: {}", kind, e.getMessage());
        }
    }

    private static String safeDetail(String d) {
        return d == null || d.isBlank() ? "(no details)" : d;
    }
}
