package org.wyrdsekai.core.recipe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * A Goose-compatible recipe — the leaf executable unit.
 *
 * <p>We reuse the Goose recipe <em>format</em> (Apache-2.0; mirrors
 * {@code goose crates/goose/src/recipe/mod.rs}) via our own parser — no Goose code is
 * copied; file formats are functional. A vanilla Goose recipe deserializes here and can
 * run as a one-step pipeline, so households' existing Goose recipes interoperate.
 *
 * <p>Unknown fields are ignored for forward-compatibility with newer Goose schema versions.
 * Goose's own {@code retry}/{@code response.json_schema} validate a unit's output INSIDE the
 * executor; that is distinct from Wyrdsekai {@link RecipeStep.Gate} steps.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GooseRecipe(
        String version,
        String title,
        String description,
        String instructions,                 // model instructions (one of instructions/prompt required)
        String prompt,                        // session-start prompt
        List<Parameter> parameters,           // {{ }} inputs
        Map<String, Object> settings,         // provider/model/temperature/max_turns
        Map<String, Object> response,         // { json_schema: ... }
        @JsonProperty("sub_recipes") List<Map<String, Object>> subRecipes,
        Map<String, Object> retry
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Parameter(String key, String description, Boolean required,
                            @JsonProperty("default") Object defaultValue) {}

    /** Goose rule: at least one of instructions/prompt must be set. */
    public boolean hasRunnableBody() {
        return (instructions != null && !instructions.isBlank())
                || (prompt != null && !prompt.isBlank());
    }
}
