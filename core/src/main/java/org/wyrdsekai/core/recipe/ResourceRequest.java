package org.wyrdsekai.core.recipe;

import org.wyrdsekai.core.security.Denial;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns a blocked recipe's {@link ResourceRequisiteGate.Decision} into a structured
 * {@link Denial} — the "if you don't have it, ask for it" connector (option a).
 *
 * <p>When the preflight blocks a heavy recipe ({@link RecipeRunner.Status#RESOURCE_DENIED}),
 * the dispatching layer doesn't fail silently or thrash: it builds this Denial and surfaces
 * it. The {@link Denial.RequestTemplate} matches the existing {@code request_access} action
 * so an agent emits a real grant-request to its steward through the same
 * Board + Grant pipeline used for every other access ask; the CLI hint covers steward
 * terminal mode. (Phase 2 adds the peer-zone borrow as a second resolution path.)
 *
 * <p>Deliberately reuses the structured-denial + grant-request machinery (F13 / )
 * rather than inventing a new request type — a missing GPU is the same dignified move as a
 * missing room key: "I need X, I don't have it, I'm asking."
 */
public final class ResourceRequest {

    public static final String CODE = "recipe_resource_unmet";

    private ResourceRequest() {}

    /** Build the steward-ask Denial for a recipe the local node can't satisfy. */
    public static Denial forDeniedRun(String recipeName, ResourceRequisiteGate.Decision d) {
        String needs = d.unmetHard().stream()
                .map(ResourceRequirement::describe)
                .collect(Collectors.joining(", "));
        String reason = "Recipe '" + recipeName + "' needs resources this node can't meet — "
                + (needs.isBlank() ? d.summary() : needs) + ".";
        String source = resourceUriFor(d.firstReason());
        String remediation = "Ask your steward to provide it, or — if a trusted peer zone has the "
                + "hardware — borrow it (cross-zone recipe dispatch). Until then this heavy run "
                + "won't start here; I'm not forcing a job that can't finish.";
        var template = Denial.RequestTemplate.forAccess(source, "use",
                "To run '" + recipeName + "' I need " + (needs.isBlank() ? "more resources" : needs)
                        + " — this node can't provide it. Requesting access to " + source + ".");
        Map<String, String> cli = new LinkedHashMap<>();
        cli.put("need", d.summary());
        cli.put("fix", "attach/free the hardware, run on a capable node, or borrow a trusted peer zone");
        return Denial.withBoth(CODE, reason, remediation, template, cli);
    }

    /** Map the first unmet requirement to the resource URI a grant would target. */
    private static String resourceUriFor(ResourceRequisiteGate.DenyReason reason) {
        return switch (reason) {
            case GPU_COUNT_INSUFFICIENT, GPU_VRAM_INSUFFICIENT -> "resource:gpu";
            case RAM_INSUFFICIENT -> "resource:ram";
            case DISK_INSUFFICIENT -> "resource:disk";
            case DATA_FILE_MISSING -> "resource:data";
            case CLOUD_KEY_MISSING -> "resource:cloud-key";
            case ALLOW -> "resource:compute";
        };
    }
}
