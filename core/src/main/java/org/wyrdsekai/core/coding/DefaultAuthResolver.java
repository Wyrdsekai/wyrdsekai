package org.wyrdsekai.core.coding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Manifest- and Key-Chest-backed implementation of {@link AuthResolver}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Look up the backend in the {@link BundleManifest}. Unknown →
 *       {@link AuthMode.AuthMissing}.</li>
 *   <li>If the entry has a non-null {@code auth.oauth} block and the
 *       backend's credential file/directory is present and non-empty,
 *       return {@link AuthMode.OAuthSession}. The OAuth probe is
 *       deliberately a cheap filesystem check, not a subprocess
 *       invocation: shelling out to {@code <backend> auth status} on
 *       every task call would dominate task-spawn latency. The
 *       upstream CLI revalidates on first use; an expired token still
 *       surfaces from the adapter's own error path.</li>
 *   <li>Otherwise, if the entry has an {@code auth.api_key.key_chest_slot}
 *       and the household Key Chest holds a non-blank value at that
 *       slot, return {@link AuthMode.ApiKey}.</li>
 *   <li>Otherwise return {@link AuthMode.AuthMissing} with
 *       {@code recoveryCommand = "wyrd coding login <name>"}.</li>
 * </ol>
 *
 * <p>The Key Chest backend is supplied as a {@code Function<String,
 * String>} so the resolver doesn't depend on any specific store
 * implementation — production wiring passes a closure over the
 * household's {@code McpKeyStore} / {@code TheSafe} actor; tests pass
 * an in-memory map.</p>
 */
public final class DefaultAuthResolver implements AuthResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthResolver.class);

    private final BundleManifest manifest;
    private final Function<String, String> keyChestLookup;
    private final Function<String, Path> credentialPathResolver;

    /**
     * Production constructor.
     *
     * @param manifest        loaded {@link BundleManifest}; the resolver
     *                        consults its {@code auth} blocks.
     * @param keyChestLookup  function mapping {@code key_chest_slot} →
     *                        plaintext value (or {@code null} when
     *                        absent). Typically a closure over the
     *                        household's key store.
     */
    public DefaultAuthResolver(BundleManifest manifest,
                               Function<String, String> keyChestLookup) {
        this(manifest, keyChestLookup, DefaultAuthResolver::expandUserHome);
    }

    /** Test seam — overrides credential-path resolution (e.g. to point at a tmpdir). */
    public DefaultAuthResolver(BundleManifest manifest,
                               Function<String, String> keyChestLookup,
                               Function<String, Path> credentialPathResolver) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.keyChestLookup = Objects.requireNonNull(keyChestLookup, "keyChestLookup");
        this.credentialPathResolver = Objects.requireNonNull(credentialPathResolver,
                "credentialPathResolver");
    }

    @Override
    public AuthMode resolveAuth(String backendName) {
        if (backendName == null || backendName.isBlank()) {
            return new AuthMode.AuthMissing("?",
                    "wyrd coding list",
                    "Backend name is null or blank");
        }
        String canonical = backendName.toLowerCase(Locale.ROOT);
        var entryOpt = manifest.get(canonical);
        if (entryOpt.isEmpty()) {
            return new AuthMode.AuthMissing(canonical,
                    "wyrd coding list",
                    "Backend '" + canonical + "' not found in manifest");
        }
        BackendBundleEntry entry = entryOpt.get();
        var auth = entry.auth();

        // 1. OAuth probe (skip when the entry declares no oauth path).
        if (auth != null && auth.oauth() != null) {
            String credPath = auth.oauth().credentialPath();
            if (credPath != null && !credPath.isBlank()) {
                Path resolved = credentialPathResolver.apply(credPath);
                if (oauthCredentialsLookLive(resolved)) {
                    log.debug("[AuthResolver] {} -> OAuthSession (path={})",
                            canonical, resolved);
                    return new AuthMode.OAuthSession();
                }
            }
        }

        // 2. API-key fallback via Key Chest slot.
        if (auth != null && auth.apiKey() != null
                && auth.apiKey().keyChestSlot() != null
                && !auth.apiKey().keyChestSlot().isBlank()) {
            String slot = auth.apiKey().keyChestSlot();
            String value = keyChestLookup.apply(slot);
            if (value != null && !value.isBlank()) {
                log.debug("[AuthResolver] {} -> ApiKey (slot={})", canonical, slot);
                return new AuthMode.ApiKey(value);
            }
        }

        // 3. Neither path live — surface an actionable recovery hint.
        String recovery = (auth != null && auth.oauth() != null)
                ? "wyrd coding login " + canonical
                : null;
        String reason;
        if (auth == null) {
            reason = "Backend '" + canonical + "' has no auth block in the manifest";
            recovery = "wyrd coding list";
        } else if (auth.oauth() != null && auth.apiKey() != null) {
            reason = "No OAuth session at " + auth.oauth().credentialPath()
                    + " and no key in Key Chest slot "
                    + auth.apiKey().keyChestSlot();
        } else if (auth.oauth() != null) {
            reason = "No OAuth session at " + auth.oauth().credentialPath();
        } else if (auth.apiKey() != null) {
            reason = "No key in Key Chest slot " + auth.apiKey().keyChestSlot();
            recovery = "wyrd config (set " + auth.apiKey().envVar() + " in Key Chest)";
        } else {
            reason = "Backend '" + canonical
                    + "' declares an empty auth block (neither OAuth nor API key)";
            recovery = "wyrd coding list";
        }
        return new AuthMode.AuthMissing(canonical, recovery, reason);
    }

    /**
     * Cheap "do they have an OAuth session" probe. The credential
     * path can be a file (most CLIs — {@code ~/.codex/auth.json}) or
     * a directory ({@code ~/.config/claude/}). Either is "live" when
     * non-empty; absence or empty contents is "no session".
     *
     * <p>Catches IO errors silently and reports them as "not live"
     * so a permissions glitch on one slot doesn't crash the whole
     * resolver — adapters surface their own auth errors when actually
     * spawning the backend.</p>
     */
    private static boolean oauthCredentialsLookLive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    return stream.findAny().isPresent();
                }
            }
            if (Files.isRegularFile(path)) {
                return Files.size(path) > 0;
            }
            return false;
        } catch (IOException e) {
            log.debug("[AuthResolver] OAuth probe IO error for {}: {}",
                    path, e.getMessage());
            return false;
        }
    }

    /**
     * Expand a leading {@code ~/} (and bare {@code ~}) to the user's
     * home directory. The manifest stores credential paths in the
     * documented form ({@code ~/.codex/auth.json}); we resolve here
     * rather than at parse time so a manifest can be loaded on a
     * machine and probed on another (test fixtures).
     */
    static Path expandUserHome(String raw) {
        if (raw == null) return null;
        String home = System.getProperty("user.home", "");
        String s = raw;
        if (s.equals("~")) return Path.of(home);
        if (s.startsWith("~/")) return Path.of(home, s.substring(2));
        if (s.startsWith("~\\")) return Path.of(home, s.substring(2));
        return Path.of(s);
    }
}
