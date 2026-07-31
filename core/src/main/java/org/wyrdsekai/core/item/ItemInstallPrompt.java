package org.wyrdsekai.core.item;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.api.ItemManifestValidator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * steward consent surface for newly
 * installed scripted items.
 *
 * <p>Phase B ships the CLI flow: a render-and-prompt method that surfaces
 * the manifest's caps grouped by tier, plus the sensitivity, warnings, and
 * author DID. The Threshold furnishing + WebSocket modal are stubbed for
 * follow-up.</p>
 *
 * <p>Tests and headless installs use {@link Mode#AUTO_GRANT} which records
 * every declared cap into the grant store without prompting. Production
 * installs default to {@link Mode#INTERACTIVE}.</p>
 */
public final class ItemInstallPrompt {

    private static final Logger log = LoggerFactory.getLogger(ItemInstallPrompt.class);

    public enum Mode {
        /** Print the install card and read y/n from stdin. */
        INTERACTIVE,
        /** Auto-approve all caps. Used by tests + scripted installs. */
        AUTO_GRANT,
        /** Auto-decline all caps. Used by `wyrd permissions show` / dry-run. */
        AUTO_DECLINE
    }

    public record Decision(boolean approved, List<String> grantedCapabilities) {}

    private final Mode mode;
    private final ItemGrantStore grantStore;
    private final Supplier<String> stdinSupplier;

    public ItemInstallPrompt(Mode mode, ItemGrantStore grantStore) {
        this(mode, grantStore, ItemInstallPrompt::readLine);
    }

    public ItemInstallPrompt(Mode mode, ItemGrantStore grantStore,
                              Supplier<String> stdinSupplier) {
        this.mode = mode;
        this.grantStore = grantStore;
        this.stdinSupplier = stdinSupplier;
    }

    /** Render the install card and (if interactive) prompt the steward. */
    public Decision prompt(ItemManifest manifest, String stewardDid) {
        if (manifest == null) {
            return new Decision(false, List.of());
        }
        var card = renderCard(manifest);
        log.info("\n{}", card);

        return switch (mode) {
            case AUTO_GRANT -> {
                if (grantStore != null) {
                    for (var cap : manifest.capabilities()) {
                        grantStore.issue(manifest.name(), cap, stewardDid, null);
                    }
                }
                yield new Decision(true, manifest.capabilities());
            }
            case AUTO_DECLINE -> new Decision(false, List.of());
            case INTERACTIVE -> {
                System.out.print("[A]pprove all  [P]ick subset  [D]ecline  [V]iew code: ");
                var line = stdinSupplier.get();
                if (line == null) yield new Decision(false, List.of());
                var ch = line.trim().toLowerCase();
                if (ch.startsWith("a")) {
                    if (grantStore != null) {
                        for (var cap : manifest.capabilities()) {
                            grantStore.issue(manifest.name(), cap, stewardDid, null);
                        }
                    }
                    yield new Decision(true, manifest.capabilities());
                }
                if (ch.startsWith("p")) {
                    var picked = pickSubset(manifest);
                    if (grantStore != null) {
                        for (var cap : picked) grantStore.issue(manifest.name(), cap, stewardDid, null);
                    }
                    yield new Decision(true, picked);
                }
                yield new Decision(false, List.of());
            }
        };
    }

    /** §5.4 — render the install card the steward sees. */
    public static String renderCard(ItemManifest m) {
        var sb = new StringBuilder();
        sb.append("┌─ Install: ").append(m.name()).append(" v").append(m.version())
            .append(" ────────────────\n");
        sb.append("│  Author:      ").append(m.author()).append("\n");
        sb.append("│  Description: ").append(truncate(m.description(), 80)).append("\n");
        sb.append("│  Sensitivity: ").append(m.dataSensitivity()).append("\n");
        sb.append("│\n");
        if (m.capabilities().isEmpty()) {
            sb.append("│  Capabilities: (none — Tier 1 implicit only)\n");
        } else {
            var byTier = new TreeMap<Integer, List<String>>();
            for (var cap : m.capabilities()) {
                byTier.computeIfAbsent(ItemManifestValidator.tierFor(cap), k -> new ArrayList<>()).add(cap);
            }
            sb.append("│  Capabilities (").append(m.capabilities().size()).append("):\n");
            for (var entry : byTier.entrySet()) {
                int tier = entry.getKey();
                var caps = entry.getValue();
                sb.append("│    Tier ").append(tier).append(":  ");
                if (tier >= 5) sb.append("⚠ ");
                sb.append(String.join(", ", caps)).append("\n");
            }
        }
        if (!m.rateLimits().isEmpty()) {
            sb.append("│\n│  Rate limits:\n");
            for (var e : m.rateLimits().entrySet()) {
                var rl = e.getValue();
                sb.append("│    ").append(e.getKey()).append(": ");
                if (rl.perMinute() != null) sb.append(rl.perMinute()).append("/min ");
                if (rl.perHour() != null) sb.append(rl.perHour()).append("/hr ");
                if (rl.perDay() != null) sb.append(rl.perDay()).append("/day");
                sb.append("\n");
            }
        }
        if (!m.installWarnings().isEmpty()) {
            sb.append("│\n│  Warnings:\n");
            for (var w : m.installWarnings()) sb.append("│    • ").append(w).append("\n");
        }
        sb.append("└──────────────────");
        return sb.toString();
    }

    private List<String> pickSubset(ItemManifest m) {
        var picked = new ArrayList<String>();
        for (var cap : m.capabilities()) {
            System.out.print("  Approve '" + cap + "' (y/N)? ");
            var line = stdinSupplier.get();
            if (line != null && line.trim().toLowerCase().startsWith("y")) picked.add(cap);
        }
        return picked;
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 3) + "...";
    }

    private static String readLine() {
        try {
            var br = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return br.readLine();
        } catch (Exception e) {
            return "";
        }
    }
}
