package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.item.ScriptedItemDef;
import org.wyrdsekai.core.item.ScriptedItemLoader;
import org.wyrdsekai.core.update.ReleaseManifest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Changing a tool she already made, instead of making another one.
 *
 * <h2>Why this is missing and what it costs</h2>
 * {@code revise_form} exists for thought-forms. Nothing equivalent existed for scripted
 * items, so every "can you make me…" produced a NEW item — and the only way to change one
 * was to ask for a replacement and live with both.
 *
 * <p>Live 2026-08-21: the steward asked four times for essentially one library tool. Two
 * of the results were refused and one was retired; the survivor,
 * {@code library_stories}, summarises when he wanted it to tell a story. Today making it
 * tell stories means asking for a fifth item that does almost the same thing, and then
 * choosing between them forever.
 *
 * <p>That is the difference between a tool-making system that compounds and one that
 * accumulates. A tool you can amend gets better; a tool you can only replace leaves a
 * trail of near-duplicates, and the person has to remember which of them works.
 *
 * <h2>How it works</h2>
 * Deliberately NOT a second pipeline. A revision is an ordinary dispatch whose instruction
 * happens to carry the current source and the change wanted. Everything downstream is
 * untouched: the same backend, the same contract gates, the same intent check, the same
 * bridge. Because the manifest name is unchanged, the loader replaces the registration and
 * {@link CodingTaskItemBridge} overwrites the kept file — so the item improves in place
 * rather than multiplying.
 */
public final class ItemRevision {

    private static final Logger log = LoggerFactory.getLogger(ItemRevision.class);

    /** Where a superseded version goes before it is overwritten. */
    public static final String VERSIONS_DIR = "versions";

    private ItemRevision() {}

    /**
     * The dispatch instruction for changing an existing item, or empty if no such item.
     *
     * <p>Empty is a real answer: asking to revise something that does not exist should
     * fall back to building it, not silently produce an item from nothing.
     *
     * @param itemName what she calls it — manifest name or display name
     * @param change   the person's words for what should be different
     */
    public static Optional<String> instructionFor(String itemName, String change) {
        if (itemName == null || itemName.isBlank()) return Optional.empty();
        var loader = ScriptedItemLoader.get();
        if (loader == null) return Optional.empty();
        var wanted = normalize(itemName);
        var def = loader.all().stream()
            .filter(d -> wanted.equals(normalize(d.itemId()))
                || wanted.equals(normalize(d.displayName())))
            .findFirst().orElse(null);
        if (def == null) {
            log.debug("[item-revision] nothing registered as '{}' — this is a build, "
                + "not a revision", itemName);
            return Optional.empty();
        }
        var name = def.itemId();
        return Optional.of("""
            REVISE AN EXISTING ITEM. Do not write a new one from scratch, and do not
            change its name — this file already exists in the world, someone is holding
            it, and the point is that it gets BETTER rather than being replaced by a
            near-duplicate.

            Write the revised file to %s.js, keeping:
              - the manifest `name` EXACTLY as "%s"
              - every capability it still needs (add any the change requires)
              - the embodiment and commands blocks (update commands if the change adds
                or removes one)
            and BUMP the manifest `version` (e.g. 1.0.0 -> 1.1.0).
            A REVISION NEVER MAKES THE ITEM LESS REAL. Every `world.<service>.<method>`
            call the current file makes (openweather, nominatim, maps, library, …)
            stays EXACTLY as it is unless the change is about that call. Do NOT replace
            a keyed service call with web.search or web.fetch — a fetch of "current
            temperature in Denver" is a guess at a web page; the service call is the
            answer. If you are unsure a call is still needed, keep it.

            WHAT SHOULD BE DIFFERENT:
            %s

            THE CURRENT FILE, verbatim — start from this, change what the request asks
            for, and leave the rest alone:

            ----------------- BEGIN %s.js -----------------
            %s
            ------------------ END %s.js ------------------
            """.formatted(name, name,
                change == null || change.isBlank()
                    ? "(no change described — ask for clarification in your summary "
                        + "rather than guessing)" : change.trim(),
                name, def.scriptSource(), name));
    }

    /** Is there something registered under this name to revise? */
    public static boolean exists(String itemName) {
        return instructionFor(itemName, "probe").isPresent();
    }

    /**
     * One spelling for an item's name: she wrote "diffusion_practice-3" for an item registered as
     * {@code diffusion_practice_3}, the match was exact, and the workshop built a new tool from
     * nothing beside the one she meant (household node, 2026-10-25).
     */
    static String normalize(String name) {
        if (name == null) return "";
        var n = name.trim().toLowerCase(Locale.ROOT);
        if (n.endsWith(".js")) n = n.substring(0, n.length() - 3);
        return n.replaceAll("[\\s\\-]+", "_").replaceAll("_+", "_");
    }

    private static final Pattern FIX_ASK = Pattern.compile(
        "^\\s*(?:please\\s+)?(?:fix|repair|mend|revise|update|change|improve|patch|correct)\\s+"
            + "(?:the\\s+|my\\s+|this\\s+|that\\s+|item\\s+|tool\\s+)*([^:;,.(\\n]{3,90})",
        Pattern.CASE_INSENSITIVE);

    /** The registered item a "fix X …" request names, by any spelling, or empty. */
    public static Optional<String> revisionNamedIn(String description) {
        var loader = ScriptedItemLoader.get();
        if (loader == null) return Optional.empty();
        return revisionNamedIn(description, loader.all().stream().map(ScriptedItemDef::itemId).toList());
    }

    /** The pure form: which of {@code knownIds} a "fix X …" request names, by any spelling. */
    static Optional<String> revisionNamedIn(String description, Collection<String> knownIds) {
        if (description == null || knownIds == null || knownIds.isEmpty()) return Optional.empty();
        if (description.startsWith("REVISE AN EXISTING ITEM")) return Optional.empty();   // already one
        var m = FIX_ASK.matcher(description);
        if (!m.find()) return Optional.empty();
        // What follows the verb, in one spelling; the longest known name it starts with is the item.
        var asked = normalize(m.group(1).replace("`", "").replace(".js", ""));
        String best = null;
        for (var id : knownIds) {
            var n = normalize(id);
            if (n.isEmpty()) continue;
            if (asked.equals(n) || asked.startsWith(n + "_")) {
                if (best == null || n.length() > normalize(best).length()) best = id;
            }
        }
        return Optional.ofNullable(best);
    }

    private static final Pattern CAPABILITIES = Pattern.compile("capabilities\\s*:\\s*\\[([^\\]]*)\\]");

    private static Set<String> capabilitiesOf(String source) {
        var out = new TreeSet<String>();
        if (source == null) return out;
        var m = CAPABILITIES.matcher(source);
        if (!m.find()) return out;
        for (var part : m.group(1).split(",")) {
            var c = part.trim().replaceAll("^[\"']|[\"']$", "").trim();
            if (!c.isEmpty()) out.add(c);
        }
        return out;
    }

    /**
     * Why a rebuilt file must not replace the one in use, or empty when it may. A rebuild that
     * carries a LOWER version, or that declares fewer abilities and keeps nothing where the one
     * in use kept notes, is a hollow copy: on the household node a 775-byte practice tool with no
     * abilities replaced the 10 KB one that used the model and her notes, at a lower version,
     * and nothing said so (2026-10-25). The one in use stays; the new file is kept beside it.
     */
    public static Optional<String> doesLess(String previousSource, String newSource) {
        if (previousSource == null || newSource == null) return Optional.empty();
        var prevVersion = versionOf(previousSource);
        var newVersion = versionOf(newSource);
        if (!"prev".equals(prevVersion) && !"prev".equals(newVersion)
                && ReleaseManifest.compareVersions(newVersion, prevVersion) < 0) {
            return Optional.of("its version " + newVersion + " is lower than the " + prevVersion + " in use");
        }
        var prevCaps = capabilitiesOf(previousSource);
        var newCaps = capabilitiesOf(newSource);
        var prevKeeps = ItemIntentCheck.persistsAnything(previousSource.toLowerCase(Locale.ROOT));
        var newKeeps = ItemIntentCheck.persistsAnything(newSource.toLowerCase(Locale.ROOT));
        if (newCaps.size() < prevCaps.size() && prevCaps.containsAll(newCaps) && prevKeeps && !newKeeps) {
            return Optional.of("it declares fewer abilities (" + (newCaps.isEmpty() ? "none" : String.join(", ", newCaps))
                + " where the one in use has " + String.join(", ", prevCaps) + ") and keeps nothing where the one in use kept notes");
        }
        return Optional.empty();
    }

    /** Keep a rebuilt file that did not replace the one in use, beside it, as a candidate. */
    public static Optional<Path> keepCandidate(Path itemsDir, String itemId, Path source) {
        if (itemsDir == null || itemId == null || source == null) return Optional.empty();
        try {
            var versions = itemsDir.resolve(VERSIONS_DIR);
            Files.createDirectories(versions);
            var stamp = versionOf(Files.readString(source));
            var target = versions.resolve(itemId + "." + stamp + "-candidate.js");
            int n = 2;
            while (Files.exists(target)) {
                target = versions.resolve(itemId + "." + stamp + "-candidate-" + n++ + ".js");
            }
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("[item-revision] the rebuilt '{}' is kept as a candidate at {}; the one in use stays", itemId, target);
            return Optional.of(target);
        } catch (Exception e) {
            log.warn("[item-revision] could not keep the candidate '{}': {}", itemId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Keep the version being replaced, before it is overwritten.
     *
     * <p>A revision that loses the previous file is a worse deal than a new item: at
     * least a duplicate leaves the working one intact. Lineage is what makes replacing
     * in place safe — {@code revise_form} bumps a version and preserves lineage, and an
     * item deserves the same.
     *
     * <p>Best-effort by design: failing to archive must never stop an improvement
     * landing. Returns the archived path when one was written.
     */
    public static Optional<Path> archive(Path itemsDir, String itemId) {
        if (itemsDir == null || itemId == null || itemId.isBlank()) return Optional.empty();
        var current = itemsDir.resolve(itemId + ".js");
        if (!Files.isRegularFile(current)) return Optional.empty();
        try {
            var versions = itemsDir.resolve(VERSIONS_DIR);
            Files.createDirectories(versions);
            var stamp = versionOf(Files.readString(current));
            var target = versions.resolve(itemId + "." + stamp + ".js");
            int n = 2;
            while (Files.exists(target)) {
                target = versions.resolve(itemId + "." + stamp + "-" + n++ + ".js");
            }
            Files.copy(current, target, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("[item-revision] kept the previous '{}' at {}", itemId, target);
            return Optional.of(target);
        } catch (Exception e) {
            log.warn("[item-revision] could not archive the previous '{}': {}",
                itemId, e.toString());
            return Optional.empty();
        }
    }

    private static final Pattern VERSION =
        Pattern.compile("version\\s*:\\s*[\"']([^\"']+)[\"']");

    /** The manifest version, for naming the archive. Unknown reads as "prev". */
    /**
     * A revision with the same version as what it replaces gets a patch bump from us.
     *
     * <h2>Why</h2>
     * The instruction asks the backend to bump the version; on 2026-08-22 22:17 goose
     * revised trip_compass (kept every adapter, added humidity) and left it at 1.0.0. The
     * archive then held {@code trip_compass.1.0.0.js} AND {@code trip_compass.1.0.0-2.js}
     * — two different programs under one version. Lineage that cannot be told apart is
     * not lineage. The person asked for the change; refusing it over a number serves no
     * one, so the number is corrected here and said aloud in the log.
     *
     * @return the source, with its version advanced if it equalled {@code previousVersion}
     */
    static String bumpIfSame(String source, String previousVersion) {
        if (source == null || previousVersion == null) return source;
        var current = versionOf(source);
        if (!current.equals(previousVersion) || "prev".equals(current)) return source;
        var parts = current.split("\\.");
        String bumped;
        try {
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2].replaceAll("[^0-9].*$", "")) : 0;
            bumped = (parts.length >= 1 ? parts[0] : "1") + "." + (parts.length >= 2 ? parts[1] : "0")
                + "." + (patch + 1);
        } catch (NumberFormatException e) {
            bumped = current + ".1";
        }
        var m = VERSION.matcher(source);
        if (!m.find()) return source;
        log.info("[item-revision] the backend left the version at {} — bumping to {} so the "
            + "archive stays distinct", current, bumped);
        return source.substring(0, m.start(1)) + bumped + source.substring(m.end(1));
    }

    static String versionOf(String source) {
        if (source == null) return "prev";
        var m = VERSION.matcher(source);
        return m.find() ? m.group(1).trim().toLowerCase(Locale.ROOT) : "prev";
    }
}
