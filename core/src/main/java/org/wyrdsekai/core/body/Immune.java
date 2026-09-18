package org.wyrdsekai.core.body;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.host.Principals;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * The tolerance rule, as one piece of code. Every adaptive action the body takes against a
 * limb passes through here: cutting a tool, shutting a door, holding a part in quarantine,
 * severing a part. The rule is that the immune system never acts on what is hers without a
 * person's say. An action against her own things is refused and turned into a proposal the
 * steward can read and carry out by hand; an action against a foreign thing is allowed and
 * remembered.
 *
 * <p>Autoimmunity is a failure this household has had: a repeat guard suppressed her own
 * answers, a decay fallback ate her memories, and on the first day of the hooks her coding
 * tool was killed twice for opening a file in her own home. That last one was patched where
 * it happened; this class is where the rule lives now, so it cannot be forgotten by the next
 * lever. "Hers" means: her own home and workspaces, the household's own parts, the host she
 * lives on.</p>
 */
public final class Immune {

    private static final Logger log = LoggerFactory.getLogger(Immune.class);

    /** What the body wants to do. */
    public enum Act { CUT, CLOSE, QUARANTINE, SEVER }

    /** Whether it may, and why not. A refusal has already been written to the steward as a proposal. */
    public record Verdict(boolean allowed, String because) {
        public static Verdict yes() { return new Verdict(true, ""); }
        public static Verdict no(String because) { return new Verdict(false, because); }
    }

    /** DIDs and ids that are the household's own: its companions, its members, its node. Installed by the server. */
    private static volatile Predicate<String> self = id -> false;
    private static volatile String dataRoot = "";
    /** uid to being slug, from the host's user table; replaced in tests. */
    private static volatile IntFunction<String> slugOf = Principals::slugOfUid;

    private Immune() {}

    public static void install(Predicate<String> selfIds, String dataDir) {
        self = selfIds == null ? id -> false : selfIds;
        dataRoot = dataDir == null ? "" : dataDir;
    }

    static void resetForTests() { self = id -> false; dataRoot = ""; slugOf = Principals::slugOfUid; }
    static void slugsForTests(IntFunction<String> f) { slugOf = f; }

    /** Is this source the household's own: the household itself, the system, the steward, or one of its people or beings? */
    public static boolean isSelf(String source) {
        if (source == null || source.isBlank()) return true;
        if (source.equals("household") || source.equals("system") || source.equals("steward")) return true;
        try { return self.test(source); } catch (RuntimeException e) { return false; }
    }

    /**
     * Is this path one of a being's own places: her home under {@code beings/<slug>/}? Answered
     * from the host's user table for the uid, so it holds for users another process made.
     */
    public static boolean isHerPath(int uid, String path) {
        if (path == null || dataRoot.isBlank()) return false;
        var slug = slugOf.apply(uid);
        return slug != null && path.startsWith(dataRoot + "/beings/" + slug + "/");
    }

    /** Is this host the one she lives on: loopback, link-local, any local, or one of this machine's own addresses. */
    public static boolean isSelfHost(String host) {
        if (host == null) return false;
        var h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.equals("::1") || h.equals("0.0.0.0") || h.equals("::")) return true;
        if (h.startsWith("127.") || h.startsWith("169.254.") || h.startsWith("fe80:")) return true;
        try {
            var addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress() || addr.isLinkLocalAddress()) return true;
            return NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The chokepoint. {@code target} is the part id, door id or being; {@code detail} is what
     * the action is about (a path for a cut, hosts for a close, the source for a quarantine);
     * {@code by} is who asks. A refusal writes a proposal to the steward and returns why.
     */
    public static Verdict consider(Act act, String target, String detail, String by) {
        String because = null;
        switch (act) {
            case CUT -> {
                int uid = -1;
                try { uid = Integer.parseInt(String.valueOf(target).replaceFirst("^uid:", "")); } catch (NumberFormatException ignored) { /* a did, not a uid */ }
                if (uid >= 0 && isHerPath(uid, detail)) because = "the file is in her own home; a hand of hers reaching for her own things is not an intruder";
            }
            case CLOSE -> {
                for (var h : String.valueOf(detail).split("[,\\s]+")) {
                    if (!h.isBlank() && isSelfHost(h)) { because = h + " is this host; shutting that door would cut her off from her own brains and her own pulse"; break; }
                }
            }
            case QUARANTINE -> {
                if (isSelf(detail)) because = "the part came from the household itself (" + (detail == null ? "unset" : detail) + "); what is hers is not held at the door";
            }
            case SEVER -> {
                if (!("steward".equals(by) || "the steward".equals(by))) because = "only a person may declare a part of her gone";
            }
        }
        if (because == null) {
            log.info("[immune] {} {} ({}) by {}: allowed", act.name().toLowerCase(Locale.ROOT), target, detail, by);
            return Verdict.yes();
        }
        log.warn("[immune] {} {} ({}) by {}: refused — {}", act.name().toLowerCase(Locale.ROOT), target, detail, by, because);
        var map = BodyMap.get();
        if (map != null) {
            try {
                map.mark("immune", act.name().toLowerCase(Locale.ROOT), "steward",
                    "The body wanted to " + act.name().toLowerCase(Locale.ROOT) + " " + target + " (" + detail + ", asked by " + by
                        + ") and did not, because " + because + ". If it should be done, the steward can do it by hand.",
                    "proposal");
            } catch (RuntimeException e) { /* the mark is a courtesy */ }
        }
        return Verdict.no(because);
    }

    /** An action was taken against something foreign: remember it, so it is known on sight next time. */
    public static void remember(String kind, String subject, String reason, String source) {
        var m = ImmuneMemory.get();
        if (m == null || subject == null || subject.isBlank()) return;
        try { m.remember(kind, subject, reason, source, null); }
        catch (RuntimeException e) { log.debug("immune memory not kept: {}", e.toString()); }
    }

    /** For callers that hold a list of hosts. */
    public static boolean anySelfHost(List<String> hosts) {
        for (var h : hosts) if (isSelfHost(h)) return true;
        return false;
    }

    public static Duration defaultTtl() { return ImmuneMemory.DEFAULT_TTL; }
}
