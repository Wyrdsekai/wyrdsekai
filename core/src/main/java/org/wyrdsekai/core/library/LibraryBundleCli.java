package org.wyrdsekai.core.library;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code wyrd library bundle} — the Library air-gap helper
 *
 * <p>Pre-downloads knowledge packs (resolved + format-converted, NOT indexed)
 * into a bundle directory on a connected machine. Carry the directory to an
 * offline node and install each pack with
 * {@code wyrd library install <name> --from-dir <bundle>/<name>}.</p>
 *
 * <p>Default selection mirrors the setup prompt
 * ({@link StarterLibraryInstaller#selectStarterPacks}): every Tier-0 pack
 * (dictionaries) plus the Tier-1 starters for the requested languages,
 * simple-wikipedia last. Override with {@code --packs}.</p>
 *
 * <p>Shell entry point: {@code bin/wyrd} {@code do_library bundle} assembles
 * the classpath and passes args through — same pattern as
 * {@code org.wyrdsekai.core.coding.CodingCli}.</p>
 */
public final class LibraryBundleCli {

    private final PrintStream out;
    private final PrintStream err;

    public LibraryBundleCli(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new LibraryBundleCli(System.out, System.err).run(args));
    }

    public int run(String[] args) {
        Path dest = null;
        Set<String> packFilter = null;
        String langsCsv = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dest" -> {
                    if (i + 1 >= args.length) return usage(2);
                    dest = Path.of(args[++i]);
                }
                case "--packs" -> {
                    if (i + 1 >= args.length) return usage(2);
                    packFilter = splitCsv(args[++i]);
                }
                case "--langs" -> {
                    if (i + 1 >= args.length) return usage(2);
                    langsCsv = args[++i];
                }
                case "-h", "--help" -> { return usage(0); }
                default -> {
                    err.println("unknown arg: " + args[i]);
                    return usage(2);
                }
            }
        }
        if (dest == null) dest = Path.of("wyrd-library-bundle");

        List<KnowledgePackRegistry.PackInfo> selection;
        if (packFilter != null) {
            selection = new ArrayList<>();
            for (var name : packFilter) {
                var pack = KnowledgePackRegistry.find(name);
                if (pack.isEmpty()) {
                    err.println("Unknown pack '" + name + "'. Available: "
                        + KnowledgePackRegistry.listAvailable().stream()
                            .map(KnowledgePackRegistry.PackInfo::name).toList());
                    return 1;
                }
                selection.add(pack.get());
            }
        } else {
            selection = StarterLibraryInstaller.selectStarterPacks(
                StarterLibraryInstaller.parseLangs(langsCsv));
        }
        if (selection.isEmpty()) {
            err.println("Nothing to bundle.");
            return 1;
        }

        out.println("Bundling " + selection.size() + " pack(s) into " + dest.toAbsolutePath());
        int failed = 0;
        for (var pack : selection) {
            out.println("== " + pack.name() + " (" + pack.estimatedSize() + ")");
            try {
                Files.createDirectories(dest);
                KnowledgePackRegistry.downloadOnly(pack.name(), dest, msg -> out.println("   " + msg));
            } catch (Exception e) {
                // One failed pack must not sink the bundle; re-running resumes (chunks/ skip).
                err.println("   FAILED: " + e.getMessage());
                failed++;
            }
        }

        out.println();
        out.println("Bundle " + (failed == 0 ? "complete" : "finished with " + failed + " failure(s))")
            + " at " + dest.toAbsolutePath());
        out.println("On the offline node, install each pack with:");
        for (var pack : selection) {
            out.println("  wyrd library install " + pack.name()
                + " --from-dir " + dest.toAbsolutePath().resolve(pack.name()));
        }
        return failed == 0 ? 0 : 1;
    }

    private int usage(int code) {
        var s = code == 0 ? out : err;
        s.println("usage: wyrd library bundle [--dest <dir>] [--packs <csv> | --langs <csv>]");
        s.println();
        s.println("Pre-download Library knowledge packs for an air-gapped install.");
        s.println("Packs are resolved, downloaded, and format-converted (not indexed);");
        s.println("carry the directory over and run:");
        s.println("  wyrd library install <name> --from-dir <dir>/<name>");
        s.println();
        s.println("Options:");
        s.println("  --dest  <dir>  Bundle destination (default: ./wyrd-library-bundle)");
        s.println("  --packs <csv>  Bundle exactly these packs (e.g. jmdict,simple-wikipedia)");
        s.println("  --langs <csv>  Starter selection for these languages (default: en);");
        s.println("                 mirrors the `wyrd setup` Library prompt");
        return code;
    }

    private static Set<String> splitCsv(String csv) {
        var set = new LinkedHashSet<String>();
        for (var part : csv.split(",")) {
            var t = part.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) set.add(t);
        }
        return set;
    }
}
