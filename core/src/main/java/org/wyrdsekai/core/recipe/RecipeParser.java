package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses + validates Wyrdsekai recipe manifests and Goose-compatible leaf recipes
 * Our own parser over the Goose YAML format — no Goose code copied.
 *
 * <p>Steps are hand-built from the YAML tree (rather than Jackson polymorphic binding)
 * so the {@code kind} discriminator handling is fully explicit and under our control.
 * Both snake_case and camelCase field spellings are accepted.
 */
public final class RecipeParser {

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final ObjectMapper JSON = new ObjectMapper();

    private RecipeParser() {}

    // ── Manifest (outer pipeline) ────────────────────────────────────────────

    public static RecipeManifest parseManifest(String yaml) {
        JsonNode root = readTree(yaml);
        String recipe = text(root, "recipe");
        if (recipe == null || recipe.isBlank()) {
            throw new RecipeValidationException("manifest missing required 'recipe' name");
        }
        String version = textOr(root, "0.1.0", "version");
        String description = textOr(root, "", "description");
        boolean deploys = root.path("deploys").asBoolean(false);
        RecipeManifest.Ownership ownership = parseOwnership(textOr(root, "RUN", "ownership"));
        Map<String, RecipeManifest.RecipeParam> params = parseParams(root.get("params"));
        List<RecipeStep> steps = parseSteps(root.get("steps"));
        // #1012 — manifest-level transient-failure retry budget. Default = 1 retry per step.
        int retryCount = intOrManifest(root, RecipeManifest.DEFAULT_RETRY_COUNT,
                "retry_count", "retryCount");
        // #1023 — quiet-hours preference: optional `prefers_hours: [2,3,4]` field.
        // Empty/missing = anytime (current behavior preserved). Bounds-checked here
        // so authoring mistakes fail fast at parse time, not silently at scheduler tick.
        List<Integer> prefersHours = parsePrefersHours(root);
        // Resource requisites: declared hardware/data needs, preflight-checked by
        // ResourceRequisiteGate before step 1. Optional; light recipes declare none.
        List<ResourceRequirement> requires = parseRequires(root);

        RecipeManifest m = new RecipeManifest(recipe, version, description, params, ownership,
                deploys, steps, retryCount, prefersHours, requires);
        validate(m);
        return m;
    }

    private static List<ResourceRequirement> parseRequires(JsonNode root) {
        JsonNode node = null;
        for (String f : new String[] { "requires", "resources" }) {
            if (root.has(f)) { node = root.get(f); break; }
        }
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new RecipeValidationException(
                    "'requires' must be an array of requirement objects, got " + node.getNodeType());
        }
        var out = new ArrayList<ResourceRequirement>();
        for (JsonNode r : node) {
            String kindRaw = text(r, "kind");
            if (kindRaw == null || kindRaw.isBlank()) {
                throw new RecipeValidationException("'requires' entry missing 'kind'");
            }
            ResourceRequirement.Kind kind;
            try {
                kind = ResourceRequirement.Kind.valueOf(kindRaw.trim().toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new RecipeValidationException("'requires' entry has unknown kind '" + kindRaw
                        + "' (expected one of GPU_COUNT, GPU_VRAM_GB, DISK_FREE_GB, RAM_GB, "
                        + "WALL_CLOCK_MIN, CLOUD_KEY, DATA_FILE)");
            }
            double amount = r.path("amount").asDouble(0.0);
            String target = text(r, "target");
            // Default hard=true — the whole point is enforcement; soft needs explicit `hard: false`.
            // (WALL_CLOCK_MIN is forced soft in the record ctor regardless.)
            boolean hard = r.path("hard").asBoolean(true);
            String note = textOr(r, "", "note");
            // Target-kinds must carry a target; amount-kinds must carry a positive amount.
            if ((kind == ResourceRequirement.Kind.CLOUD_KEY || kind == ResourceRequirement.Kind.DATA_FILE)
                    && (target == null || target.isBlank())) {
                throw new RecipeValidationException("'requires' " + kind + " entry needs a 'target'");
            }
            out.add(new ResourceRequirement(kind, amount, target, hard, note));
        }
        return out;
    }

    private static List<Integer> parsePrefersHours(JsonNode root) {
        JsonNode node = null;
        for (String f : new String[] { "prefers_hours", "prefersHours" }) {
            if (root.has(f)) { node = root.get(f); break; }
        }
        if (node == null || node.isNull()) return List.of();
        if (!node.isArray()) {
            throw new RecipeValidationException(
                    "'prefers_hours' must be an array of integers (0-23), got " + node.getNodeType());
        }
        var out = new ArrayList<Integer>();
        for (JsonNode v : node) {
            if (!v.isInt()) {
                throw new RecipeValidationException(
                        "'prefers_hours' entries must be integers, got " + v.toString());
            }
            int h = v.asInt();
            if (h < 0 || h > 23) {
                throw new RecipeValidationException(
                        "'prefers_hours' entries must be in [0,23], got " + h);
            }
            out.add(h);
        }
        return out;
    }

    public static RecipeManifest parseManifestFile(Path path) {
        return parseManifest(readFile(path));
    }

    // ── Goose leaf recipe ────────────────────────────────────────────────────

    public static GooseRecipe parseGooseRecipe(String yaml) {
        GooseRecipe r;
        try {
            r = YAML.readValue(yaml, GooseRecipe.class);
        } catch (IOException e) {
            throw new RecipeValidationException("invalid Goose recipe YAML: " + e.getMessage());
        }
        if (r.title() == null || r.title().isBlank()) {
            throw new RecipeValidationException("Goose recipe missing 'title'");
        }
        if (!r.hasRunnableBody()) {
            throw new RecipeValidationException(
                    "Goose recipe '" + r.title() + "' needs at least one of 'instructions' or 'prompt'");
        }
        return r;
    }

    public static GooseRecipe parseGooseRecipeFile(Path path) {
        return parseGooseRecipe(readFile(path));
    }

    // ── steps ────────────────────────────────────────────────────────────────

    private static List<RecipeStep> parseSteps(JsonNode stepsNode) {
        List<RecipeStep> steps = new ArrayList<>();
        if (stepsNode == null || !stepsNode.isArray()) {
            return steps; // validated as empty → fails validate()
        }
        for (JsonNode s : stepsNode) {
            String id = text(s, "id");
            StepKind kind = StepKind.from(text(s, "kind"));
            // #1012 — optional per-step timeout override (e.g. `timeout: 30m`). Parser-side
            // validation: bad strings fail fast at parse time, not at run time.
            Duration stepTimeout = RecipeDurations.parse(text(s, "timeout"));
            steps.add(switch (kind) {
                case SHELL -> new RecipeStep.Shell(id, text(s, "command"), text(s, "rollback"), stepTimeout);
                case GOOSE_RECIPE -> new RecipeStep.GooseRecipeRef(
                        id, first(s, "recipe_ref", "recipeRef"), objectMap(s.get("params")), stepTimeout);
                case BACKEND -> new RecipeStep.Backend(
                        id, text(s, "prompt"), stringList(s.get("tools")),
                        first(s, "success_contract", "successContract"), stepTimeout);
                case GATE -> new RecipeStep.Gate(
                        id, text(s, "condition"), firstOr(s, RecipeStep.Gate.STOP, "on_fail", "onFail"),
                        parseWelfareClass(s.get("welfare")));
                case DECISION -> new RecipeStep.Decision(
                        id, text(s, "reads"), stringMap(s.get("branches")));
                case LONG_JOB -> new RecipeStep.LongJob(
                        id, text(s, "command"),
                        intOr(s, 60, "poll_seconds", "pollSeconds"),
                        first(s, "done_when", "doneWhen"), stepTimeout);
            });
        }
        return steps;
    }

    // ── validation ─────────────────────────────────────────────────────────────

    static void validate(RecipeManifest m) {
        if (m.steps().isEmpty()) {
            throw new RecipeValidationException("recipe '" + m.recipe() + "' has no steps");
        }
        Set<String> ids = new HashSet<>();
        for (RecipeStep s : m.steps()) {
            if (s.id() == null || s.id().isBlank()) {
                throw new RecipeValidationException("step of kind " + s.kind() + " has blank id");
            }
            if (!ids.add(s.id())) {
                throw new RecipeValidationException("duplicate step id: " + s.id());
            }
        }
        for (RecipeStep s : m.steps()) {
            switch (s) {
                case RecipeStep.Gate g -> {
                    if (g.condition() == null || g.condition().isBlank()) {
                        throw new RecipeValidationException("gate '" + g.id() + "' has no condition");
                    }
                    if (!g.stopsOnFail() && !ids.contains(g.onFail())) {
                        throw new RecipeValidationException(
                                "gate '" + g.id() + "' onFail '" + g.onFail() + "' is not STOP or a known step id");
                    }
                }
                case RecipeStep.Decision d -> {
                    for (String target : d.branches().values()) {
                        if (!ids.contains(target)) {
                            throw new RecipeValidationException(
                                    "decision '" + d.id() + "' branches to unknown step id: " + target);
                        }
                    }
                }
                default -> { /* no per-kind structural rule */ }
            }
        }
        // welfare-floor: any recipe that deploys a production artifact
        // must carry at least a metric gate AND a regression gate before deploy.
        if (m.deploys() && m.stepsOfKind(StepKind.GATE).size() < 2) {
            throw new RecipeValidationException(
                    "recipe '" + m.recipe() + "' deploys but has fewer than 2 GATE steps "
                    + "(a metric gate AND a regression gate are both required before deploy)");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static RecipeManifest.Ownership parseOwnership(String raw) {
        try {
            return RecipeManifest.Ownership.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RecipeValidationException("unknown ownership: " + raw);
        }
    }

    private static Map<String, RecipeManifest.RecipeParam> parseParams(JsonNode node) {
        Map<String, RecipeManifest.RecipeParam> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return out;
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            out.put(e.getKey(), new RecipeManifest.RecipeParam(
                    textOr(v, "string", "type"),
                    v.path("required").asBoolean(false),
                    v.has("default") ? plain(v.get("default")) : null));
        }
        return out;
    }

    private static JsonNode readTree(String yaml) {
        try {
            return YAML.readTree(yaml);
        } catch (IOException e) {
            throw new RecipeValidationException("invalid recipe YAML: " + e.getMessage());
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read recipe file: " + path, e);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOr(JsonNode n, String dflt, String field) {
        String v = text(n, field);
        return v == null ? dflt : v;
    }

    /** First non-null text among several field-name spellings. */
    private static String first(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n, f);
            if (v != null) return v;
        }
        return null;
    }

    private static String firstOr(JsonNode n, String dflt, String... fields) {
        String v = first(n, fields);
        return v == null ? dflt : v;
    }

    /**
     * Parse a Gate's {@code welfare:} tag ( OPEN-R4). Missing or unrecognized
     * value defaults to {@link RecipeStep.WelfareClass#TEMPORARY} — PERMANENT must be
     * authored explicitly, never inferred. Case-insensitive; trims whitespace.
     */
    static RecipeStep.WelfareClass parseWelfareClass(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return RecipeStep.WelfareClass.TEMPORARY;
        }
        String s = node.asText().trim().toUpperCase();
        if ("PERMANENT".equals(s)) return RecipeStep.WelfareClass.PERMANENT;
        return RecipeStep.WelfareClass.TEMPORARY;
    }

    private static int intOr(JsonNode n, int dflt, String... fields) {
        for (String f : fields) {
            JsonNode v = n.get(f);
            if (v != null && v.isNumber()) return v.asInt();
        }
        return dflt;
    }

    /** Manifest-level int lookup with null-safe traversal — used for top-level options like
     *  {@code retry_count} where the absent field is normal, not an error. */
    private static int intOrManifest(JsonNode root, int dflt, String... fields) {
        if (root == null) return dflt;
        for (String f : fields) {
            JsonNode v = root.get(f);
            if (v != null && v.isNumber()) return v.asInt();
        }
        return dflt;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode v : node) out.add(v.asText());
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(e.getKey(), e.getValue().asText());
            }
        }
        return out;
    }

    private static Map<String, Object> objectMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(e.getKey(), plain(e.getValue()));
            }
        }
        return out;
    }

    /** Convert a scalar/container JsonNode to a plain Java value. */
    private static Object plain(JsonNode v) {
        try {
            return JSON.treeToValue(v, Object.class);
        } catch (IOException e) {
            return v.asText();
        }
    }
}
