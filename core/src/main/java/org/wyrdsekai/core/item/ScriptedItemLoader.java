package org.wyrdsekai.core.item;

import org.wyrdsekai.core.agent.ActionPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.config.WyrdConfig;
import org.wyrdsekai.scripting.api.ItemEmbodimentSpec;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.core.update.ReleaseManifest;
import org.wyrdsekai.scripting.api.ItemApiSurface;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * (Phase A0) — disk-based scripted-item loader.
 *
 * <p>Scans two directories on boot:
 * <ul>
 *   <li>{@code scripts/items/*.js} — bundled with the repo.</li>
 *   <li>{@code ~/.wyrdsekai/items/*.js} — user-installed.</li>
 * </ul>
 * Both are opt-in: a missing directory returns an empty list. Each {@code .js}
 * file's head is parsed for {@code exports.manifest = {...}} via
 * {@link ItemManifestParser}; manifests that fail validation are dropped with
 * a WARN. Duplicate item IDs across files: second wins, with a WARN.</p>
 *
 * <p>Loaded items participate in {@link ToolItemStarterKit#standard()}
 * alongside the JVM-baked ones via
 * {@link ToolItemStarterKit#loadedScriptedItems()}.</p>
 */
public final class ScriptedItemLoader {

    private static final Logger log = LoggerFactory.getLogger(ScriptedItemLoader.class);

    /** Default user-install dir. */
    private static final Path USER_DIR = Paths.get(System.getProperty("user.home", "."),
        ".wyrdsekai", "items");

    /**
     * Where items the household ITSELF made are kept, under the runtime data root.
     *
     * <p>An item a backend authored and the bridge accepted was registered in memory only:
     * the {@code .js} stayed in the task's scratch workspace, which nothing scans at boot.
     * So it worked until the next restart and then quietly stopped existing — the
     * RoomObject and the inventory row survived, the script behind them did not. Live
     * 2026-08-20: {@code library_storyteller} registered, and after the next upgrade the
     * loader was back to 57 items from 1 dir.
     *
     * <p>Deliberately under the DATA root, not {@code scripts/items} in the install root:
     * a package upgrade replaces the install, and a thing she made must not be collateral
     * of a deploy.
     */
    public static Path householdItemsDir() {
        // Same override convention as wyrdsekai.jdbc.url / wyrdsekai.scripts — an
        // operator knob, and what lets this be exercised without a configured data root.
        var override = System.getProperty("wyrdsekai.items.dir");
        if (override != null && !override.isBlank()) return Path.of(override);
        var data = WyrdConfig.get().dataDir();
        return data == null || data.isBlank() ? null : Path.of(data, "items");
    }

    /**
     * Resolve the bundled {@code scripts/items} dir. Same search discipline as
     * Main's room-script lookup (whose comment already warned: without it,
     * ".deb-installed deployments silently disable all room scripts") — a
     * system-install service runs with CWD {@code /}, so a bare relative path
     * finds NOTHING and every furnishing goes dead ("used xxx" and nothing
     * happens; second-node, 2026-07-04). Order: dev-checkout CWD paths, then
     * {@code WyrdConfig.installRoot()} (env WYRDSEKAI_HOME / profile /
     * jar-derived), then the standard package roots.
     */
    static Path resolveBundledDir() {
        var candidates = new ArrayList<Path>();
        candidates.add(Paths.get("scripts", "items"));
        candidates.add(Paths.get("..", "scripts", "items"));
        try {
            var home = WyrdConfig.get().installRoot();
            if (home != null && !home.isBlank()) {
                candidates.add(Path.of(home, "scripts", "items"));
            }
        } catch (RuntimeException ignore) {
            // Config unavailable (exotic test bootstrap) — fall through to fixed roots.
        }
        candidates.add(Path.of("/opt/wyrdsekai/scripts/items"));
        candidates.add(Path.of("/usr/local/wyrdsekai/scripts/items"));
        for (var c : candidates) {
            if (Files.isDirectory(c)) return c;
        }
        return null;
    }

    private static final ScriptedItemLoader INSTANCE = new ScriptedItemLoader();

    private final ConcurrentHashMap<String, ScriptedItemDef> loaded =
        new ConcurrentHashMap<>();

    private final List<Path> searchDirs = new ArrayList<>();

    // items the boot migration pass tagged with a
    // v1-default embodiment shim. Surfaced in data/manifest_audit.json so the
    // steward can plan the per-item rewrite.
    private final List<MigrationAuditEntry> migrationAudit =
        Collections.synchronizedList(new ArrayList<>());

    // Items-as-tools contract — items the boot pass shimmed with a derived
    // default `commands` entry because their manifest declared none. Kept
    // separate from the §18 embodiment audit so each list keeps its own
    // semantics; both land in data/manifest_audit.json.
    private final List<MigrationAuditEntry> commandsAudit =
        Collections.synchronizedList(new ArrayList<>());

    /** Migration audit entry for one item the boot pass shimmed. */
    public record MigrationAuditEntry(String itemId, String path,
                                       Instant at, String reason) {}

    private ScriptedItemLoader() {
        // Production default: bundled (install-root aware) + user dir
        var bundled = resolveBundledDir();
        if (bundled != null) {
            searchDirs.add(bundled);
            log.info("ScriptedItemLoader: bundled items dir {}", bundled.toAbsolutePath());
        } else {
            log.warn("ScriptedItemLoader: no bundled scripts/items dir found "
                + "(cwd, WYRDSEKAI_HOME, /opt, /usr/local) — only user items will load");
        }
        if (Files.isDirectory(USER_DIR)) searchDirs.add(USER_DIR);
        // Items this household made. Created eagerly so a first accepted item has
        // somewhere to land and is picked up on the next boot like any other.
        var made = householdItemsDir();
        if (made != null) {
            try {
                Files.createDirectories(made);
                searchDirs.add(made);
            } catch (Exception e) {
                log.warn("ScriptedItemLoader: could not prepare {} — items the household "
                    + "makes will not survive a restart: {}", made, e.toString());
            }
        }
    }

    public static ScriptedItemLoader get() { return INSTANCE; }

    /** For tests: replace search dirs entirely. */
    public synchronized void setSearchDirs(List<Path> dirs) {
        searchDirs.clear();
        if (dirs != null) searchDirs.addAll(dirs);
    }

    /** All currently loaded items, snapshot. */
    public List<ScriptedItemDef> all() {
        return List.copyOf(loaded.values());
    }

    public Optional<ScriptedItemDef> get(String itemId) {
        return Optional.ofNullable(loaded.get(itemId));
    }

    /** An item whose script calls what this node's world API does not have; loaded, and it will fail on use. */
    public record WiringAuditEntry(String itemId, String path, List<String> unresolved) {}
    private final Map<String, WiringAuditEntry> wiringAudit = new ConcurrentHashMap<>();

    /** A copy of an item that another author's copy of the same id kept out: what was kept, what was not, and why. */
    public record ShadowedEntry(String itemId, String keptPath, String keptAuthor, String keptVersion,
                                String shadowedPath, String shadowedAuthor, String shadowedVersion) {}
    private final List<ShadowedEntry> shadowed = Collections.synchronizedList(new ArrayList<>());

    /** Copies kept out by the author rule on the last scan, for the audit file and the steward. */
    public List<ShadowedEntry> shadowed() { return List.copyOf(shadowed); }

    /** Items loaded with calls that do not resolve here — for the steward, and for `wyrd items check`. */
    public List<WiringAuditEntry> wiringAudit() { return List.copyOf(wiringAudit.values()); }

    /** Re-scan all configured directories. Idempotent. */
    public synchronized List<ScriptedItemDef> reloadAll() {
        loaded.clear();
        // Audits must reset too — otherwise subsequent boot scans pile up
        // duplicate shim entries for the same items.
        migrationAudit.clear();
        commandsAudit.clear();
        wiringAudit.clear();
        shadowed.clear();
        var dirs = scannedDirs();
        for (var dir : dirs) {
            scanDir(dir);
        }
        log.info("ScriptedItemLoader: {} item(s) loaded from {} dir(s){}{}",
            loaded.size(), dirs.size(),
            wiringAudit.isEmpty() ? "" : " — " + wiringAudit.size() + " MIS-WIRED (a missing call fails on use; an unread command does the same thing whatever is asked): " + String.join(", ", wiringAudit.keySet()),
            shadowed.isEmpty() ? "" : " — " + shadowed.size() + " copy(ies) kept out by another author's same-named item: "
                + String.join(", ", shadowed.stream().map(ShadowedEntry::itemId).distinct().toList()));
        return all();
    }

    /**
     * The search dirs with the same directory counted once, however it is spelled.
     *
     * <p>On a .deb host {@code ~/.wyrdsekai} is a symlink to {@code /var/lib/wyrdsekai}, so the
     * user dir and the household dir are ONE directory reached two ways — and every reload
     * warned "duplicate item id … replaces …" for all of her items, one path replacing the
     * other (live 2026-09-03, ~20 warnings per reload, none of them a duplicate). Resolve
     * each dir to its real path and keep the first spelling of each.
     */
    synchronized List<Path> scannedDirs() {
        var seen = new java.util.LinkedHashMap<Path, Path>();
        for (var dir : searchDirs) {
            if (dir == null) continue;
            Path key;
            try {
                key = dir.toRealPath();
            } catch (IOException e) {
                key = dir.toAbsolutePath().normalize();
            }
            seen.putIfAbsent(key, dir);
        }
        return List.copyOf(seen.values());
    }

    /** Convenience for boot: ensure default dirs are picked up + reload. */
    public synchronized List<ScriptedItemDef> bootScan() {
        // Default dirs may not have existed at construction time (fresh install)
        var bundled = resolveBundledDir();
        if (bundled != null && !searchDirs.contains(bundled)) {
            searchDirs.add(bundled);
            log.info("ScriptedItemLoader: bundled items dir {} (boot scan)",
                bundled.toAbsolutePath());
        }
        if (!searchDirs.contains(USER_DIR) && Files.isDirectory(USER_DIR)) {
            searchDirs.add(USER_DIR);
        }
        return reloadAll();
    }

    private void scanDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> stream = Files.list(dir)) {
            stream
                .filter(p -> p.getFileName().toString().endsWith(".js"))
                .sorted()
                .forEach(p -> loadOne(p, /* allowMigration */ true));
        } catch (IOException e) {
            log.warn("ScriptedItemLoader: failed to list {}: {}", dir, e.getMessage());
        }
    }

    private boolean loadOne(Path path) {
        // Default loadOne (called from register()) is hot-reload — fail-fast
        // on missing embodiment.
        return loadOne(path, /* allowMigration */ false);
    }

    /** @return true when {@code path} is what now serves its item id; false when it was skipped or kept out. */
    private boolean loadOne(Path path, boolean allowMigration) {
        try {
            var script = Files.readString(path);
            var manifest = ItemManifestParser.parse(script);
            if (manifest == null) {
                log.warn("ScriptedItemLoader: {} has no parseable exports.manifest — skipped", path);
                return false;
            }
            var validation = ItemManifestValidator.validate(manifest);
            if (!validation.valid()) {
                log.warn("ScriptedItemLoader: {} manifest invalid: {}", path, validation.errors());
                return false;
            }
            for (var w : validation.warnings()) {
                log.info("ScriptedItemLoader: {} manifest warning: {}", path, w);
            }
            // fail-fast embodiment-block gate. Boot scan
            // (allowMigration=true) shims missing items and records them for
            // the audit JSON; hot-reload (allowMigration=false) REJECTS.
            ItemEmbodimentSpec embodiment;
            try {
                var parsed = ItemManifestParser.parseEmbodiment(script);
                embodiment = ItemManifestValidator.requireEmbodiment(
                    parsed, allowMigration, manifest.name());
                if (embodiment.isMigrated()) {
                    migrationAudit.add(new MigrationAuditEntry(
                        manifest.name(), path.toString(),
                        embodiment.migration().at(), embodiment.reason()));
                    log.warn("ScriptedItemLoader: {} loaded with v1-default embodiment shim "
                        + "— see data/manifest_audit.json", path);
                }
            } catch (ItemManifestValidator.ManifestEmbodimentMissingException e) {
                log.error("ScriptedItemLoader: §18 REJECT for {}: {}", path, e.getMessage());
                return false;
            }
            // Items-as-tools contract — fail-fast `commands` gate, same
            // boot-vs-register split as embodiment: boot scan
            // (allowMigration=true) shims a derived default command and
            // records it for the audit JSON; register/hot-reload
            // (allowMigration=false) REJECTS.
            try {
                var commands = ItemManifestValidator.requireCommands(
                    manifest, allowMigration, prettyName(manifest.name()));
                if (manifest.commands().isEmpty()) {
                    manifest = manifest.withCommands(commands);
                    commandsAudit.add(new MigrationAuditEntry(
                        manifest.name(), path.toString(), Instant.now(),
                        "commands missing — derived default '"
                            + commands.getFirst().label() + "' shim applied"));
                    log.warn("ScriptedItemLoader: {} loaded with derived default "
                        + "commands shim — see data/manifest_audit.json", path);
                }
            } catch (ItemManifestValidator.ManifestCommandsMissingException e) {
                log.error("ScriptedItemLoader: commands REJECT for {}: {}",
                    path, e.getMessage());
                return false;
            }
            // Items-as-tools contract — entrypoint presence gate. A scripted
            // item without a callable invoke()/execute() is dead on `use`;
            // reject it at registration rather than at first use. Cheap
            // textual check (same style as WorkbenchValidator) — no JS
            // parsing. Boot scan keeps legacy files alive with a WARN
            // (e.g. onUse-only furnishings), mirroring the shim split.
            if (!hasEntrypoint(script)) {
                if (allowMigration) {
                    log.warn("ScriptedItemLoader: {} has no invoke()/execute() "
                        + "entrypoint — loaded for back-compat, but `use` cannot "
                        + "call it; add `function invoke(params)`", path);
                } else {
                    log.error("ScriptedItemLoader: entrypoint REJECT for {}: script "
                        + "body declares no `function invoke(`/`function execute(` "
                        + "(or exports.invoke/exports.execute assignment) — a "
                        + "scripted item must implement its declared commands", path);
                    return false;
                }
            }
            var itemId = manifest.name();
            var existing = loaded.get(itemId);
            // A NAME THAT IS ALREADY A VERB: the tool list bakes the runtime's own actions in and
            // drops any loaded script with the same id, so an item named after one can never be
            // reached — the companion built "craft_from_template" (the thing she had meant to
            // call) and it was kept as a dead item beside the real action (2026-09-11). Refuse it
            // at the door and say what to do; bundled items are the runtime's own and exempt.
            if (!isBundledPath(path) && !isSystemAuthor(manifest.author())
                    && ActionPolicy.isKnownAction(itemId)) {
                var why = "'" + itemId + "' is the name of a builtin action — an item by that name "
                    + "can never be reached (the builtin always wins). Give it its own name.";
                log.warn("ScriptedItemLoader: {} at {} not loaded: {}", itemId, path, why);
                wiringAudit.put(itemId + "@" + path.getFileName(),
                    new WiringAuditEntry(itemId, path.toString(), List.of(why)));
                return false;
            }
            // AUTHORSHIP: the same id from a different author replaces a loaded copy only when its
            // version is newer. Three items the coding backend wrote (author did:wyrd:openhands,
            // v1.0.0, a third the length) took the names of bundled items (did:wyrd:system, v1.0.0)
            // and, because the household dir scans second, replaced them — the journal that then
            // died on `use` was theirs, not ours (household node 2026-09-08). A steward who means to
            // replace a bundled item bumps the version; an agent who means a new item names it.
            if (existing != null && !sameAuthor(existing.manifest().author(), manifest.author())
                    && ReleaseManifest.compareVersions(manifest.version(), existing.manifest().version()) <= 0) {
                log.warn("ScriptedItemLoader: duplicate item id '{}' — '{}' (author {}, v{}) does not replace '{}' "
                        + "(author {}, v{}): a different author's copy replaces only when its version is newer. "
                        + "Give it its own name, or bump its version if it is meant to replace.",
                    itemId, path, manifest.author(), manifest.version(),
                    existing.sourcePath(), existing.manifest().author(), existing.manifest().version());
                shadowed.add(new ShadowedEntry(itemId, String.valueOf(existing.sourcePath()),
                    existing.manifest().author(), existing.manifest().version(),
                    path.toString(), manifest.author(), manifest.version()));
                return false;
            }
            // WIRING: every world.* call must exist on this node. A copy whose calls do not
            // resolve is broken before its first use (the journal that called a renamed
            // method, household node 2026-09-08). It never replaces a copy that works; alone,
            // it loads with a WARN naming the fix, and the audit carries it for the steward.
            var unresolved = ItemApiSurface.check(script);
            var calls = new ArrayList<String>();
            for (var u : unresolved) calls.add(u.reason());
            // A tool that names commands it does not honour is mis-wired the same way: it says
            // it does something and does not (2026-09-10).
            ItemApiSurface.commandsNeverRead(script,
                manifest.commands() == null ? List.of() : manifest.commands()).ifPresent(calls::add);
            if (!calls.isEmpty()) {
                if (existing != null && !wiringAudit.containsKey(itemId)) {
                    log.warn("ScriptedItemLoader: '{}' at {} calls what this node does not have — keeping the working copy at {}. {}",
                        itemId, path, existing.sourcePath(), String.join(" | ", calls));
                    return false;
                }
                log.warn("ScriptedItemLoader: '{}' at {} is mis-wired{}: {}",
                    itemId, path,
                    unresolved.isEmpty() ? " and does not do what it says" : " and WILL fail on use",
                    String.join(" | ", calls));
                wiringAudit.put(itemId, new WiringAuditEntry(itemId, path.toString(), calls));
            } else {
                wiringAudit.remove(itemId);   // a working copy replaced a broken one
            }
            if (existing != null) {
                log.warn("ScriptedItemLoader: duplicate item id '{}' — '{}' replaces '{}'",
                    itemId, path, existing.sourcePath());
            }
            var def = new ScriptedItemDef(itemId, prettyName(itemId),
                manifest.description(), manifest, script, path);
            loaded.put(itemId, def);
            log.info("ScriptedItemLoader: loaded '{}' v{} ({} caps) from {}",
                itemId, manifest.version(), manifest.capabilities().size(), path);
            return true;
        } catch (IOException e) {
            log.warn("ScriptedItemLoader: read failed {}: {}", path, e.getMessage());
            return false;
        }
    }

    /** A file under the bundled items directory is the runtime's own, whatever author string it carries. */
    static boolean isBundledPath(Path path) {
        var bundled = resolveBundledDir();
        try {
            return bundled != null && path != null
                && path.toAbsolutePath().normalize().startsWith(bundled.toAbsolutePath().normalize());
        } catch (Exception e) {
            return false;
        }
    }

    /** The runtime's own items carry {@code did:wyrd:system}; an absent author is read as ours too. */
    static boolean isSystemAuthor(String author) {
        return author == null || author.isBlank() || "did:wyrd:system".equalsIgnoreCase(author.trim());
    }

    private static boolean sameAuthor(String a, String b) {
        var x = a == null ? "" : a.trim();
        var y = b == null ? "" : b.trim();
        return x.isEmpty() || y.isEmpty() || x.equalsIgnoreCase(y);
    }

    /**
     * Items-as-tools contract — cheap textual entrypoint check. True when the
     * script body contains a callable {@code invoke}/{@code execute}
     * entrypoint: a {@code function invoke(} / {@code function execute(}
     * declaration or an {@code exports.invoke =} / {@code exports.execute =}
     * assignment. Deliberately a contains-check (same style as
     * {@code WorkbenchValidator}) — no JS parsing. Public so
     * {@link org.wyrdsekai.core.coding.CodingTaskItemBridge} can pre-check
     * agent-authored artifacts and name the violation in its own log.
     */
    public static boolean hasEntrypoint(String script) {
        if (script == null) return false;
        return script.contains("function invoke(")
            || script.contains("function execute(")
            || script.contains("exports.invoke =")
            || script.contains("exports.invoke=")
            || script.contains("exports.execute =")
            || script.contains("exports.execute=");
    }

    private static String prettyName(String itemId) {
        if (itemId == null || itemId.isEmpty()) return "Item";
        var parts = itemId.split("_");
        var sb = new StringBuilder();
        for (var p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /**
     * Register a scripted item from a single {@code .js} path at runtime.
     * Used by {@link org.wyrdsekai.core.coding.CodingTaskItemBridge} when an
     * agent-generated artifact lands in a workspace and needs to be
     * picked up by {@code use <id>} without restarting the daemon.
     *
     * <p>Same parsing + validation as {@link #scanDir} — manifests that
     * fail validation are NOT registered (returns empty). A duplicate id
     * replaces the existing entry with a WARN only when the author matches
     * or the version is newer, the same rule as the disk scan; otherwise the
     * loaded copy stands and this returns empty (the loader's log names the
     * fix). Also reusable by Goose / future coding adapters.</p>
     *
     * @param path absolute or repo-relative path to a {@code .js} file.
     * @return the loaded def on success, empty when parsing/validation
     *         failed.
     */
    public synchronized Optional<ScriptedItemDef> register(Path path) {
        if (path == null) return Optional.empty();
        if (!loadOne(path)) return Optional.empty();   // skipped, rejected, or kept out — not this file
        // loadOne keys by manifest.name(); resolve back via that key. We
        // re-parse cheaply to recover the id rather than mutating loadOne
        // — keeping its single-responsibility shape.
        try {
            var script = Files.readString(path);
            var manifest = ItemManifestParser.parse(script);
            if (manifest == null) return Optional.empty();
            return Optional.ofNullable(loaded.get(manifest.name()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Drop a registered item by id. Idempotent — silent no-op when the
     * id is unknown. Used when a coding artifact is destroyed or
     * superseded.
     */
    public synchronized void forget(String itemId) {
        if (itemId == null || itemId.isBlank()) return;
        loaded.remove(itemId);
    }

    /**
     * snapshot of the migration audit list. Items
     * here were auto-shimmed by the boot pass because their manifest had
     * no embodiment block; the steward should replace each shim with an
     * explicit declaration on the next manual edit.
     */
    public List<MigrationAuditEntry> migrationAudit() {
        return List.copyOf(migrationAudit);
    }

    /**
     * Items-as-tools contract — snapshot of the commands-shim audit list.
     * Items here were auto-shimmed by the boot pass with a derived default
     * {@code {label: "Use <name>", args: ""}} command because their manifest
     * declared none; each needs an explicit {@code commands} block on the
     * next manual edit (register/hot-reload already rejects new offenders).
     */
    public List<MigrationAuditEntry> commandsAudit() {
        return List.copyOf(commandsAudit);
    }

    /**
     * write the migration audit to
     * {@code data/manifest_audit.json} for the steward UI. No-ops cleanly
     * when there's nothing to audit (avoids a misleading empty-array file).
     * Atomic via .tmp + rename per established Wyrdsekai pattern.
     */
    public synchronized void writeMigrationAudit(Path auditPath) {
        if (auditPath == null) return;
        try {
            Files.createDirectories(auditPath.getParent());
            var mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("spec", "embodiment/18.3");
            payload.put("writtenAt", Instant.now().toString());
            payload.put("migrationShimVersion",
                ItemEmbodimentSpec.MIGRATION_VERSION);
            payload.put("items", List.copyOf(migrationAudit));
            // Items-as-tools contract — items whose manifests declared no
            // `commands`; boot shimmed a derived default entry for each.
            payload.put("commandsShimmed", List.copyOf(commandsAudit));
            // Items whose world.* calls do not exist on this node — broken before first use.
            payload.put("misWired", List.copyOf(wiringAudit.values()));
            payload.put("shadowed", List.copyOf(shadowed));
            var tmp = auditPath.resolveSibling(auditPath.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), payload);
            Files.move(tmp, auditPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
            log.info("ScriptedItemLoader: wrote manifest audit ({} items needing embodiment) to {}",
                migrationAudit.size(), auditPath);
        } catch (IOException e) {
            log.warn("ScriptedItemLoader: failed to write manifest audit to {}: {}",
                auditPath, e.getMessage());
        }
    }

    /** Diagnostics surface for the {@code wyrd items list} CLI. */
    public Map<String, Map<String, Object>> diagnostics() {
        var out = new LinkedHashMap<String, Map<String, Object>>();
        for (var def : loaded.values()) {
            out.put(def.itemId(), def.manifestSnapshot());
        }
        return out;
    }

    // -----------------------------------------------------------------------
    //  WatchService — auto-reload on disk changes
    //  Closes Phase A0 deferred item: hot-reload of scripts/items/.
    // -----------------------------------------------------------------------

    /** Default debounce window: coalesce events within 500ms into one reload. */
    private static final long WATCH_DEBOUNCE_MS = 500L;

    private volatile Thread watchThread;
    private volatile WatchService watchService;
    private final AtomicReference<Long> lastReloadAt = new AtomicReference<>(0L);

    /**
     * Start a background daemon thread that watches all configured search
     * directories for {@code ENTRY_CREATE}, {@code ENTRY_MODIFY}, and
     * {@code ENTRY_DELETE} events on {@code .js} files, debouncing closely-
     * spaced events into a single {@link #reloadAll()} call.
     *
     * <p>Idempotent: a second call is a no-op while the watcher is alive.
     * Stop with {@link #stopWatching()}.</p>
     */
    public synchronized void startWatching() {
        if (watchThread != null && watchThread.isAlive()) return;
        try {
            var ws = FileSystems.getDefault().newWatchService();
            int registered = 0;
            for (var dir : searchDirs) {
                if (!Files.isDirectory(dir)) continue;
                dir.register(ws,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
                registered++;
            }
            if (registered == 0) {
                ws.close();
                log.debug("ScriptedItemLoader: no directories to watch");
                return;
            }
            this.watchService = ws;
            this.watchThread = Thread.ofVirtual()
                .name("scripted-item-watcher")
                .unstarted(this::watchLoop);
            this.watchThread.start();
            log.info("ScriptedItemLoader: watching {} dir(s) for hot-reload", registered);
        } catch (IOException e) {
            log.warn("ScriptedItemLoader: failed to start watcher: {}", e.getMessage());
        }
    }

    /** Stop the watcher and close the underlying {@link WatchService}. */
    public synchronized void stopWatching() {
        var ws = this.watchService;
        var t = this.watchThread;
        this.watchService = null;
        this.watchThread = null;
        if (ws != null) {
            try { ws.close(); } catch (IOException ignored) {}
        }
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * The poll loop. Runs on a virtual daemon thread. Coalesces events within
     * {@link #WATCH_DEBOUNCE_MS} ms — a series of saves-in-progress on the
     * same file produces a single reload, not five.
     */
    private void watchLoop() {
        var ws = this.watchService;
        if (ws == null) return;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = ws.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    break;
                }
                boolean sawJsEvent = false;
                for (var event : key.pollEvents()) {
                    var ctx = event.context();
                    if (ctx instanceof Path p && p.toString().endsWith(".js")) {
                        sawJsEvent = true;
                    }
                }
                key.reset();
                if (!sawJsEvent) continue;

                // Debounce: settle for the window so burst writes coalesce
                // into a single reload, then drain pending events.
                try {
                    Thread.sleep(WATCH_DEBOUNCE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                // Drain any further keys that piled up while we slept.
                WatchKey extra;
                while ((extra = ws.poll()) != null) {
                    extra.pollEvents();
                    extra.reset();
                }
                try {
                    reloadAll();
                    lastReloadAt.set(System.currentTimeMillis());
                } catch (Exception e) {
                    log.warn("ScriptedItemLoader: hot-reload failed: {}", e.getMessage());
                }
            }
        } finally {
            log.debug("ScriptedItemLoader: watch loop exited");
        }
    }

    /** For tests: epoch ms of the most recent watch-triggered reload (0 if none). */
    public long lastReloadAt() { return lastReloadAt.get(); }
}
