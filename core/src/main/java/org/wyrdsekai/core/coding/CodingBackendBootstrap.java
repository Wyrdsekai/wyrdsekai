package org.wyrdsekai.core.coding;

import com.typesafe.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.agent.LocalCommandRouter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Wires concrete {@link CodingTaskBackend} + {@link BackendAdapter}
 * implementations into the process-wide {@link BackendRegistry} based on
 * typesafe-config flags under {@code wyrdsekai.coding.backends.*}.
 *
 * <p>Phase 2b registers {@link OpenCodeBackend} (the new default-on backend
 * ). CodePlane registration stays a
 * Main-side concern for now because it requires both the legacy item
 * store and a wired {@code CommandRouter}; this bootstrap focuses on the
 * config-driven backends that have no inter-component dependencies.</p>
 *
 * <p>Idempotent: calling {@code init} twice is a no-op (silent), so test
 * harnesses can re-init after registry clears.</p>
 */
public final class CodingBackendBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CodingBackendBootstrap.class);

    private CodingBackendBootstrap() {}

    /**
     * Process-wide {@link AuthResolver}; lazily seeded by {@link
     * #init(Config, Function)} (or its no-arg overload) and consumed by
     * paid-backend adapters via {@link #authResolver()}. Held as an
     * {@link AtomicReference} so re-init in tests is idempotent and so
     * a partial bootstrap (e.g. the manifest-less unit-test path) leaves
     * a sensible default in place.
     */
    private static final AtomicReference<AuthResolver> AUTH_RESOLVER =
            new AtomicReference<>();

    /** Always-empty fallback used until a real resolver is wired. */
    private static final AuthResolver UNCONFIGURED = backendName ->
            new AuthMode.AuthMissing(
                    backendName == null ? "?" : backendName,
                    "wyrd coding list",
                    "AuthResolver not initialised — call CodingBackendBootstrap.init(...) first");

    /**
     * Read the coding-backends config block and register concrete
     * backends + adapters that are (a) enabled and (b) not already
     * registered in the global {@link BackendRegistry}.
     *
     * <p>Equivalent to {@code init(config, null)} — installs the
     * "always-AuthMissing" resolver. Production callers should use
     * the two-arg overload to wire a real Key Chest backend.</p>
     */
    public static void init(Config config) {
        init(config, null);
    }

    /**
     * Wires concrete backends + the dual-path {@link AuthResolver}.
     *
     * @param config         typesafe Config (typically
     *                       {@code ConfigFactory.load()}).
     * @param keyChestLookup function mapping a Key Chest slot name to
     *                       its plaintext value (or {@code null} when
     *                       absent). When {@code null}, the resolver
     *                       falls back to a no-op that always returns
     *                       {@link AuthMode.AuthMissing} — useful in
     *                       tests + early-boot scenarios.
     */
    public static void init(Config config, Function<String, String> keyChestLookup) {
        var registry = BackendRegistry.get();
        var router = LocalCommandRouter.get();

        // -- AuthResolver wiring (built first so paid-backend registration
        //    in Phase 2e can consult it eagerly. The resolver is a thin
        //    reader over the manifest; building it here costs nothing
        //    when paid backends are absent. Existing OpenHands +
        //    Goose/Cline/Continue paths still go through authForBackends
        //    (a closure over authResolver()) so they pick up this
        //    resolver immediately.)
        AuthResolver resolver = buildAuthResolver(keyChestLookup);
        AUTH_RESOLVER.set(resolver);

        // -- OpenCode (default-on, Phase 2b) ----------------------------
        if (registry.backendFor(OpenCodeBackend.NAME).isEmpty()) {
            var openCodeConfig = OpenCodeRuntimeConfig.fromConfig(config);
            if (openCodeConfig.enabled()) {
                registry.register(new OpenCodeBackend(openCodeConfig));
                registry.register(new OpenCodeEventAdapter());
                log.info("OpenCode backend wired (executable={}, base_url={}, model={})",
                    openCodeConfig.executablePath(),
                    openCodeConfig.baseUrl(),
                    openCodeConfig.model());
            } else {
                log.info("OpenCode backend disabled in config; skipping registration");
            }
        }

        // -- Pi (Phase 2f, default-on per SPEC §2.5) --------------------
        // Lightweight MIT subprocess (~12MB npm, sub-second startup). Routes
        // through ~/.pi/agent/models.json to the household's local 9B by
        // default; cloud providers are explicit opt-in. The de facto stand-in
        // for §85.17 CodePlane while CodePlane isn't household-ready.
        if (registry.backendFor(PiCodingBackend.NAME).isEmpty()) {
            var piConfig = PiCodingRuntimeConfig.fromConfig(config);
            if (piConfig.enabled()) {
                if (binaryReachable(PiCodingBackend.NAME, piConfig.executablePath())) {
                    var piResolver = (AuthResolver) name -> authResolver().resolveAuth(name);
                    registry.register(new PiCodingBackend(piConfig, piResolver));
                    log.info("Pi backend wired (executable={}, model={}, provider={})",
                        piConfig.executablePath(),
                        piConfig.model(),
                        piConfig.provider());
                } else {
                    log.info("Pi backend enabled in config but binary '{}' not reachable; "
                        + "skipping registration (run `wyrd setup pi` to install)",
                        piConfig.executablePath());
                }
            } else {
                log.info("Pi backend disabled in config; skipping registration");
            }
        }

        // -- OpenHands (Phase 2c, opt-in heavy autonomous backend) ------
        // Skipped silently when (a) config has it disabled, (b) Docker is
        // not reachable on the host. We probe Docker before registering so
        // a household without Docker doesn't see OpenHands in the
        // backend list at all (cleaner than register-then-fail-health).
        if (registry.backendFor(OpenHandsBackend.NAME).isEmpty()) {
            var openHandsConfig = OpenHandsRuntimeConfig.fromConfig(config);
            if (openHandsConfig.enabled()) {
                if (OpenHandsBackend.probeDockerDefault()) {
                    // AuthResolver was wired at the top of init(); pass a
                    // thunk so OpenHands picks up the live resolver.
                    var ohResolver = (AuthResolver) name -> authResolver().resolveAuth(name);
                    registry.register(new OpenHandsBackend(openHandsConfig, ohResolver));
                    registry.register(new OpenHandsEventAdapter());
                    log.info("OpenHands backend wired (agent_server_url={}, docker_image={}, max_ram_gb={})",
                        openHandsConfig.agentServerUrl(),
                        openHandsConfig.dockerImage(),
                        openHandsConfig.maxRamGb());
                } else {
                    log.info("OpenHands backend enabled in config but Docker is not reachable; skipping registration");
                }
            } else {
                log.debug("OpenHands backend disabled in config; skipping registration");
            }
        }

        // -- Phase 2d: Goose / Cline / Continue ------------------------
        // Each adapter:
        //   • registers when enabled in config AND a usable binary is
        //     reachable (per-backend Bundled-Installer status check; the
        //     installer is the canonical "is the binary on disk" probe);
        //   • silently skips otherwise so the selection chain falls
        //     through cleanly to the next backend.
        // Tier defaults to CLOUD_PAID across the trio (SPEC §9.2 cost
        // posture). Auth gates are enforced at submitTask time, not at
        // bootstrap — registration shouldn't depend on whether a key is
        // currently in the Key Chest (the steward may add it later).
        AuthResolver authForBackends = name -> authResolver().resolveAuth(name);

        if (registry.backendFor(GooseBackend.NAME).isEmpty()) {
            var gooseConfig = GooseRuntimeConfig.fromConfig(config);
            if (gooseConfig.enabled()) {
                if (binaryReachable(GooseBackend.NAME, gooseConfig.executablePath())) {
                    registry.register(new GooseBackend(gooseConfig, authForBackends));
                    registry.register(new GooseEventAdapter());
                    log.info("Goose backend wired (executable={}, provider={})",
                        gooseConfig.executablePath(), gooseConfig.provider());
                } else {
                    log.info("Goose backend enabled in config but binary '{}' not reachable; "
                        + "skipping registration", gooseConfig.executablePath());
                }
            } else {
                log.debug("Goose backend disabled in config; skipping registration");
            }
        }

        if (registry.backendFor(ClineBackend.NAME).isEmpty()) {
            var clineConfig = ClineRuntimeConfig.fromConfig(config);
            if (clineConfig.enabled()) {
                if (binaryReachable(ClineBackend.NAME, clineConfig.executablePath())) {
                    registry.register(new ClineBackend(clineConfig, authForBackends));
                    registry.register(new ClineEventAdapter());
                    log.info("Cline backend wired (executable={}, provider={}) — "
                        + "gRPC stdout schema is upstream-marked-unstable; "
                        + "defensive parser active",
                        clineConfig.executablePath(),
                        clineConfig.provider() == null ? "(auto)" : clineConfig.provider());
                } else {
                    log.info("Cline backend enabled in config but binary '{}' not reachable; "
                        + "skipping registration", clineConfig.executablePath());
                }
            } else {
                log.debug("Cline backend disabled in config; skipping registration");
            }
        }

        if (registry.backendFor(ContinueBackend.NAME).isEmpty()) {
            var continueConfig = ContinueRuntimeConfig.fromConfig(config);
            if (continueConfig.enabled()) {
                if (binaryReachable(ContinueBackend.NAME, continueConfig.executablePath())) {
                    registry.register(new ContinueBackend(continueConfig, authForBackends));
                    registry.register(new ContinueEventAdapter());
                    log.info("Continue backend wired (executable={}, agent={})",
                        continueConfig.executablePath(),
                        continueConfig.agent() == null ? "(default)" : continueConfig.agent());
                } else {
                    log.info("Continue backend enabled in config but binary '{}' not reachable; "
                        + "skipping registration", continueConfig.executablePath());
                }
            } else {
                log.debug("Continue backend disabled in config; skipping registration");
            }
        }

        // -- Phase 2e: paid-tier backends ------------------------------
        // Each adapter:
        //   • registers when enabled in config AND auth resolves to a
        //     non-AuthMissing path (paid backends should NOT register
        //     when the household has no auth — selection chain stays
        //     clean instead of misleading).
        //   • Devin is REST-only (no binary); the binary check is
        //     skipped, only the auth gate matters.
        //
        // Note: AuthResolver is wired AFTER the Phase 2d block but
        // before this block uses it via authForBackends, which is a
        // closure over authResolver(). We re-read here for clarity.

        // 2e.1: Claude Code SDK (extends ClaudeCliInference pattern).
        if (registry.backendFor(ClaudeSdkBackend.NAME).isEmpty()) {
            var claudeConfig = ClaudeSdkRuntimeConfig.fromConfig(config);
            if (claudeConfig.enabled()) {
                if (!binaryReachable(ClaudeSdkBackend.NAME, claudeConfig.executablePath())) {
                    log.info("Claude SDK backend enabled in config but binary '{}' not reachable; "
                        + "skipping registration", claudeConfig.executablePath());
                } else if (paidBackendAuthMissing(ClaudeSdkBackend.NAME)) {
                    log.debug("Claude SDK backend enabled but auth missing; skipping registration "
                        + "(run `wyrd coding login claude-sdk` or set ANTHROPIC_API_KEY)");
                } else {
                    registry.register(new ClaudeSdkBackend(claudeConfig, authForBackends));
                    registry.register(new ClaudeSdkEventAdapter());
                    log.info("Claude SDK backend wired (executable={}, model={}, useBare={})",
                        claudeConfig.executablePath(),
                        claudeConfig.model(),
                        claudeConfig.useBare());
                }
            } else {
                log.debug("Claude SDK backend disabled in config; skipping registration");
            }
        }

        // 2e.2a: Codex CLI.
        if (registry.backendFor(CodexCliBackend.NAME).isEmpty()) {
            var codexConfig = CodexCliRuntimeConfig.fromConfig(config);
            if (codexConfig.enabled()) {
                if (!binaryReachable(CodexCliBackend.NAME, codexConfig.executablePath())) {
                    log.info("Codex CLI backend enabled in config but binary '{}' not reachable; "
                        + "skipping registration", codexConfig.executablePath());
                } else if (paidBackendAuthMissing(CodexCliBackend.NAME)) {
                    log.debug("Codex CLI backend enabled but auth missing; skipping registration "
                        + "(run `wyrd coding login codex` or set OPENAI_API_KEY)");
                } else {
                    registry.register(new CodexCliBackend(codexConfig, authForBackends));
                    registry.register(new CodexCliEventAdapter());
                    log.info("Codex CLI backend wired (executable={}, provider={})",
                        codexConfig.executablePath(),
                        codexConfig.provider() == null ? "(default)" : codexConfig.provider());
                }
            } else {
                log.debug("Codex CLI backend disabled in config; skipping registration");
            }
        }

        // 2e.2b: Gemini CLI.
        if (registry.backendFor(GeminiCliBackend.NAME).isEmpty()) {
            var geminiConfig = GeminiCliRuntimeConfig.fromConfig(config);
            if (geminiConfig.enabled()) {
                if (!binaryReachable(GeminiCliBackend.NAME, geminiConfig.executablePath())) {
                    log.info("Gemini CLI backend enabled in config but binary '{}' not reachable; "
                        + "skipping registration", geminiConfig.executablePath());
                } else if (paidBackendAuthMissing(GeminiCliBackend.NAME)) {
                    log.debug("Gemini CLI backend enabled but auth missing; skipping registration "
                        + "(set GEMINI_API_KEY in your Key Chest — no headless OAuth flow as of May 2026)");
                } else {
                    registry.register(new GeminiCliBackend(geminiConfig, authForBackends));
                    registry.register(new GeminiCliEventAdapter());
                    log.info("Gemini CLI backend wired (executable={}, model={}, trust={})",
                        geminiConfig.executablePath(),
                        geminiConfig.model(),
                        geminiConfig.trustWorkspace());
                }
            } else {
                log.debug("Gemini CLI backend disabled in config; skipping registration");
            }
        }

        // 2e.3: Devin (REST-only, no binary).
        if (registry.backendFor(DevinBackend.NAME).isEmpty()) {
            var devinConfig = DevinRuntimeConfig.fromConfig(config);
            if (devinConfig.enabled()) {
                if (devinConfig.orgId() == null || devinConfig.orgId().isBlank()) {
                    log.info("Devin backend enabled in config but no org_id set; skipping registration "
                        + "(set coding.backends.devin.org_id)");
                } else if (paidBackendAuthMissing(DevinBackend.NAME)) {
                    log.debug("Devin backend enabled but auth missing; skipping registration "
                        + "(set DEVIN_API_KEY in your Key Chest)");
                } else {
                    registry.register(new DevinBackend(devinConfig, authForBackends));
                    registry.register(new DevinEventAdapter());
                    log.info("Devin backend wired (api_base={}, org_id={}, max_wallclock_hours={})",
                        devinConfig.apiBase(),
                        devinConfig.orgId(),
                        devinConfig.maxWallclockHours());
                }
            } else {
                log.debug("Devin backend disabled in config; skipping registration");
            }
        }

        // register a CodingNamespaceHandler
        // for every backend that landed in the registry. The router resolves
        // <backend>.<verb> to the matching handler; each handler delegates
        // to its backend via BackendRegistry.backendFor(name). Idempotent:
        // re-init replaces the prior handler (LocalCommandRouter logs the
        // swap). Skipping handler registration leaves the namespace empty
        // — the workshop falls through to its narration-only fallback.
        for (var backend : registry.backends()) {
            var name = backend.name();
            if (router.hasHandler(name)) continue;
            router.register(name, new CodingNamespaceHandler(name, registry));
        }

        log.info("AuthResolver wired ({})", resolver.getClass().getSimpleName());
    }

    /**
     * Probe whether a paid backend's auth path is currently
     * non-{@link AuthMode.AuthMissing}. Phase 2e's bootstrap consults
     * this <i>before</i> registering each paid backend so the selection
     * chain stays clean — registering an adapter that will instantly
     * fail every {@code submitTask} call would mislead the policy
     * script. Re-evaluation on auth-state change happens at the next
     * household {@code wyrd config reload} (households re-init the
     * bootstrap when keys change).
     */
    static boolean paidBackendAuthMissing(String backendName) {
        var auth = authResolver().resolveAuth(backendName);
        return auth instanceof AuthMode.AuthMissing;
    }

    /**
     * Returns the process-wide resolver. Always non-null: returns a
     * sentinel that emits {@link AuthMode.AuthMissing} when {@link
     * #init(Config, Function)} hasn't been called.
     */
    public static AuthResolver authResolver() {
        AuthResolver r = AUTH_RESOLVER.get();
        return r == null ? UNCONFIGURED : r;
    }

    /** Test seam — drop the wired resolver. */
    static void resetForTest() {
        AUTH_RESOLVER.set(null);
    }

    /**
     * Phase 2d helper — is the named backend's binary reachable on this
     * host? Two probes, in order:
     * <ol>
     *   <li>Bundle installer: if the household has run
     *       {@code wyrd coding install <name>} the binary lives at a
     *       known location with a {@code .version} marker. Cheap.</li>
     *   <li>PATH lookup: walk {@code PATH} looking for an executable
     *       file whose name matches the configured executable (or the
     *       backend's default). Mirrors the {@code which} logic in
     *       {@code OpenHandsBackend.probeDockerDefault}.</li>
     * </ol>
     *
     * <p>Either probe is sufficient — a household that runs
     * {@code goose} from PATH (homebrew install, e.g.) shouldn't need to
     * also run {@code wyrd coding install goose}. Bootstrap stays
     * silent on miss; the steward sees a {@code skipping registration}
     * line in the log if they care.</p>
     */
    static boolean binaryReachable(String backendName, String executablePath) {
        if (executablePath == null || executablePath.isBlank()) return false;

        // (1) Bundle installer status — only consult when the binary
        // string is just the bare name (the manifest installs to a
        // canonical path under the bundle root, not at an absolute path
        // a steward set themselves).
        if (!executablePath.contains("/") && !executablePath.contains("\\")) {
            try {
                var manifestOpt = loadManifestQuietly();
                if (manifestOpt.isPresent()) {
                    var root = Path.of(
                        Objects.requireNonNullElse(
                            System.getenv("WYRDSEKAI_DATA_DIR"),
                            "data") + "/coding-cli-bundle");
                    var status = new BundleInstaller(manifestOpt.get(),
                        new AirGapBundleCache(root.resolve("cache")))
                            .getStatus(backendName, root);
                    if (status.installed()) return true;
                }
            } catch (Exception e) {
                log.debug("Bundle-status probe for {} failed: {}",
                    backendName, e.getMessage());
            }
        }

        // (2) Direct path or PATH lookup.
        try {
            var p = Path.of(executablePath);
            if (p.isAbsolute()) {
                return Files.isExecutable(p);
            }
        } catch (Exception _) {
            // Not a path-shaped string — fall through to PATH lookup.
        }

        String path = System.getenv("PATH");
        if (path == null) return false;
        for (var dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            var candidate = Path.of(dir, executablePath);
            try {
                if (Files.isExecutable(candidate)) return true;
            } catch (Exception _) { /* skip unreadable dir */ }
        }
        return false;
    }

    private static AuthResolver buildAuthResolver(Function<String, String> keyChestLookup) {
        Optional<BundleManifest> manifest = loadManifestQuietly();
        if (manifest.isEmpty()) {
            return UNCONFIGURED;
        }
        Function<String, String> lookup = keyChestLookup != null
                ? keyChestLookup
                : slot -> null;
        return new DefaultAuthResolver(manifest.get(), lookup);
    }

    private static Optional<BundleManifest> loadManifestQuietly() {
        try {
            return Optional.of(BundleManifest.load(BundleManifest.resolveDefaultManifestPath()));
        } catch (IOException e) {
            log.warn("AuthResolver: bundle manifest unreadable ({}); paid backends will surface AuthMissing",
                    e.getMessage());
            return Optional.empty();
        } catch (BundleManifest.ManifestValidationException e) {
            log.warn("AuthResolver: bundle manifest invalid ({}); paid backends will surface AuthMissing",
                    e.getMessage());
            return Optional.empty();
        }
    }
}
