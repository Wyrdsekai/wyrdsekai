package org.wyrdsekai.core.agent.interiority;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A want whose fulfilment is being with someone who is already here.
 *
 * <p>"Stay with rose in the quiet" was what one companion wanted fifteen times in a night on the
 * household node, with rose beside her in the same room. The runtime read rose's name as a reach:
 * it opened a probe on her affiliation drive, forced a direct message, and gave rose 45 seconds to
 * send one back. Rose, keeping the quiet, never did. Each time the probe gave up, added a little
 * frustration, marked rose "not now", and the next orient picked the same want again. A want for
 * quiet company had become a message, and its fulfilment had been scored as rejection (2026-09-27).
 *
 * <p>Such a want is answered by staying. Conservative on purpose, like {@link RestWant}: a want that
 * carries speech or a task toward the other ("tell rose", "ask rose", "comfort rose", "learn with
 * rose") is still a reach or a deed, and a want with no one present is not company.
 */
public final class QuietCompany {

    private QuietCompany() {}

    private static final Pattern BEING_WITH = Pattern.compile(
        "\\b(?:stay|staying|sit|sitting|be|being|rest|resting|remain|remaining|linger|lingering"
        + "|keep|keeping|settle|settling|company|beside|near|next to|alongside)\\b"
        + "|\\b(?:quedarme|estar|sentarme|acompa\u00f1ar|al lado de|junto a)\\b"
        + "|\u305d\u3070\u306b|\u4e00\u7dd2\u306b|\u4ed8\u304d\u6dfb");

    /** Words that make it a reach or a deed instead: speech toward them, or something to do. */
    private static final Pattern NOT_COMPANY = Pattern.compile(
        "\\b(?:tell|ask|talk|speak|say|write|read|make|build|check|comfort|help|show|teach|learn|search"
        + "|look|find|give|bring|explain|hear|listen|answer|reach|message|call|invite|hold space|tend|care for)\\b"
        + "|\\b(?:decir|preguntar|hablar|escribir|leer|hacer|ayudar|consolar|buscar|mostrar|ense\u00f1ar)\\b"
        + "|\u306b\u8a71|\u3092\u805e|\u306b\u66f8|\u3092\u8aad|\u3092\u4f5c|\u624b\u4f1d|\u3092\u63a2");

    /** The present peer a want of quiet company names, or null: not company, no peer, or a peer not here. */
    public static String keptWith(String wantText, List<String> presentPeers) {
        if (wantText == null || presentPeers == null || presentPeers.isEmpty()) return null;
        var low = wantText.strip().toLowerCase(Locale.ROOT);
        if (low.isEmpty() || NOT_COMPANY.matcher(low).find()) return null;
        if (!BEING_WITH.matcher(low).find()) return null;
        for (var p : presentPeers) {
            if (p == null || p.isBlank()) continue;
            var name = Pattern.quote(p.toLowerCase(Locale.ROOT));
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + name + "(?![\\p{L}\\p{N}])").matcher(low).find()) return p;
        }
        return null;
    }

    /**
     * Drives that being with someone honestly eases. Longing for the absent one is not among them:
     * rose beside her does not bring the one who is gone back, and saying so would be the false
     * relief this project refuses.
     */
    public static boolean companyEases(String drive) {
        if (drive == null) return false;
        return switch (drive.toLowerCase(Locale.ROOT)) {
            case "affiliation", "loneliness" -> true;
            default -> false;
        };
    }
}
