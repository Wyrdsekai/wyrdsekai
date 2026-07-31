package org.wyrdsekai.server.session;

import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.scripting.i18n.ScriptMessageCatalog;
import org.wyrdsekai.server.ssh.SshAdapter;

import java.util.List;

/**
 * Shared rendering + actions for the multi-surface session verbs, used by every
 * transport (SSH, telnet, WebSocket, …) so the messaging stays identical.
 * <p>
 * One account may hold several live channels at once (CLI + SSH + web + phone),
 * all backing ONE in-world presence. The verbs:
 * <ul>
 *   <li>{@code quit}/{@code exit} — detach THIS channel; {@link #detachHint}
 *       returns a one-line note about the others still open (or null if none).</li>
 *   <li>{@code logout}/{@code quitall} — {@link #logoutOthers} closes every other
 *       channel; the caller then exits its own, so the room departure fires once.</li>
 *   <li>{@code sessions} — {@link #render} lists the channels;
 *       {@link #killByIndex} closes one by its listed number.</li>
 * </ul>
 */
public final class SessionCommands {

    private SessionCommands() {}

    /**
     * One-line note for {@code quit}/{@code exit} when the account stays present
     * on other surfaces, or {@code null} when this was the last channel (in which
     * case the caller just prints its normal goodbye).
     */
    public static String detachHint(ClientConnectionRegistry registry,
                                    String playerId, String sessionId, String locale) {
        if (registry == null || playerId == null) return null;
        int others = countOthers(registry, playerId, sessionId);
        if (others <= 0) return null;
        return ScriptMessageCatalog.forLang(locale).get("session.detach_hint", others);
    }

    /**
     * Close every live channel for this account except {@code sessionId} (the
     * caller, which exits itself afterwards). Returns the number closed.
     */
    public static int logoutOthers(ClientConnectionRegistry registry,
                                   String playerId, String sessionId) {
        if (registry == null || playerId == null) return 0;
        return registry.disconnectOthers(playerId, sessionId, "logout");
    }

    /** Multi-line listing of the account's live channels, current one marked. */
    public static String render(ClientConnectionRegistry registry,
                                String playerId, String sessionId, String locale) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        var sessions = registry == null ? List.<ClientConnection>of()
            : registry.sessionsFor(playerId);
        var sb = new StringBuilder();
        sb.append(catalog.get("session.list_header", sessions.size()));
        int i = 1;
        for (var c : sessions) {
            boolean current = sessionId != null && sessionId.equals(c.sessionId());
            sb.append('\n').append(catalog.get("session.list_line",
                i, surfaceLabel(c), current ? catalog.get("session.list_this") : ""));
            i++;
        }
        return sb.toString();
    }

    /**
     * Handle {@code sessions kill <n>}: close the channel at listed index
     * {@code n} (1-based, matching {@link #render}). Returns a status line.
     * Killing one's own channel is allowed — it falls through to that channel's
     * normal disconnect cleanup.
     */
    public static String killByIndex(ClientConnectionRegistry registry,
                                     String playerId, String locale, List<String> args) {
        var catalog = ScriptMessageCatalog.forLang(locale);
        Integer idx = parseKillIndex(args);
        var sessions = registry == null ? List.<ClientConnection>of()
            : registry.sessionsFor(playerId);
        if (idx == null || idx < 1 || idx > sessions.size()) {
            return catalog.get("session.kill_bad");
        }
        var target = sessions.get(idx - 1);
        try {
            target.disconnect("closed via sessions kill");
        } catch (RuntimeException ignored) {}
        return catalog.get("session.killed", idx);
    }

    /** True when the args are a {@code kill <n>} form (so the surface routes here). */
    public static boolean isKill(List<String> args) {
        return args != null && !args.isEmpty() && "kill".equalsIgnoreCase(args.get(0));
    }

    /**
     * In-world SSH-key management for the CALLING account — {@code key} /
     * {@code key list} / {@code key add <pubkey…>} / {@code key remove <n>}.
     * SECURITY: always scoped to {@code selfUserId} (the logged-in player), so a
     * user can only ever manage THEIR OWN keys from the Study, never another
     * account's. Returns the text to print. Keys take effect on the next
     * connection (live resolver — no restart).
     */
    public static String key(AuthService auth, String selfUserId, List<String> args) {
        if (auth == null || selfUserId == null) return "SSH key management is unavailable here.";
        var sub = (args == null || args.isEmpty()) ? "list" : args.get(0).toLowerCase();
        switch (sub) {
            case "add" -> {
                if (args.size() < 2) return "Usage: key add ssh-ed25519 AAAA... [label]";
                // Rejoin everything after "add" as the raw key line (type base64 [comment]).
                var raw = String.join(" ", args.subList(1, args.size()));
                var keyLine = SshAdapter.sshKeyLineFromOpenSsh(raw);
                if (keyLine == null) {
                    return "That's not a valid SSH public key (expected ssh-ed25519 / ssh-rsa / ecdsa-sha2-* + base64).";
                }
                var parts = raw.trim().split("\\s+", 3);
                var label = parts.length >= 3 && !parts[2].isBlank() ? parts[2].trim() : "study";
                if (auth.addSshKey(selfUserId, keyLine, label)) {
                    return "SSH key added (" + label + "). Your next login with it is keyless — no restart needed.";
                }
                return "That key is already on your account.";
            }
            case "remove", "rm" -> {
                var keys = auth.listSshKeys(selfUserId);
                if (args.size() < 2) return "Usage: key remove <number>   (see `key list`)";
                if (keys.isEmpty()) return "You have no SSH keys.";
                String target = null;
                try {
                    int idx = Integer.parseInt(args.get(1).trim());
                    if (idx >= 1 && idx <= keys.size()) target = keys.get(idx - 1).keyLine();
                } catch (NumberFormatException e) {
                    for (var k : keys) if (k.keyLine().contains(args.get(1))) { target = k.keyLine(); break; }
                }
                if (target == null) return "No matching key — run `key list` to see the numbers.";
                return auth.removeSshKey(selfUserId, target)
                    ? "SSH key removed. It stops working on the next connection."
                    : "Couldn't remove that key.";
            }
            default -> {
                var keys = auth.listSshKeys(selfUserId);
                if (keys.isEmpty()) {
                    return "You have no SSH keys.\nAdd one:  key add ssh-ed25519 AAAA... my-laptop";
                }
                var sb = new StringBuilder("Your SSH keys:\n");
                int n = 1;
                for (var k : keys) {
                    var kp = k.keyLine().split("\\s+");
                    var b64 = kp.length >= 2 ? kp[1] : k.keyLine();
                    var tail = b64.length() > 12 ? "…" + b64.substring(b64.length() - 12) : b64;
                    var label = k.comment() == null || k.comment().isBlank() ? "-" : k.comment();
                    sb.append("  ").append(n++).append(". ")
                      .append(kp.length >= 2 ? kp[0] : "").append(' ').append(tail)
                      .append("  [").append(label).append("]\n");
                }
                sb.append("Add:  key add <pubkey> [label]    Remove:  key remove <number>");
                return sb.toString();
            }
        }
    }

    private static Integer parseKillIndex(List<String> args) {
        if (args == null || args.size() < 2) return null;
        try {
            return Integer.parseInt(args.get(1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int countOthers(ClientConnectionRegistry registry,
                                   String playerId, String sessionId) {
        int total = registry.liveSessionCount(playerId);
        // The caller is still registered at quit time, so subtract it out.
        boolean selfCounted = sessionId != null
            && registry.findBySessionId(sessionId).isPresent();
        return Math.max(0, total - (selfCounted ? 1 : 0));
    }

    /** Friendly surface label derived from the connection's implementation. */
    private static String surfaceLabel(ClientConnection c) {
        var name = c.getClass().getSimpleName().toLowerCase();
        if (name.contains("shell") || name.contains("ssh")) return "ssh";
        if (name.contains("telnet")) return "telnet";
        if (name.contains("websocket") || name.contains("ws")) return "web/cli";
        return "session";
    }
}
