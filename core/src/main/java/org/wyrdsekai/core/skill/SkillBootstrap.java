package org.wyrdsekai.core.skill;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.wyrdsekai.core.skill.impl.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Static utility that wires all skill executors into a SkillRegistry
 * based on configuration. Config-dependent executors are only created
 * when their keys are present. Always-on executors need no external deps.
 */
public final class SkillBootstrap {

    private static final Logger LOG = Logger.getLogger(SkillBootstrap.class.getName());

    private SkillBootstrap() {}

    /**
     * Process-wide native skill registry, installed once at server startup so the
     * companion spawn path (ZoneGuardian → CompanionCapabilities) can reach the
     * SAME populated registry — before Phase 1 it was constructed only inside the
     * Between bridge (BetweenActor:771) and every companion got skillRegistry=null.
     */
    private static volatile SkillRegistry shared;

    /** Install the process-wide native skill registry (server startup). */
    public static void installShared(SkillRegistry registry) {
        shared = registry;
    }

    /** The installed registry, or null before startup wires it. */
    public static SkillRegistry shared() {
        return shared;
    }

    public static SkillRegistry create(Map<String, String> config) {
        if (config == null) config = Map.of();
        var registry = new SkillRegistry(null, null);

        // --- Always-on executors (no configuration required) ---
        register(registry, new WeatherSkillExecutor());
        register(registry, new MedicationSkillExecutor());
        register(registry, new GrocerySkillExecutor());
        register(registry, new ClipboardBridgeSkillExecutor());
        register(registry, new NotificationBridgeSkillExecutor());
        // Emergency contacts (3.2): "Name:phone[:relation],Name2:phone2[,…]"
        // from wyrdsekai.skills.emergency.contacts — herald.call.emergency's
        // contact book. The executor stays registered even with no contacts:
        // the substrate emergency path dials the jurisdiction number directly.
        register(registry, new EmergencyCallSkillExecutor(
            parseEmergencyContacts(config.get("emergency.contacts"))));
        register(registry, new HealthBridgeSkillExecutor());
        register(registry, new LibraryContributionSkillExecutor());
        register(registry, new ScreenshotSkillExecutor());

        // --- Config-dependent: Knowledge ---
        String kiwixUrl = config.get("kiwix.url");
        if (kiwixUrl != null && !kiwixUrl.isBlank()) {
            register(registry, new KiwixSkillExecutor(kiwixUrl));
        }

        // --- Config-dependent: Home Automation ---
        String haUrl = config.get("ha.url");
        if (haUrl != null && !haUrl.isBlank()) {
            register(registry, new HomeAssistantSkillExecutor(haUrl));
        }

        // --- Config-dependent: Calendar ---
        String caldavUrl = config.get("caldav.url");
        if (caldavUrl != null && !caldavUrl.isBlank()) {
            register(registry, new CalDavSkillExecutor(caldavUrl));
        }

        if ("true".equalsIgnoreCase(config.get("gcal.enabled"))) {
            register(registry, new GCalSkillExecutor());
        }

        if ("true".equalsIgnoreCase(config.get("outlook.enabled"))) {
            register(registry, new OutlookCalSkillExecutor());
        }

        // --- Config-dependent: Email ---
        String emailCreds = config.get("email.credentials");
        if (emailCreds != null && !emailCreds.isBlank()) {
            register(registry, new EmailSkillExecutor());
        }

        if ("true".equalsIgnoreCase(config.get("gmail.enabled"))) {
            register(registry, new GmailSkillExecutor());
        }

        // --- Config-dependent: Chat ---
        if ("true".equalsIgnoreCase(config.get("gchat.enabled"))) {
            register(registry, new GoogleChatSkillExecutor());
        }

        String signalPhone = config.get("signal.phone");
        if (signalPhone != null && !signalPhone.isBlank()) {
            register(registry, new SignalSkillExecutor(null, signalPhone));
        }

        if ("true".equalsIgnoreCase(config.get("keybase.enabled"))) {
            register(registry, new KeybaseSkillExecutor());
        }

        // --- Config-dependent: Notes ---
        String obsidianPath = config.get("obsidian.vault_path");
        if (obsidianPath != null && !obsidianPath.isBlank()) {
            register(registry, new ObsidianSkillExecutor(obsidianPath));
        }

        // --- Config-dependent: Voice ---
        String whisperUrl = config.get("whisper.url");
        if (whisperUrl != null && !whisperUrl.isBlank()) {
            register(registry, new WhisperSkillExecutor(whisperUrl));
        }

        // --- Config-dependent: Privacy ---
        if ("true".equalsIgnoreCase(config.get("privacy.enabled"))) {
            register(registry, new PrivacyComSkillExecutor());
        }

        // --- Config-dependent: Payments ---
        if ("true".equalsIgnoreCase(config.get("stripe.enabled"))) {
            register(registry, new StripeIssuingSkillExecutor());
        }

        // --- Config-dependent: RSS ---
        if ("true".equalsIgnoreCase(config.get("rss.enabled"))) {
            register(registry, new RssSkillExecutor());
        }

        // --- Config-dependent: Transit ---
        String transitPath = config.get("transit.gtfs_path");
        if (transitPath != null && !transitPath.isBlank()) {
            register(registry, new TransitSkillExecutor(Path.of(transitPath)));
        }

        // --- Config-dependent: Books ---
        if ("true".equalsIgnoreCase(config.getOrDefault("books.enabled", "false"))) {
            register(registry, new BookAcquisitionSkillExecutor());
        }

        // --- Config-dependent: Filesystem mounts ---
        String fsMounts = config.get("fs.mounts");
        if (fsMounts != null && !fsMounts.isBlank()) {
            Map<String, Path> mounts = parseMounts(fsMounts);
            if (!mounts.isEmpty()) {
                register(registry, new FilesystemSkillExecutor(mounts));
            }
        }

        // --- Config-dependent: File search ---
        String fileSearchRoots = config.get("filesearch.roots");
        if (fileSearchRoots != null && !fileSearchRoots.isBlank()) {
            List<Path> roots = parseRoots(fileSearchRoots);
            if (!roots.isEmpty()) {
                register(registry, new FileSearchSkillExecutor(roots));
            }
        }

        // --- Config-dependent: Document extraction ---
        String docsPath = config.get("docs.path");
        if (docsPath != null && !docsPath.isBlank()) {
            register(registry, new DocumentExtractorSkillExecutor(docsPath));
        }

        // --- Config-dependent: OpenClaw Gateway (lowest precedence) ---
        String openclawUrl = config.get("openclaw.url");
        if (openclawUrl != null && !openclawUrl.isBlank()) {
            var gateway = new OpenClawGatewayExecutor(openclawUrl);
            register(registry, gateway);
            // Kick the WebSocket + catalogue load now, not on first use — the
            // executor only supports() skills it has CATALOGUED, so a lazy
            // connect meant the whole gateway surface stayed invisible until
            // an invocation that could never resolve. Fire-and-forget; the
            // executor reconnects with backoff if the gateway is down.
            try {
                gateway.connectAsync();
            } catch (Exception e) {
                LOG.warning("SkillBootstrap: OpenClaw gateway connect kick failed: "
                    + e.getMessage());
            }
        }

        LOG.info("SkillBootstrap: registered " + registry.allSkills().size() + " skills");
        return registry;
    }

    // ── HOCON → flat-key bridge ( / PLAN 1.3) ────────────

    /**
     * Flatten {@code wyrdsekai.skills.*} from the loaded HOCON stack into the
     * flat-key map {@link #create(Map)} reads. Before this bridge the config
     * surface was dead-ended: reference.conf/scroll writes spoke
     * {@code wyrdsekai.skills.openclaw.url} while create() read the bare
     * {@code openclaw.url} from a map nothing populated — so every
     * config-dependent executor (Kiwix, Home Assistant, OpenClaw gateway, …)
     * silently never registered.
     */
    public static Map<String, String> configFromHocon() {
        try {
            return configFromHocon(ConfigFactory.load());
        } catch (Exception e) {
            LOG.warning("SkillBootstrap: config load failed — no skill config: " + e.getMessage());
            return Map.of();
        }
    }

    /** Testable overload of {@link #configFromHocon()}. */
    public static Map<String, String> configFromHocon(Config config) {
        var out = new LinkedHashMap<String, String>();
        if (config == null || !config.hasPath("wyrdsekai.skills")) return out;
        try {
            var skills = config.getConfig("wyrdsekai.skills");
            for (var entry : skills.entrySet()) {
                var value = entry.getValue().unwrapped();
                if (value != null) {
                    out.put(entry.getKey(), String.valueOf(value));
                }
            }
        } catch (Exception e) {
            LOG.warning("SkillBootstrap: wyrdsekai.skills unreadable: " + e.getMessage());
        }
        return out;
    }

    // ── SKILL.md import ( — OpenClaw / ClawHub / Hermes) ──

    /**
     * Import SKILL.md skills into {@code registry}: seed the bundled
     * {@code resources/openclaw-skills/} definitions into {@code skillsDir}
     * (copy-if-absent, so a steward can edit or delete them), then scan the
     * whole directory. Modern frontmatter skills register as PROMPT-tier via
     * {@link ImportedSkillMdExecutor} ONLY when their required bins/env are
     * actually present — a skill whose CLI is missing stays off the live
     * surface (the Library's installer view is where "available but not
     * installed" belongs; offering an unrunnable skill to the model is the
     * talks-but-can't-do failure). Legacy structured SKILL.md files bind
     * their tools to {@link CliSkillExecutor} when the binary is on PATH.
     *
     * @return the number of skills registered LIVE
     */
    public static int importSkillMd(SkillRegistry registry, Path skillsDir) {
        return importSkillMd(registry, skillsDir,
            Path.of(System.getProperty("user.home", "."), ".hermes", "skills"));
    }

    /** Variant with an explicit Hermes root (tests; multi-user daemons). */
    public static int importSkillMd(SkillRegistry registry, Path skillsDir, Path hermesDir) {
        var importer = new SkillMdImporter();
        var promptExec = new ImportedSkillMdExecutor();
        var cliExec = new CliSkillExecutor();

        seedBundledSkills(skillsDir);

        // Scan order decides precedence on same-named dirs: the wyrdsekai
        // skills dir wins over the user's Hermes library. ~/.hermes/skills is
        // the agentskills.io convention (Hermes Agent's source of truth) —
        // same SKILL.md format, so a person's existing skill library carries
        // straight over. Both roots are steward-owned local dirs (same trust).
        var seen = new HashSet<String>();
        int[] counts = {0, 0}; // live, dormant
        importSkillDir(registry, skillsDir, importer, promptExec, cliExec, seen, counts);
        if (hermesDir != null && Files.isDirectory(hermesDir)) {
            importSkillDir(registry, hermesDir, importer, promptExec, cliExec, seen, counts);
        }

        if (promptExec.size() > 0) registry.registerExecutor(promptExec);
        registry.registerExecutor(cliExec);
        LOG.info("SkillBootstrap: SKILL.md import — " + counts[0] + " live, " + counts[1]
            + " dormant (missing bins/env)");
        return counts[0];
    }

    private static void importSkillDir(SkillRegistry registry, Path root,
                                        SkillMdImporter importer,
                                        ImportedSkillMdExecutor promptExec,
                                        CliSkillExecutor cliExec,
                                        Set<String> seen, int[] counts) {
        if (!Files.isDirectory(root)) {
            LOG.info("SkillBootstrap: no skills directory at " + root);
            return;
        }
        List<Path> dirs;
        try (var stream = Files.list(root)) {
            dirs = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            LOG.warning("SkillBootstrap: cannot list " + root + ": " + e.getMessage());
            return;
        }
        int live = 0;
        int dormant = 0;

        for (var dir : dirs) {
            if (!seen.add(dir.getFileName().toString())) continue; // earlier root wins
            var skillMd = Files.exists(dir.resolve("SKILL.md"))
                ? dir.resolve("SKILL.md") : dir.resolve("skill.md");
            if (!Files.exists(skillMd)) continue;
            String content;
            try {
                content = Files.readString(skillMd);
            } catch (IOException e) {
                LOG.warning("SkillBootstrap: unreadable " + skillMd + ": " + e.getMessage());
                continue;
            }
            var cliName = dir.getFileName().toString();

            var modern = importer.importModern(content, "workshop");
            if (modern.isPresent()) {
                var skill = modern.get();
                var missing = missingPreconditions(skill.requiredBins(), skill.requiredEnv());
                if (missing.isEmpty()) {
                    promptExec.register(skill.definition(), skill.instructions());
                    live++;
                } else {
                    // No silent caps: name exactly what keeps it dormant.
                    dormant++;
                    LOG.info("SkillBootstrap: '" + skill.definition().id()
                        + "' imported but DORMANT — missing " + missing);
                }
                continue;
            }

            // Legacy structured format — one CLI-tier tool per ## heading.
            try {
                var defs = importer.importFromMarkdown(skillMd, cliName, "workshop");
                if (defs.isEmpty()) continue;
                if (!isInPath(cliName)) {
                    dormant += defs.size();
                    LOG.info("SkillBootstrap: '" + cliName + "' skills imported but DORMANT — "
                        + "binary '" + cliName + "' not on PATH");
                    continue;
                }
                for (var def : defs) {
                    cliExec.registerBinding(new CliSkillExecutor.CliSkillBinding(
                        def.id(), cliName, def, null, Map.of()));
                    live++;
                }
            } catch (IOException e) {
                LOG.warning("SkillBootstrap: failed to import " + skillMd + ": " + e.getMessage());
            }
        }

        counts[0] += live;
        counts[1] += dormant;
    }

    /**
     * Copy the bundled {@code openclaw-skills/<name>/SKILL.md} resources into
     * {@code skillsDir} if absent. The bundle ships an {@code index.txt}
     * naming its skill dirs because jar-internal directory listing is not
     * reliable across launchers. Existing files are never overwritten — the
     * steward owns the on-disk copy.
     */
    static void seedBundledSkills(Path skillsDir) {
        var loader = SkillBootstrap.class.getClassLoader();
        List<String> names;
        try (var idx = loader.getResourceAsStream("openclaw-skills/index.txt")) {
            if (idx == null) return; // no bundle on this classpath
            names = new String(idx.readAllBytes()).lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList();
        } catch (IOException e) {
            LOG.warning("SkillBootstrap: bundle index unreadable: " + e.getMessage());
            return;
        }
        int seeded = 0;
        for (var name : names) {
            if (!name.matches("[a-z0-9][a-z0-9_-]*")) continue;
            var target = skillsDir.resolve(name).resolve("SKILL.md");
            if (Files.exists(target)) continue;
            try (var in = loader.getResourceAsStream("openclaw-skills/" + name + "/SKILL.md")) {
                if (in == null) {
                    LOG.warning("SkillBootstrap: bundle index names '" + name
                        + "' but the resource is missing");
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.write(target, in.readAllBytes());
                seeded++;
            } catch (IOException e) {
                LOG.warning("SkillBootstrap: failed to seed '" + name + "': " + e.getMessage());
            }
        }
        if (seeded > 0) {
            LOG.info("SkillBootstrap: seeded " + seeded + " bundled SKILL.md skills into " + skillsDir);
        }
    }

    /** Which of the declared bins/env preconditions are absent on this host. */
    private static List<String> missingPreconditions(List<String> bins, List<String> env) {
        var missing = new ArrayList<String>();
        for (var bin : bins) {
            if (!isInPath(bin)) missing.add("bin:" + bin);
        }
        for (var name : env) {
            var v = System.getenv(name);
            if (v == null || v.isBlank()) missing.add("env:" + name);
        }
        return missing;
    }

    static boolean isInPath(String binary) {
        if (binary == null || binary.isBlank()) return false;
        try {
            var os = System.getProperty("os.name", "").toLowerCase();
            var pb = os.contains("win")
                ? new ProcessBuilder("where", binary)
                : new ProcessBuilder("which", binary);
            pb.redirectErrorStream(true);
            var p = pb.start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Parse "Name:phone[:relation],…" into emergency contacts. */
    static List<EmergencyCallSkillExecutor.EmergencyContact> parseEmergencyContacts(String spec) {
        if (spec == null || spec.isBlank()) return List.of();
        var out = new ArrayList<EmergencyCallSkillExecutor.EmergencyContact>();
        for (var entry : spec.split(",")) {
            var parts = entry.trim().split(":", 3);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) continue;
            out.add(new EmergencyCallSkillExecutor.EmergencyContact(
                parts[0].trim(), parts[1].trim(),
                parts.length > 2 ? parts[2].trim() : ""));
        }
        return List.copyOf(out);
    }

    private static Map<String, Path> parseMounts(String spec) {
        Map<String, Path> mounts = new LinkedHashMap<>();
        for (String entry : spec.split(",")) {
            String[] parts = entry.trim().split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                mounts.put(parts[0].trim(), Path.of(parts[1].trim()));
            }
        }
        return mounts;
    }

    private static List<Path> parseRoots(String spec) {
        List<Path> roots = new ArrayList<>();
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                roots.add(Path.of(trimmed));
            }
        }
        return roots;
    }

    private static void register(SkillRegistry registry, SkillExecutor executor) {
        try {
            registry.registerExecutor(executor);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to register executor: " + executor.getClass().getSimpleName(), e);
        }
    }
}
