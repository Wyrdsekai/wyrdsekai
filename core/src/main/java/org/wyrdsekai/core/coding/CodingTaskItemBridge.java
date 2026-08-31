package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.common.model.RoomObject;
import org.wyrdsekai.common.protocol.S2CMessage;
import org.wyrdsekai.core.agent.AgentEvent;
import org.wyrdsekai.core.familiar.DynamicFormValidator;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestParser;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Generic event bridge: listens on {@link
 * org.wyrdsekai.core.agent.AgentEventStream} for any
 * {@link AgentEvent.ZoneBroadcast} whose namespace matches a registered
 * {@link BackendAdapter}, and places the resulting {@link CodingArtifact}
 * into the originating room.
 *
 * <p>. Replaces the codezaiku-specific
 * {@link org.wyrdsekai.core.codezaiku.CodeZaikuItemBridge}, which now
 * delegates to this class.</p>
 */
public class CodingTaskItemBridge implements Consumer<AgentEvent> {

    private static final Logger log = LoggerFactory.getLogger(CodingTaskItemBridge.class);

    private final BackendRegistry registry;
    private final Consumer<RoomItemPlacement> roomObjectPlacer;

    /**
     * @param registry         the process-wide backend registry; the bridge
     *                         dispatches events through whichever adapter
     *                         is registered for the inbound namespace.
     * @param roomObjectPlacer callback that places the generated room
     *                         object(s) into the originating room.
     */
    public CodingTaskItemBridge(BackendRegistry registry,
                                 Consumer<RoomItemPlacement> roomObjectPlacer) {
        this.registry = registry;
        this.roomObjectPlacer = roomObjectPlacer;
    }

    /** Where to place a generated set of room objects. */
    public record RoomItemPlacement(String roomId, List<RoomObject> objects) {}

    /**
     * What registration decided. {@code registered} means the item is usable;
     * {@code problems} carries WHY it is not, when known — the honest-placement
     * input. Both false/empty means there was nothing to judge (no .js, an
     * unreadable workspace): the legacy router path, not a known-broken item.
     */
    public record RegistrationOutcome(boolean registered, List<String> problems) {
        static final RegistrationOutcome NOTHING_TO_JUDGE =
            new RegistrationOutcome(false, List.of());
        static RegistrationOutcome ok() { return new RegistrationOutcome(true, List.of()); }
        boolean knownBroken() { return !registered && !problems.isEmpty(); }
    }

    /**
     * The honest face of an item that did not pass its checks. Until 2026-08-27 a
     * failed registration placed the object wearing its own manifest description —
     * "Draft a reading list from saved passages…" on a file that crashes on first
     * use. The person examining it had no way to tell; the only honesty was a log
     * line. The description now says what it is, the state map carries a machine-
     * readable flag, and the maker's intent is kept so the thing is still
     * recognisably what was asked for.
     */
    static RoomObject markUnfinished(RoomObject o, List<String> problems) {
        var why = problems.isEmpty() ? "it did not pass its checks"
            : problems.getFirst();
        if (why.length() > 220) why = why.substring(0, 220) + "…";
        var desc = "UNFINISHED — built, but it does not work yet and cannot be used. "
            + "Meant to be: " + o.description() + " What still fails: " + why
            + " It can be taken back to the workshop for repair.";
        return new RoomObject(o.id(), o.name(), desc, o.takeable(), o.visible(),
            o.cloneable(), o.aliases(), Map.of("needs_repair", "true"));
    }

    @Override
    public void accept(AgentEvent event) {
        if (!(event instanceof AgentEvent.ZoneBroadcast zb)) return;

        var adapter = registry.adapterFor(zb.namespace()).orElse(null);
        if (adapter == null) {
            // Not a coding backend event — drop silently. Other namespaces
            // (IoT, observability, …) are handled by their own listeners.
            return;
        }

        CodingArtifact artifact;
        try {
            artifact = adapter.translateEvent(zb);
        } catch (Exception e) {
            log.warn("Adapter {} failed to translate event in room {}: {}",
                adapter.namespace(), zb.roomId(), e.getMessage());
            return;
        }
        if (artifact == null) {
            // NOT SILENTLY. A terminal broadcast the adapter does not recognise is a
            // finished build that will never reach a room, and until 2026-08-23 this line
            // said nothing about it: CodeZaiku's first two runs on staging produced real
            // files and vanished here with no log at all.
            if (zb.message() instanceof S2CMessage.ZoneResponse zr && zr.data() != null
                    && zr.data().has("event")) {
                log.warn("CodingTaskItemBridge: adapter '{}' made no artifact from event '{}' "
                    + "(files={}) — a finished task that will not be placed",
                    adapter.namespace(), zr.data().path("event").asText(),
                    zr.data().path("files").size());
            }
            return;
        }

        var roomObjects = new ArrayList<RoomObject>();
        if (!placeable(artifact)) {
            // A run that touched no files made nothing. Placing a "codex" for it — and
            // handing that to the person who asked — is how an empty object reached the
            // steward's hands on 2026-08-21 after goose never reached a model.
            log.info("CodingTaskItemBridge: task {} produced no files — nothing to place",
                artifact.taskId());
            return;
        }
        var primary = toRoomObject(artifact);
        if (primary != null) {
            var outcome = stampRegistry(primary, artifact);
            if (outcome != null && outcome.knownBroken()) {
                primary = markUnfinished(primary, outcome.problems());
            }
            roomObjects.add(primary);
        }

        // Best-effort: pull any sibling artifacts the adapter knows about
        // (e.g. CodeZaiku emits a SourceArtifact + BuildArtifact pair from
        // a single board completion). Adapters can stash siblings on the
        // primary's backendMetadata; we don't presume the shape here.
        if (artifact instanceof SourceArtifact src && src.backendMetadata() != null) {
            var sibling = src.backendMetadata().get("__sibling_build");
            if (sibling instanceof BuildArtifact ba) {
                var rb = toRoomObject(ba);
                if (rb != null) {
                    roomObjects.add(rb);
                    stampRegistry(rb, ba);   // build artifact: never a scripted item
                }
            }
        }

        if (roomObjects.isEmpty()) return;
        roomObjectPlacer.accept(new RoomItemPlacement(zb.roomId(), roomObjects));
        log.info("Placed {} {} item(s) in room {} (task {})",
            roomObjects.size(), artifact.backend(), zb.roomId(), artifact.taskId());
    }

    /**
     * Stamp the {@link CodingItemRegistry} with the link between this
     * placed RoomObject id and the underlying backend + artifact id.
     * RoomActor.onUseObject reads
     * this on {@code use <id> <verb>} to route through the router
     * instead of the room script's regular {@code onUse} hook.
     *
     * <p>Also tries to register the artifact's GraalJS file with
     * {@link org.wyrdsekai.core.item.ScriptedItemLoader} so {@code use}
     * resolves through {@link
     * org.wyrdsekai.scripting.sandbox.ItemScriptExecutor} (the
     * items-as-tools path) instead of needing the backend's
     * {@code runArtifact}. Best-effort — when the agent didn't produce a
     * matching {@code .js} (or didn't follow the manifest contract), we
     * fall through to the legacy router path silently.</p>
     */
    private static RegistrationOutcome stampRegistry(RoomObject roomObject, CodingArtifact artifact) {
        if (roomObject == null || artifact == null) return null;
        var kind = artifact instanceof SourceArtifact ? "codex" : "artifact";
        CodingItemRegistry.get().stamp(new CodingItemMetadata(
            roomObject.id(),
            artifact.backend(),
            artifact.taskId(),
            artifact.artifactId(),
            kind));
        if (artifact instanceof SourceArtifact src) {
            return tryRegisterScriptedItem(roomObject, src);
        }
        return null;
    }

    /**
     * Look for {@code <name>.js} files in the source artifact's workspace
     * and register them with {@link
     * org.wyrdsekai.core.item.ScriptedItemLoader}. The contract (per
     * {@code OpenHandsBackend.ITEMS_AS_TOOLS_PREAMBLE}): agent writes one
     * {@code .js} at the workspace root with an
     * {@code exports.manifest = {name: "..."}} and an
     * {@code invoke(params)} function. We rely on the loader's own
     * manifest validator to reject malformed files.
     *
     * <p>Workspace path interpretation: the recorded
     * {@code workspacePath} is what the backend told us (already
     * host-remapped by {@link OpenHandsBackend}'s
     * {@link CodingWorkspaceMount} when configured). Reusable across
     * coding backends — Goose / Cline / Continue land here too once
     * their adapters wire the same items-as-tools prompt.</p>
     */
    // Public for testability — the in-package CodingTaskItemBridgeEmbodimentRejectTest
    // invokes this with hand-crafted artifacts to assert the §18 REJECT gate;
    // GooseLiveInvocationE2ETest (tier3, different package) invokes it with the
    // actual file a real goose run wrote against the local 9B. Production
    // callers reach here via the event-bridge placement flow (stampRegistry).
    public static RegistrationOutcome tryRegisterScriptedItem(RoomObject roomObject, SourceArtifact src) {
        var workspace = src.workspacePath();
        if (workspace == null || workspace.isBlank()) return RegistrationOutcome.NOTHING_TO_JUDGE;
        var root = Path.of(workspace);
        if (!Files.isDirectory(root)) {
            log.debug("CodingTaskItemBridge: workspace {} not host-readable; "
                + "skipping ScriptedItemLoader registration", workspace);
            return RegistrationOutcome.NOTHING_TO_JUDGE;
        }
        // Prefer the agent's declared file list. Fall back to scanning
        // the workspace root for any *.js the loader can parse.
        var candidates = new ArrayList<Path>();
        if (src.files() != null) {
            for (var f : src.files()) {
                if (f == null || f.isBlank()) continue;
                if (!f.toLowerCase().endsWith(".js")) continue;
                var p = root.resolve(f.startsWith("/")
                    ? f.substring(f.lastIndexOf('/') + 1) : f);
                if (Files.isRegularFile(p)) candidates.add(p);
            }
        }
        if (candidates.isEmpty()) {
            try (var stream = Files.list(root)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .forEach(candidates::add);
            } catch (Exception e) {
                log.debug("CodingTaskItemBridge: workspace {} list failed: {}",
                    workspace, e.getMessage());
                return RegistrationOutcome.NOTHING_TO_JUDGE;
            }
        }
        if (candidates.isEmpty()) return RegistrationOutcome.NOTHING_TO_JUDGE;
        // COMPLIANT FILES FIRST. The loop below registers the first candidate that
        // passes and stops. Live 2026-08-22 20:44 goose wrote the same weather tool
        // three times — weather-loft-tool.js (hyphenated, no weather calls),
        // weather_loft.js and weather_loft_tool.js (both real) — and declared order put
        // the broken one first: it was REJECTED, and the two working ones beside it were
        // never looked at. manifestScript() already picks compliance-first for the room
        // object's name; registration has to agree with it, or the object is named for
        // a file that never registers. Stable sort: declared order survives within a group.
        candidates.sort(Comparator.comparingInt(p -> {
            try {
                return ItemContractCheck.isCompliant(
                    Files.readString(p), p.getFileName().toString()) ? 0 : 1;
            } catch (Exception e) {
                return 1;
            }
        }));
        var loader = ScriptedItemLoader.get();
        var codingRegistry = CodingItemRegistry.get();
        for (var p : candidates) {
            // v1.5 — pre-check the embodiment block
            // BEFORE handing to ScriptedItemLoader.register so we can emit
            // a steward-visible denial that names the missing/invalid
            // shape. Same gate as hot-reload (ManifestEmbodimentMissingException);
            // the steward needs to see this in the bridge log to know which
            // agent-authored artifact got rejected and why.
            try {
                var script = Files.readString(p);
                var manifest = ItemManifestParser.parse(script);
                var manifestName = manifest != null ? manifest.name() : p.getFileName().toString();
                DynamicFormValidator.requireEmbodiment(
                    script, manifestName);
                // Items-as-tools contract — same pre-check idea for the
                // `commands` block and the invoke()/execute() entrypoint,
                // BEFORE handing to ScriptedItemLoader.register (which
                // enforces both on its no-migration path anyway). The
                // pre-check exists so the bridge log NAMES what the agent
                // got wrong; the artifact stays placed as a plain legacy
                // artifact either way.
                ItemManifestValidator.requireCommands(
                    manifest, /* allowMigration */ false, manifestName);
                // Ask the RUNTIME whether there is a function to call, not the text.
                // ScriptedItemLoader.hasEntrypoint is a substring check, and on
                // 2026-08-21 it said yes to a `function invoke` sealed inside
                // `(function (exports) { ... })(exports)` — unreachable by any caller.
                // The file passed here, the smoke below refused it, and the two gates
                // disagreeing is what put an unusable item in a person's hands. One
                // definition now, shared with the repair loop.
                var entrypoint = ItemContractCheck.entrypointProblem(script, manifestName);
                if (entrypoint.isPresent()) {
                    log.warn("CodingTaskItemBridge: contract REJECT registering "
                        + "agent-authored scripted item from {} (RoomObject {}): {} "
                        + "Falling back to plain artifact placement (legacy router path).",
                        p, roomObject.id(), entrypoint.get());
                    continue;
                }
                // Invoke-once smoke (2026-08-16, steward-mandated). The
                // manifest checks above prove structure; this proves the
                // code path actually EXECUTES — the gap that let a
                // loader-valid item crash (or silently misbehave) on its
                // first real `use`. REJECT = own-code failure the first
                // person to touch it was guaranteed to hit; INCONCLUSIVE
                // = the stub harness couldn't model what the item needs,
                // which must never kill a legitimate item.
                var smoke = ItemInvokeSmoke.run(
                    manifest != null ? manifest.name() : p.getFileName().toString(),
                    script, manifest);
                switch (smoke.verdict()) {
                    case REJECT -> {
                        log.warn("CodingTaskItemBridge: invoke-once REJECT registering "
                            + "agent-authored scripted item from {} (RoomObject {}): {} "
                            + "— the item would break on first use. Falling back to "
                            + "plain artifact placement (legacy router path).",
                            p, roomObject.id(), smoke.detail());
                        continue;
                    }
                    case INCONCLUSIVE -> log.warn(
                        "CodingTaskItemBridge: invoke-once smoke INCONCLUSIVE for {} "
                            + "(RoomObject {}): {} — registering anyway (harness "
                            + "limitation, not an item fault).",
                        p, roomObject.id(), smoke.detail());
                    case PASS -> log.debug(
                        "CodingTaskItemBridge: invoke-once smoke PASS for {}", p);
                }
            } catch (ItemManifestValidator.ManifestEmbodimentMissingException ex) {
                var denial = DynamicFormValidator.denialFrom(
                    ex, p.getFileName().toString());
                log.error("CodingTaskItemBridge: §18 REJECT registering agent-authored "
                    + "scripted item from {} (RoomObject {}): [{}] {}",
                    p, roomObject.id(), denial.messageKey(), denial.detail());
                continue;
            } catch (ItemManifestValidator.ManifestCommandsMissingException ex) {
                log.warn("CodingTaskItemBridge: contract REJECT registering "
                    + "agent-authored scripted item from {} (RoomObject {}): {} "
                    + "Falling back to plain artifact placement (legacy router path).",
                    p, roomObject.id(), ex.getMessage());
                continue;
            } catch (IOException ioe) {
                log.debug("CodingTaskItemBridge: read failed for {} during contract pre-check: {}",
                    p, ioe.getMessage());
                continue;
            }
            var def = loader.register(p);
            if (def.isEmpty()) {
                // The loader logs WHY. What was missing is that this file is now going out
                // as an inert artifact — on 2026-08-22 the only other line said
                // "Placed 1 goose item(s)", which reads as success for a tool that does
                // not exist. Say plainly what the person is about to be handed.
                log.warn("CodingTaskItemBridge: {} did not register — it will be placed as "
                    + "an inert artifact, NOT a usable tool", p);
            }
            if (def.isPresent()) {
                var scriptedId = def.get().itemId();
                log.info("CodingTaskItemBridge: registered scripted item '{}' "
                    + "from {} (placed as RoomObject '{}')",
                    scriptedId, p, roomObject.id());
                // Keep it. Registration is in-memory and the script lives in the task's
                // scratch workspace, which nothing scans at boot — so an accepted item
                // worked until the next restart and then quietly stopped existing, while
                // its RoomObject and inventory row survived (live 2026-08-20). Copy it
                // where the loader looks, under the DATA root so a package upgrade cannot
                // take away something she made.
                keepAcceptedItem(p, scriptedId);
                // Re-stamp the side-registry with the scripted item id so
                // RoomActor.dispatchCodingItemUse can resolve the placed
                // codex-XYZ RoomObject back to the registered manifest
                // and route through ItemScriptExecutor instead of the
                // legacy LocalCommandRouter / runArtifact path.
                var existing = codingRegistry.lookup(roomObject.id()).orElse(null);
                if (existing != null) {
                    codingRegistry.stamp(new CodingItemMetadata(
                        existing.roomObjectId(),
                        existing.backend(),
                        existing.taskId(),
                        existing.artifactId(),
                        existing.kind(),
                        scriptedId));
                }
                // Take only the first manifest-bearing file. The
                // items-as-tools contract is one file per task — extra
                // .js files (helpers, tests) shouldn't shadow the
                // primary item.
                return RegistrationOutcome.ok();
            } else {
                log.debug("CodingTaskItemBridge: {} did not register "
                    + "(no parseable manifest) — `use` will fall through",
                    p.getFileName());
            }
        }
        // Nothing registered. Report WHY, from the best (compliance-sorted first)
        // candidate — that is the file the person's object is named for.
        var best = candidates.getFirst();
        List<String> problems;
        try {
            problems = ItemContractCheck.problems(
                Files.readString(best), best.getFileName().toString());
        } catch (Exception e) {
            problems = List.of("the item file could not be read: " + e.getMessage());
        }
        if (problems.isEmpty()) {
            // The checker passes it but the loader refused (their agreement has
            // drifted before — 2026-08-22 web-sight). Honest, not silent.
            problems = List.of("the item passed its pre-checks but the loader "
                + "refused it — see the server log");
        }
        return new RegistrationOutcome(false, problems);
    }

    /** Is there anything here to put in a room? A source artifact with no files is not. */
    static boolean placeable(CodingArtifact a) {
        if (a == null) return false;
        if (!(a instanceof SourceArtifact s)) return true;
        if (s.files() == null || s.files().isEmpty()) return false;
        // A CLAIM of files is not a file. Live on staging 2026-08-22: goose returned
        // SUCCEEDED naming a file it had not written — the workspace directory was empty —
        // and this guard, which reads the claim, passed it straight through to "Placed 1
        // goose item(s)". The steward would have picked up an object with nothing inside.
        // The list says what it MEANT to write; the disk says what it wrote.
        if (s.workspacePath() == null || s.workspacePath().isBlank()) {
            return true;   // nowhere to verify against — trust the claim rather than drop work
        }
        Path root;
        try {
            root = Path.of(s.workspacePath());
            if (!Files.isDirectory(root)) return true;   // same: unverifiable, not disproved
        } catch (Exception e) {
            return true;
        }
        for (var candidate : candidateScripts(root, s.files())) {
            if (Files.isRegularFile(candidate)) return true;
        }
        for (var declared : s.files()) {
            if (declared != null && !declared.isBlank()
                    && Files.isRegularFile(Path.of(declared))) {
                return true;
            }
        }
        log.info("CodingTaskItemBridge: task {} named {} file(s) that are not on disk — "
            + "nothing was made, so nothing is placed", s.taskId(), s.files().size());
        return false;
    }

    /**
     * Render a {@link CodingArtifact} as a {@link RoomObject}.
     *
     * <p>Default rendering is a takeable, visible item with a one-line
     * description. Backend-specific extras (CodeZaiku board ID, language,
     * etc.) live in {@link SourceArtifact#backendMetadata() backendMetadata}
     * — adapters that want richer rendering can override this method via
     * the {@link BackendAdapter} hook in a future revision.</p>
     */
    private static RoomObject toRoomObject(CodingArtifact a) {
        if (a == null) return null;
        var idPrefix = a instanceof SourceArtifact ? "codex" : "artifact";
        var desc = describe(a);
        var roomId = idPrefix + "-" + shortId(a.artifactId());
        // Name it what it IS. Every source artifact used to be called "codex", so a
        // person asking for a library tool got an object called "codex" and no way to
        // connect the two — and a second one made both unaddressable. The item names
        // itself in its own manifest; use that.
        var label = a instanceof SourceArtifact src
            ? manifestNameOf(src).orElse(idPrefix) : idPrefix;
        // Prefer what the item says about itself over the codex boilerplate — a uuid and
        // a file count tell a person nothing about what they are holding.
        if (a instanceof SourceArtifact src2) {
            var told = manifestDescription(src2, label).orElse(null);
            if (told != null) desc = told;
        }
        return new RoomObject(roomId, label, desc, true, true);
    }

    /**
     * The name the agent gave its own item, read from the {@code .js} it wrote.
     *
     * <p>Best-effort and silent on failure: a run that produced no manifest-bearing file
     * still gets placed, just under the generic label. Reading the file here costs one
     * small read on a path we are about to read anyway during registration.
     */
    static Optional<String> manifestNameOf(SourceArtifact src) {
        return manifestOf(src).map(ItemManifest::name)
            .filter(n -> n != null && !n.isBlank());
    }

    /**
     * How to actually USE this thing, in the item's own words.
     *
     * <p>An accepted item arrived carrying a description of itself and a list of its
     * commands — the manifest fields that exist precisely to answer "what is this and what
     * do I type" — and the room showed none of it. The steward got
     * {@code "A goose codex containing 1 file(s) for task bd605d46-716c-…"}: a uuid and a
     * file count. Live 2026-08-20, his words: <i>"I got the item but I don't know how to
     * actually use it."</i>
     *
     * <p>So render what the item says about itself, and turn each declared command into the
     * literal line a person types.
     */
    static Optional<String> manifestDescription(SourceArtifact src, String itemName) {
        return manifestDescription(src, itemName, willRegister(src));
    }

    /**
     * @param usable whether the item will actually register. When false the declared
     *               commands are NOT rendered as things to type — see {@link #willRegister}
     *               for the day that mattered — and the description says plainly that the
     *               thing arrived unfinished, which is the truth and is actionable.
     */
    static Optional<String> manifestDescription(SourceArtifact src, String itemName,
                                                boolean usable) {
        return manifestOf(src).map(m -> {
            var sb = new StringBuilder();
            if (m.description() != null && !m.description().isBlank()) {
                sb.append(m.description().trim());
            }
            var cmds = usable ? m.commands() : null;
            if (!usable) {
                if (sb.length() > 0) sb.append(' ');
                sb.append("It came back unfinished — the workshop could not make it"
                    + " runnable, so there is nothing to `use` yet.");
            }
            if (cmds != null && !cmds.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(cmds.size() == 1 ? "Use it with: " : "Use it with — ");
                for (int i = 0; i < cmds.size(); i++) {
                    var c = cmds.get(i);
                    if (i > 0) sb.append("; ");
                    var args = c.args() == null ? "" : c.args().trim();
                    sb.append('`').append("use ").append(itemName);
                    if (!args.isEmpty()) sb.append(' ').append(args);
                    sb.append('`');
                    if (c.label() != null && !c.label().isBlank()) {
                        sb.append(" — ").append(c.label().trim());
                    }
                }
                sb.append('.');
            }
            return sb.length() == 0 ? null : sb.toString();
        }).filter(d -> d != null && !d.isBlank());
    }

    /**
     * Every {@code .js} this run could have written, in the order worth trying.
     *
     * <h2>Why a declared path is not enough</h2>
     * The declared paths come first — they are the run's own account of what it wrote.
     * But an absolute path was previously reduced to its BASENAME and resolved against
     * the workspace root, which silently discards any subdirectory.
     *
     * <p>Live 2026-08-21: goose wrote {@code build/Weather.js} — a perfectly good item,
     * with {@code nominatim.geocode} and {@code openweather.current} in its manifest, the
     * first time the generated surface actually reached an authoring model. The bridge
     * looked for {@code Weather.js} at the root, found nothing, and placed a nameless
     * "goose codex containing 1 file(s)". The work was done and thrown away over a
     * directory separator.
     *
     * <p>So: try the declared paths as given, then as basenames, then WALK the workspace.
     * The workspace is this task's own scratch directory — anything {@code .js} in it was
     * put there by this run.
     */
    static List<Path> candidateScripts(Path root, List<String> declared) {
        var out = new LinkedHashSet<Path>();
        if (declared != null) {
            for (var f : declared) {
                if (f == null || !f.toLowerCase(Locale.ROOT).endsWith(".js")) continue;
                var asGiven = Path.of(f).isAbsolute() ? Path.of(f) : root.resolve(f);
                if (Files.isRegularFile(asGiven)) out.add(asGiven);
                var slash = Math.max(f.lastIndexOf('/'), f.lastIndexOf('\\'));
                if (slash >= 0) {
                    var byName = root.resolve(f.substring(slash + 1));
                    if (Files.isRegularFile(byName)) out.add(byName);
                }
            }
        }
        try (var walk = Files.walk(root, 4)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".js"))
                .forEach(out::add);
        } catch (Exception e) {
            log.debug("Could not walk {} for item scripts: {}", root, e.toString());
        }
        return List.copyOf(out);
    }

    /** Parse the manifest out of the .js this run declared, if it has one. */
    static Optional<ItemManifest> manifestOf(SourceArtifact src) {
        return manifestScript(src).map(p -> {
            try {
                return ItemManifestParser.parse(Files.readString(p));
            } catch (Exception e) {
                return null;
            }
        }).filter(m -> m != null && m.name() != null && !m.name().isBlank());
    }

    /** The .js file this run declared that carries a usable manifest. */
    static Optional<Path> manifestScript(SourceArtifact src) {
        try {
            if (src.workspacePath() == null || src.workspacePath().isBlank()) {
                return Optional.empty();
            }
            var root = Path.of(src.workspacePath());
            if (!Files.isDirectory(root)) return Optional.empty();
            // Prefer a file that will actually REGISTER, not merely the first one with a
            // manifest. The registration loop below takes the first file that passes the
            // gates; this chose the first that parsed, and when a backend answers a name
            // complaint by writing a corrected second file the two disagreed. Live on
            // staging 2026-08-22: the room showed an object called `mediamisc-organizer`
            // (the rejected file) while the item that registered was
            // `organize_media_mirrors`. `use` on the visible name did nothing, and the
            // working tool had no object at all — the person had no name that worked.
            Path firstNamed = null;
            for (var candidate : candidateScripts(root, src.files())) {
                var source = Files.readString(candidate);
                var manifest = ItemManifestParser.parse(source);
                if (manifest == null || manifest.name() == null || manifest.name().isBlank()) {
                    continue;
                }
                if (firstNamed == null) firstNamed = candidate;
                if (ItemContractCheck.isCompliant(
                        source, candidate.getFileName().toString())) {
                    return Optional.of(candidate);
                }
            }
            // Nothing clean: fall back to the first named file, so a task that produced
            // only a broken item still gets placed and still says what it was meant to be.
            if (firstNamed != null) return Optional.of(firstNamed);
        } catch (Exception e) {
            log.debug("Could not read a manifest name for {}: {}",
                src.taskId(), e.toString());
        }
        return Optional.empty();
    }

    /**
     * Will this file survive the registration gates — i.e. is {@code use <name>} a real promise?
     *
     * <p>Placement happens before registration, so the description is written by something
     * that does not yet know whether the item is real. On 2026-08-21 that produced the
     * cruellest possible version of the failure: the manifest's declared commands were
     * rendered into the room description as literal lines to type, the bridge then refused
     * the file for having no callable {@code invoke()}, and the steward — reading an
     * instruction the world itself had given him — typed {@code use library_query} and was
     * told there was no such object.
     *
     * <p>An item may still be placed when it does not register; the work exists and is his.
     * What it may NOT do is tell him to type something that cannot work.
     */
    static boolean willRegister(SourceArtifact src) {
        return manifestScript(src).map(p -> {
            try {
                return ItemContractCheck.isCompliant(
                    Files.readString(p), p.getFileName().toString());
            } catch (Exception e) {
                return false;
            }
        }).orElse(false);
    }

    /**
     * Copy an accepted item into the household's items directory so it outlives the run.
     *
     * <p>Only ever called after the manifest, embodiment, commands, entrypoint and
     * invoke-once smoke have all passed — a rejected file must not be made permanent.
     * Best-effort: a failure here leaves the item working for this session, which is
     * strictly better than refusing to place it.
     */
    private static void keepAcceptedItem(Path source, String scriptedId) {
        try {
            var dir = ScriptedItemLoader.householdItemsDir();
            if (dir == null) return;
            Files.createDirectories(dir);
            var target = dir.resolve(
                (scriptedId == null || scriptedId.isBlank()
                    ? source.getFileName().toString() : scriptedId + ".js"));
            // Replacing in place is what makes revision better than duplication — but
            // only if the version being replaced is kept. Losing it would make a
            // revision a worse deal than a new item, which at least leaves the working
            // one intact.
            var previous = ItemRevision.archive(dir, scriptedId);
            if (previous.isPresent()) {
                // Same version as what we just archived? Advance it — see bumpIfSame.
                var prevVersion = ItemRevision.versionOf(Files.readString(previous.get()));
                var revised = ItemRevision.bumpIfSame(Files.readString(source), prevVersion);
                Files.writeString(target, revised);
            } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("CodingTaskItemBridge: kept '{}' at {} — it will still be here after "
                + "a restart", scriptedId, target);
        } catch (Exception e) {
            log.warn("CodingTaskItemBridge: could not keep '{}' beyond this session: {}",
                scriptedId, e.toString());
        }
    }

    private static String describe(CodingArtifact a) {
        return switch (a) {
            case SourceArtifact s -> {
                int n = s.files() == null ? 0 : s.files().size();
                yield String.format("A %s codex containing %d file(s) for task %s.",
                    s.backend(), n, s.taskId());
            }
            case BuildArtifact b -> String.format(
                "A %s artifact (status=%s, tests %d/%d) for task %s.",
                b.backend(), b.status(), b.testsPassed(),
                b.testsPassed() + b.testsFailed(), b.taskId());
        };
    }

    private static String shortId(UUID id) {
        if (id == null) return "0";
        return id.toString().substring(0, 8);
    }
}
