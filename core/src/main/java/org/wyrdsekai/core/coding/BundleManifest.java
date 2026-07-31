package org.wyrdsekai.core.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Loader + validator for {@code data/coding-cli-bundle/manifest.json}.
 *
 * <p>The manifest is the single source of truth for which coding backends
 * are downloadable, what version the household is pinned to, and what
 * sha256 the installer checks downloads against. Every {@code wyrd
 * coding} subcommand routes through here.</p>
 *
 * <p>Schema validation is deliberately strict on top-level required fields
 * ({@code manifest_version}, {@code backends}) but lenient on per-entry
 * fields — different backend kinds (bundled / downloadable / setup-helper
 * / config-only) have different required field sets, and {@link
 * BackendBundleEntry} handles its own optional-field tolerance via
 * {@code @JsonIgnoreProperties}. The only structural checks done here
 * are the things that, if wrong, would silently corrupt downstream
 * behaviour.</p>
 */
public final class BundleManifest {

    /**
     * The manifest schema version this build's authors target. v2 (2026-05-04)
     * added {@code distribution} + {@code npm_package} on per-entry records
     * to support npm-distributed CLIs (Cline, Continue, Claude SDK,
     * Gemini CLI). v1 manifests still load — see
     * {@link #MIN_SUPPORTED_MANIFEST_VERSION}; the v2 fields are nullable so
     * older manifests degrade to {@code distribution = "github_release"}
     * cleanly.
     */
    public static final int SUPPORTED_MANIFEST_VERSION = 2;

    /**
     * Lowest manifest version this build will load. v1 manifests still work
     * (no shape changes, only additive fields in v2). Bumping the floor is a
     * breaking change for the household manifest cache — coordinate with
     * {@code scripts/build-coding-cli-manifest.sh}.
     */
    public static final int MIN_SUPPORTED_MANIFEST_VERSION = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int manifestVersion;
    private final Map<String, BackendBundleEntry> backends;

    private BundleManifest(int manifestVersion, Map<String, BackendBundleEntry> backends) {
        this.manifestVersion = manifestVersion;
        this.backends = backends;
    }

    /** Parsed {@code manifest_version}. */
    public int manifestVersion() { return manifestVersion; }

    /**
     * Backend entries keyed by name (canonical lowercase). Ordering is the
     * insertion order from the source JSON, which the manifest authors
     * sort to match the Study Coding Slate's display order.
     */
    public Map<String, BackendBundleEntry> backends() { return backends; }

    /** Look up a backend by name; {@link Optional#empty()} if unknown. */
    public Optional<BackendBundleEntry> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(backends.get(name.toLowerCase(Locale.ROOT)));
    }

    /**
     * Load a manifest from disk. Throws {@link ManifestValidationException}
     * on any structural problem (missing top-level fields, wrong manifest
     * version, malformed entries). The exception message is expected to
     * surface to the steward via the CLI, so it must be actionable.
     */
    public static BundleManifest load(Path manifestPath) throws IOException {
        if (!Files.isReadable(manifestPath)) {
            throw new ManifestValidationException(
                    "Coding-CLI bundle manifest not found or unreadable: " + manifestPath);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(manifestPath.toFile());
        } catch (IOException e) {
            throw new ManifestValidationException(
                    "Failed to parse manifest JSON at " + manifestPath + ": " + e.getMessage(), e);
        }
        return parse(root, manifestPath.toString());
    }

    /**
     * Parse from an already-decoded JSON tree. Useful for tests that build
     * the structure programmatically.
     */
    public static BundleManifest parse(JsonNode root, String sourceLabel) {
        if (root == null || !root.isObject()) {
            throw new ManifestValidationException(
                    "Manifest at " + sourceLabel + " must be a JSON object");
        }
        JsonNode versionNode = root.get("manifest_version");
        if (versionNode == null || !versionNode.canConvertToInt()) {
            throw new ManifestValidationException(
                    "Manifest at " + sourceLabel + " is missing required integer 'manifest_version'");
        }
        int version = versionNode.asInt();
        // Accept v1 (legacy) and v2 (current). v2 only adds optional fields
        // (distribution, npm_package), so v1 manifests still load cleanly —
        // BackendBundleEntry's @JsonIgnoreProperties handles unknown forward-
        // compat fields, and missing fields default to github_release at the
        // installer.
        if (version < MIN_SUPPORTED_MANIFEST_VERSION || version > SUPPORTED_MANIFEST_VERSION) {
            throw new ManifestValidationException(
                    "Manifest at " + sourceLabel + " has manifest_version=" + version
                            + " but this build supports v" + MIN_SUPPORTED_MANIFEST_VERSION
                            + "..v" + SUPPORTED_MANIFEST_VERSION
                            + ". Update wyrdsekai or pin to a compatible release.");
        }
        JsonNode backendsNode = root.get("backends");
        if (backendsNode == null || !backendsNode.isObject()) {
            throw new ManifestValidationException(
                    "Manifest at " + sourceLabel + " is missing required object 'backends'");
        }

        Map<String, BackendBundleEntry> entries = new LinkedHashMap<>();
        var fields = backendsNode.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            String name = field.getKey();
            if (name == null || name.isBlank()) {
                throw new ManifestValidationException(
                        "Manifest at " + sourceLabel + " contains a backend with a blank name");
            }
            String canonical = name.toLowerCase(Locale.ROOT);
            BackendBundleEntry raw;
            try {
                raw = MAPPER.treeToValue(field.getValue(), BackendBundleEntry.class);
            } catch (Exception e) {
                throw new ManifestValidationException(
                        "Manifest entry '" + name + "' (in " + sourceLabel
                                + ") is malformed: " + e.getMessage(), e);
            }
            BackendBundleEntry entry = raw.withName(canonical);
            validateEntry(entry, sourceLabel);
            entries.put(canonical, entry);
        }

        // Map.copyOf doesn't preserve insertion order — the Coding Slate
        // furnishing relies on the manifest authors' ordering, so wrap the
        // LinkedHashMap in an unmodifiable view that keeps it.
        return new BundleManifest(version, Collections.unmodifiableMap(entries));
    }

    /**
     * Per-entry sanity checks. Any field combination the {@link
     * BundleInstaller} cannot act on is surfaced here rather than as a
     * confusing NPE deep in the install path.
     */
    private static void validateEntry(BackendBundleEntry entry, String sourceLabel) {
        if (entry.bundled()) {
            // Bundled backend: must declare path + version. Everything else
            // is optional (sha256 isn't needed when the binary is in the
            // install image — verified at build time, not at runtime).
            if (entry.version() == null || entry.version().isBlank()) {
                throw new ManifestValidationException(
                        "Bundled backend '" + entry.name() + "' (in " + sourceLabel
                                + ") must declare a 'version'");
            }
            if (entry.path() == null || entry.path().isBlank()) {
                throw new ManifestValidationException(
                        "Bundled backend '" + entry.name() + "' (in " + sourceLabel
                                + ") must declare a 'path'");
            }
            return;
        }
        // Non-bundled: three sub-shapes.
        if (entry.configOnly()) {
            // Devin-style: nothing else required. We accept the entry as-is.
            return;
        }
        if (entry.dockerImage() != null && !entry.dockerImage().isBlank()) {
            // OpenHands-style: docker_image + setup_command. Don't require
            // download URLs because we don't manage the binary.
            return;
        }
        // npm-distributed (v2 schema, 2026-05-04): cline, continue, claude-sdk,
        // gemini-cli. Validation is per-distribution: npm uses the package name
        // (no URL template / sha256 map — npm has its own integrity), github
        // releases use the URL template + sha256 map.
        if (entry.isNpmDistribution()) {
            if (entry.npmPackage() == null || entry.npmPackage().isBlank()) {
                throw new ManifestValidationException(
                        "npm-distributed backend '" + entry.name() + "' (in " + sourceLabel
                                + ") must declare 'npm_package'");
            }
            if (entry.version() == null || entry.version().isBlank()) {
                throw new ManifestValidationException(
                        "npm-distributed backend '" + entry.name() + "' (in " + sourceLabel
                                + ") must declare a 'version'");
            }
            return;
        }
        // Plain downloadable (github_release): needs URL template + sha256 map
        // (entries may be the TODO_RUN_BUILD_HELPER placeholder; we accept
        // that here so the manifest is loadable in dev. The installer will
        // refuse to install when it discovers a placeholder hash.).
        if (entry.downloadUrlTemplate() == null || entry.downloadUrlTemplate().isBlank()) {
            throw new ManifestValidationException(
                    "Downloadable backend '" + entry.name() + "' (in " + sourceLabel
                            + ") must declare 'download_url_template'");
        }
        if (entry.sha256PerPlatform() == null || entry.sha256PerPlatform().isEmpty()) {
            throw new ManifestValidationException(
                    "Downloadable backend '" + entry.name() + "' (in " + sourceLabel
                            + ") must declare a non-empty 'sha256_per_platform' map");
        }
        if (entry.version() == null || entry.version().isBlank()) {
            throw new ManifestValidationException(
                    "Downloadable backend '" + entry.name() + "' (in " + sourceLabel
                            + ") must declare a 'version'");
        }
    }

    /**
     * Resolve the manifest path the way the rest of the wyrd CLI does it:
     * walk a search path of canonical install locations, returning the
     * first manifest found. Mirrors {@code _resolve_embedding_bundle} in
     * {@code bin/wyrd}.
     */
    public static Path resolveDefaultManifestPath() {
        // Honour explicit override first — used by tests (system property,
        // easier under modern JDK security) and by air-gapped installs
        // that ship a side-loaded manifest (env var).
        String prop = System.getProperty("wyrdsekai.coding.bundle.manifest");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String override = System.getenv("WYRDSEKAI_CODING_BUNDLE_MANIFEST");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        for (String candidate : new String[]{
                "/opt/wyrdsekai/data/coding-cli-bundle/manifest.json",
                "/usr/local/wyrdsekai/data/coding-cli-bundle/manifest.json",
        }) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) return p;
        }
        // Source-mode fallback — relative to current working directory.
        Path cwdRel = Path.of("data/coding-cli-bundle/manifest.json");
        if (Files.isReadable(cwdRel)) return cwdRel.toAbsolutePath();
        // Return the canonical relative path so callers see a useful
        // "not found at <path>" error rather than null.
        return cwdRel.toAbsolutePath();
    }

    /** Thrown for any structural problem that should surface to the user. */
    public static final class ManifestValidationException extends RuntimeException {
        public ManifestValidationException(String message) { super(message); }
        public ManifestValidationException(String message, Throwable cause) { super(message, cause); }
    }
}
