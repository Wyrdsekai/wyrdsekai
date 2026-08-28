package org.wyrdsekai.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Arrays;

/**
 * Single source of truth for user-facing wyrdsekai configuration.
 *
 * <p>Resolution order (highest precedence wins):
 * <ol>
 *   <li>Environment variables (escape hatch — for testing, CI, one-offs)</li>
 *   <li>{@code ~/.wyrdsekai/profile.toml} (canonical user config)</li>
 *   <li>Built-in defaults — auto-detected where reasonable
 *       (hostname, install paths, common venvs, etc.)</li>
 * </ol>
 *
 * <p>Why TOML for user-facing config: structured (sections), comments
 * survive edits, supports types, idiomatic for modern tools. The file
 * format itself doesn't matter much; what matters is that operators
 * can see <i>all</i> config options grouped sensibly with defaults
 * documented inline, instead of a flat list of {@code WYRDSEKAI_FOO=bar}
 * lines with no structure.</p>
 *
 * <p>Why keep env-var escape hatch: systemd unit files, Docker compose,
 * CI scripts, debug sessions all want one-line overrides. {@code env}
 * also remains the runtime mechanism that JVM/Java reads — operators
 * who set env vars explicitly continue to win, no surprise.</p>
 *
 * <p>Operationally: the {@code wyrd} CLI calls {@link #emitEnvFile} to
 * resolve TOML+defaults to a {@code env}-style file that systemd /
 * launchd source as {@code EnvironmentFile=}. This keeps the existing
 * {@code System.getenv()} call sites working unchanged while migration
 * proceeds incrementally.</p>
 */
public final class WyrdConfig {

    private static final Logger log = LoggerFactory.getLogger(WyrdConfig.class);

    private static volatile WyrdConfig instance;

    /** Flat representation of profile.toml, keyed as {@code section.key}. */
    private final Map<String, String> profile;
    /** Absolute path the profile was loaded from (may not exist). */
    private final Path profilePath;
    /** True if profile.toml was actually loaded (vs missing → defaults only). */
    private final boolean profileLoaded;

    private WyrdConfig(Map<String, String> profile, Path profilePath, boolean loaded) {
        this.profile = profile;
        this.profilePath = profilePath;
        this.profileLoaded = loaded;
    }

    /** Singleton accessor. Loads once, then caches.  Use {@link #reload} to refresh. */
    public static WyrdConfig get() {
        var i = instance;
        if (i == null) {
            synchronized (WyrdConfig.class) {
                if (instance == null) instance = load();
                i = instance;
            }
        }
        return i;
    }

    public static synchronized WyrdConfig reload() {
        instance = load();
        return instance;
    }

    /**
     * Test-only: build a config from a flat {@code section.key} profile map
     * (the shape {@link #parseToml} produces). Env vars still override, exactly
     * as in production, so tests must not rely on a key that is also set in the
     * ambient environment.
     */
    static WyrdConfig forProfile(Map<String, String> profile) {
        return new WyrdConfig(Map.copyOf(profile), defaultProfilePath(), true);
    }

    private static WyrdConfig load() {
        var path = defaultProfilePath();
        if (!Files.isRegularFile(path)) {
            return new WyrdConfig(Map.of(), path, false);
        }
        try {
            var parsed = parseToml(Files.readString(path));
            log.info("WyrdConfig: loaded {} entries from {}", parsed.size(), path);
            return new WyrdConfig(parsed, path, true);
        } catch (Exception e) {
            log.warn("WyrdConfig: failed to load {} ({}), falling back to env+defaults",
                path, e.getMessage());
            return new WyrdConfig(Map.of(), path, false);
        }
    }

    public static Path defaultProfilePath() {
        var override = System.getenv("WYRDSEKAI_PROFILE");
        if (override != null && !override.isBlank()) return Path.of(override);
        return Path.of(System.getProperty("user.home"), ".wyrdsekai", "profile.toml");
    }

    public Path path() { return profilePath; }
    public boolean profileLoaded() { return profileLoaded; }

    // ── Resolution helpers ─────────────────────────────────────────────

    /** Source of a resolved value — used by {@code wyrd config audit}. */
    public enum Source { ENV, PROFILE, DEFAULT, MISSING }

    public record Resolved(String value, Source source) {}

    /**
     * Resolve a single setting. Env takes precedence, then profile.toml,
     * then the supplied default. Returns null if all three are absent.
     */
    public String resolve(String envKey, String tomlKey, Supplier<String> defaultFn) {
        return resolveDetailed(envKey, tomlKey, defaultFn).value();
    }

    public Resolved resolveDetailed(String envKey, String tomlKey, Supplier<String> defaultFn) {
        var env = envKey != null ? System.getenv(envKey) : null;
        if (env != null && !env.isBlank()) return new Resolved(env, Source.ENV);
        if (tomlKey != null) {
            var v = profile.get(tomlKey);
            if (v != null && !v.isBlank()) return new Resolved(v, Source.PROFILE);
        }
        var def = defaultFn != null ? defaultFn.get() : null;
        if (def != null && !def.isBlank()) return new Resolved(def, Source.DEFAULT);
        return new Resolved(null, Source.MISSING);
    }

    public boolean resolveBool(String envKey, String tomlKey, boolean defaultValue) {
        var v = resolve(envKey, tomlKey, () -> Boolean.toString(defaultValue));
        if (v == null) return defaultValue;
        v = v.trim().toLowerCase(Locale.ROOT);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    /** Resolve to {@code int} with a default; returns {@code defaultValue} on parse error. */
    public int resolveInt(String envKey, String tomlKey, int defaultValue) {
        var v = resolve(envKey, tomlKey, () -> Integer.toString(defaultValue));
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    /** Resolve to {@code double} with a default; returns {@code defaultValue} on parse error. */
    public double resolveDouble(String envKey, String tomlKey, double defaultValue) {
        var v = resolve(envKey, tomlKey, () -> Double.toString(defaultValue));
        if (v == null) return defaultValue;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    // ── Typed accessors for common settings ────────────────────────────

    public String nodeName() {
        return resolve("WYRDSEKAI_NODE_NAME", "node.name", WyrdConfig::detectHostname);
    }

    public String zoneId() {
        // No "home" default — that name conflicts with the
        // furnishing concept. Fresh installs get a generated name from
        // `wyrd config init`. If a node somehow boots without a zone
        // set, fall back to a hostname-derived bundle so behavior is
        // stable across restarts (vs random) but still distinct from
        // any other node on the LAN.
        return resolve("WYRDSEKAI_ZONE_ID", "node.zone",
            () -> ZoneNameGenerator.bundleForSeed(detectHostname()).zoneName());
    }

    /** Aesthetic theme for this zone. One of: arcane, cyberpunk, steampunk,
     *  garden, wild, sanctuary, minimalist, none. */
    public String theme() {
        return resolve("WYRDSEKAI_ZONE_THEME", "node.theme",
            () -> ZoneNameGenerator.bundleForSeed(detectHostname()).theme());
    }

    /**
     * How a companion born WITHOUT an explicit archetype is seeded (individuality
     * "B build"). {@code "particular"} (default) → a freely sampled
     * {@link org.wyrdsekai.core.soul.TemperamentSeed}, so every fresh install births
     * a genuinely unique household; {@code "neutral"} → the balanced pre-individuality
     * default (zero-regression). Explicit archetypes/presets and the per-agent
     * {@code "random"}/{@code "neutral"} sentinels always override this. Existing souls
     * are unaffected — reload re-derives temperament from the persisted genome. The
     * {@code wyrdsekai.birth.mode} system property wins (used to pin {@code neutral} in
     * the test suite for determinism); then {@code WYRDSEKAI_BIRTH_MODE} / profile.
     */
    public String birthMode() {
        var sys = System.getProperty("wyrdsekai.birth.mode");
        if (sys != null && !sys.isBlank()) return sys;
        return resolve("WYRDSEKAI_BIRTH_MODE", "agent.birth_mode", () -> "particular");
    }

    public boolean mdnsEnabled() {
        return resolveBool("WYRDSEKAI_MDNS_ENABLED", "discovery.mdns_enabled", true);
    }

    public String mdnsService() {
        return resolve("WYRDSEKAI_MDNS_SERVICE", "discovery.mdns_service",
            () -> "_wyrdsekai._tcp.local.");
    }

    public String publicUrl() {
        return resolve("WYRDSEKAI_ZONE_PUBLIC_URL", "node.public_url", () -> null);
    }

    public String natsUrl() {
        return resolve("WYRDSEKAI_NATS_URL", "nats.url", () -> "nats://127.0.0.1:4222");
    }

    public String inferenceUrl() {
        return resolve("WYRDSEKAI_INFERENCE_URL", "inference.url",
            () -> "http://127.0.0.1:8200");
    }

    /**
     * Seat-config: named seats (wearer / hands / portal), each an optional
     * model + endpoint + reasoning mode. A seat places PRESENCE-adjacent
     * roles onto models; hermod places work. Empty model = seat unset,
     * callers fall back to the routine/complex tier mapping.
     */
    public String seatModel(String seat) {
        // resolve() reports a blank default as MISSING (null) — an unset
        // seat must read as "", or every caller needs a null guard.
        var v = resolve("WYRDSEKAI_SEAT_" + seat.toUpperCase() + "_MODEL",
            "inference.seat." + seat + ".model", () -> "");
        return v == null ? "" : v;
    }

    public String seatUrl(String seat) {
        return resolve("WYRDSEKAI_SEAT_" + seat.toUpperCase() + "_URL",
            "inference.seat." + seat + ".url", this::inferenceUrl);
    }

    /**
     * hermod: data domains RESIDENT on this device (comma-separated).
     * Declared by the deployment, never inferred — what counts as
     * "photos live here" is a human judgment. Empty = no domain-scoped
     * work may be routed TO this device.
     */
    public List<String> hermodDataDomains() {
        var raw = resolve("WYRDSEKAI_HERMOD_DOMAINS", "hermod.data_domains", () -> "");
        return raw == null || raw.isBlank() ? List.of()
            : Arrays.stream(raw.split(",")).map(String::trim)
                .filter(d -> !d.isBlank()).toList();
    }

    /** "think" | "nothink" — maps to chat_template_kwargs enable_thinking. */
    public String seatMode(String seat) {
        return resolve("WYRDSEKAI_SEAT_" + seat.toUpperCase() + "_MODE",
            "inference.seat." + seat + ".mode", () -> "think");
    }

    /**
     * Household inference auto-share — "offer" side. When on, this node serves a
     * household member's cross-zone inference request with an unlimited (family) quota
     * regardless of any bilateral agreement. Default OFF; {@code wyrd setup} flips it on
     * when a local GPU is detected (a CPU box has nothing worth sharing). Scoped to the
     * household trust boundary (HouseholdStore) — public/federation peers are never
     * affected.
     */
    public boolean inferenceHouseholdShare() {
        return resolveBool("WYRDSEKAI_INFERENCE_HOUSEHOLD_SHARE", "inference.household_share", false);
    }

    /**
     * Household inference auto-share — "borrow" side. When on AND this node has no usable
     * local GPU, heavy companion inference auto-prefers a household peer's GPU backend
     * (local CPU stays the health-fallback). Default ON. Non-household peers are never
     * auto-preferred.
     */
    public boolean inferenceHouseholdBorrow() {
        return resolveBool("WYRDSEKAI_INFERENCE_HOUSEHOLD_BORROW", "inference.household_borrow", true);
    }

    public boolean betweenEnabled() {
        return resolveBool("WYRDSEKAI_BETWEEN_ENABLED", "between.enabled", true);
    }

    public String relayUrl() {
        return resolve("WYRDSEKAI_RELAY_URL", "relay.url", () -> null);
    }

    public String relayUser() {
        return resolve("WYRDSEKAI_RELAY_USER", "relay.user", () -> null);
    }

    public String relayToken() {
        return resolve("WYRDSEKAI_RELAY_TOKEN", "relay.token", () -> null);
    }

    public String relayFingerprint() {
        return resolve("WYRDSEKAI_RELAY_FINGERPRINT", "relay.fingerprint", () -> null);
    }

    /** Visibility of leg 0 (the legacy unsuffixed relay). Default PRIVATE. */
    public String relayVisibility() {
        return resolve("WYRDSEKAI_RELAY_VISIBILITY", "relay.visibility", () -> "private");
    }

    // ── SSH-over-relay reverse tunnel ────────
    // When enabled, the zone holds an outbound autossh reverse tunnel to the
    // relay's forwarding-only sshd so bare `ssh` can reach this NAT'd zone's
    // own invite-only MUD sshd. All four values are written by `wyrd relay
    // ssh-enable` from the relay's signed response; the zone never invents them.

    /** Whether to spawn the reverse tunnel at start (set by `wyrd relay ssh-enable`). */
    public boolean sshTunnelEnabled() {
        return resolveBool("WYRDSEKAI_SSH_TUNNEL_ENABLED", "ssh_tunnel.enabled", false);
    }

    /** The relay host the reverse tunnel dials (its control sshd). */
    public String sshTunnelRelayHost() {
        return resolve("WYRDSEKAI_SSH_TUNNEL_RELAY_HOST", "ssh_tunnel.relay_host", () -> null);
    }

    /** The relay's tunnel-sshd control port (default 2222). */
    public int sshTunnelRelayPort() {
        return resolveInt("WYRDSEKAI_SSH_TUNNEL_RELAY_PORT", "ssh_tunnel.relay_port", 2222);
    }

    /** The relay-assigned remote port this zone's tunnel binds (public in port topology, loopback in jump). */
    public int sshTunnelRemotePort() {
        return resolveInt("WYRDSEKAI_SSH_TUNNEL_REMOTE_PORT", "ssh_tunnel.remote_port", 0);
    }

    /** Relay topology — {@code port} (per-zone public port) or {@code jump} (one ProxyJump port). */
    public String sshTunnelTopology() {
        return resolve("WYRDSEKAI_SSH_TUNNEL_TOPOLOGY", "ssh_tunnel.topology", () -> "port");
    }

    /**
     * The bind address the reverse tunnel requests on the relay. {@code 0.0.0.0}
     * in {@code port} topology (the relay publishes the port); {@code 127.0.0.1}
     * in {@code jump} topology (the port stays loopback, reached via ProxyJump).
     */
    public String sshTunnelBind() {
        return "jump".equalsIgnoreCase(sshTunnelTopology()) ? "127.0.0.1" : "0.0.0.0";
    }

    // ── Multi-homing ──────────────────────

    /**
     * The zone's privacy floor — the weakest relay visibility it will tolerate
     * homing on. Default {@code PRIVATE}: an unlisted zone must never acquire a
     * PUBLIC relay leg (the load-bearing invariant, §2.1). Raise to PUBLIC for
     * a deliberately-public zone (the airlock / commons play zone).
     */
    public RelayLegConfig.Visibility zonePrivacyFloor() {
        return RelayLegConfig.Visibility.parse(
            resolve("WYRDSEKAI_ZONE_PRIVACY_FLOOR", "zone.privacy_floor", () -> "private"),
            RelayLegConfig.Visibility.PRIVATE);
    }

    /** Operator override that permits a PUBLIC leg under a PRIVATE floor. */
    public boolean allowPublicLeg() {
        return resolveBool("WYRDSEKAI_ALLOW_PUBLIC_LEG", "zone.allow_public_leg", false);
    }

    /**
     * All configured relay legs.
     *
     * <p>Leg 0 is the legacy unsuffixed {@code WYRDSEKAI_RELAY_*} /
     * {@code relay.*}; legs 2..N are the numbered suffixes
     * {@code WYRDSEKAI_RELAY_URL_2} / {@code relay.url_2}, etc. (the flat TOML
     * parser has no arrays-of-tables, so numbered keys are the representation).
     * Reading stops at the first absent numbered url.</p>
     *
     * <p><b>Privacy rail (§2.1):</b> a PUBLIC leg is dropped (with a warning)
     * when the zone floor is PRIVATE and {@link #allowPublicLeg()} is false, so
     * a private zone cannot silently acquire a public home.</p>
     *
     * <p>Back-compat: a single-leg zone (unsuffixed only) yields exactly one
     * leg, identical to today's behavior. A zone with no relay configured
     * yields an empty list.</p>
     */
    public List<RelayLegConfig> relayLegs() {
        var floor = zonePrivacyFloor();
        var allowPublic = allowPublicLeg();
        var legs = new ArrayList<RelayLegConfig>();

        // Leg 0 — legacy unsuffixed names.
        addLegIfPresent(legs, relayUrl(), relayUser(), relayToken(),
            relayFingerprint(), relayVisibility(), floor, allowPublic, 0);

        // Legs 2..N — numbered suffixes; stop at first gap.
        for (int n = 2; n <= 32; n++) {
            var url = resolve("WYRDSEKAI_RELAY_URL_" + n, "relay.url_" + n, () -> null);
            if (url == null || url.isBlank()) break;
            int idx = n;
            var user = resolve("WYRDSEKAI_RELAY_USER_" + n, "relay.user_" + n, () -> null);
            var token = resolve("WYRDSEKAI_RELAY_TOKEN_" + n, "relay.token_" + n, () -> null);
            var fp = resolve("WYRDSEKAI_RELAY_FINGERPRINT_" + n, "relay.fingerprint_" + n, () -> null);
            var vis = resolve("WYRDSEKAI_RELAY_VISIBILITY_" + n, "relay.visibility_" + n, () -> "private");
            addLegIfPresent(legs, url, user, token, fp, vis, floor, allowPublic, idx);
        }
        return List.copyOf(legs);
    }

    private static void addLegIfPresent(List<RelayLegConfig> out, String url, String user,
            String token, String fingerprint, String visibilityStr,
            RelayLegConfig.Visibility floor, boolean allowPublic, int legIndex) {
        if (url == null || url.isBlank()) return;
        var vis = RelayLegConfig.Visibility.parse(visibilityStr, RelayLegConfig.Visibility.PRIVATE);
        // Privacy rail: a private-floor zone must not home on a public relay.
        if (vis == RelayLegConfig.Visibility.PUBLIC
                && floor == RelayLegConfig.Visibility.PRIVATE && !allowPublic) {
            log.warn("WyrdConfig: dropping PUBLIC relay leg {} ({}) — zone privacy floor is "
                + "PRIVATE. A private zone must not home on a public relay. "
                + "Raise WYRDSEKAI_ZONE_PRIVACY_FLOOR=public or set "
                + "WYRDSEKAI_ALLOW_PUBLIC_LEG=true to permit it.", legIndex, url);
            return;
        }
        out.add(new RelayLegConfig(url, blankToNull(user), blankToNull(token),
            blankToNull(fingerprint), vis));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    public boolean peerTrainingHost() {
        return resolveBool("WYRDSEKAI_PEER_TRAINING_HOST", "peer_training.host", false);
    }

    public String peerTrainingRelayUrl() {
        // Falls back to the household relay URL so operators don't need to
        // duplicate it. They only need a separate URL if the peer-training
        // relay is on a different NATS server (uncommon).
        return resolve("WYRDSEKAI_PEER_TRAINING_RELAY_URL",
            "peer_training.relay_url", this::relayUrl);
    }

    public String peerTrainingRelayUser() {
        return resolve("WYRDSEKAI_PEER_TRAINING_RELAY_USER",
            "peer_training.relay_user", () -> "peer_trainer");
    }

    public String peerTrainingRelayToken() {
        return resolve("WYRDSEKAI_PEER_TRAINING_RELAY_TOKEN",
            "peer_training.relay_token", () -> null);
    }

    public Path adapterDir() {
        var raw = resolve("WYRDSEKAI_ADAPTER_DIR", "paths.adapter_dir",
            () -> System.getProperty("user.home") + "/.wyrdsekai/adapters");
        return Path.of(raw);
    }

    public Path wyrdBin() {
        return Path.of(resolve("WYRDSEKAI_BIN", "paths.wyrd_bin", WyrdConfig::detectWyrdBin));
    }

    // ── Storage paths ──────────────────────────────────────────────────

    /** {@code WYRDSEKAI_JDBC_URL} — primary persistence DSN. May be null on first boot. */
    public String jdbcUrl() {
        return resolve("WYRDSEKAI_JDBC_URL", "storage.jdbc_url", () -> null);
    }

    /** {@code WYRDSEKAI_DATA_DIR} — runtime data root. */
    public String dataDir() {
        return resolve("WYRDSEKAI_DATA_DIR", "paths.data_dir", () -> null);
    }

    /** {@code WYRDSEKAI_HOME} — install root (e.g. /usr/local/wyrdsekai). */
    public String installRoot() {
        return resolve("WYRDSEKAI_HOME", "paths.install_root", WyrdConfig::deriveInstallRoot);
    }

    /** Cached jar-derived install root: null = not yet computed, "" = computed/none. */
    private static volatile String derivedInstallRoot;

    /**
     * Best-effort install-root derivation from the running code location — the
     * last-resort fallback for {@link #installRoot()} when {@code WYRDSEKAI_HOME}
     * and the profile are both unset.
     *
     * <p>The jpackage Windows {@code .msi} (and any flat app-image layout) lays
     * every jar directly in the install payload dir — a sibling of {@code rooms/},
     * {@code scripts/}, {@code data/}. A node launched straight from
     * {@code Wyrdsekai.exe} (Start-menu shortcut / double-click) carries no env,
     * so without this it can't find {@code <home>/rooms} and silently disables all
     * room scripts (docks.js federation transit included). Walking up from the
     * running jar to the dir that holds {@code rooms/} or {@code scripts/} lets the
     * node self-locate its tree regardless of launch method or working directory.
     *
     * <p>Returns null in dev/source runs (jar under {@code build/} with no sibling
     * payload) — the existing cwd-relative candidates cover those. Also improves the
     * {@code .deb}/{@code .pkg} (jar in {@code lib/}, payload one level up) for free.
     */
    private static String deriveInstallRoot() {
        var cached = derivedInstallRoot;
        if (cached != null) return cached.isEmpty() ? null : cached;
        String result = null;
        try {
            var src = WyrdConfig.class.getProtectionDomain().getCodeSource();
            if (src != null && src.getLocation() != null) {
                var loc = Path.of(src.getLocation().toURI());
                // loc = the core jar (flat/lib layouts) or a classes dir (dev).
                // Walk up to 3 levels for a dir holding the standalone payload.
                var dir = Files.isRegularFile(loc) ? loc.getParent() : loc;
                for (int i = 0; i < 3 && dir != null; i++, dir = dir.getParent()) {
                    if (Files.isDirectory(dir.resolve("rooms"))
                        || Files.isDirectory(dir.resolve("scripts"))) {
                        result = dir.toAbsolutePath().normalize().toString();
                        break;
                    }
                }
            }
        } catch (Exception | LinkageError ignore) {
            // CodeSource/URI quirks (custom classloaders, exotic jlink) → null.
        }
        derivedInstallRoot = (result == null) ? "" : result;
        return result;
    }

    /** {@code WYRDSEKAI_SCRIPTS_DIR} / {@code WYRDSEKAI_SCRIPTS} — std scripts dir. */
    public String scriptsDir() {
        var v = resolve("WYRDSEKAI_SCRIPTS_DIR", "paths.scripts_dir", () -> null);
        if (v != null) return v;
        return resolve("WYRDSEKAI_SCRIPTS", null, () -> null);
    }

    /** {@code WYRDSEKAI_REPO_DIR} — checkout root for tooling that calls back into the repo. */
    public String repoDir() {
        return resolve("WYRDSEKAI_REPO_DIR", "paths.repo_dir", () -> null);
    }

    /** {@code WYRDSEKAI_LLAMA_CPP_DIR} — llama.cpp checkout for local inference. */
    public String llamaCppDir() {
        return resolve("WYRDSEKAI_LLAMA_CPP_DIR", "paths.llama_cpp_dir", () -> null);
    }

    /** {@code WYRDSEKAI_NATS_SERVER_BINARY} — explicit nats-server path override. */
    public String natsServerBinary() {
        return resolve("WYRDSEKAI_NATS_SERVER_BINARY", "paths.nats_server", () -> null);
    }

    /** {@code WYRDSEKAI_SOUL_DIR} — companion soul-manifest persistence dir. */
    public String soulDir() {
        return resolve("WYRDSEKAI_SOUL_DIR", "paths.soul_dir", () -> null);
    }

    /** {@code WYRDSEKAI_SOULSTORE_DIR} — alias for soul_dir used by FamiliarPersistenceStore. */
    public String soulstoreDir() {
        var v = resolve("WYRDSEKAI_SOULSTORE_DIR", "paths.soulstore_dir", () -> null);
        return v != null ? v : soulDir();
    }

    /** {@code WYRDSEKAI_SOUL_SEED} — bootstrap soul seed file. */
    public String soulSeed() {
        return resolve("WYRDSEKAI_SOUL_SEED", "paths.soul_seed", () -> null);
    }

    // ── Inference URLs (related siblings) ──────────────────────────────

    /** {@code WYRDSEKAI_OLLAMA_URL} — Ollama backend URL. */
    public String ollamaUrl() {
        return resolve("WYRDSEKAI_OLLAMA_URL", "inference.ollama_url", () -> null);
    }

    /** {@code WYRDSEKAI_SGLANG_URL} — SGLang backend URL. */
    public String sglangUrl() {
        return resolve("WYRDSEKAI_SGLANG_URL", "inference.sglang_url", () -> null);
    }

    /** {@code WYRDSEKAI_EMBEDDING_URL} — remote embedding service URL. */
    public String embeddingUrl() {
        return resolve("WYRDSEKAI_EMBEDDING_URL", "inference.embedding_url", () -> null);
    }

    /** {@code WYRDSEKAI_SEARXNG_URL} — Searxng metasearch URL. */
    public String searxngUrl() {
        return resolve("WYRDSEKAI_SEARXNG_URL", "search.searxng_url", () -> null);
    }

    /** {@code WYRDSEKAI_PREDICTION_MODEL} — Granite TTM model id. */
    public String predictionModel() {
        return resolve("WYRDSEKAI_PREDICTION_MODEL", "prediction.model", () -> null);
    }

    /** {@code WYRDSEKAI_MODEL_ROUTINE} — small/fast model id. */
    public String modelRoutine() {
        return resolve("WYRDSEKAI_MODEL_ROUTINE", "inference.model_routine", () -> null);
    }

    /** {@code WYRDSEKAI_MODEL_COMPLEX} — large/deep model id. */
    public String modelComplex() {
        return resolve("WYRDSEKAI_MODEL_COMPLEX", "inference.model_complex", () -> null);
    }

    /** {@code WYRDSEKAI_VOICE_BACKEND} — voice backend kind (mlx, llamacpp, ollama, sglang). */
    public String voiceBackend() {
        return resolve("WYRDSEKAI_VOICE_BACKEND", "voice.backend", () -> null);
    }

    /** {@code WYRDSEKAI_VOICE_BACKEND_PYTHON} — explicit python path for voice training. */
    public String voiceBackendPython() {
        return resolve("WYRDSEKAI_VOICE_BACKEND_PYTHON", "voice.backend_python", () -> null);
    }

    /** {@code WYRDSEKAI_VOICE_URL} — base URL of the 4B voice backend (no {@code /v1}
     *  suffix; the OpenAI provider appends it). Defaults to the dual-inference
     *  voice port. Used off-actor by {@link org.wyrdsekai.core.room.ThemedDescriptionService}. */
    public String voiceUrl() {
        return resolve("WYRDSEKAI_VOICE_URL", "voice.url", () -> "http://127.0.0.1:8201");
    }

    /** {@code WYRDSEKAI_VOICE_ENABLED} — whether this node runs a distinct 4B voice
     *  backend. Seeded {@code true} by the installers wherever the dual-MLX /
     *  dual-llama voice brain is configured (macOS .pkg, Linux .deb via
     *  {@code wyrd setup}, Windows .msi). The voice-pass default keys off this:
     *  a node with a 4B voice re-voices the 9B's content through it by default;
     *  a single-model node leaves it off (no pointless second pass). Spawn-side
     *  signal (bin/wyrd / wyrd.ps1 decide whether to start the voice server);
     *  the Java backend is registered from {@link #voiceUrl()} / auto-probe. */
    public boolean voiceEnabled() {
        return resolveBool("WYRDSEKAI_VOICE_ENABLED", "voice.enabled", false);
    }

    /** {@code WYRDSEKAI_THEMED_ROOM_DESCRIPTIONS} — when true (default), look
     *  lazily bakes an LLM rewrite of each room's description in the active zone
     *  theme's voice (cached per room×theme), falling back to the deterministic
     *  {@link org.wyrdsekai.core.room.ZoneAestheticDescriber} restyle until the
     *  bake lands. Set false to keep only the deterministic restyle. */
    public boolean themedRoomDescriptionsEnabled() {
        var v = resolve("WYRDSEKAI_THEMED_ROOM_DESCRIPTIONS", "theme.llm_room_descriptions",
            () -> "true");
        return v == null || !"false".equalsIgnoreCase(v.trim());
    }

    /** {@code WYRDSEKAI_TRAINING_POLICY} — DeepSleepTrainer policy ("aggressive", "conservative"). */
    public String trainingPolicy() {
        return resolve("WYRDSEKAI_TRAINING_POLICY", "training.policy", () -> null);
    }

    // ── Cluster / discovery ──────────────────────────────────────────────

    /** {@code WYRDSEKAI_ARTERY_PORT} — Pekko artery TCP port. */
    public String arteryPort() {
        return resolve("WYRDSEKAI_ARTERY_PORT", "cluster.artery_port", () -> null);
    }

    /** {@code WYRDSEKAI_DIRECTORY_PEERS} — comma-separated peer URLs. */
    public String directoryPeers() {
        return resolve("WYRDSEKAI_DIRECTORY_PEERS", "directory.peers", () -> null);
    }

    /** {@code WYRDSEKAI_RENDEZVOUS_URLS} — comma-separated rendezvous URLs. */
    public String rendezvousUrls() {
        return resolve("WYRDSEKAI_RENDEZVOUS_URLS", "directory.rendezvous_urls", () -> null);
    }

    /** {@code WYRDSEKAI_ZONE_SECRET} — household pre-shared secret. Null = household trust. */
    public String zoneSecret() {
        return resolve("WYRDSEKAI_ZONE_SECRET", "zone.secret", () -> null);
    }

    /** {@code WYRDSEKAI_ZONE_AESTHETIC} — explicit aesthetic JSON file path. */
    public String zoneAestheticPath() {
        return resolve("WYRDSEKAI_ZONE_AESTHETIC", "zone.aesthetic_path", () -> null);
    }

    // ── Boolean / flag accessors ──────────────────────────────────────

    /** {@code WYRDSEKAI_FORGE_ENABLED} — overnight Forge consolidation. */
    public boolean forgeEnabled() {
        return resolveBool("WYRDSEKAI_FORGE_ENABLED", "forge.enabled", false);
    }

    /**
     * {@code WYRDSEKAI_M2_HARDGATE_ENABLED} — when true, low-confidence plan
     * scores below 0.4 inject a [Preflight] hint into the companion's working
     * memory so the next inference cycle reconsiders the plan. Default true.
     *
     * <p>Combined with {@link #m3HardgateEnabled}: when both are true (default),
     * the hard-gate fires only when M2 AND M3 both vote reject (intersection,
     * high-precision). When only M2 is enabled, M2-only behavior. When only M3
     * is enabled, M3-only behavior. When neither, the gate is off entirely.</p>
     */
    public boolean m2HardgateEnabled() {
        return resolveBool("WYRDSEKAI_M2_HARDGATE_ENABLED",
            "m2_m3.hardgate_enabled", true);
    }

    /**
     * {@code WYRDSEKAI_M3_HARDGATE_ENABLED} — when true (default), M3's vote is
     * required alongside M2's for the plan-creation hard-gate to fire. Path A
     * calibration (2026-05-08) brought M3 Brier from 0.466 → 0.187 by adding
     * step-by-step example anchoring; M3 is now a viable hard-gate signal.
     *
     * <p>The combined AND-gate (M2 ∧ M3 both reject) is high-precision: only
     * 18% of plans hit "both reject" in the bank, and most are real
     * antipatterns. Disabling reverts to M2-only (the pre-Path-A behavior).</p>
     */
    public boolean m3HardgateEnabled() {
        return resolveBool("WYRDSEKAI_M3_HARDGATE_ENABLED",
            "m2_m3.m3_hardgate_enabled", true);
    }

    /**
     * {@code WYRDSEKAI_M2_STEPGATE_ENABLED} — Phase 6.2 step-level decision
     * prediction: after each goal completes, M3 simulates the remaining plan
     * (with executed steps as ground-truth prefix) and may inject a
     * {@code [Preflight]} step hint into working memory if the rest looks
     * unlikely to succeed. Default true now that Path A made M3 calibration
     * usable; flipping to true gathers per-step calibration data
     * ({@code stepRow:true} entries in {@code data/m3/predictions.jsonl})
     * so the step-gate can be tuned or reverted from observed behavior.
     */
    public boolean m2StepgateEnabled() {
        return resolveBool("WYRDSEKAI_M2_STEPGATE_ENABLED",
            "m2_m3.stepgate_enabled", true);
    }

    /** {@code WYRDSEKAI_ORACLE_ENABLED} — Oracle bridge enabled. */
    public boolean oracleEnabled() {
        return resolveBool("WYRDSEKAI_ORACLE_ENABLED", "oracle.enabled", false);
    }

    /** {@code WYRDSEKAI_ALLOW_MODEL_DOWNLOAD} — fetch missing models on boot. */
    public boolean allowModelDownload() {
        return resolveBool("WYRDSEKAI_ALLOW_MODEL_DOWNLOAD", "models.allow_download", false);
    }

    /** {@code WYRDSEKAI_OFFLINE} — disable all network calls. */
    public boolean offline() {
        return resolveBool("WYRDSEKAI_OFFLINE", "network.offline", false);
    }

    /** {@code WYRDSEKAI_MERGE_SYSTEM_MESSAGES} — collapse system messages in prompts. */
    public boolean mergeSystemMessages() {
        return resolveBool("WYRDSEKAI_MERGE_SYSTEM_MESSAGES", "prompt.merge_system_messages", false);
    }

    /** {@code WYRDSEKAI_ENVELOPE_VERIFY} — federation envelope verify mode (off/warn/enforce). */
    public String envelopeVerify() {
        return resolve("WYRDSEKAI_ENVELOPE_VERIFY", "federation.envelope_verify", () -> null);
    }

    /** {@code WYRDSEKAI_SHADOW_LOG} — shadow-mode rollout enabled. */
    public boolean shadowLog() {
        return resolveBool("WYRDSEKAI_SHADOW_LOG", "observability.shadow_log", false);
    }

    // ── Geo / arxiv / GPU hints ─────────────────────────────────────

    public String latitude()    { return resolve("WYRDSEKAI_LATITUDE",    "geo.latitude",    () -> null); }
    public String longitude()   { return resolve("WYRDSEKAI_LONGITUDE",   "geo.longitude",   () -> null); }
    public String arxivFields() { return resolve("WYRDSEKAI_ARXIV_FIELDS","oracle.arxiv_fields", () -> null); }
    public String gpuName()     { return resolve("WYRDSEKAI_GPU_NAME",    "hardware.gpu_name", () -> null); }
    public String gpuVramMb()   { return resolve("WYRDSEKAI_GPU_VRAM_MB", "hardware.gpu_vram_mb", () -> null); }

    // ── Defaults (auto-detection) ──────────────────────────────────────

    private static String detectHostname() {
        try {
            var h = InetAddress.getLocalHost().getHostName();
            // Strip domain — we want short hostnames (home-server, mac-node), not FQDNs
            var dot = h.indexOf('.');
            return dot > 0 ? h.substring(0, dot) : h;
        } catch (Exception e) {
            return "node";
        }
    }

    private static String detectWyrdBin() {
        String[] candidates = {
            "/usr/local/wyrdsekai/bin/wyrd",
            "/opt/wyrdsekai/bin/wyrd",
            System.getProperty("user.home") + "/src/wyrdsekai/bin/wyrd",
            "/usr/local/bin/wyrd"
        };
        for (var c : candidates) {
            if (Files.isExecutable(Path.of(c))) return c;
        }
        return candidates[0];
    }

    // ── TOML emit / audit ──────────────────────────────────────────────

    /**
     * Emit a {@code KEY=VALUE} bash-sourceable representation of the resolved
     * config. Used by {@code wyrd config apply} to feed systemd / launchd via
     * EnvironmentFile.  Comments mark which values came from defaults vs the
     * profile, so audit output is informative even when sourced.
     */
    public String emitEnvFile() {
        var sb = new StringBuilder();
        sb.append("# Wyrdsekai resolved environment — DO NOT EDIT BY HAND\n");
        sb.append("# Source: ").append(profilePath).append(profileLoaded ? "" : " (missing)").append("\n");
        sb.append("# Edit profile.toml and run `wyrd config apply` to regenerate.\n\n");

        record E(String envKey, String tomlKey, Supplier<String> def) {}
        E[] entries = {
            new E("WYRDSEKAI_NODE_NAME", "node.name", WyrdConfig::detectHostname),
            new E("WYRDSEKAI_ZONE_ID", "node.zone", () -> "home"),
            new E("WYRDSEKAI_NATS_URL", "nats.url", () -> "nats://127.0.0.1:4222"),
            new E("WYRDSEKAI_INFERENCE_URL", "inference.url", () -> "http://127.0.0.1:8200"),
            new E("WYRDSEKAI_BETWEEN_ENABLED", "between.enabled", () -> "true"),
            new E("WYRDSEKAI_RELAY_URL", "relay.url", () -> null),
            new E("WYRDSEKAI_RELAY_USER", "relay.user", () -> null),
            new E("WYRDSEKAI_RELAY_TOKEN", "relay.token", () -> null),
            new E("WYRDSEKAI_PEER_TRAINING_HOST", "peer_training.host", () -> "false"),
            new E("WYRDSEKAI_PEER_TRAINING_RELAY_TOKEN", "peer_training.relay_token", () -> null),
        };
        for (var e : entries) {
            var r = resolveDetailed(e.envKey, e.tomlKey, e.def);
            if (r.value() != null) {
                sb.append("# from ").append(r.source()).append('\n');
                sb.append(e.envKey).append('=').append(r.value()).append('\n');
            }
        }
        return sb.toString();
    }

    // ── Minimal TOML parser ────────────────────────────────────────────

    /**
     * Parse the subset of TOML wyrdsekai uses: top-level key=value, sections
     * ([name]), strings (quoted or bare), booleans, and integers. No tables
     * arrays, no inline tables, no multi-line strings — the config is small
     * and a 30-line parser is preferable to a Jackson dependency for it.
     *
     * <p>Returns flat map keyed as {@code section.key} (or just {@code key}
     * for top-level entries). Comment lines (starting with {@code #}) and
     * trailing-line comments are stripped.</p>
     */
    static Map<String, String> parseToml(String content) {
        var out = new LinkedHashMap<String, String>();
        var section = "";
        for (var raw : content.split("\\R")) {
            var line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // Strip trailing comments — but keep '#' inside quoted strings.
            line = stripTrailingComment(line);
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            var eq = line.indexOf('=');
            if (eq <= 0) continue;
            var key = line.substring(0, eq).trim();
            var val = line.substring(eq + 1).trim();
            // Strip surrounding quotes (single or double) — yes we lose
            // escape semantics, but for URLs / tokens / names it's fine.
            if (val.length() >= 2 &&
                ((val.startsWith("\"") && val.endsWith("\"")) ||
                 (val.startsWith("'")  && val.endsWith("'")))) {
                val = val.substring(1, val.length() - 1);
            }
            var fullKey = section.isEmpty() ? key : section + "." + key;
            out.put(fullKey, val);
        }
        return out;
    }

    private static String stripTrailingComment(String line) {
        var inQuote = false;
        char qc = 0;
        for (int i = 0; i < line.length(); i++) {
            var c = line.charAt(i);
            if (inQuote) {
                if (c == qc) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                qc = c;
            } else if (c == '#') {
                return line.substring(0, i).trim();
            }
        }
        return line;
    }

    // ── Track-C C9 — recipe scheduler ship-defaults ──────

    /** Whether the {@code RecipeScheduler} actor boots in this zone. */
    public boolean schedulerEnabled() {
        return resolveBool("WYRDSEKAI_RECIPES_SCHEDULER_ENABLED",
            "recipes.scheduler.enabled", true);
    }

    /** Polling cadence for the scheduler actor (minutes). Production default 60. */
    public int schedulerPollMinutes() {
        return parseIntOr(resolve("WYRDSEKAI_RECIPES_POLL_MINUTES",
            "recipes.scheduler.poll_minutes", () -> "60"), 60);
    }

    /** Daily GPU budget for recipe runs (hours). */
    public int schedulerGpuDailyHours() {
        return parseIntOr(resolve("WYRDSEKAI_RECIPES_GPU_DAILY_HOURS",
            "recipes.welfare.gpu_daily_hours", () -> "6"), 6);
    }

    /** Monthly recipe-run cap. */
    public int schedulerMonthlyRunCap() {
        return parseIntOr(resolve("WYRDSEKAI_RECIPES_MONTHLY_CAP",
            "recipes.welfare.monthly_run_cap", () -> "100"), 100);
    }

    /** Consecutive deploy failures before a recipe is auto-paused (deploy-ceiling). */
    public int schedulerDeployCeiling() {
        return parseIntOr(resolve("WYRDSEKAI_RECIPES_DEPLOY_CEILING",
            "recipes.welfare.deploy_ceiling", () -> "3"), 3);
    }

    /** Whether gap-detection-triggered enqueues fire from sleep-pass findings. */
    public boolean schedulerGapDetectionEnabled() {
        return resolveBool("WYRDSEKAI_RECIPES_GAP_DETECTION",
            "recipes.gap_detection.enabled", true);
    }

    /** Sustained-pattern tick threshold before gap-detection enqueues a recipe. */
    public int schedulerGapTicks() {
        return parseIntOr(resolve("WYRDSEKAI_RECIPES_GAP_TICKS",
            "recipes.gap_detection.ticks", () -> "5"), 5);
    }

    /** Sustained-pattern window in hours before gap-detection enqueues. */
    public int schedulerGapWindowHours() {
        return parseIntOr(resolve("WYRDSEKAI_RECIPES_GAP_WINDOW_HOURS",
            "recipes.gap_detection.window_hours", () -> "48"), 48);
    }

    /** Minimum autonomy tier name that may emit {@code request_recipe}. */
    public String schedulerRequestRecipeMinTier() {
        return resolve("WYRDSEKAI_RECIPES_REQUEST_MIN_TIER",
            "recipes.request_recipe.min_tier", () -> "companion");
    }

    /**
     * (option c — BYO cloud). Steward-configured path to a
     * launch script that runs a heavy recipe on the household's own cloud when a
     * resource-denied recipe has no eligible local node or trusted peer. Empty
     * (default) = no cloud backend; the recipe-denied path stops at the steward
     * ask (option a). See {@code scripts/cloud/*.sh} reference scripts.
     */
    public String recipesCloudLaunchScript() {
        return resolve("WYRDSEKAI_RECIPE_CLOUD_LAUNCH",
            "recipes.cloud_launch.script", () -> "");
    }

    /**
     * Comma-separated list of classifier heads to enroll at boot. Empty
     * means: auto-discover from {@code data/classifiers/pretrained/} — see
     * {@code ShipDefaultEnrollmentProvisioner.discoverHeads}. The
     * provisioner falls back to a curated baseline when discovery returns
     * nothing (fresh install, no model files yet).
     */
    public String schedulerEnrolledHeads() {
        return resolve("WYRDSEKAI_RECIPES_ENROLLED_HEADS",
            "recipes.enrolled_heads", () -> "");
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    // ── #1036 Substrate-pressure aggregator knobs ─────────────────────────

    /** Rolling-mean window in days for the bondholder-voice welfare gate. */
    public int substratePressureWindowDays() {
        return resolveInt("WYRDSEKAI_SUBSTRATE_PRESSURE_WINDOW_DAYS",
            "substrate.pressure.window_days", 30);
    }

    /** Aggregation mode: {@code mean} (default) or {@code p95}. */
    public String substratePressureAggregation() {
        return resolve("WYRDSEKAI_SUBSTRATE_PRESSURE_AGGREGATION",
            "substrate.pressure.aggregation", () -> "mean");
    }

    /** Days to keep samples before pruneOlderThan() trims them. */
    public int substratePressureRetentionDays() {
        return resolveInt("WYRDSEKAI_SUBSTRATE_PRESSURE_RETENTION_DAYS",
            "substrate.pressure.retention_days", 90);
    }

    // ── #1037 Bondholder pair-mining knobs ────────────────────────────────

    /** How far back to mine bondholder turns for pair construction. */
    public int bondholderPairsLookbackDays() {
        return resolveInt("WYRDSEKAI_BONDHOLDER_PAIRS_LOOKBACK_DAYS",
            "bondholder.pairs.lookback_days", 90);
    }

    /** Skip turns shorter than this many characters (denoise). */
    public int bondholderPairsMinTurnChars() {
        return resolveInt("WYRDSEKAI_BONDHOLDER_PAIRS_MIN_TURN_CHARS",
            "bondholder.pairs.min_turn_chars", 10);
    }

    /** Conversation-turn retention. Older rows pruned on sleep-pass. */
    public int conversationTurnsRetentionDays() {
        return resolveInt("WYRDSEKAI_CONVERSATION_TURNS_RETENTION_DAYS",
            "bondholder.pairs.retention_days", 180);
    }

    // ── Arc 3 — peer-bond auto-formation knobs ───────
    //
    // The suggestion detector is non-coercive (INFO chronicle finding only);
    // these knobs let stewards tune cadence for households with very
    // different collaboration intensities — busy multi-agent zones may want
    // a higher threshold, while small households may benefit from a lower
    // bar. Defaults match the spec (15 interactions in 14 days).

    /** Number of significant peer interactions in the window before the
     *  chronicle surfaces a "propose peer bond?" suggestion. */
    public int peerBondSuggestionThreshold() {
        return resolveInt("WYRDSEKAI_PEER_BOND_SUGGESTION_THRESHOLD",
            "peer_bond.suggestion.threshold", 15);
    }

    /** Trailing window (days) the peer-interaction count rolls over. */
    public int peerBondSuggestionWindowDays() {
        return resolveInt("WYRDSEKAI_PEER_BOND_SUGGESTION_WINDOW_DAYS",
            "peer_bond.suggestion.window_days", 14);
    }

    /**
     * DECENTMEM τ-floor ( §"two cheap wire-ins"): minimum
     * cosine similarity a prior EPISODIC fragment must clear to be admitted as
     * recursion context for the inner-monologue. Below this floor the match is
     * too weak to be "what you remember thinking last time" — admitting it
     * invites confabulation, so the agent explores fresh instead. Conservative
     * default: rejects only genuinely-unrelated matches (normalized sentence
     * embeddings put loosely-related content well above 0.20). Set to 0 (or
     * negative) to disable the floor and restore pure top-K behaviour. */
    public double episodicRecursionMinSimilarity() {
        return resolveDouble("WYRDSEKAI_EPISODIC_RECURSION_MIN_SIMILARITY",
            "memory.episodic_recursion.min_similarity", 0.20);
    }

    /** Co-presence "C" (2026-06-15): when a human bondholder is present in the room, keep ambient
     *  agent↔agent chatter OFF the room's aloud channel (it still flows over the silent tell_agent
     *  backchannel) so two companions talking don't flood the human's screen. Companions still reply
     *  aloud when the human addresses them, and still voice solo musings. Default on; set false to
     *  let agents banter aloud in front of humans. */
    public boolean agentsQuietWhenHumanPresent() {
        return resolveBool("WYRDSEKAI_AGENTS_QUIET_WHEN_HUMAN_PRESENT",
            "presence.agents_quiet_when_human_present", true);
    }

    // ── #1038 Library-compact prune knobs ─────────────────────────────────

    /** Default TTL for Lucene chunks lacking an explicit expiry. */
    public int libraryCompactPruneDefaultTtlDays() {
        return resolveInt("WYRDSEKAI_LIBRARY_COMPACT_PRUNE_TTL_DAYS",
            "library.compact.prune.default_ttl_days", 365);
    }

    /** Master switch for the prune action (steward kill-switch). */
    public boolean libraryCompactPruneEnabled() {
        return resolveBool("WYRDSEKAI_LIBRARY_COMPACT_PRUNE_ENABLED",
            "library.compact.prune.enabled", true);
    }

    // ── #1039 Library-compact reembed knobs ───────────────────────────────

    /**
     * Target embedding-model version. {@code auto} means the current
     * {@code EmbeddingModel.VERSION}; explicit value pins to a specific model.
     */
    public String libraryCompactReembedTargetModel() {
        return resolve("WYRDSEKAI_LIBRARY_COMPACT_REEMBED_TARGET",
            "library.compact.reembed.target_model", () -> "auto");
    }

    /** Reembed batch size (chunks per commit). */
    public int libraryCompactReembedBatchSize() {
        return resolveInt("WYRDSEKAI_LIBRARY_COMPACT_REEMBED_BATCH",
            "library.compact.reembed.batch_size", 100);
    }

    /** Master switch for the reembed action. */
    public boolean libraryCompactReembedEnabled() {
        return resolveBool("WYRDSEKAI_LIBRARY_COMPACT_REEMBED_ENABLED",
            "library.compact.reembed.enabled", true);
    }

    // ── Test/debug helpers ─────────────────────────────────────────────

    /** Snapshot of all profile values for audit/debug. */
    public Map<String, String> profileSnapshot() {
        return new LinkedHashMap<>(profile);
    }
}
