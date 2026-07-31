package org.wyrdsekai.core.naming;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON rendering for {@link ZoneAddressResolver.Result}. Used by
 * {@code world.resolveZone()} in scripts and by the CLI — both need a
 * machine-readable representation of the resolver's outcome.
 *
 * <h2>Shape</h2>
 *
 * <p>On success:</p>
 * <pre>
 * {"ok":true, "canonical":"did:wyrd:z6Mk…:kitchen",
 *             "fingerprint":"z6Mk…", "label":"kitchen"}
 * </pre>
 *
 * <p>On failure:</p>
 * <pre>
 * {"ok":false, "code":"reserved_keyword",
 *              "message":"'home' is a reserved keyword, not a zone…"}
 * </pre>
 *
 * <p>Keeping the formatting here (rather than as {@code toString} on
 * {@code Result}) lets the resolver stay pure and Jackson-free. Consumers
 * that want a different projection (e.g. a protobuf wire form) can provide
 * their own translator without touching the resolver.</p>
 */
public final class ZoneResolveJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ZoneResolveJson() {}

    /**
     * Convenience wrapper: look up the singleton, resolve, and JSON-render.
     * Returns an {@code unavailable} error JSON if the service is
     * uninitialised — matches the {@link BridgeDataProvider#resolveZone}
     * default, so call sites don't have to branch.
     */
    public static String fromService(ZoneAddressResolverService service, String input) {
        if (service == null) {
            return "{\"ok\":false,\"code\":\"unavailable\","
                + "\"message\":\"Zone resolution not available — service not initialised\"}";
        }
        return format(service.resolver().resolve(input));
    }

    /** Render a resolver result as JSON. Never throws. */
    public static String format(ZoneAddressResolver.Result result) {
        try {
            var node = MAPPER.createObjectNode();
            switch (result) {
                case ZoneAddressResolver.Result.Ok ok -> {
                    node.put("ok", true);
                    node.put("canonical", ok.address().toCanonical());
                    node.put("fingerprint", ok.address().fingerprint());
                    node.put("label", ok.address().label());
                }
                case ZoneAddressResolver.Result.Err err -> {
                    node.put("ok", false);
                    node.put("code", err.code());
                    node.put("message", err.message());
                }
            }
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            // Jackson serialisation failure for this shape would be a programmer
            // bug, but don't propagate — return a well-formed error JSON the
            // caller can still parse.
            return "{\"ok\":false,\"code\":\"serialisation_error\","
                + "\"message\":\"Failed to render resolver result as JSON\"}";
        }
    }
}
