package org.wyrdsekai.core.recipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * #1014 (OPEN-R1) — the write side of the agent-authored recipe
 * compartment. Where {@link RecipeService} <em>reads</em> {@code data/recipes/}
 * (household ∪ classpath), this <em>imports</em> a recipe an agent (or steward)
 * authored into that compartment, after the two-stage gate:
 *
 * <ol>
 *   <li>{@link RecipeParser#parseManifest} — structural validity (steps, gate
 *       references, the deploys ≥2-gate rule). Throws on a bad shape.</li>
 *   <li>{@link AuthoredRecipeValidator} — the authoring contract (allowed step
 *       kinds, scripts-only SHELL, no shadowing a ship recipe, PERMANENT floor
 *       on a deploy). This is the boundary that makes self-authored recipes
 *       safe to run under the same welfare gates as ship recipes.</li>
 * </ol>
 *
 * <p>Imports write the original YAML text verbatim (preserving the author's
 * comments + formatting) with an atomic {@code .tmp}+rename. Export is just
 * "read the file back" — there is no manifest→YAML serializer, by design: the
 * on-disk text IS the source of truth. A name that collides with a bundled ship
 * recipe is refused (can't shadow); an existing authored file is refused unless
 * {@code overwrite} (a revise).</p>
 */
public final class RecipeAuthorService {

    private static final Logger log = LoggerFactory.getLogger(RecipeAuthorService.class);
    private static final String EXT = ".recipe.yaml";

    private final Path recipesDir;          // data/recipes — the compartment
    private final Path scriptsRoot;         // install scripts/ (nullable in tests)
    private final Set<String> reservedNames; // bundled ship-recipe names
    private final AuthoredRecipeLog authoredLog; // B.1 (nullable)

    public RecipeAuthorService(Path recipesDir, Path scriptsRoot, Set<String> reservedNames) {
        this(recipesDir, scriptsRoot, reservedNames, null);
    }

    /**
     * With a provenance log so {@code shape_recipe} acts are counted by
     * {@link RecipeProvenanceReport} (authoring writes a file, not a queue row,
     * so it would otherwise be invisible). {@code authoredLog} may be null —
     * the in-world path that has no jdbc handle uses the 3-arg form.
     */
    public RecipeAuthorService(Path recipesDir, Path scriptsRoot, Set<String> reservedNames,
                               AuthoredRecipeLog authoredLog) {
        this.recipesDir = recipesDir;
        this.scriptsRoot = scriptsRoot;
        this.reservedNames = reservedNames == null ? Set.of() : Set.copyOf(reservedNames);
        this.authoredLog = authoredLog;
    }

    public record ImportResult(boolean ok, String name, String path,
                               List<String> violations, String error) {
        static ImportResult rejected(String error, List<String> violations) {
            return new ImportResult(false, null, null,
                violations == null ? List.of() : violations, error);
        }
        static ImportResult imported(String name, Path path) {
            return new ImportResult(true, name, path.toString(), List.of(), null);
        }
    }

    /**
     * Validate + persist an authored recipe from its YAML text. Returns a
     * structured result — never throws for an author error (the caller narrates
     * the rejection). {@code overwrite=false} refuses to clobber an existing
     * authored file.
     */
    public ImportResult importRecipe(String yamlText, String authorDid, boolean overwrite) {
        if (yamlText == null || yamlText.isBlank()) {
            return ImportResult.rejected("empty recipe", List.of());
        }

        // 1) structural parse + validate (throws RecipeValidationException)
        RecipeManifest manifest;
        try {
            manifest = RecipeParser.parseManifest(yamlText);
        } catch (RecipeValidationException e) {
            return ImportResult.rejected(e.getMessage(), List.of());
        } catch (RuntimeException e) {
            return ImportResult.rejected("could not parse recipe: " + e.getMessage(), List.of());
        }

        // 2) authoring contract (the safety boundary)
        var v = AuthoredRecipeValidator.validate(manifest, reservedNames, scriptsRoot);
        if (!v.ok()) {
            return ImportResult.rejected("authoring contract", v.violations());
        }

        String name = manifest.recipe();
        Path target = recipesDir.resolve(name + EXT);
        try {
            Files.createDirectories(recipesDir);
            if (Files.exists(target) && !overwrite) {
                return ImportResult.rejected(
                    "recipe '" + name + "' already exists (set overwrite to revise)", List.of());
            }
            Path tmp = recipesDir.resolve(name + EXT + ".tmp");
            Files.writeString(tmp, yamlText, StandardCharsets.UTF_8);
            Files.move(tmp, target,
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Authored recipe imported: {} (by {}, overwrite={})",
                name, authorDid == null ? "?" : authorDid, overwrite);
            if (authoredLog != null) {
                authoredLog.record(authorDid, name, Instant.now());
            }
            return ImportResult.imported(name, target);
        } catch (IOException e) {
            log.warn("Authored recipe write failed for '{}': {}", name, e.getMessage());
            return ImportResult.rejected("could not write recipe: " + e.getMessage(), List.of());
        }
    }

    /** Names of recipes in the authored compartment (not the bundled set). */
    public List<String> listAuthored() {
        var out = new ArrayList<String>();
        if (recipesDir != null && Files.isDirectory(recipesDir)) {
            try (Stream<Path> s = Files.list(recipesDir)) {
                s.map(p -> p.getFileName().toString())
                        .filter(f -> f.endsWith(EXT))
                        .map(f -> f.substring(0, f.length() - EXT.length()))
                        .sorted()
                        .forEach(out::add);
            } catch (IOException ignored) {
                // unreadable → empty
            }
        }
        return out;
    }

    /** Read an authored recipe's YAML back (export). Empty if not authored. */
    public Optional<String> exportRecipe(String name) {
        if (!safeName(name)) return Optional.empty();
        Path p = recipesDir.resolve(name + EXT);
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Retire an authored recipe (delete its file). Never touches the bundled
     *  set. Returns true if a file was removed. */
    public boolean removeRecipe(String name) {
        if (!safeName(name) || reservedNames.contains(name)) return false;
        Path p = recipesDir.resolve(name + EXT);
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Authored recipe remove failed for '{}': {}", name, e.getMessage());
            return false;
        }
    }

    private static boolean safeName(String name) {
        return name != null && !name.isBlank()
            && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }
}
