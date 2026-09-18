package org.wyrdsekai.core.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.Immune;
import org.wyrdsekai.core.config.WyrdConfig;

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
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The doors as firewall sets. A door on the body's map (the relay, the librarian, a federated
 * zone) can be shut without any inference: its addresses go into an nftables set that the
 * host's output chain rejects, through the {@code wyrdsekai-doors} helper. The reflex arena
 * may do it from its table; the steward may do it from {@code wyrd body door}. Either way she
 * is told afterwards by a mark, because a door she cannot see through is a part of her body
 * that changed.
 *
 * <p>Linux only. Which addresses a door has is answered by a resolver the server installs
 * (the relay's host, the librarian's endpoint, a zone's manifest); a door the resolver does
 * not know is refused rather than guessed.</p>
 */
public final class HostDoors {

    private static final Logger log = LoggerFactory.getLogger(HostDoors.class);
    private static final Pattern SAFE_DOOR = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
    private static final Pattern SAFE_HOST = Pattern.compile("[A-Za-z0-9_.:-]{1,253}");

    /** The command runner; tests swap it. */
    public interface Exec { HostHand.ExecResult run(List<String> argv, Duration timeout); }

    private static volatile Exec exec = HostDoors::defaultExec;
    private static volatile Function<String, List<String>> resolver = id -> List.of();
    private static volatile String os = System.getProperty("os.name", "");
    private static volatile Path script;

    private HostDoors() {}

    /** The server installs this: door id to the hosts behind it. */
    public static void setResolver(Function<String, List<String>> r) { resolver = r == null ? id -> List.of() : r; }
    static void setExecForTests(Exec e, String osName, Path scriptPath) { exec = e; os = osName; script = scriptPath; }
    static void resetForTests() { exec = HostDoors::defaultExec; os = System.getProperty("os.name", ""); script = null; resolver = id -> List.of(); }

    public static Map<String, Object> close(String doorId, String who) {
        var out = base("close", doorId);
        if (!os.toLowerCase().contains("linux")) return refuse(out, "doors as firewall sets exist on Linux only");
        if (doorId == null || !SAFE_DOOR.matcher(doorId).matches()) return refuse(out, "not a door id");
        var hosts = new ArrayList<String>();
        try { hosts.addAll(resolver.apply(doorId)); } catch (RuntimeException e) { log.debug("door resolver failed: {}", e.toString()); }
        hosts.removeIf(h -> h == null || h.isBlank() || !SAFE_HOST.matcher(h).matches());
        if (hosts.isEmpty()) return refuse(out, "I do not know the addresses behind " + doorId + "; nothing was shut");
        // The tolerance rule has the last word: a door that opens onto this host is not a door,
        // and shutting it would cut her off from her own brains and her own pulse. The helper
        // refuses too; this is the earlier, cheaper no, and the one that writes the proposal.
        var verdict = Immune.consider(Immune.Act.CLOSE, doorId, String.join(",", hosts), who);
        if (!verdict.allowed()) return refuse(out, doorId + ": " + verdict.because());
        var argv = new ArrayList<String>(List.of(scriptPath(), "close", doorId));
        argv.addAll(hosts);
        var r = exec.run(argv, Duration.ofSeconds(20));
        finish(out, r);
        if (Boolean.TRUE.equals(out.get("ok"))) {
            mark(doorId, "I shut the door to " + name(doorId) + "; nothing passes through it until it opens.",
                "by " + who + " hosts=" + String.join(",", hosts));
            Immune.remember("door", doorId, "the door was shut by " + who, String.join(",", hosts));
        }
        return out;
    }

    public static Map<String, Object> open(String doorId, String who) {
        var out = base("open", doorId);
        if (!os.toLowerCase().contains("linux")) return refuse(out, "doors as firewall sets exist on Linux only");
        if (doorId == null || !SAFE_DOOR.matcher(doorId).matches()) return refuse(out, "not a door id");
        var r = exec.run(List.of(scriptPath(), "open", doorId), Duration.ofSeconds(20));
        finish(out, r);
        if (Boolean.TRUE.equals(out.get("ok")) && !String.valueOf(out.get("output")).contains("was not shut")) {
            mark(doorId, "The door to " + name(doorId) + " is open again.", "by " + who);
        }
        return out;
    }

    public static Map<String, Object> list() {
        var out = base("list", null);
        if (!os.toLowerCase().contains("linux")) return refuse(out, "doors as firewall sets exist on Linux only");
        finish(out, exec.run(List.of(scriptPath(), "list"), Duration.ofSeconds(10)));
        return out;
    }

    /** The one definition lives in the immune chokepoint. */
    static boolean isSelf(String host) { return Immune.isSelfHost(host); }

    private static Map<String, Object> base(String action, String doorId) {
        var out = new LinkedHashMap<String, Object>();
        out.put("action", action);
        if (doorId != null) out.put("door", doorId);
        return out;
    }

    private static void finish(Map<String, Object> out, HostHand.ExecResult r) {
        out.put("ok", !r.timedOut() && r.exit() == 0);
        out.put("exit", r.exit());
        var text = (r.out() == null ? "" : r.out().strip());
        if (!Boolean.TRUE.equals(out.get("ok"))) {
            out.put("error", r.timedOut() ? "the doors helper did not answer in time"
                : (r.err() == null || r.err().isBlank() ? "exit " + r.exit() : r.err().strip()));
        }
        out.put("output", text);
        log.info("[doors] {} {} ok={} {}", out.get("action"), out.getOrDefault("door", ""), out.get("ok"),
            out.get("error") == null ? "" : out.get("error"));
    }

    private static Map<String, Object> refuse(Map<String, Object> out, String why) {
        out.put("ok", false);
        out.put("error", why);
        return out;
    }

    private static String name(String doorId) {
        var map = BodyMap.get();
        if (map != null) {
            var p = map.part(doorId);
            if (p.isPresent() && p.get().name() != null && !p.get().name().isBlank()) return p.get().name();
        }
        return doorId;
    }

    private static void mark(String doorId, String text, String detail) {
        var map = BodyMap.get();
        if (map == null) return;
        try { map.mark("door", doorId, null, text, detail); }
        catch (RuntimeException e) { log.debug("door mark not written: {}", e.toString()); }
    }

    /** The helper: beside the brainstem on an installed node, in packaging in a source tree. */
    static String scriptPath() {
        if (script != null) return script.toString();
        var env = System.getenv("WYRDSEKAI_DOORS_SCRIPT");
        if (env != null && !env.isBlank()) return env;
        for (var c : List.of(Path.of("/opt/wyrdsekai/bin/wyrdsekai-doors"),
                Path.of("packaging", "brainstem", "wyrdsekai-doors"),
                Path.of("..", "packaging", "brainstem", "wyrdsekai-doors"))) {
            if (Files.isExecutable(c)) return c.toString();
        }
        var scripts = WyrdConfig.get().scriptsDir();
        if (scripts != null && !scripts.isBlank()) {
            var c = Path.of(scripts).resolveSibling("bin").resolve("wyrdsekai-doors");
            if (Files.isExecutable(c)) return c.toString();
        }
        return "/opt/wyrdsekai/bin/wyrdsekai-doors";
    }

    private static HostHand.ExecResult defaultExec(List<String> argv, Duration timeout) {
        try {
            var pb = new ProcessBuilder(argv);
            pb.environment().put("LC_ALL", "C.UTF-8");
            var d = WyrdConfig.get().dataDir();
            if (d != null && !d.isBlank()) pb.environment().put("WYRDSEKAI_DATA", d);
            var p = pb.start();
            p.getOutputStream().close();
            var out = new StringBuilder();
            var err = new StringBuilder();
            var to = Thread.ofVirtual().start(() -> {
                try (var s = p.getInputStream()) { out.append(new String(s.readAllBytes(), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            });
            var te = Thread.ofVirtual().start(() -> {
                try (var s = p.getErrorStream()) { err.append(new String(s.readAllBytes(), StandardCharsets.UTF_8)); } catch (Exception ignored) { }
            });
            boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) { p.destroyForcibly(); return new HostHand.ExecResult(-1, out.toString(), err.toString(), true); }
            to.join(); te.join();
            return new HostHand.ExecResult(p.exitValue(), out.toString(), err.toString(), false);
        } catch (Exception e) {
            return new HostHand.ExecResult(-1, "", e.toString(), false);
        }
    }
}
