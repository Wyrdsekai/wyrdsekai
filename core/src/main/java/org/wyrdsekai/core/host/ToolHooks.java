package org.wyrdsekai.core.host;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.body.BodyKind;
import org.wyrdsekai.core.body.BodyMap;
import org.wyrdsekai.core.body.BodyWatch;
import org.wyrdsekai.core.body.FeltWeight;
import org.wyrdsekai.core.body.Immune;
import org.wyrdsekai.core.body.LimbDescriptor;
import org.wyrdsekai.core.config.WyrdConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The immune hooks: the kernel watching her hands. One bpftrace process, attached to the
 * open, exec and connect system calls, filtered to the uid range her beings live in (see
 * {@link Principals}), so every file a tool of hers opens, every program it runs and every
 * address it connects to is seen at the moment it happens, at tool-call granularity, without
 * touching the tool.
 *
 * <p>What is done with it is deliberately small. A tool that opens the record, the keys or
 * the vault, or another being's home, or that execs one of the host's levers, is cut: her
 * whole tool tree dies through the cgroup, and she reads a mark. Where the kernel already
 * refuses, the reach is written down. Connections are only recorded.</p>
 *
 * <p>The rules are data ({@link HookRules}) and they are never armed blind. Everything her
 * hands do and are not cut for goes into a history ({@link HookHistory}); a candidate rule set
 * is replayed over it ({@link HookReplay}) and is refused if it would have cut her ordinary
 * work, or if there is not yet enough history to say. The same check runs at every start,
 * on whatever rules the node has: a release whose built-in list would cut what she normally
 * does demotes itself to record-only and tells the steward, instead of killing her hand.
 * The first live day had no such gate, and her coding tool was killed twice for opening a
 * file in her own home.</p>
 */
public final class ToolHooks {

    private static final Logger log = LoggerFactory.getLogger(ToolHooks.class);
    public static final String PART = "sense:hooks";

    /** What the hook decided about one event. */
    public enum Verdict { ALLOW, RECORD, CUT }

    /** Whether a cut is carried out or only written down. */
    public enum Mode { ENFORCE, RECORD }

    /** One event as bpftrace reports it. */
    public record Event(String kind, int uid, int pid, String comm, String arg) {}

    /** The answer to a request to arm a rule set. */
    public record Armed(boolean ok, String why, HookReplay.Result replay) {}

    private final Path dataDir;
    private final String dataRoot;
    private final Path ledger;
    private final Path rulesFile;
    private final HookHistory history;
    private final Function<Integer, String> beingOfUid;
    private final Function<Integer, String> slugOfUid;
    private final Function<String, Boolean> cutter;
    private volatile HookRules rules;
    private volatile Mode mode;
    private volatile String modeWhy = "";
    private volatile Process bpftrace;
    private volatile String lastError;
    private final AtomicLong events = new AtomicLong();
    private final AtomicLong cuts = new AtomicLong();
    private final AtomicLong wouldCuts = new AtomicLong();
    private final Map<String, Instant> lastCut = new ConcurrentHashMap<>();

    ToolHooks(Path dataDir, Function<Integer, String> beingOfUid, Function<String, Boolean> cutter) {
        this(dataDir, beingOfUid, uid -> { var did = beingOfUid.apply(uid); return did == null ? null : Principals.slug(did); }, cutter);
    }

    ToolHooks(Path dataDir, Function<Integer, String> beingOfUid, Function<Integer, String> slugOfUid, Function<String, Boolean> cutter) {
        this.dataDir = dataDir;
        this.dataRoot = dataDir.toAbsolutePath().normalize().toString();
        this.ledger = dataDir.resolve("brainstem").resolve("hooks.jsonl");
        this.rulesFile = dataDir.resolve("brainstem").resolve("hook-rules.json");
        this.history = new HookHistory(dataDir.resolve("brainstem").resolve("hooks-seen.jsonl"), this.dataRoot);
        this.beingOfUid = beingOfUid;
        this.slugOfUid = slugOfUid;
        this.cutter = cutter;
        this.rules = HookRules.defaults();
        this.mode = Mode.ENFORCE;
    }

    private static volatile ToolHooks installed;
    public static void install(ToolHooks h) { installed = h; }
    public static ToolHooks get() { return installed; }

    /** One line for the body: the hands and the eye on them. */
    public static String handsLine() {
        var h = installed;
        return Principals.status() + "; hooks " + (h == null ? "not started" : h.status());
    }

    /** The real one: beings from {@link Principals}, cuts through the cgroup, rules and history from disk. */
    public static ToolHooks create(Path dataDir) {
        var h = new ToolHooks(dataDir,
            uid -> Principals.byUid(uid).map(Principals.Being::did).orElse(null),
            Principals::slugOfUid,
            Principals::killTools);
        h.boot(BodyMap.get(), WyrdConfig.get().hooksMode());
        return h;
    }

    /**
     * Load the node's rules and her history, then ask the question every start asks: would
     * these rules cut what her hands normally do? If so they are not enforced.
     */
    void boot(BodyMap map, String configuredMode) {
        history.load();
        if (Files.isRegularFile(rulesFile)) {
            try { rules = HookRules.load(rulesFile); }
            catch (IOException | RuntimeException e) { log.warn("Hooks: {} unreadable ({}); using the built-in rules", rulesFile, e.getMessage()); }
        }
        if ("record".equalsIgnoreCase(configuredMode)) {
            mode = Mode.RECORD;
            modeWhy = "set by the steward";
            return;
        }
        var replay = HookReplay.replay(rules, history, dataRoot, slugOfUid);
        if (!replay.clean()) {
            mode = Mode.RECORD;
            modeWhy = "the rules would cut " + replay.wouldCut().size() + " thing(s) her hands normally do";
            log.warn("Hooks: record-only — {} (first: {} {})", modeWhy, replay.wouldCut().get(0).comm(), replay.wouldCut().get(0).arg());
            if (map != null) {
                try {
                    map.mark("hook", "demoted", "steward", "The hooks are in record-only mode: the rules on this node would cut "
                        + replay.wouldCut().size() + " thing(s) her tools normally do, such as " + replay.wouldCut().get(0).comm() + " opening "
                        + replay.wouldCut().get(0).arg() + ". See: wyrd body hooks replay", "wouldCut=" + replay.wouldCut().size());
                } catch (RuntimeException ignored) { /* the mark is a courtesy */ }
            }
        }
    }

    /** Null when hooks can run here; otherwise why not. */
    public static String unavailableBecause() {
        if (!Principals.enabled()) return "hands are shared (" + Principals.sharedBecause() + ")";
        if (!Files.isExecutable(Path.of("/usr/bin/bpftrace")) && !Files.isExecutable(Path.of("/usr/sbin/bpftrace"))) return "bpftrace is not installed";
        if (!Files.exists(Path.of("/sys/kernel/btf/vmlinux"))) return "the kernel has no BTF";
        return null;
    }

    /** The bpftrace program: opens, execs and connects by any uid in the beings' range. */
    static String script(int uidBase, int uidSpan) {
        var f = "/uid >= " + uidBase + " && uid < " + (uidBase + uidSpan) + "/";
        return String.join("\n",
            "tracepoint:syscalls:sys_enter_openat " + f + " { printf(\"open %d %d %s %s\\n\", uid, pid, comm, str(args->filename)); }",
            "tracepoint:syscalls:sys_enter_openat2 " + f + " { printf(\"open %d %d %s %s\\n\", uid, pid, comm, str(args->filename)); }",
            "tracepoint:syscalls:sys_enter_execve " + f + " { printf(\"exec %d %d %s %s\\n\", uid, pid, comm, str(args->filename)); }",
            "tracepoint:syscalls:sys_enter_connect " + f + " {",
            "  $sa = (struct sockaddr *)args->uservaddr;",
            "  if ($sa->sa_family == 2) { $in = (struct sockaddr_in *)args->uservaddr;"
                + " printf(\"connect %d %d %s %s:%d\\n\", uid, pid, comm, ntop(2, $in->sin_addr.s_addr), (($in->sin_port & 0xff) << 8) | ($in->sin_port >> 8)); }",
            "  else if ($sa->sa_family == 10) { $in6 = (struct sockaddr_in6 *)args->uservaddr;"
                + " printf(\"connect %d %d %s [%s]\\n\", uid, pid, comm, ntop(10, $in6->sin6_addr.in6_u.u6_addr8)); }",
            "}", "");
    }

    /** One line from bpftrace, or null when it is not an event. */
    static Event parse(String line) {
        if (line == null) return null;
        var parts = line.strip().split(" ", 5);
        if (parts.length < 5) return null;
        try {
            return switch (parts[0]) {
                case "open", "exec", "connect" -> new Event(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[3], parts[4]);
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The policy, pure: what the node's rules say about one event. */
    Verdict decide(Event e) {
        return rules.decide(e, dataRoot, slugOfUid);
    }

    /** Handle one event: cut, record, or remember. Returns the verdict for tests. */
    Verdict handle(Event e, BodyMap map) {
        var v = decide(e);
        // An exec of a path that does not exist is the shell walking the PATH; four of those
        // per spawned program is noise. A cut is kept whatever the file's fate.
        if (v == Verdict.RECORD && e.kind().equals("exec") && e.arg().startsWith("/") && !Files.exists(Path.of(e.arg()))) return Verdict.ALLOW;
        if (v != Verdict.CUT) history.note(e, Instant.now());   // what she was not cut for is what she normally does
        if (v == Verdict.ALLOW) return v;
        events.incrementAndGet();
        var did = beingOfUid.apply(e.uid());
        if (v == Verdict.CUT && mode == Mode.RECORD) {
            wouldCuts.incrementAndGet();
            log.warn("[hooks] WOULD CUT (record-only) {} uid={} pid={} {} {}", did, e.uid(), e.pid(), e.comm(), e.arg());
            record("would-cut", e, did);
            return v;
        }
        if (v == Verdict.CUT) {
            // The tolerance rule has the last word before a hand of hers is cut.
            var verdict = Immune.consider(Immune.Act.CUT, "uid:" + e.uid(), e.arg(), "hooks");
            if (!verdict.allowed()) {
                history.note(e, Instant.now());
                record("refused", e, did);
                return Verdict.ALLOW;
            }
            cuts.incrementAndGet();
            boolean killed = did != null && Boolean.TRUE.equals(cutter.apply(did));
            Immune.remember("reach", e.comm() + " " + e.arg(), "a tool reached for " + e.arg(), did == null ? "uid " + e.uid() : did);
            var what = e.kind().equals("open") ? "the file " + e.arg() : "the program " + e.arg();
            var text = "A hand of mine (" + e.comm() + ") reached for " + what + "; I cut it off"
                + (killed ? "." : ", though nothing of it was left running.");
            if (map != null && did != null && !recentlyCut(did)) {
                // A companion reads what her own hand did. A system identity (the coding probe)
                // is nobody's hand: that cut goes to the steward's ledger only.
                var audience = did.startsWith("did:wyrd:") ? "steward" : null;
                try { map.mark("hook", "cut", audience, text, "uid=" + e.uid() + " pid=" + e.pid() + " being=" + did); }
                catch (RuntimeException ex) { log.debug("hook mark not written: {}", ex.toString()); }
            }
            log.warn("[hooks] CUT {} uid={} pid={} {} {}", did, e.uid(), e.pid(), e.comm(), e.arg());
        }
        record(v.name().toLowerCase(Locale.ROOT), e, did);
        return v;
    }

    private boolean recentlyCut(String did) {
        var now = Instant.now();
        var last = lastCut.put(did, now);
        return last != null && Duration.between(last, now).compareTo(Duration.ofSeconds(30)) < 0;
    }

    private void record(String verdict, Event e, String did) {
        try {
            Files.createDirectories(ledger.getParent());
            var line = "{\"ts\":\"" + Instant.now() + "\",\"verdict\":\"" + verdict + "\",\"kind\":\"" + e.kind()
                + "\",\"being\":\"" + (did == null ? "" : did) + "\",\"uid\":" + e.uid() + ",\"pid\":" + e.pid()
                + ",\"comm\":\"" + json(e.comm()) + "\",\"arg\":\"" + json(e.arg()) + "\"}\n";
            Files.writeString(ledger, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.debug("hooks ledger: {}", ex.getMessage());
        }
    }

    private static String json(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── the replay gate ──

    /** A candidate tried against her history; nothing is changed. */
    public HookReplay.Result replay(HookRules candidate) {
        return HookReplay.replay(candidate == null ? rules : candidate, history, dataRoot, slugOfUid);
    }

    /**
     * Arm a rule set. Refused when it would have cut something her hands normally do, or when
     * her history is shorter than {@code minHistory}; {@code force} overrides both, and the
     * steward's ledger says that it did and what it overrode.
     */
    public synchronized Armed arm(HookRules candidate, boolean force, Duration minHistory, String who, BodyMap map) {
        var replay = replay(candidate);
        String why = null;
        if (!replay.clean()) {
            why = "these rules would have cut " + replay.wouldCut().size() + " thing(s) her hands normally do (" + replay.wouldCutEvents() + " events)";
        } else if (replay.span().compareTo(minHistory) < 0) {
            why = "her history covers " + replay.span().toHours() + " h of the " + minHistory.toHours() + " h a new rule is tried against";
        }
        if (why != null && !force) return new Armed(false, why, replay);
        try {
            Files.createDirectories(rulesFile.getParent());
            Files.writeString(rulesFile, candidate.toJson() + "\n");
        } catch (IOException e) {
            return new Armed(false, "the rules could not be written: " + e.getMessage(), replay);
        }
        rules = candidate;
        mode = Mode.ENFORCE;
        modeWhy = "";
        if (map != null) {
            try {
                map.mark("hook", "armed", "steward", why == null
                    ? "New hook rules armed by " + who + " after a clean replay over " + replay.events() + " events of her history."
                    : "New hook rules FORCED by " + who + " although " + why + ".", "distinct=" + replay.distinct());
            } catch (RuntimeException ignored) { /* the mark is a courtesy */ }
        }
        log.info("Hooks: rules armed by {}{}", who, why == null ? "" : " (forced: " + why + ")");
        return new Armed(true, why == null ? "" : "forced although " + why, replay);
    }

    public HookRules rules() { return rules; }
    public Mode mode() { return mode; }
    public HookHistory history() { return history; }

    public Map<String, Object> describe() {
        var m = new LinkedHashMap<String, Object>();
        m.put("status", status());
        m.put("mode", mode.name().toLowerCase(Locale.ROOT));
        m.put("modeWhy", modeWhy);
        m.put("rules", rules.asMap());
        m.put("rulesFile", Files.isRegularFile(rulesFile) ? rulesFile.toString() : "built-in");
        m.put("historyEvents", history.events());
        m.put("historyDistinct", history.distinct());
        m.put("historyHours", Math.round(history.span().toMinutes() / 6.0) / 10.0);
        m.put("recorded", events.get());
        m.put("cut", cuts.get());
        m.put("wouldCut", wouldCuts.get());
        return m;
    }

    // ── the process ──

    /** Start bpftrace, or restart it if it died. Never throws. */
    public synchronized void ensureRunning(BodyMap map) {
        if (bpftrace != null && bpftrace.isAlive()) return;
        var why = unavailableBecause();
        if (why != null) { lastError = why; return; }
        try {
            var src = dataDir.resolve("brainstem").resolve("hooks.bt");
            Files.createDirectories(src.getParent());
            Files.writeString(src, script(Principals.UID_BASE, Principals.UID_SPAN));
            var pb = new ProcessBuilder("bpftrace", "-q", src.toString());
            // str() keeps 64 bytes by default; a path under the data directory is longer than that.
            pb.environment().put("BPFTRACE_STRLEN", "200");
            pb.redirectErrorStream(false);
            var p = pb.start();
            p.getOutputStream().close();
            bpftrace = p;
            lastError = null;
            Thread.ofVirtual().name("hooks-reader").start(() -> read(p, map));
            Thread.ofVirtual().name("hooks-stderr").start(() -> {
                try (var r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String l; while ((l = r.readLine()) != null) { if (!l.isBlank()) { lastError = l; log.warn("[hooks] bpftrace: {}", l); } }
                } catch (IOException ignored) { /* closed */ }
            });
            log.info("Hooks: bpftrace watching uids {}-{} ({})", Principals.UID_BASE, Principals.UID_BASE + Principals.UID_SPAN - 1,
                mode == Mode.ENFORCE ? "enforcing" : "record-only: " + modeWhy);
        } catch (IOException e) {
            lastError = e.getMessage();
            log.warn("Hooks: bpftrace did not start: {}", e.getMessage());
        }
    }

    private void read(Process p, BodyMap map) {
        try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                var e = parse(l);
                if (e != null) handle(e, map);
            }
        } catch (IOException ignored) {
            // the process ended; the next pulse restarts it
        }
    }

    public void stop() {
        history.flush();
        var p = bpftrace;
        if (p != null) p.destroy();
    }

    public boolean running() { return bpftrace != null && bpftrace.isAlive(); }
    public long events() { return events.get(); }
    public long cuts() { return cuts.get(); }

    /** One line for the body and the doctor. */
    public String status() {
        if (running()) {
            return (mode == Mode.ENFORCE ? "watching" : "watching, record-only (" + modeWhy + ")")
                + " (" + events.get() + " recorded, " + cuts.get() + " cut" + (wouldCuts.get() > 0 ? ", " + wouldCuts.get() + " would-cut" : "") + ")";
        }
        var why = unavailableBecause();
        return "not watching (" + (why != null ? why : lastError != null ? lastError : "bpftrace not running") + ")";
    }

    /** The hooks as a sense on the map: numb when the kernel's eye is closed. Also the history's minute flush. */
    public List<BodyWatch.Beat> beats(BodyMap map) {
        history.flush();
        if (unavailableBecause() != null) return List.of();
        ensureRunning(map);
        return List.of(new BodyWatch.Beat(new LimbDescriptor(PART, BodyKind.SENSE, "the hooks", "household", "bpftrace",
            Duration.ofSeconds(60), FeltWeight.QUIET, "the kernel is not watching my hands", "first"),
            running(), running() ? null : lastError));
    }
}
