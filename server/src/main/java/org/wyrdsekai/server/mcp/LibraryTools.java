package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.LibraryProvider;
import org.wyrdsekai.core.library.LibraryReaders;
import org.wyrdsekai.core.persistence.AuthService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Registers the household's library (LIBRARY_PROTOCOL.md) on the inbound MCP tool registry,
 * so a peer household or a research librarian can be a patron of ours. JSON in, JSON out;
 * every refusal is an MCP error result carrying the protocol's stable code.
 *
 * <p>Every tool acts for the caller the door proved ({@link #callers}): a library reader
 * token or a household login session. The patron is that caller, never what the body says,
 * and {@code library_submit} needs a writer.
 */
public final class LibraryTools {

    private static final Logger log = LoggerFactory.getLogger(LibraryTools.class);
    private static final ObjectMapper M = new ObjectMapper();

    private LibraryTools() {}

    public static void register(McpToolRegistry registry, LibraryProvider provider) {
        register(registry, "library_status", "This library: id, name, contract version, what it holds for outside patrons",
            schema(), provider);
        register(registry, "library_ask", "Ask the library a question; returns an answer package of cited entries, or holds_nothing",
            schema("question", "string", "k", "integer", "patron", "object"), provider);
        register(registry, "library_search", "Search the library; returns hits with id, kind, title, snippet, score, state",
            schema("query", "string", "k", "integer", "subject", "string", "cursor", "string", "patron", "object"), provider);
        register(registry, "library_get", "One entry in full, with every source",
            schema("id", "string"), provider);
        register(registry, "library_read", "The captured text behind a source locator — the verbatim path",
            schema("locator", "string", "max_chars", "integer"), provider);
        register(registry, "library_established", "Has this library established a claim? verdict + entries + disputes",
            schema("claim", "string", "patron", "object"), provider);
        register(registry, "library_submit", "Offer a sourced claim into this library's review as a draft",
            schema("claim", "string", "claim_type", "string", "sources", "array", "confidence", "string", "patron", "object"), provider);
        register(registry, "library_subjects", "The library's subject vocabulary", schema(), provider);
        log.info("Library protocol {} served inbound as '{}' ({} tools)", LibraryProvider.CONTRACT,
            provider.status().get("library_name"), LibraryProvider.TOOLS.size());
    }

    /** Household roles whose members may offer drafts to the library through this door. */
    private static final Set<String> WRITER_ROLES = Set.of("steward", "bondholder", "member");

    /**
     * Who a request to the library door is: a library reader token ({@code wyrd library
     * reader add}) or a household login session, sent as {@code Authorization: Bearer}.
     */
    public static McpEndpoint.CallerResolver callers(LibraryReaders readers, AuthService auth) {
        return ctx -> caller(LibraryReaders.bearer(ctx.header("Authorization")), readers, auth);
    }

    static Optional<McpToolRegistry.Caller> caller(String token, LibraryReaders readers, AuthService auth) {
        if (token == null || token.isBlank()) return Optional.empty();
        if (readers != null) {
            var reader = readers.resolve(token);
            if (reader.isPresent()) {
                var r = reader.get();
                return Optional.of(new McpToolRegistry.Caller(r.did(), r.name(),
                    r.level() == LibraryReaders.Level.write));
            }
        }
        if (auth != null) {
            try {
                var user = auth.validateSession(token);
                if (user.isPresent()) {
                    var u = user.get();
                    var name = u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName();
                    return Optional.of(new McpToolRegistry.Caller(u.id(), name, WRITER_ROLES.contains(u.role())));
                }
            } catch (RuntimeException e) {
                log.warn("library door session check failed: {}", e.toString());
            }
        }
        return Optional.empty();
    }

    private static void register(McpToolRegistry registry, String tool, String description,
                                 JsonNode schema, LibraryProvider provider) {
        registry.registerForCaller(tool, description, schema, (args, caller) -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = args == null || args.isNull()
                    ? Map.of() : M.convertValue(args, Map.class);
                var result = provider.call(tool, asCaller(tool, map, caller));
                var out = M.createObjectNode();
                out.putArray("content").addObject().put("type", "text").put("text", M.writeValueAsString(result));
                return out;
            } catch (LibraryProvider.ProtocolError e) {
                var out = M.createObjectNode();
                out.put("isError", true);
                out.putArray("content").addObject().put("type", "text").put("text", e.getMessage());
                out.putObject("data").put("code", e.code);
                return out;
            } catch (Exception e) {
                log.warn("library tool {} failed: {}", tool, e.toString());
                var out = M.createObjectNode();
                out.put("isError", true);
                out.putArray("content").addObject().put("type", "text").put("text", "The library could not answer just now.");
                out.putObject("data").put("code", "unavailable");
                return out;
            }
        });
    }

    /** The arguments with the patron set to the proven caller; refuses a body naming someone else. */
    static Map<String, Object> asCaller(String tool, Map<String, Object> args, McpToolRegistry.Caller caller) {
        var claimed = args.get("patron") instanceof Map<?, ?> p ? p : null;
        var claimedDid = claimed == null || claimed.get("did") == null ? "" : String.valueOf(claimed.get("did")).strip();
        var did = caller.did() == null ? "" : caller.did().strip();
        if (!claimedDid.isEmpty() && !claimedDid.equals(did)) {
            throw new LibraryProvider.ProtocolError("forbidden",
                "This request proves " + (did.isEmpty() ? "'" + caller.name() + "'" : did)
                    + ", not the did it names.");
        }
        if ("library_submit".equals(tool) && !caller.writer()) {
            throw new LibraryProvider.ProtocolError("forbidden",
                "'" + caller.name() + "' may read this library, not write to it.");
        }
        var patron = new LinkedHashMap<String, Object>();
        if (!did.isEmpty()) patron.put("did", did);
        patron.put("name", caller.name());
        patron.put("runtime", claimed != null && claimed.get("runtime") != null
            ? String.valueOf(claimed.get("runtime")) : "mcp");
        var out = new LinkedHashMap<String, Object>(args);
        out.put("patron", patron);
        return out;
    }

    /** A flat object schema from name/type pairs. */
    private static JsonNode schema(String... pairs) {
        var s = M.createObjectNode();
        s.put("type", "object");
        var props = s.putObject("properties");
        for (int i = 0; i + 1 < pairs.length; i += 2) props.putObject(pairs[i]).put("type", pairs[i + 1]);
        return s;
    }
}
