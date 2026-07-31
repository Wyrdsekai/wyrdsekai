package org.wyrdsekai.scripting.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.scripting.api.ScriptCrypto;
import org.wyrdsekai.scripting.api.ScriptDatabase;
import org.wyrdsekai.scripting.api.ScriptFileSystem;
import org.wyrdsekai.scripting.api.ScriptHtmlParser;
import org.wyrdsekai.scripting.api.ScriptHttpClient;

import java.nio.file.Path;

/**
 * Builds a GraalJS {@link Context} with API bindings appropriate to the sandbox level.
 *
 * <p>Each sandbox level adds capabilities on top of the previous one:
 * <ul>
 *   <li>ROOM_SCRIPT — No additional APIs (world API injected separately by ScriptSandbox)</li>
 *   <li>SKILL_BASIC — http, html, crypto, JSON</li>
 *   <li>SKILL_DATA — All of BASIC + db (factory), fs</li>
 *   <li>SKILL_SERVER — All of DATA (HTTP server deferred)</li>
 *   <li>SKILL_FULL — Full Java interop</li>
 * </ul>
 */
public class SandboxContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(SandboxContextBuilder.class);

    private SandboxContextBuilder() {}

    /**
     * Build a GraalJS context configured for the given sandbox level.
     *
     * @param level     The sandbox level determining available APIs
     * @param workspace The workspace directory for file/database operations (may be null for ROOM_SCRIPT/SKILL_BASIC)
     * @return A configured GraalJS context with injected APIs
     */
    public static Context build(SandboxLevel level, Path workspace) {
        return build(level, workspace, null);
    }

    /**
     * Build a GraalJS context configured for the given sandbox level, with a shared engine.
     *
     * @param level     The sandbox level determining available APIs
     * @param workspace The workspace directory for file/database operations (may be null for ROOM_SCRIPT/SKILL_BASIC)
     * @param engine    Optional shared GraalJS engine (may be null)
     * @return A configured GraalJS context with injected APIs
     */
    public static Context build(SandboxLevel level, Path workspace, Engine engine) {
        if (level == null) {
            throw new IllegalArgumentException("Sandbox level must not be null");
        }

        var context = createContext(level, engine);
        injectApis(context, level, workspace);
        return context;
    }

    /**
     * Create the raw GraalJS context with appropriate security settings.
     */
    private static Context createContext(SandboxLevel level, Engine engine) {
        var builder = Context.newBuilder("js")
            .allowExperimentalOptions(true);

        if (engine != null) {
            builder.engine(engine);
        }

        // EXPLICIT host access: only @HostAccess.Export methods are callable.
        // List/Map/Array access enabled so Java collections returned by our APIs
        // (e.g. ScriptDatabase.query() → List<Map>, ScriptHtmlParser.selectAll() → List)
        // are usable as JS arrays/objects without manual conversion.
        var skillHostAccess = HostAccess.newBuilder(HostAccess.EXPLICIT)
            .allowListAccess(true)
            .allowMapAccess(true)
            .allowArrayAccess(true)
            .build();

        return switch (level) {
            case ROOM_SCRIPT -> builder
                .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT).build())
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build();

            case SKILL_BASIC -> builder
                .allowHostAccess(skillHostAccess)
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build();

            case SKILL_DATA -> builder
                .allowHostAccess(skillHostAccess)
                .allowIO(false)    // IO sandboxed via our Java wrappers, not GraalJS IO
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build();

            case SKILL_SERVER -> builder
                .allowHostAccess(skillHostAccess)
                .allowIO(false)
                .allowCreateThread(false)
                .allowNativeAccess(false)
                .build();

            case SKILL_FULL -> {
                // #15 (2026-07-19 OSS hardening) — SKILL_FULL grants
                // allowAllAccess(true), which is effectively host RCE. It is
                // steward-gated at grant time (AgentPermissions.maxSandboxLevel →
                // sandbox.full / *.*), but every actual USE must be loudly audited
                // so a full-access execution is never silent. A tighter HostAccess
                // allowlist is intentionally NOT applied here: this level backs the
                // coding workbench, which needs arbitrary host access to compile
                // and run code.
                log.warn("SKILL_FULL sandbox built — allowAllAccess(true), host "
                    + "RCE-capable. Must only be reachable via a steward-gated grant "
                    + "(sandbox.full or *.*).");
                yield builder.allowAllAccess(true).build();
            }
        };
    }

    /**
     * Inject Java-backed API objects into the JS global scope based on sandbox level.
     */
    private static void injectApis(Context context, SandboxLevel level, Path workspace) {
        var bindings = context.getBindings("js");

        // SKILL_BASIC and above: http, html, crypto
        if (level.includes(SandboxLevel.SKILL_BASIC)) {
            // #3 (2026-07-19) — the coding workbench is a steward-invoked
            // full-code-execution context (it compiles + runs arbitrary code),
            // so it may reach LAN/loopback services (local DBs, dev servers); it
            // is NOT the item-script SSRF surface. Use the permissive policy,
            // which still blocks the never-legitimate ranges (cloud metadata,
            // any-local, multicast).
            bindings.putMember("http", new ScriptHttpClient(false));
            bindings.putMember("html", new ScriptHtmlParser());
            bindings.putMember("crypto", new ScriptCrypto());

            // Add a console.log polyfill for script debugging
            context.eval("js", """
                if (typeof console === 'undefined') {
                    var console = { log: function() {}, warn: function() {}, error: function() {} };
                }
                """);
        }

        // SKILL_DATA and above: fs, Database factory
        if (level.includes(SandboxLevel.SKILL_DATA)) {
            if (workspace != null) {
                bindings.putMember("fs", new ScriptFileSystem(workspace));

                // Inject a Database factory function (since JS can't call Java constructors directly
                // in EXPLICIT host access mode)
                var dbFactory = new DatabaseFactory(workspace);
                bindings.putMember("_dbFactory", dbFactory);
                context.eval("js", """
                    var Database = function(name) {
                        return _dbFactory.create(name);
                    };
                    """);
            } else {
                log.warn("SKILL_DATA level requested but no workspace path provided — " +
                    "fs and Database APIs will not be available");
            }
        }
    }

    /**
     * Factory class for creating ScriptDatabase instances from JS.
     * Needed because JS can't call Java constructors directly in EXPLICIT host access mode.
     */
    public static class DatabaseFactory {
        private final Path workspace;

        public DatabaseFactory(Path workspace) {
            this.workspace = workspace;
        }

        @HostAccess.Export
        public ScriptDatabase create(String dbName) {
            return new ScriptDatabase(workspace, dbName);
        }
    }
}
