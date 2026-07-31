package org.wyrdsekai.core.search;

import org.wyrdsekai.core.util.HardwareProbe;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI surface for {@code wyrd embedding-model} — exposes the registry +
 * selection state to the bash wrapper. The bash side handles the bytes
 * (downloads, selector-file writes), this side handles the bookkeeping
 * (which model is active, what's installed, what does the host suggest).
 *
 * <h2>Subcommands</h2>
 * <ul>
 *   <li>{@code status}     — current model id / version / dimension / install state</li>
 *   <li>{@code list}       — every registered model, with bundled/downloaded flag</li>
 *   <li>{@code recommend}  — runs {@link HardwareProbe} and prints the suggestion</li>
 *   <li>{@code current}    — print just the currently-resolved model id (script-friendly)</li>
 *   <li>{@code path <id>}  — print download URLs / target paths for a model (script-friendly)</li>
 * </ul>
 *
 * <p>Exit code 0 on success, 1 on user error, 2 on a setup problem the operator
 * needs to fix (e.g. unknown id).
 */
public final class EmbeddingModelAdminMain {

    private EmbeddingModelAdminMain() {}

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        var sub = args[0];
        try {
            switch (sub) {
                case "status"    -> status();
                case "list"      -> list();
                case "recommend" -> recommend();
                case "current"   -> current();
                case "path"      -> path(args);
                case "help", "-h", "--help" -> usage();
                default -> {
                    System.err.println("Unknown subcommand: " + sub);
                    usage();
                    System.exit(1);
                }
            }
        } catch (Exception e) {
            System.err.println("error: " + e.getMessage());
            System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("""
            wyrd embedding-model <subcommand>

              status        Show the active embedding model + install state
              list          List all registered models with state and disk footprint
              recommend     Recommend a model based on host RAM
              current       Print only the active model id (for scripts)
              path <id>     Print download URLs + target paths for a model
              set <id>      (handled by wyrd wrapper) Pin the active model
              download <id> (handled by wyrd wrapper) Fetch ONNX + tokenizer

            Selection precedence:
              1. $WYRDSEKAI_EMBEDDING_MODEL env var
              2. ~/.wyrdsekai/embedding-model.txt   (written by `set`)
              3. bundled default (paraphrase-l12, 384d, ~120MB)

            Switching the active model changes the Lucene HNSW dimension if the new
            model has a different embedding size (paraphrase-l12 / e5-small = 384,
            e5-base = 768, bge-m3 = 1024). After `set`, restart wyrdsekai and run
            `wyrd embed-migrate --run` to re-emit stored vectors against the new model.
            """);
    }

    private static void status() {
        var active = EmbeddingService.resolveActiveModel();
        boolean present = EmbeddingService.modelFilesPresent(active);
        var src = selectionSource();

        System.out.println("Active embedding model");
        System.out.println("  id            : " + active.id());
        System.out.println("  display       : " + active.displayName());
        System.out.println("  version       : " + active.version());
        System.out.println("  dimension     : " + active.dimension());
        System.out.println("  is default    : " + (active == EmbeddingModel.bundledDefault() ? "yes" : "no"));
        System.out.println("  files present : " + (present ? "yes" : "no — run `wyrd embedding-model download " + active.id() + "`"));
        System.out.println("  selected via  : " + src);

        long ram = HardwareProbe.availableRamGB();
        var rec = HardwareProbe.recommendedEmbeddingModelForRam(ram);
        System.out.println();
        System.out.println("Host: " + ram + " GB RAM → recommended " + rec.id()
            + (rec.id().equals(active.id()) ? " (matches current)" : " (differs from current)"));
    }

    private static void list() {
        var defaultId = EmbeddingModel.bundledDefault().id();
        System.out.printf("%-16s %-8s %-6s %-10s %-50s%n",
            "ID", "DIM", "SIZE", "STATE", "DESCRIPTION");
        for (var m : EmbeddingModel.all()) {
            boolean isDefault = m.id().equals(defaultId);
            String state;
            if (EmbeddingService.modelFilesPresent(m)) {
                state = isDefault ? "default*" : "ready";
            } else {
                state = isDefault ? "default" : "available";
            }
            System.out.printf("%-16s %-8d %4dMB %-10s %-50s%n",
                m.id(), m.dimension(), m.approxSizeMB(), state, m.displayName());
        }
        System.out.println();
        System.out.println("default* = files present under ~/.wyrdsekai/models/.  default = ONNX not yet on disk;");
        System.out.println("`wyrd setup` will install it from the bundled assets or HuggingFace.");
        System.out.println("ready = downloaded.  available = registered but not on disk;");
        System.out.println("run `wyrd embedding-model download <id>` to fetch.");
    }

    private static void recommend() {
        long ram = HardwareProbe.availableRamGB();
        var rec = HardwareProbe.recommendedEmbeddingModelForRam(ram);
        System.out.println("Detected RAM: " + ram + " GB");
        System.out.println("Recommended : " + rec.id() + "  (" + rec.displayName() + ", "
            + rec.dimension() + "d, ~" + rec.approxSizeMB() + "MB)");
        System.out.println();
        var active = EmbeddingService.resolveActiveModel();
        if (rec.id().equals(active.id())) {
            System.out.println("Already active. Nothing to do.");
        } else {
            System.out.println("To switch:");
            if (!EmbeddingService.modelFilesPresent(rec)) {
                System.out.println("  wyrd embedding-model download " + rec.id());
            }
            System.out.println("  wyrd embedding-model set " + rec.id());
            System.out.println("  wyrd restart");
            System.out.println("  wyrd embed-migrate --run     # re-embed stored vectors");
        }
    }

    private static void current() {
        // Script-friendly: just the id, no decoration.
        System.out.println(EmbeddingService.resolveActiveModel().id());
    }

    private static void path(String[] args) {
        if (args.length < 2) {
            System.err.println("usage: path <id>");
            System.exit(1);
        }
        var m = EmbeddingModel.byId(args[1]);
        if (m == null) {
            System.err.println("Unknown model id: " + args[1]);
            System.exit(1);
        }
        var modelsDir = Path.of(System.getProperty("user.home"), ".wyrdsekai", "models");
        // Single-line key=value pairs so the bash side can parse easily.
        System.out.println("id=" + m.id());
        System.out.println("version=" + m.version());
        System.out.println("dimension=" + m.dimension());
        System.out.println("bundled=" + m.bundled());
        System.out.println("onnx_url=" + (m.onnxDownloadUrl() == null ? "" : m.onnxDownloadUrl()));
        System.out.println("tokenizer_url=" + (m.tokenizerDownloadUrl() == null ? "" : m.tokenizerDownloadUrl()));
        System.out.println("onnx_target=" + modelsDir.resolve(m.fallbackModelFile()));
        System.out.println("tokenizer_target=" + modelsDir.resolve(m.fallbackTokenizerFile()));
        System.out.println("approx_size_mb=" + m.approxSizeMB());
    }

    private static String selectionSource() {
        var env = System.getenv(EmbeddingService.SELECTOR_ENV);
        if (env != null && !env.isBlank() && EmbeddingModel.byId(env.trim()) != null) {
            return "env $" + EmbeddingService.SELECTOR_ENV;
        }
        var p = Path.of(System.getProperty("user.home"), EmbeddingService.SELECTOR_FILE);
        try {
            if (Files.isReadable(p)) {
                var line = Files.readString(p).trim();
                if (!line.isEmpty() && EmbeddingModel.byId(line) != null) {
                    return "file " + p;
                }
            }
        } catch (Exception ignored) {}
        return "bundled default";
    }
}
