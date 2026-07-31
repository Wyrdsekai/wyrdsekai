package org.wyrdsekai.server;

import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.persistence.AuthService;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.server.ssh.SshAdapter;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Headless CLI for managing per-account SSH public keys — the surface behind
 * {@code wyrd key add|list|remove}. Complements the in-world Study "keyring"
 * (see {@code world.keys.*}) so keys can be added AFTER account creation, not
 * just at bootstrap/invite-redeem time.
 *
 * <p>Keys are bound to ONE account (SPEC SSH security fix 2026-07-03): the
 * authenticator resolves an offered key to its owner and logs in AS that owner,
 * so a key can never impersonate another account. This tool writes/reads the
 * same {@code user_ssh_keys} store, opening {@code world.db} directly like
 * {@link InviteAdminMain}.</p>
 */
public final class KeyAdminMain {

    private KeyAdminMain() {}

    public static void main(String[] args) {
        System.exit(run(resolveJdbcUrl(), System.out, System.err, args));
    }

    static int run(String jdbcUrl, PrintStream out, PrintStream err, String... args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("-h") || args[0].equals("--help")) {
            printUsage(out);
            return args.length == 0 ? 1 : 0;
        }
        if (jdbcUrl == null) {
            err.println("[wyrd] no database found (WYRDSEKAI_JDBC_URL / world.db) — is the zone set up?");
            return 2;
        }
        var auth = new AuthService(jdbcUrl, SqlDialect.fromJdbcUrl(jdbcUrl));
        try {
            return switch (args[0]) {
                case "add"    -> doAdd(auth, out, err, tail(args));
                case "list"   -> doList(auth, out, err, tail(args));
                case "remove", "rm" -> doRemove(auth, out, err, tail(args));
                default -> { err.println("[wyrd] unknown key command: " + args[0]); printUsage(err); yield 1; }
            };
        } catch (Exception e) {
            err.println("[wyrd] key command failed: " + e.getMessage());
            return 2;
        }
    }

    // wyrd key add <username> <pubkey-file-or-line> [--label X]
    private static int doAdd(AuthService auth, PrintStream out, PrintStream err, String[] a) {
        String username = null, keySource = null, label = null;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("--label")) { label = i + 1 < a.length ? a[++i] : null; }
            else if (username == null) username = a[i];
            else if (keySource == null) keySource = a[i];
        }
        if (username == null || keySource == null) {
            err.println("Usage: wyrd key add <username> <pubkey-file|'ssh-... AAAA...'> [--label name]");
            return 1;
        }
        var user = auth.findUserByUsername(username);
        if (user.isEmpty()) { err.println("[wyrd] no such account: " + username); return 1; }

        var raw = readKeySource(keySource, err);
        if (raw == null) return 1;
        var keyLine = SshAdapter.sshKeyLineFromOpenSsh(raw);
        if (keyLine == null) {
            err.println("[wyrd] not a valid OpenSSH public key (expect: ssh-ed25519 / ssh-rsa / ecdsa-sha2-* + base64)");
            return 1;
        }
        var comment = label != null ? label : commentOf(raw, username);
        if (auth.addSshKey(user.get().id(), keyLine, comment)) {
            out.println("[wyrd] key added for '" + username + "' (" + comment + "). Login is now keyless — no restart needed.");
            return 0;
        }
        out.println("[wyrd] that key is already bound to '" + username + "' (no change).");
        return 0;
    }

    // wyrd key list [username]
    private static int doList(AuthService auth, PrintStream out, PrintStream err, String[] a) {
        if (a.length == 0) { err.println("Usage: wyrd key list <username>"); return 1; }
        var user = auth.findUserByUsername(a[0]);
        if (user.isEmpty()) { err.println("[wyrd] no such account: " + a[0]); return 1; }
        var keys = auth.listSshKeys(user.get().id());
        if (keys.isEmpty()) { out.println("[wyrd] no SSH keys bound to '" + a[0] + "'."); return 0; }
        var fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
        out.println("SSH keys for '" + a[0] + "':");
        int n = 1;
        for (var k : keys) {
            out.printf("  %d. %s  [%s]  %s%n", n++, shortKey(k.keyLine()),
                k.comment() == null || k.comment().isBlank() ? "-" : k.comment(),
                fmt.format(k.addedAt()));
        }
        return 0;
    }

    // wyrd key remove <username> <n|fingerprint-prefix>
    private static int doRemove(AuthService auth, PrintStream out, PrintStream err, String[] a) {
        if (a.length < 2) { err.println("Usage: wyrd key remove <username> <index-from-list|key-prefix>"); return 1; }
        var user = auth.findUserByUsername(a[0]);
        if (user.isEmpty()) { err.println("[wyrd] no such account: " + a[0]); return 1; }
        var keys = auth.listSshKeys(user.get().id());
        if (keys.isEmpty()) { err.println("[wyrd] '" + a[0] + "' has no SSH keys."); return 1; }
        String target = null;
        try {
            int idx = Integer.parseInt(a[1]);
            if (idx >= 1 && idx <= keys.size()) target = keys.get(idx - 1).keyLine();
        } catch (NumberFormatException ignore) {
            for (var k : keys) if (k.keyLine().contains(a[1])) { target = k.keyLine(); break; }
        }
        if (target == null) { err.println("[wyrd] no matching key (use `wyrd key list " + a[0] + "` for indexes)."); return 1; }
        if (auth.removeSshKey(user.get().id(), target)) {
            out.println("[wyrd] key removed from '" + a[0] + "'. Takes effect on the next connection.");
            return 0;
        }
        err.println("[wyrd] remove failed.");
        return 1;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static String readKeySource(String src, PrintStream err) {
        // A file path → read it; otherwise treat the arg itself as the key line.
        var p = Path.of(src);
        if (Files.isRegularFile(p)) {
            try {
                return Files.readString(p).trim();
            } catch (Exception e) {
                err.println("[wyrd] could not read key file " + src + ": " + e.getMessage());
                return null;
            }
        }
        return src.trim();
    }

    private static String commentOf(String rawLine, String fallback) {
        var parts = rawLine.trim().split("\\s+", 3);
        return parts.length >= 3 && !parts[2].isBlank() ? parts[2].trim() : fallback;
    }

    private static String shortKey(String keyLine) {
        var parts = keyLine.split("\\s+");
        if (parts.length < 2) return keyLine;
        var b64 = parts[1];
        var tail = b64.length() > 12 ? b64.substring(b64.length() - 12) : b64;
        return parts[0] + " …" + tail;
    }

    private static String[] tail(String[] a) {
        if (a.length <= 1) return new String[0];
        var out = new String[a.length - 1];
        System.arraycopy(a, 1, out, 0, out.length);
        return out;
    }

    static String resolveJdbcUrl() {
        var env = WyrdConfig.get().jdbcUrl();
        if (env != null && !env.isBlank()) return env;
        var dataDir = WyrdConfig.get().dataDir();
        if (dataDir == null || dataDir.isBlank()) dataDir = System.getProperty("user.home") + "/.wyrdsekai";
        var p = Path.of(dataDir, "world.db");
        return Files.exists(p) ? "jdbc:sqlite:" + p.toAbsolutePath() : null;
    }

    private static void printUsage(PrintStream out) {
        out.println("""
            wyrd key — manage per-account SSH public keys

            Usage:
              wyrd key add <username> <pubkey-file|'ssh-ed25519 AAAA...'> [--label name]
              wyrd key list <username>
              wyrd key remove <username> <index|key-prefix>

            A key is bound to exactly one account; logging in with it lands you in
            THAT account (it can never impersonate another). Changes are live — no
            restart. In-world, use the Study keyring to manage your own keys.""");
    }
}
