package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * One entry in {@code data/coding-cli-bundle/manifest.json}. Per
 *
 * <p>Three shapes share the same record:</p>
 * <ul>
 *   <li><b>Bundled</b> ({@code bundled=true}): ships in the install image,
 *       no download required. Fields used: {@code version}, {@code path},
 *       {@code configTemplate}.</li>
 *   <li><b>Downloadable</b> ({@code bundled=false}): manifest pins
 *       {@code downloadUrlTemplate} + {@code sha256PerPlatform}; binary is
 *       fetched on first {@code wyrd coding install}.</li>
 *   <li><b>Setup-helper / config-only</b> ({@code dockerImage} or
 *       {@code configOnly=true}): no managed binary; either runs through a
 *       separate setup command (OpenHands → Docker) or is purely API-keyed
 *       (Devin).</li>
 * </ul>
 *
 * <p>Optional fields are nullable; consumers must guard with
 * {@code != null} before use. Jackson is configured to ignore unknown
 * properties so a manifest from a newer Wyrdsekai release loads cleanly
 * on an older client (forward-compat).</p>
 *
 * @param name                   stable backend identifier (matches the key
 *                               in the manifest's {@code backends} map and
 *                               the {@link CodingTaskBackend#name()} of any
 *                               adapter that wires it). Populated by
 *                               {@link BundleManifest} after load — never
 *                               serialised into the JSON itself.
 * @param bundled                {@code true} when the binary ships inside
 *                               the install image; {@code false} for
 *                               download-on-select.
 * @param version                pinned version string ({@code "X.Y.Z"});
 *                               substituted into {@code downloadUrlTemplate}.
 * @param path                   relative path under the install root where
 *                               a bundled binary lives; non-null only when
 *                               {@code bundled=true}.
 * @param configTemplate         relative path to a per-backend config
 *                               template; non-null only when bundled.
 * @param downloadUrlTemplate    URL template with {@code {version}},
 *                               {@code {platform}}, {@code {arch}}
 *                               substitutions; non-null only when
 *                               {@code bundled=false} <i>and</i> not a
 *                               setup-helper / config-only entry.
 * @param sha256PerPlatform      map keyed by {@code "<platform>-<arch>"}
 *                               (e.g. {@code "linux-x64"}); each value is
 *                               the lowercase hex sha256 of the platform
 *                               archive; placeholder
 *                               {@code "TODO_RUN_BUILD_HELPER"} marks
 *                               un-pinned slots that the build helper must
 *                               populate before release.
 * @param sizeMb                 advisory disk-cost hint shown in the Study
 *                               Coding Slate confirmation dialogue.
 * @param dockerImage            non-null for setup-helper backends
 *                               (e.g. OpenHands) — the manifest names the
 *                               image but doesn't manage the pull;
 *                               {@code setupCommand} owns that.
 * @param setupCommand           shell command shown to the steward when
 *                               this backend needs out-of-band setup
 *                               (paired with {@code dockerImage}).
 * @param configOnly             {@code true} for cloud-SaaS backends that
 *                               have no local binary at all (Devin).
 * @param tosWarning             surface-level ToS reminder shown in the
 *                               install dialogue (cloud / paid backends).
 * @param minVersion             lower bound enforced by
 *                               {@link BundleInstaller#installBackend} —
 *                               refuses to install if {@code version <
 *                               minVersion} (e.g. Gemini RCE pin).
 * @param minVersionReason       human-readable explanation of why the
 *                               lower bound exists.
 * @param implementationWarning  defensive note for adapter authors
 *                               (e.g. Cline's gRPC instability warning).
 * @param distribution           v2 schema (2026-05-04): {@code "github_release"}
 *                               (default; classic atomic-tarball pipeline) or
 *                               {@code "npm"} (Cline, Continue, Claude SDK,
 *                               Gemini CLI — installed via {@code npm install
 *                               -g <pkg>@<version>}). Null/absent in legacy
 *                               v1 manifests; {@link #effectiveDistribution()}
 *                               normalises the read.
 * @param npmPackage             v2 schema: npm registry name (e.g.
 *                               {@code "@continuedev/cli"}). Required when
 *                               {@code distribution = "npm"}, ignored
 *                               otherwise.
 * @param auth                   dual-path auth descriptor (OAuth +/- API
 * key).
 *                               {@code null} for backends that need no
 *                               auth (local-only, e.g. OpenCode).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BackendBundleEntry(
        // Not in JSON — populated post-deserialisation by BundleManifest.
        String name,

        @JsonProperty("bundled")
        boolean bundled,

        @JsonProperty("version")
        String version,

        @JsonProperty("path")
        String path,

        @JsonProperty("config_template")
        String configTemplate,

        @JsonProperty("download_url_template")
        String downloadUrlTemplate,

        @JsonProperty("sha256_per_platform")
        Map<String, String> sha256PerPlatform,

        @JsonAlias({"size_mb"})
        @JsonProperty("size_mb")
        Integer sizeMb,

        @JsonProperty("docker_image")
        String dockerImage,

        @JsonProperty("setup_command")
        String setupCommand,

        @JsonProperty("config_only")
        boolean configOnly,

        @JsonProperty("tos_warning")
        String tosWarning,

        @JsonProperty("min_version")
        String minVersion,

        @JsonProperty("min_version_reason")
        String minVersionReason,

        @JsonProperty("implementation_warning")
        String implementationWarning,

        @JsonProperty("distribution")
        String distribution,

        @JsonProperty("npm_package")
        String npmPackage,

        @JsonProperty("auth")
        AuthDescriptor auth
) {

    /** v2 distribution channel: classic GitHub release tarballs. */
    public static final String DISTRIBUTION_GITHUB_RELEASE = "github_release";

    /** v2 distribution channel: install via {@code npm install -g <pkg>}. */
    public static final String DISTRIBUTION_NPM = "npm";

    /**
     * Returns a new entry with {@code name} populated. The on-disk JSON
     * uses backend names as map keys, not embedded fields — Jackson can't
     * round-trip the key into the record on its own, so {@link
     * BundleManifest} calls this after deserialisation.
     */
    public BackendBundleEntry withName(String resolvedName) {
        return new BackendBundleEntry(
                resolvedName, bundled, version, path, configTemplate,
                downloadUrlTemplate, sha256PerPlatform, sizeMb,
                dockerImage, setupCommand, configOnly, tosWarning,
                minVersion, minVersionReason, implementationWarning,
                distribution, npmPackage,
                auth
        );
    }

    /**
     * Resolved distribution channel — defaults to {@link
     * #DISTRIBUTION_GITHUB_RELEASE} when the JSON omits the field
     * (back-compat with v1 manifests). Always non-null.
     */
    public String effectiveDistribution() {
        if (distribution == null || distribution.isBlank()) return DISTRIBUTION_GITHUB_RELEASE;
        return distribution;
    }

    /**
     * True when this entry installs via npm (v2 schema). False for the
     * default github-release path. Used by {@link BundleInstaller} to
     * pick the install pipeline (atomic tarball download vs
     * {@code npm install -g}).
     */
    public boolean isNpmDistribution() {
        return DISTRIBUTION_NPM.equalsIgnoreCase(effectiveDistribution());
    }

    /**
     * Container for the per-backend dual-auth descriptor (SPEC §9.2 +
     * §9.2.1). Both {@code oauth} and {@code apiKey} are optional —
     * absence means "no path of that type"; an entry with both null is
     * legal (e.g. an unauthenticated local backend) and resolves as
     * {@link AuthMode.OAuthSession} only when the resolver hits a
     * present-and-non-null {@code oauth} probe path. The CLI surfaces
     * the human-readable {@code note} to stewards before invoking the
     * native flow.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthDescriptor(
            @JsonProperty("oauth")
            OAuthDescriptor oauth,

            @JsonProperty("api_key")
            ApiKeyDescriptor apiKey
    ) {}

    /**
     * Per-backend OAuth flow descriptor. {@code command} is the literal
     * argv passed through {@link ProcessBuilder} (split on whitespace
     * — backends shell-quote their own args). {@code credentialPath}
     * is the location the backend writes its tokens to; we never read
     * the contents, only test for non-empty existence as a cheap
     * "do they have a session" probe.
     *
     * <p>{@code headlessSupported=false} means the flow needs a browser
     * (Anthropic's Claude Code uses a localhost callback); the {@code
     * wyrd coding login} CLI refuses to invoke headless-incapable
     * flows on a non-TTY host unless {@code --force} is passed.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OAuthDescriptor(
            @JsonProperty("command")
            String command,

            @JsonProperty("credential_path")
            String credentialPath,

            @JsonProperty("headless_supported")
            boolean headlessSupported,

            @JsonProperty("note")
            String note
    ) {}

    /**
     * Per-backend API-key fallback descriptor. {@code envVar} is the
     * conventional environment variable that the upstream CLI itself
     * checks (e.g. {@code OPENAI_API_KEY}); {@code keyChestSlot} is
     * the named slot in the household Key Chest where Wyrdsekai stores
     * the encrypted value. They typically match but can diverge if a
     * household alias is needed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ApiKeyDescriptor(
            @JsonProperty("env_var")
            String envVar,

            @JsonProperty("key_chest_slot")
            String keyChestSlot,

            @JsonProperty("note")
            String note
    ) {}

    /**
     * True when this entry refers to a binary the {@link BundleInstaller}
     * is responsible for. False for setup-helper (Docker pull) and
     * config-only (Devin) entries — those go through different paths.
     *
     * <p>npm-distributed entries (v2 schema) are installable: they go
     * through {@code npm install -g <pkg>@<version>} instead of the
     * download+sha256 path. {@code downloadUrlTemplate} can be null for
     * npm-distributed entries — the test "is the installer responsible
     * for putting this binary on disk" is satisfied by either a URL
     * template or an npm package.</p>
     */
    public boolean isInstallable() {
        if (dockerImage != null && !dockerImage.isBlank()) return false;
        if (configOnly) return false;
        if (bundled) return false;
        if (isNpmDistribution()) {
            return npmPackage != null && !npmPackage.isBlank();
        }
        return downloadUrlTemplate != null && !downloadUrlTemplate.isBlank();
    }

    /**
     * Look up the sha256 for a {@code "<platform>-<arch>"} key; returns
     * {@code null} if the manifest has no entry (caller surfaces an
     * actionable error to the steward in that case).
     */
    public String sha256For(String platformArch) {
        if (sha256PerPlatform == null) return null;
        return sha256PerPlatform.get(platformArch);
    }

    /**
     * Substitute the {@code {version}}, {@code {platform}}, {@code {arch}},
     * {@code {rust_triple}}, {@code {rust_triple_musl}} tokens in
     * {@link #downloadUrlTemplate}. Returns {@code null} for
     * npm-distributed entries (those have no template-based download —
     * the installer shells out to {@code npm install -g} instead) and
     * for entries whose template field is unset.
     *
     * <p>Rust-target-triple substitution exists because some upstreams
     * (goose, codex) ship per-Rust-target tarballs rather than the simpler
     * {@code <os>-<arch>} pattern. The Rust triple for a given (platform,
     * arch) pair is fixed; we just look it up here.</p>
     */
    public String resolvedDownloadUrl(String platform, String arch) {
        if (downloadUrlTemplate == null) return null;
        String resolvedVersion = version == null ? "" : version;
        return downloadUrlTemplate
                .replace("{version}", resolvedVersion)
                .replace("{platform}", platform)
                .replace("{arch}", arch)
                .replace("{rust_triple}", rustTripleFor(platform, arch, false))
                .replace("{rust_triple_musl}", rustTripleFor(platform, arch, true));
    }

    /**
     * Map a Wyrdsekai platform-arch pair to its Rust target triple. The
     * {@code musl} flag picks the static-linked linux variant some upstreams
     * prefer (e.g. {@code x86_64-unknown-linux-musl}). Unknown pairs return
     * an empty string, which lets the consumer fail fast on the resulting
     * malformed URL rather than fabricate a download.
     */
    static String rustTripleFor(String platform, String arch, boolean musl) {
        return switch (platform + "-" + arch) {
            case "linux-x64" ->
                    musl ? "x86_64-unknown-linux-musl" : "x86_64-unknown-linux-gnu";
            case "linux-arm64" ->
                    musl ? "aarch64-unknown-linux-musl" : "aarch64-unknown-linux-gnu";
            case "darwin-x64"   -> "x86_64-apple-darwin";
            case "darwin-arm64" -> "aarch64-apple-darwin";
            case "windows-x64"  -> "x86_64-pc-windows-msvc";
            default -> "";
        };
    }
}
