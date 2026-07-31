package org.wyrdsekai.scripting.api;

import java.util.List;
import java.util.Map;

/**
 * capability manifest for a scripted item.
 *
 * <p>Authored at the head of an item's JS file as {@code exports.manifest = {...}}.
 * The runtime extracts this object before evaluating the script body so capability
 * gating can be applied to every {@code @HostAccess.Export} call.</p>
 *
 * <p>{@link #manifestVersion} defaults to {@code "1.0"} when absent (spec §5.1).
 * {@link #dataSensitivity} defaults to {@code "low"}.</p>
 */
public record ItemManifest(
    String name,
    String version,
    String description,
    String author,
    List<String> capabilities,
    Map<String, RateLimit> rateLimits,
    String dataSensitivity,
    List<String> installWarnings,
    List<String> externalDomains,
    List<String> mcpServers,
    List<String> safeSlots,
    String signature,
    String installHandler,
    String uninstallHandler,
    String manifestVersion,
    Double spendLimitUsdPerDay,
    List<Command> commands,
    /** — the script's declared embodiment block. Was parsed
     *  from every shipped item's manifest but DROPPED here until 2026-07-11, so
     *  all 55 disk-loaded items WARNed at boot as "no embodiment declaration". */
    ItemEmbodimentSpec embodiment,
    /** The typed parameters {@code invoke(params)} actually reads — see {@link Param}.
     *  Empty when the script declares none, in which case the runtime falls back to a
     *  single free-form {@code query} slot. */
    List<Param> params
) {

    /**
     * Phase 2 — script-declared menu commands. A scripted item lists the sub-verbs
     * it supports (e.g. pinboard: "Read summary" with args="", "Read details" with
     * args="details"); the room hint builder surfaces each as its own action menu
     * entry, dispatched as {@code use:<itemName>|<args>}.
     *
     * @param label   menu label shown to the user (e.g. "Read pinboard details")
     * @param args    args appended after the item name on dispatch; passed to the
     *                script's {@code invoke(params)} as {@code params.args}
     */
    public record Command(String label, String args) {}

    /**
     * A parameter the script's {@code invoke(params)} actually reads, declared so the
     * model can be TOLD about it.
     *
     * <p>Until this existed, every scripted item was advertised to the model with one
     * optional, undescribed {@code query} string ("Free-form parameter forwarded to the
     * item's invoke(params) function"). The model therefore had to guess what each tool
     * wanted. It guessed wrong in exactly the ways you'd expect: {@code morning_briefing}
     * — whose script hard-requires an {@code address} — was called with {@code {query: ""}}
     * and failed every time, and the companion then told the user there was no weather
     * data rather than that it had called its own tool wrong. Meanwhile the calculator
     * received an entire English sentence stuffed into {@code query}, because no typed
     * slot existed to hold an expression.</p>
     *
     * <p>A tool the model cannot see the shape of is a tool the model cannot call. The
     * label prose ("needs an address") is not a schema — this is.</p>
     *
     * @param name        the key read from {@code params} (e.g. {@code address})
     * @param type        JSON-schema type: {@code string}, {@code number}, {@code boolean}
     * @param description what to put in it, written FOR the model
     * @param required    true → the model is told it must supply this
     */
    public record Param(String name, String type, String description, boolean required) {}

    /** Back-compat constructor — pre-Phase-2 callers that don't pass {@code commands}. */
    public ItemManifest(String name, String version, String description, String author,
                        List<String> capabilities, Map<String, RateLimit> rateLimits,
                        String dataSensitivity, List<String> installWarnings,
                        List<String> externalDomains, List<String> mcpServers,
                        List<String> safeSlots, String signature, String installHandler,
                        String uninstallHandler, String manifestVersion,
                        Double spendLimitUsdPerDay) {
        this(name, version, description, author, capabilities, rateLimits,
             dataSensitivity, installWarnings, externalDomains, mcpServers,
             safeSlots, signature, installHandler, uninstallHandler,
             manifestVersion, spendLimitUsdPerDay, List.of(), null, List.of());
    }

    /** Back-compat constructor — pre-embodiment callers that pass commands. */
    public ItemManifest(String name, String version, String description, String author,
                        List<String> capabilities, Map<String, RateLimit> rateLimits,
                        String dataSensitivity, List<String> installWarnings,
                        List<String> externalDomains, List<String> mcpServers,
                        List<String> safeSlots, String signature, String installHandler,
                        String uninstallHandler, String manifestVersion,
                        Double spendLimitUsdPerDay, List<Command> commands) {
        this(name, version, description, author, capabilities, rateLimits,
             dataSensitivity, installWarnings, externalDomains, mcpServers,
             safeSlots, signature, installHandler, uninstallHandler,
             manifestVersion, spendLimitUsdPerDay, commands, null, List.of());
    }

    /** Back-compat constructor — callers predating typed {@link Param} declarations. */
    public ItemManifest(String name, String version, String description, String author,
                        List<String> capabilities, Map<String, RateLimit> rateLimits,
                        String dataSensitivity, List<String> installWarnings,
                        List<String> externalDomains, List<String> mcpServers,
                        List<String> safeSlots, String signature, String installHandler,
                        String uninstallHandler, String manifestVersion,
                        Double spendLimitUsdPerDay, List<Command> commands,
                        ItemEmbodimentSpec embodiment) {
        this(name, version, description, author, capabilities, rateLimits,
             dataSensitivity, installWarnings, externalDomains, mcpServers,
             safeSlots, signature, installHandler, uninstallHandler,
             manifestVersion, spendLimitUsdPerDay, commands, embodiment, List.of());
    }


    /** Canonical default manifest version when an item omits the field. */
    public static final String DEFAULT_MANIFEST_VERSION = "1.0";

    /** Default data sensitivity (spec §5). */
    public static final String DEFAULT_DATA_SENSITIVITY = "low";

    /** Per-capability rate limit from {@code rate_limits} block. */
    public record RateLimit(Integer perMinute, Integer perHour, Integer perDay) {}

    public ItemManifest {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        rateLimits = rateLimits == null ? Map.of() : Map.copyOf(rateLimits);
        installWarnings = installWarnings == null ? List.of() : List.copyOf(installWarnings);
        externalDomains = externalDomains == null ? List.of() : List.copyOf(externalDomains);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        safeSlots = safeSlots == null ? List.of() : List.copyOf(safeSlots);
        commands = commands == null ? List.of() : List.copyOf(commands);
        params = params == null ? List.of() : List.copyOf(params);
        if (manifestVersion == null || manifestVersion.isBlank()) {
            manifestVersion = DEFAULT_MANIFEST_VERSION;
        }
        if (dataSensitivity == null || dataSensitivity.isBlank()) {
            dataSensitivity = DEFAULT_DATA_SENSITIVITY;
        }
    }

    /** Convenience: rate-limit lookup, returning null when absent. */
    public RateLimit rateLimitFor(String capability) {
        return rateLimits.get(capability);
    }

    /**
     * Copy of this manifest with the given {@code commands} list. Used by the
     * boot-time commands migration shim (see
     * {@code ItemManifestValidator.requireCommands}) to materialise the derived
     * default command on legacy manifests that declare none.
     */
    public ItemManifest withCommands(List<Command> newCommands) {
        return new ItemManifest(name, version, description, author, capabilities,
            rateLimits, dataSensitivity, installWarnings, externalDomains,
            mcpServers, safeSlots, signature, installHandler, uninstallHandler,
            manifestVersion, spendLimitUsdPerDay, newCommands);
    }

    /** Empty manifest (Tier 1 only, no caps required) — used by trusted JVM-baked items. */
    public static ItemManifest empty(String name) {
        return new ItemManifest(
            name, "0.0.0", "", "did:wyrd:system",
            List.of(), Map.of(),
            DEFAULT_DATA_SENSITIVITY, List.of(),
            List.of(), List.of(), List.of(),
            null, null, null,
            DEFAULT_MANIFEST_VERSION, null);
    }
}
