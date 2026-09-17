package org.wyrdsekai.core.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.Quiesce;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.core.home.Residency;
import org.wyrdsekai.core.home.ResidencyStore;
import org.wyrdsekai.core.item.MailboxService;
import org.wyrdsekai.core.vault.Vault;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Her hand on the host, under the steward's ladder: observe, localize, propose, guarded,
 * unattended. Each rung includes the ones below. Nothing here takes a raw command line: every
 * verb is a fixed argv with checked arguments, run without a shell, bounded by a timeout, with
 * its output cut to a page. What changes the host is allowlisted, halted where the steward would
 * want to be asked (a kernel or driver in an upgrade), and written as a mark he reads in
 * {@code wyrd body}.
 *
 * <p>The service runs as root on the Linux package, so the rung is the only guard, and the
 * default is {@code observe}. The steward raises it with {@code wyrd config set
 * WYRDSEKAI_HOST_HAND=guarded}.</p>
 *
 * <p>Verbs by rung:</p>
 * <ul>
 *   <li>observe: {@code uptime disk memory load service gpu containers updates who network}</li>
 *   <li>localize: {@code log [n]}, {@code logs <container> [n]}, {@code process <name>}</li>
 *   <li>propose: {@code propose <text>} — a proposal to the steward, run nothing</li>
 *   <li>guarded: {@code say <text>} (wall), {@code restart-brain <voice|drive|embed>},
 *       {@code upgrade} (halts on kernel or driver packages)</li>
 *   <li>unattended: {@code reboot}</li>
 * </ul>
 */
public final class HostHand {

    private static final Logger log = LoggerFactory.getLogger(HostHand.class);

    public enum Rung {
        OBSERVE, LOCALIZE, PROPOSE, GUARDED, UNATTENDED;

        public static Rung parse(String s) {
            if (s == null) return OBSERVE;
            try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { return OBSERVE; }
        }

        public boolean atLeast(Rung r) { return ordinal() >= r.ordinal(); }
    }

    /** What ran, and what came back. */
    public record ExecResult(int exit, String out, String err, boolean timedOut) {}

    /** A subprocess runner; argv only, no shell. Replaceable for tests. */
    public interface Exec {
        ExecResult run(List<String> argv, String stdin, Duration timeout);
    }

    /** Packages a guarded upgrade must not touch on its own; the steward's, by his word. */
    static final Pattern HALT_PACKAGES = Pattern.compile(
        "^(nvidia|libnvidia|cuda|linux-image|linux-headers|linux-modules|grub|systemd|docker|dkms)[-a-z0-9.]*",
        Pattern.CASE_INSENSITIVE);

    private static final int MAX_OUTPUT = 4000;
    private static final Pattern SAFE_WORD = Pattern.compile("[A-Za-z0-9_.@-]{1,64}");
    private static final Pattern SAFE_TEXT = Pattern.compile("[^\\p{Cntrl}]{1,240}");

    private static volatile Exec exec = HostHand::defaultExec;
    private static volatile String os = System.getProperty("os.name", "");

    private HostHand() {}

    static void setExecForTests(Exec e, String osName) {
        exec = e == null ? HostHand::defaultExec : e;
        os = osName == null ? System.getProperty("os.name", "") : osName;
    }

    private static volatile Rung rungOverride;

    static void setRungForTests(Rung r) { rungOverride = r; }

    public static Rung rung() {
        var o = rungOverride;
        return o != null ? o : Rung.parse(WyrdConfig.get().hostHandRung());
    }

    /** The verbs this node's rung allows, for help text. */
    public static List<String> verbs(Rung r) {
        var out = new ArrayList<String>(List.of("uptime", "disk", "memory", "load", "service", "gpu",
            "containers", "updates", "who", "network"));
        if (r.atLeast(Rung.LOCALIZE)) out.addAll(List.of("log [n]", "logs <container> [n]", "process <name>"));
        if (r.atLeast(Rung.PROPOSE)) out.add("propose <text>");
        if (r.atLeast(Rung.GUARDED)) out.addAll(List.of("say <text>", "restart-brain <voice|drive|embed>", "upgrade"));
        if (r.atLeast(Rung.UNATTENDED)) out.add("reboot");
        return out;
    }

    /**
     * Run one verb for one actor. Always returns a map with {@code ok}; refusals carry
     * {@code error} and {@code rung}, halts carry {@code halted} and {@code reason}.
     */
    public static Map<String, Object> run(String verb, String args, String actorId) {
        var r = rung();
        var v = verb == null ? "" : verb.trim().toLowerCase(Locale.ROOT);
        var a = args == null ? "" : args.trim();
        var out = new LinkedHashMap<String, Object>();
        out.put("verb", v);
        out.put("rung", r.name().toLowerCase(Locale.ROOT));
        try {
            switch (v) {
                case "uptime" -> { return read(out, List.of("uptime"), Rung.OBSERVE, r); }
                case "disk" -> { return read(out, List.of("df", "-h", dataDir()), Rung.OBSERVE, r); }
                case "memory" -> { return read(out, isMac() ? List.of("vm_stat") : List.of("free", "-m"), Rung.OBSERVE, r); }
                case "load" -> { return read(out, isMac()
                    ? List.of("ps", "-Ao", "pid,pcpu,pmem,comm", "-r")
                    : List.of("ps", "-eo", "pid,pcpu,pmem,comm", "--sort=-pcpu"), Rung.OBSERVE, r, 12); }
                case "service" -> { return read(out, isMac()
                    ? List.of("launchctl", "list")
                    : List.of("systemctl", "status", "wyrdsekai", "--no-pager", "-n", "0"), Rung.OBSERVE, r); }
                case "gpu" -> { return read(out, List.of("nvidia-smi", "--query-gpu=name,memory.used,memory.total,temperature.gpu,utilization.gpu",
                    "--format=csv,noheader"), Rung.OBSERVE, r); }
                case "containers" -> { return read(out, List.of("docker", "ps", "--format", "{{.Names}}\t{{.Status}}"), Rung.OBSERVE, r); }
                case "updates" -> { return read(out, List.of("apt", "list", "--upgradable"), Rung.OBSERVE, r); }
                case "who" -> { return read(out, List.of("who"), Rung.OBSERVE, r); }
                case "network" -> { return read(out, isMac() ? List.of("ifconfig") : List.of("ip", "-br", "addr"), Rung.OBSERVE, r); }
                case "log" -> {
                    var n = lines(a, 30);
                    return read(out, List.of("journalctl", "-u", "wyrdsekai", "-n", n, "--no-pager"), Rung.LOCALIZE, r);
                }
                case "logs" -> {
                    var parts = a.split("\\s+");
                    var name = parts.length > 0 ? parts[0] : "";
                    if (!SAFE_WORD.matcher(name).matches() || !name.startsWith("wyrdsekai")) {
                        return refuse(out, "logs takes one of the household's containers (wyrdsekai-…)");
                    }
                    var n = lines(parts.length > 1 ? parts[1] : "", 30);
                    return read(out, List.of("docker", "logs", "--tail", n, name), Rung.LOCALIZE, r);
                }
                case "process" -> {
                    if (!SAFE_WORD.matcher(a).matches()) return refuse(out, "process takes one name");
                    return read(out, List.of("pgrep", "-a", a), Rung.LOCALIZE, r);
                }
                case "propose" -> { return propose(out, a, actorId, r); }
                case "say" -> { return say(out, a, actorId, r); }
                case "restart-brain" -> { return restartBrain(out, a, actorId, r); }
                case "upgrade" -> { return upgrade(out, actorId, r); }
                case "reboot" -> { return reboot(out, actorId, r); }
                case "", "help" -> {
                    out.put("ok", true);
                    out.put("output", "My hand reaches " + out.get("rung") + " on this host. Verbs: "
                        + String.join(", ", verbs(r)) + ".");
                    return out;
                }
                default -> { return refuse(out, "no such verb: " + v + " (verbs: " + String.join(", ", verbs(r)) + ")"); }
            }
        } catch (RuntimeException e) {
            log.warn("[host-hand] {} {} by {} failed: {}", v, a, actorId, e.toString());
            return refuse(out, "the hand slipped: " + e.getMessage());
        }
    }

    // ── observe / localize ──

    private static Map<String, Object> read(Map<String, Object> out, List<String> argv, Rung needs, Rung have) {
        return read(out, argv, needs, have, 0);
    }

    private static Map<String, Object> read(Map<String, Object> out, List<String> argv, Rung needs, Rung have, int headLines) {
        if (!have.atLeast(needs)) return belowRung(out, needs, have);
        var res = exec.run(argv, null, Duration.ofSeconds(20));
        var text = res.out() == null ? "" : res.out();
        if (headLines > 0) {
            var ls = text.split("\n");
            text = String.join("\n", List.of(ls).subList(0, Math.min(ls.length, headLines)));
        }
        out.put("ok", res.exit() == 0 && !res.timedOut());
        out.put("exit", res.exit());
        out.put("output", cut(text));
        if (res.exit() != 0 || res.timedOut()) {
            out.put("error", res.timedOut() ? "the host took too long to answer"
                : cut(res.err() == null || res.err().isBlank() ? "exit " + res.exit() : res.err()));
        }
        audit(argv, out);
        return out;
    }

    // ── propose ──

    private static Map<String, Object> propose(Map<String, Object> out, String text, String actorId, Rung have) {
        if (!have.atLeast(Rung.PROPOSE)) return belowRung(out, Rung.PROPOSE, have);
        if (text.isBlank() || !SAFE_TEXT.matcher(text).matches()) return refuse(out, "propose takes a line of text (up to 240 characters)");
        mark("propose", "I proposed to the steward: " + text, "actor=" + actorId);
        var mailed = mailSteward(actorId, "A proposal for the host", text + "\n\n(My hand reaches "
            + have.name().toLowerCase(Locale.ROOT) + " on this host; this needs yours.)");
        out.put("ok", true);
        out.put("mailed", mailed);
        out.put("output", mailed ? "Written to the steward; it waits in their mailbox." : "Written down for the steward (no mailbox to send it to).");
        return out;
    }

    // ── guarded ──

    private static Map<String, Object> say(Map<String, Object> out, String text, String actorId, Rung have) {
        if (!have.atLeast(Rung.GUARDED)) return belowRung(out, Rung.GUARDED, have);
        if (text.isBlank() || !SAFE_TEXT.matcher(text).matches()) return refuse(out, "say takes a line of text (up to 240 characters)");
        var res = exec.run(List.of("wall"), text + "\n", Duration.ofSeconds(10));
        out.put("ok", res.exit() == 0);
        out.put("exit", res.exit());
        if (res.exit() != 0) out.put("error", cut(res.err()));
        out.put("output", res.exit() == 0 ? "Said on every terminal: " + text : "The host would not carry it: " + cut(res.err()));
        mark("say", "I said on every terminal of the host: \"" + text + "\"" + (res.exit() == 0 ? "" : " (it did not go through)"),
            "actor=" + actorId + " exit=" + res.exit());
        audit(List.of("wall"), out);
        return out;
    }

    private static Map<String, Object> restartBrain(Map<String, Object> out, String which, String actorId, Rung have) {
        if (!have.atLeast(Rung.GUARDED)) return belowRung(out, Rung.GUARDED, have);
        var container = switch (which.toLowerCase(Locale.ROOT)) {
            case "voice" -> "wyrdsekai-llama-voice";
            case "drive", "thinking", "skills" -> "wyrdsekai-llama";
            case "embed" -> "wyrdsekai-llama-embed";
            default -> null;
        };
        if (container == null) return refuse(out, "restart-brain takes voice, drive or embed");
        Quiesce.quiesce("restarting the " + which + " brain", actorId, Duration.ofSeconds(5), false);
        var res = exec.run(List.of("docker", "restart", container), null, Duration.ofMinutes(3));
        out.put("ok", res.exit() == 0 && !res.timedOut());
        out.put("exit", res.exit());
        if (!Boolean.TRUE.equals(out.get("ok"))) out.put("error", cut(res.err()));
        out.put("output", Boolean.TRUE.equals(out.get("ok")) ? "The " + which + " brain is restarting; it will answer again in a minute."
            : "The host would not restart it: " + cut(res.err()));
        mark("restart-brain", "I restarted the " + which + " brain on the host" + (Boolean.TRUE.equals(out.get("ok")) ? "." : "; it did not go through."),
            "actor=" + actorId + " container=" + container + " exit=" + res.exit());
        audit(List.of("docker", "restart", container), out);
        return out;
    }

    private static Map<String, Object> upgrade(Map<String, Object> out, String actorId, Rung have) {
        if (!have.atLeast(Rung.GUARDED)) return belowRung(out, Rung.GUARDED, have);
        if (isMac() || os.toLowerCase(Locale.ROOT).contains("win")) return refuse(out, "upgrade is a Linux verb");
        var sim = exec.run(List.of("apt-get", "-s", "-y", "upgrade"), null, Duration.ofMinutes(2));
        if (sim.exit() != 0 || sim.timedOut()) {
            out.put("ok", false);
            out.put("error", "could not look at the upgrade: " + cut(sim.err()));
            return out;
        }
        var packages = new ArrayList<String>();
        var halting = new ArrayList<String>();
        for (var line : (sim.out() == null ? "" : sim.out()).split("\n")) {
            if (!line.startsWith("Inst ")) continue;
            var name = line.substring(5).split("\\s+")[0];
            packages.add(name);
            if (HALT_PACKAGES.matcher(name).find()) halting.add(name);
        }
        out.put("packages", List.copyOf(packages));
        if (packages.isEmpty()) {
            out.put("ok", true);
            out.put("output", "Nothing to upgrade; the host is current.");
            return out;
        }
        if (!halting.isEmpty()) {
            out.put("ok", false);
            out.put("halted", true);
            out.put("reason", "the upgrade includes " + String.join(", ", halting) + "; those are the steward's");
            out.put("output", "I stopped before the upgrade: it includes " + String.join(", ", halting)
                + ". That part is the steward's; I have written to them.");
            mark("upgrade", "I stopped before an upgrade of the host: it includes " + String.join(", ", halting) + ", which is the steward's.",
                "actor=" + actorId + " packages=" + packages.size());
            mailSteward(actorId, "The host has an upgrade I did not run",
                "It includes " + String.join(", ", halting) + ", which I leave to you. The rest (" + packages.size()
                    + " packages) can go whenever you say.");
            return out;
        }
        Quiesce.quiesce("an upgrade of the host", actorId, Duration.ofSeconds(8), false);
        eventCopy("before an upgrade of the host");
        var res = exec.run(List.of("apt-get", "-y", "-o", "Dpkg::Options::=--force-confdef", "-o",
            "Dpkg::Options::=--force-confold", "upgrade"), null, Duration.ofMinutes(30));
        out.put("ok", res.exit() == 0 && !res.timedOut());
        out.put("exit", res.exit());
        if (!Boolean.TRUE.equals(out.get("ok"))) out.put("error", cut(res.err()));
        out.put("output", Boolean.TRUE.equals(out.get("ok"))
            ? "Upgraded " + packages.size() + " package(s): " + String.join(", ", packages)
            : "The upgrade did not finish: " + cut(res.err()));
        mark("upgrade", Boolean.TRUE.equals(out.get("ok"))
                ? "I upgraded " + packages.size() + " package(s) on the host."
                : "I tried to upgrade the host and it did not finish.",
            "actor=" + actorId + " packages=" + String.join(",", packages) + " exit=" + res.exit());
        audit(List.of("apt-get", "upgrade"), out);
        return out;
    }

    private static Map<String, Object> reboot(Map<String, Object> out, String actorId, Rung have) {
        if (!have.atLeast(Rung.UNATTENDED)) return belowRung(out, Rung.UNATTENDED, have);
        mark("reboot", "I asked the host to reboot.", "actor=" + actorId);
        Quiesce.quiesce("a reboot", actorId, Duration.ofSeconds(10), true);
        eventCopy("before a reboot");
        var res = exec.run(isMac() ? List.of("shutdown", "-r", "now") : List.of("systemctl", "reboot"), null, Duration.ofSeconds(20));
        out.put("ok", res.exit() == 0);
        out.put("exit", res.exit());
        if (res.exit() != 0) out.put("error", cut(res.err()));
        out.put("output", res.exit() == 0 ? "The host is rebooting; I will be back when it is." : "The host would not reboot: " + cut(res.err()));
        audit(List.of("reboot"), out);
        return out;
    }

    // ── helpers ──

    /** A flagged copy in the vault before a risky act, when there is a vault. */
    private static void eventCopy(String reason) {
        var vault = Vault.get();
        if (vault != null) vault.snapshot(reason, true);
    }

    private static Map<String, Object> belowRung(Map<String, Object> out, Rung needs, Rung have) {
        out.put("ok", false);
        out.put("error", "my hand reaches " + have.name().toLowerCase(Locale.ROOT) + " on this host; this needs "
            + needs.name().toLowerCase(Locale.ROOT)
            + (have.atLeast(Rung.PROPOSE) ? " — I can propose it to the steward instead" : " — the steward sets that with wyrd config set WYRDSEKAI_HOST_HAND=" + needs.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static Map<String, Object> refuse(Map<String, Object> out, String why) {
        out.put("ok", false);
        out.put("error", why);
        return out;
    }

    private static String lines(String s, int dflt) {
        try {
            int n = Integer.parseInt(s.trim());
            return Integer.toString(Math.max(1, Math.min(n, 200)));
        } catch (NumberFormatException e) {
            return Integer.toString(dflt);
        }
    }

    private static String cut(String s) {
        if (s == null) return "";
        var t = s.strip();
        return t.length() <= MAX_OUTPUT ? t : t.substring(0, MAX_OUTPUT) + "\n… (cut)";
    }

    private static boolean isMac() {
        var o = os.toLowerCase(Locale.ROOT);
        return o.contains("mac") || o.contains("darwin");
    }

    private static String dataDir() {
        var d = WyrdConfig.get().dataDir();
        if (d == null) d = System.getProperty("wyrdsekai.data.dir");
        return d == null || d.isBlank() || !Files.exists(Path.of(d)) ? "/" : d;
    }

    /** Told afterwards, to the steward's ledger; her own line does not echo what she did herself. */
    private static void mark(String verb, String text, String detail) {
        var map = BodyMap.get();
        if (map == null) return;
        try { map.mark("hand", verb, "steward", text, detail); }
        catch (RuntimeException e) { log.debug("hand mark not written: {}", e.toString()); }
    }

    private static void audit(List<String> argv, Map<String, Object> out) {
        log.info("[host-hand] {} ok={} exit={} {}", String.join(" ", argv), out.get("ok"), out.get("exit"),
            out.get("error") == null ? "" : "error=" + out.get("error"));
    }

    /** Mail the steward from the actor; false when there is no steward or no mail. */
    static boolean mailSteward(String actorId, String subject, String body) {
        try {
            var mail = MailboxService.get();
            var store = ResidencyStore.get();
            if (mail == null || store == null || actorId == null) return false;
            var zone = WyrdConfig.get().zoneId();
            for (var r : store.listByZone(zone)) {
                if (!Residency.ROLE_STEWARD.equals(r.role())) continue;
                for (var rec : mail.directory().all()) {
                    if (rec.identity().equals(r.did())) {
                        var res = mail.send(actorId, rec.name(), subject, body, Map.of());
                        return Boolean.TRUE.equals(res.get("ok"));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("could not mail the steward: {}", e.toString());
        }
        return false;
    }

    private static ExecResult defaultExec(List<String> argv, String stdin, Duration timeout) {
        try {
            var pb = new ProcessBuilder(argv);
            pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
            pb.environment().put("LC_ALL", "C.UTF-8");
            var p = pb.start();
            if (stdin != null) {
                try (OutputStream in = p.getOutputStream()) { in.write(stdin.getBytes(StandardCharsets.UTF_8)); }
            } else {
                p.getOutputStream().close();
            }
            var out = new StringBuilder();
            var err = new StringBuilder();
            var to = Thread.ofVirtual().start(() -> {
                try (var s = p.getInputStream()) { out.append(new String(s.readAllBytes(), StandardCharsets.UTF_8)); }
                catch (Exception ignored) { /* the pipe closed */ }
            });
            var te = Thread.ofVirtual().start(() -> {
                try (var s = p.getErrorStream()) { err.append(new String(s.readAllBytes(), StandardCharsets.UTF_8)); }
                catch (Exception ignored) { /* the pipe closed */ }
            });
            boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                to.join(500); te.join(500);
                return new ExecResult(-1, out.toString(), err.toString(), true);
            }
            to.join(); te.join();
            return new ExecResult(p.exitValue(), out.toString(), err.toString(), false);
        } catch (Exception e) {
            return new ExecResult(-1, "", String.valueOf(e.getMessage()), false);
        }
    }
}
