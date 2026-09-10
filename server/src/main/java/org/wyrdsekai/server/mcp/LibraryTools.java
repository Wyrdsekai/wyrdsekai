package org.wyrdsekai.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.library.LibraryProvider;

import java.util.Map;

/**
 * Registers the household's library (LIBRARY_PROTOCOL.md) on the inbound MCP tool registry,
 * so a peer household or a research librarian can be a patron of ours. JSON in, JSON out;
 * every refusal is an MCP error result carrying the protocol's stable code.
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

    private static void register(McpToolRegistry registry, String tool, String description,
                                 JsonNode schema, LibraryProvider provider) {
        registry.register(tool, description, schema, args -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = args == null || args.isNull()
                    ? Map.of() : M.convertValue(args, Map.class);
                var result = provider.call(tool, map);
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

    /** A flat object schema from name/type pairs. */
    private static JsonNode schema(String... pairs) {
        var s = M.createObjectNode();
        s.put("type", "object");
        var props = s.putObject("properties");
        for (int i = 0; i + 1 < pairs.length; i += 2) props.putObject(pairs[i]).put("type", pairs[i + 1]);
        return s;
    }
}
