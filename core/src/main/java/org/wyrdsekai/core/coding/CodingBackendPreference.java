package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Which coding backend gets the work — as a SETTING, not a class reference.
 *
 * <h2>Why this exists</h2>
 * Asked on 2026-08-21 whether the installer should let someone choose CodeZaiku instead
 * of Goose, and the honest answer turned out to be that there was nothing for an
 * installer to set. The default was compiled in, in five separate places:
 *
 * <ul>
 *   <li>{@code CompanionActor}'s {@code dispatch_task} —
 *       {@code registry.backendFor(GooseBackend.NAME).or(() -> backends().findFirst())}.
 *       Installing CodeZaiku and enabling it in config would have changed nothing here:
 *       she would still pick Goose, because the class was named in the code. And with
 *       Goose absent she would have picked an arbitrary registered backend with no log
 *       line saying why.</li>
 *   <li>Three call sites passing the literal {@code List.of("goose", "pi")} — the
 *       companion's recipe dispatch, the item world API, and the recipe scheduler.</li>
 * </ul>
 *
 * <p>So switching the household to CodeZaiku meant editing Java in five places and
 * rebuilding, which is not a switch — it is a fork. One setting, one resolver, and the
 * installer question answers itself.
 *
 * <h2>Behaviour is unchanged unless someone sets it</h2>
 * With no config key present the chain is exactly what was compiled in before —
 * {@code goose} then {@code pi} — so an existing node behaves identically. Naming a
 * preference puts it at the FRONT of the chain rather than replacing it, so a node whose
 * preferred backend is not installed still works instead of going silent; that fallback
 * is logged, because "it quietly used a different one" is how a person ends up debugging
 * the wrong backend.
 */
public final class CodingBackendPreference {

    private static final Logger log =
        LoggerFactory.getLogger(CodingBackendPreference.class);

    /** {@code wyrdsekai.coding.default_backend = "codezaiku"} — a single name. */
    public static final String CONFIG_KEY = "wyrdsekai.coding.default_backend";

    /**
     * {@code wyrdsekai.coding.preferred_backends = ["codezaiku", "goose"]} — the whole
     * chain, when one name is not enough.
     */
    public static final String CONFIG_CHAIN_KEY = "wyrdsekai.coding.preferred_backends";
    /** The hyphenated spellings reference.conf actually uses (and binds the env vars to). */
    public static final String CONFIG_KEY_HYPHEN = "wyrdsekai.coding.default-backend";
    public static final String CONFIG_CHAIN_KEY_HYPHEN = "wyrdsekai.coding.preferred-backends";

    /**
     * What was compiled in before this class existed. Goose drives the local 9B
     * truthfully where Pi fabricates results (SPEC §2.6), so the order is not arbitrary
     * and stays the fallback.
     */
    public static final List<String> BUILT_IN_CHAIN = List.of("goose", "pi");

    private CodingBackendPreference() {}

    private static volatile Config live;

    /**
     * Boot: hand over the config the backends were wired from.
     *
     * <p>Set where the backends are registered, so the preference and the registry are
     * read from the same file. A setting nothing installs is a setting that does not
     * exist — that is the whole class of bug this file was written during.
     */
    public static void useConfig(Config config) {
        live = config;
        log.info("[coding-backend] preference chain: {}", chain());
    }

    /** Test seam. */
    public static void resetForTests() {
        live = null;
    }

    /** The preference chain from live config. */
    public static List<String> chain() {
        return chain(live);
    }

    /**
     * The preference chain: configured names first, then the built-in chain.
     *
     * <p>Order is preserved and duplicates dropped, so naming {@code goose} explicitly
     * does not make it appear twice, and naming {@code codezaiku} does not silently drop
     * Goose as a fallback.
     */
    public static List<String> chain(Config config) {
        var out = new LinkedHashSet<String>();
        if (config != null) {
            try {
                if (config.hasPath(CONFIG_CHAIN_KEY)) {
                    for (var n : config.getStringList(CONFIG_CHAIN_KEY)) add(out, n);
                }
                if (config.hasPath(CONFIG_KEY)) add(out, config.getString(CONFIG_KEY));
                // BOTH SPELLINGS. reference.conf binds the env var to `default-backend`
                // (hyphen) and this class read only `default_backend` (underscore): two
                // HOCON keys, so WYRDSEKAI_CODING_DEFAULT_BACKEND=codezaiku set a key
                // nobody consulted and the chain stayed [goose, pi]. Found 2026-08-23
                // while wiring CodeZaiku onto the staging node — the documented way to
                // choose a backend had never worked. The typesafe convention is hyphens;
                // the code and docs say underscores; a node may say either.
                if (config.hasPath(CONFIG_KEY_HYPHEN)) add(out, config.getString(CONFIG_KEY_HYPHEN));
                if (config.hasPath(CONFIG_CHAIN_KEY_HYPHEN)) {
                    for (var n : config.getStringList(CONFIG_CHAIN_KEY_HYPHEN)) add(out, n);
                }
            } catch (Exception e) {
                log.warn("[coding-backend] could not read a backend preference ({}) — "
                    + "using the built-in chain {}", e.toString(), BUILT_IN_CHAIN);
            }
        }
        out.addAll(BUILT_IN_CHAIN);
        return List.copyOf(new ArrayList<>(out));
    }

    /**
     * The backend to hand work to, or empty when none of them is registered.
     *
     * <p>Never falls through to "whatever happens to be first". A node with a backend
     * registered that nobody asked for is a node that will do the work with something the
     * steward did not choose — which is worth a log line and a deliberate decision, not a
     * silent {@code findFirst()}.
     */
    public static java.util.Optional<CodingTaskBackend> resolve() {
        var registry = BackendRegistry.get();
        var wanted = chain();
        for (var name : wanted) {
            var found = registry.backendFor(name);
            if (found.isPresent()) {
                if (!name.equals(wanted.get(0))) {
                    log.info("[coding-backend] preferred '{}' is not registered — using "
                        + "'{}' from the chain {}", wanted.get(0), name, wanted);
                }
                return found;
            }
        }
        var any = registry.backends().stream().findFirst();
        any.ifPresent(b -> log.warn("[coding-backend] none of {} is registered — falling "
            + "back to '{}', which nobody asked for", wanted, b.name()));
        return any;
    }

    private static void add(LinkedHashSet<String> out, String name) {
        if (name == null) return;
        var trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) out.add(trimmed);
    }
}
