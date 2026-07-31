package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.soul.FragmentKind;
import org.wyrdsekai.core.soul.SoulFragment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Track-B B1 — first-boot release-evidence seed loader.
 *
 * <p>Reads {@code data/release-evidence/*-soul-fragment-seed.json} files
 * (produced by {@code RecipeBakeMain} during release packaging) and
 * returns the embedded {@link SoulFragment}s, ready for ingestion into
 * a freshly-created soul. Attribution stamp is
 * {@link #RELEASE_BAKE_DID} — the bondholder sees "I learned this when
 * the household was built," not "you told me this."</p>
 *
 * <p>Pure-logic and best-effort: bad JSON / missing fields / wrong
 * schema all skip the entry with a WARN and continue. The seed is a
 * <em>nicety</em>, not a load-bearing invariant — first boot still
 * works if the evidence dir is empty (e.g. dev builds, or
 * {@code BAKE_SKIP_HEADS=task_present,...}).</p>
 *
 * <h2>Schema</h2>
 * <pre>{
 *   "schema": "wyrdsekai.release-evidence.soul-fragment-seed.v1",
 *   "head": "task_present",
 *   "recipe": "retrain-classifier-head",
 *   "bake_did": "did:wyrd:release-bake",
 *   "baked_at": "2026-05-25T09:00:00Z",
 *   "baseline_sha256": "...",
 *   "evolved_sha256": "...",
 *   "duration_ms": 12345,
 *   "fragment": {
 *     "id": "recipe-retrain-classifier-head-<ts>",
 *     "kind": "DEXTERITY",
 *     "category": "procedure",
 *     "label": "Recipe run: retrain-classifier-head",
 *     "text": "I ran the recipe ... and it succeeded."
 *   }
 * }</pre>
 */
public final class ReleaseBakeSeedLoader {

    private static final Logger log = LoggerFactory.getLogger(ReleaseBakeSeedLoader.class);

    /** Synthetic DID seeds are attributed to; matches {@code RecipeBakeMain.RELEASE_BAKE_DID}. */
    public static final String RELEASE_BAKE_DID = "did:wyrd:release-bake";

    public static final String EXPECTED_SCHEMA =
        "wyrdsekai.release-evidence.soul-fragment-seed.v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReleaseBakeSeedLoader() {}

    /**
     * Scan {@code evidenceDir} for {@code *-soul-fragment-seed.json},
     * parse each, return the embedded {@link SoulFragment}s. Returns
     * an empty list when the directory is missing/empty — the agent
     * birth path falls back to its existing initial-fragments behavior.
     *
     * <p>Files are returned in name-sorted order so the bondholder's
     * first-boot fragment list is deterministic across rebuilds.</p>
     */
    public static List<SoulFragment> loadSeeds(Path evidenceDir) {
        if (evidenceDir == null || !Files.isDirectory(evidenceDir)) {
            return List.of();
        }
        var out = new ArrayList<SoulFragment>();
        try (Stream<Path> files = Files.list(evidenceDir)) {
            var ordered = files
                .filter(p -> p.getFileName().toString()
                    .endsWith("-soul-fragment-seed.json"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
            for (var f : ordered) {
                var frag = parseSeed(f);
                if (frag != null) out.add(frag);
            }
        } catch (IOException e) {
            log.warn("ReleaseBakeSeedLoader.loadSeeds({}) failed: {}",
                evidenceDir, e.toString());
        }
        return out;
    }

    /**
     * Parse a single seed file. Returns {@code null} on any parse error
     * — the caller treats null as "skip this seed, log already happened."
     */
    public static SoulFragment parseSeed(Path file) {
        try {
            var root = MAPPER.readTree(Files.readAllBytes(file));
            var schema = root.path("schema").asText("");
            if (!EXPECTED_SCHEMA.equals(schema)) {
                log.warn("Skipping {}: unexpected schema '{}' (want '{}')",
                    file.getFileName(), schema, EXPECTED_SCHEMA);
                return null;
            }
            var fragNode = root.path("fragment");
            if (!fragNode.isObject()) {
                log.warn("Skipping {}: no 'fragment' object", file.getFileName());
                return null;
            }
            var id = fragNode.path("id").asText(null);
            var label = fragNode.path("label").asText(null);
            var text = fragNode.path("text").asText(null);
            if (id == null || id.isBlank() || text == null || text.isBlank()) {
                log.warn("Skipping {}: fragment missing id/text", file.getFileName());
                return null;
            }
            var category = fragNode.path("category").asText("procedure");
            // We always emit DEXTERITY at the seed surface — the bake
            // contract is "successful recipe-run procedure fragments."
            // Any other kind in the JSON is taken as advisory only.
            var rawKind = fragNode.path("kind").asText("DEXTERITY");
            FragmentKind kind = parseKind(rawKind);
            // Use the static factory so the fragment lands with the same
            // shape RecipeForgeIngester.runFragment produces. We map
            // CONVENTION / STRUCTURAL onto their own factories; anything
            // else (DEFAULT / unknown) falls back to DEXTERITY — the
            // seed contract.
            return switch (kind) {
                case CONVENTION -> SoulFragment.convention(id, category, label, text);
                case STRUCTURAL -> SoulFragment.structural(id, category, label, text);
                case EPISODIC, NARRATIVE, DEXTERITY ->
                    SoulFragment.dexterity(id, category, label, text);
            };
        } catch (Exception e) {
            log.warn("ReleaseBakeSeedLoader.parseSeed({}) failed: {}",
                file.getFileName(), e.toString());
            return null;
        }
    }

    private static FragmentKind parseKind(String raw) {
        if (raw == null) return FragmentKind.DEXTERITY;
        try {
            return FragmentKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return FragmentKind.DEXTERITY;
        }
    }

    /** Resolve the conventional evidence dir relative to the data dir. */
    public static Path defaultEvidenceDir(Path dataDir) {
        if (dataDir == null) return null;
        return dataDir.resolve("release-evidence");
    }
}
