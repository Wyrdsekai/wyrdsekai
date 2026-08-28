package org.wyrdsekai.core.coding;

import org.wyrdsekai.core.inference.LocalInferenceEndpoint;
import com.typesafe.config.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runtime configuration for the CodeZaiku CLI backend.
 *
 * <p>Contract agreed with the CodeZaiku team 2026-08-15 (see
 * backend table): CodeZaiku is a drop-in beside
 * Goose — same argv shape, workspace as subprocess CWD, health via
 * {@code codezaiku --version}. Model routing travels ONLY via env:
 * {@code CODEZAIKU_DRIVE} (OpenAI-compatible endpoint) and
 * {@code CODEZAIKU_MODEL}. CodeZaiku reads config files too
 * ({@code $CODEZAIKU_CONFIG} / {@code ~/.codezaiku/config} /
 * {@code /etc/codezaiku/config}) but env wins by first-hit precedence,
 * so our injection is authoritative regardless of what a user has on
 * disk. {@code CODEZAIKU_DISTILLER_URL} is deliberately not modeled.</p>
 */
public record CodeZaikuRuntimeConfig(
    boolean enabled,
    String executablePath,
    String driveUrl,
    String model,
    Duration maxWallclock,
    List<String> extraFlags,
    boolean driveUrlFromConfig,
    boolean modelFromConfig
) {

    /**
     * Back-compat shape: a value you actually PASS is a value you chose.
     *
     * <p>Passing {@code "https://drive.example.com"} here is a deliberate act, so it
     * claims authority. Passing null or blank is not, so it falls to the default level
     * and yields to whatever config file the machine's owner wrote — which is exactly
     * what {@link #defaults()} does, since it passes nulls throughout.
     */
    public CodeZaikuRuntimeConfig(boolean enabled, String executablePath, String driveUrl,
            String model, Duration maxWallclock, List<String> extraFlags) {
        this(enabled, executablePath, driveUrl, model, maxWallclock, extraFlags,
            driveUrl != null && !driveUrl.isBlank(),
            model != null && !model.isBlank());
    }

    /** Stable name of the typesafe-config root for this backend. */
    public static final String CONFIG_ROOT = "wyrdsekai.coding.backends.codezaiku";

    /** Default executable name; resolved against PATH. */
    public static final String DEFAULT_EXECUTABLE = "codezaiku";

    /** CodeZaiku's own default endpoint. We always inject explicitly —
     *  never rely on this matching the household's actual llama port
     *  (home-server serves 8200, the home zone serves 8201). */
    public static final String DEFAULT_DRIVE_URL = "http://localhost:8200";

    /** CodeZaiku's default request-model field. */
    public static final String DEFAULT_MODEL = "local-model";

    public static final Duration DEFAULT_MAX_WALLCLOCK = Duration.ofMinutes(30);

    public CodeZaikuRuntimeConfig {
        if (executablePath == null || executablePath.isBlank()) {
            executablePath = resolveDefaultExecutable();
        }
        // Filling the value is right; ERASING the fact that we filled it is not. Until
        // 2026-08-21 this constructor collapsed "the operator chose localhost:8200" and
        // "nobody said, so we guessed" into the same record, and buildEnv() could only
        // then inject CODEZAIKU_DRIVE as authoritative. A machine configured against a
        // hosted endpoint was silently redirected to localhost, and `codezaiku doctor`
        // read the config FILE — so it reported healthy while the run went elsewhere.
        // (Reported by the CodeZaiku team, who ship the _DEFAULT precedence in 55ab5182.)
        if (driveUrl == null || driveUrl.isBlank()) {
            driveUrl = DEFAULT_DRIVE_URL;
            driveUrlFromConfig = false;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
            modelFromConfig = false;
        }
        if (maxWallclock == null) maxWallclock = DEFAULT_MAX_WALLCLOCK;
        if (extraFlags == null) extraFlags = List.of();
    }

    /**
     * Prefer the bundled binary when present (same trap as Goose:
     * {@code wyrd setup} installs under the data dir's
     * {@code coding-cli-bundle/} but does not touch PATH), else fall back
     * to a bare PATH lookup.
     */
    static String resolveDefaultExecutable() {
        // One resolver for every backend: it searches the bundle dir, the
        // system prefixes, and the homes a per-user installer actually writes
        // to -- including the data-directory owner's, which is the one a
        // systemd service (HOME=/root) otherwise cannot see.
        return BackendExecutableResolver.resolve(DEFAULT_EXECUTABLE);
    }

    /** Configured, else what this node serves, else the compiled-in default. */
    public String effectiveDriveUrl() {
        if (driveUrlFromConfig) return driveUrl;
        return LocalInferenceEndpoint.resolve().map(LocalInferenceEndpoint.Endpoint::url)
            .orElse(driveUrl);
    }

    /** Configured, else the id the live server reports, else the compiled-in default. */
    public String effectiveModel() {
        if (modelFromConfig) return model;
        return LocalInferenceEndpoint.resolve().map(LocalInferenceEndpoint.Endpoint::modelId)
            .orElse(model);
    }

    public static CodeZaikuRuntimeConfig defaults() {
        return new CodeZaikuRuntimeConfig(true, null, null, null, null, null);
    }

    public static CodeZaikuRuntimeConfig fromConfig(Config config) {
        if (config == null || !config.hasPath(CONFIG_ROOT)) return defaults();
        var c = config.getConfig(CONFIG_ROOT);
        return new CodeZaikuRuntimeConfig(
            !c.hasPath("enabled") || c.getBoolean("enabled"),
            c.hasPath("executable-path") ? c.getString("executable-path") : null,
            c.hasPath("drive-url") ? c.getString("drive-url") : null,
            c.hasPath("model") ? c.getString("model") : null,
            c.hasPath("max-wallclock-minutes")
                ? Duration.ofMinutes(c.getLong("max-wallclock-minutes")) : null,
            c.hasPath("extra-flags") ? List.copyOf(c.getStringList("extra-flags")) : null,
            // A present-but-blank setting is NOT a choice: CodeZaiku treats blank as
            // absent at every level, so claiming authority for one would pin nothing
            // while looking authoritative here.
            c.hasPath("drive-url") && !c.getString("drive-url").isBlank(),
            c.hasPath("model") && !c.getString("model").isBlank());
    }
}
