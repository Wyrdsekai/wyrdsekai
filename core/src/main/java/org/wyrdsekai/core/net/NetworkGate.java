package org.wyrdsekai.core.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * the zone-level network allowlist gate.
 *
 * <p>Every {@link NetworkCapability} call ({@code sshRun}, {@code scpTo/From},
 * and the item-facing {@code httpRequest}) checks here BEFORE any I/O. The
 * verdict is per-KIND:</p>
 *
 * <ul>
 *   <li><b>ssh / scp</b> — the credentialed, lateral-movement kinds — default
 *       <b>DENY</b>. An agent reaches such a host only via a steward allowlist
 *       entry that also binds the credential ({@code keyRef}). This is the
 *       "arbitrary-with-allowlist" posture: the mechanism supports any host, the
 *       policy starts closed.</li>
 *   <li><b>http / https</b> — default <b>ALLOW</b> (operator 2026-07-02). Outbound
 *       HTTP is already an agent capability via {@code web_search}; the gate sees
 *       it for metering + an optional steward RESTRICT hook (add an http entry to
 *       flip that host-set to allowlist-mode), but does not wall it by default.</li>
 * </ul>
 *
 * <p>Household-internal transfer (courier satchel) does not pass through this
 * gate — it rides the authenticated NATS household bus, whose trust boundary is
 * the roster, not a host allowlist.</p>
 */
public final class NetworkGate {

    private static final Logger log = LoggerFactory.getLogger(NetworkGate.class);

    public static final String ALLOWLIST_PATH = "wyrdsekai.net.allowlist";
    public static final String DEFAULT_PATH = "wyrdsekai.net.default";

    /** Per-kind default verdicts when no allowlist entry matches. */
    private static final Map<String, Boolean> BUILTIN_DEFAULTS = Map.of(
        "ssh", false,
        "scp", false,
        "http", true,
        "https", true);

    private final List<NetworkAllowEntry> allowlist;
    private final Map<String, Boolean> defaults;

    public NetworkGate(List<NetworkAllowEntry> allowlist, Map<String, Boolean> defaults) {
        this.allowlist = allowlist == null ? List.of() : List.copyOf(allowlist);
        var d = new HashMap<>(BUILTIN_DEFAULTS);
        if (defaults != null) d.putAll(defaults);
        this.defaults = Map.copyOf(d);
    }

    /** An empty gate — nothing allowlisted, builtin defaults (http open, ssh closed). */
    public static NetworkGate empty() {
        return new NetworkGate(List.of(), Map.of());
    }

    public List<NetworkAllowEntry> allowlist() {
        return allowlist;
    }

    public boolean defaultFor(String kind) {
        return defaults.getOrDefault(kind == null ? "" : kind.toLowerCase(), false);
    }

    /**
     * Check whether {@code kind} reach to {@code host} is permitted.
     *
     * @param kind   one of ssh/scp/http/https
     * @param host   destination host
     * @param scheme URL scheme for http/https checks (null for ssh/scp)
     */
    public NetworkVerdict check(String kind, String host, String scheme) {
        if (kind == null || kind.isBlank()) return NetworkVerdict.deny("deny:no-kind");
        var k = kind.toLowerCase();
        if (host == null || host.isBlank()) return NetworkVerdict.deny("deny:no-host");

        // A matching allowlist entry wins — carries the credential handle.
        for (var e : allowlist) {
            if (!e.grants(k) || !e.matchesHost(host)) continue;
            if (("http".equals(k) || "https".equals(k)) && !e.permitsScheme(scheme)) {
                return NetworkVerdict.deny("deny:scheme");
            }
            return NetworkVerdict.allowFrom(e);
        }

        // No entry. For http/https, an EXPLICIT allowlist for this kind means
        // the steward opted into restrict-mode → deny off-list. Otherwise the
        // permissive default applies (web stays open).
        if (("http".equals(k) || "https".equals(k)) && hasAnyEntryForKind(k)) {
            return NetworkVerdict.deny("deny:not-allowlisted");
        }
        return defaultFor(k)
            ? NetworkVerdict.allowDefault()
            : NetworkVerdict.deny("deny:not-allowlisted");
    }

    /** ssh command-family check for far-hand (allowlist entry may pin a prefix). */
    public NetworkVerdict checkSshCommand(String host, String command) {
        var v = check("ssh", host, null);
        if (!v.allowed()) return v;
        var entry = v.entry();
        if (entry != null && entry.commandPrefix() != null && !entry.commandPrefix().isBlank()) {
            if (command == null || !command.trim().startsWith(entry.commandPrefix())) {
                return NetworkVerdict.deny("deny:command-prefix");
            }
        }
        return v;
    }

    private boolean hasAnyEntryForKind(String kind) {
        return allowlist.stream().anyMatch(e -> e.grants(kind));
    }

    // ─── config parsing ───────────────────────────────────────────────

    /**
     * Build a gate from {@code wyrdsekai.net.allowlist} + {@code wyrdsekai.net.default}.
     * Absent config → {@link #empty()} (ssh/scp closed, http/https open).
     */
    public static NetworkGate fromConfig(Config config) {
        if (config == null) return empty();
        var entries = new ArrayList<NetworkAllowEntry>();
        try {
            if (config.hasPath(ALLOWLIST_PATH)) {
                for (var co : config.getConfigList(ALLOWLIST_PATH)) {
                    var host = co.hasPath("host") ? co.getString("host") : null;
                    if (host == null || host.isBlank()) continue;
                    var kinds = co.hasPath("kinds")
                        ? new LinkedHashSet<>(lower(co.getStringList("kinds")))
                        : Set.<String>of();
                    var keyRef = co.hasPath("key-ref") ? co.getString("key-ref")
                        : co.hasPath("key_ref") ? co.getString("key_ref") : null;
                    var schemes = co.hasPath("schemes") ? co.getStringList("schemes") : List.<String>of();
                    var cmdPrefix = co.hasPath("command-prefix") ? co.getString("command-prefix")
                        : co.hasPath("command_prefix") ? co.getString("command_prefix") : null;
                    entries.add(new NetworkAllowEntry(host, kinds, keyRef, schemes, cmdPrefix));
                }
            }
        } catch (ConfigException e) {
            log.warn("[NetworkGate] malformed {} — treating as empty: {}", ALLOWLIST_PATH, e.getMessage());
        }

        var defaults = new HashMap<String, Boolean>();
        try {
            if (config.hasPath(DEFAULT_PATH)) {
                var d = config.getConfig(DEFAULT_PATH);
                for (var kind : List.of("ssh", "scp", "http", "https")) {
                    if (d.hasPath(kind)) defaults.put(kind, parseVerdict(d.getString(kind)));
                }
            }
        } catch (ConfigException e) {
            log.warn("[NetworkGate] malformed {} — using builtin defaults: {}", DEFAULT_PATH, e.getMessage());
        }
        return new NetworkGate(entries, defaults);
    }

    private static boolean parseVerdict(String s) {
        if (s == null) return false;
        var t = s.trim().toLowerCase();
        return t.equals("allow") || t.equals("true") || t.equals("on") || t.equals("yes");
    }

    private static List<String> lower(List<String> in) {
        var out = new ArrayList<String>(in.size());
        for (var s : in) out.add(s == null ? "" : s.toLowerCase());
        return out;
    }

    /**
     * Host matcher shared with {@link NetworkAllowEntry}. Exact match or a
     * wildcard pattern where {@code *} spans hostname-legal chars (mirrors the
     * items-as-tools {@code WebApi} external-domains matcher).
     */
    static boolean hostMatches(String host, String pattern) {
        if (host == null || pattern == null || pattern.isBlank()) return false;
        if (host.equalsIgnoreCase(pattern)) return true;
        var sb = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            var c = pattern.charAt(i);
            if (c == '*') sb.append("[a-zA-Z0-9_.-]*");
            else if ("\\.+?()[]{}|^$".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        sb.append("$");
        return host.matches(sb.toString());
    }
}
