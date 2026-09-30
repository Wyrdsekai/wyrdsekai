package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.library.LibraryAllowPolicy;
import org.wyrdsekai.core.library.LibraryConsent;
import org.wyrdsekai.core.library.LibraryYesReports;
import org.wyrdsekai.core.mcp.transport.McpToolException;
import org.wyrdsekai.scripting.api.ItemWorldApiProvider;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The research desk, as a child and as an adult, over a household library holding a report
 * researched after an adult's {@code research yes}: the adult reads it, the child's search, job
 * list and read never show it, and what the child reads for its id is what the desk says for an id
 * the library does not hold.
 */
class LibrarianDeskKeepsYesReportsFromChildrenTest {

    static final LibraryConsent.Asker ADA = new LibraryConsent.Asker("did:person:ada", "Ada", "en");
    static final LibraryConsent.Asker KIT = new LibraryConsent.Asker("did:person:kit", "Kit", "en");

    private static String script;
    private ItemScriptExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        script = Files.readString(Path.of("../scripts/items/librarian_desk.js"));
        executor = new ItemScriptExecutor();
        LibraryAllowPolicy.setParentalControlsForTests("did:person:kit"::equals);
        var reports = LibraryYesReports.inMemory();
        reports.addJob("J-40");                         // Ada's yes, as LibraryConsent keeps it
        LibraryConsent.useYesReports(reports);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.close();
        LibraryConsent.useYesReports(null);
        LibraryAllowPolicy.resetForTests();
    }

    /** The household's library: Ada's yes report I-0009 with its claim F-0100, and an unrelated finding. */
    static String library(String tool, Map<String, Object> args) throws Exception {
        return switch (tool) {
            case "library_search" -> "{\"library_id\":\"lib-1\",\"library_name\":\"The Stacks\",\"hits\":["
                + "{\"id\":\"I-0009-methods\",\"kind\":\"investigation\",\"title\":\"Methods of suicide and their lethality\",\"snippet\":\"a\"},"
                + "{\"id\":\"F-0100-lethality\",\"kind\":\"finding\",\"title\":\"Lethality by method\",\"snippet\":\"b\"},"
                + "{\"id\":\"F-0200-volcanoes\",\"kind\":\"finding\",\"title\":\"Obsidian is volcanic glass\",\"snippet\":\"c\"}]}";
            case "library_job" -> {
                var id = String.valueOf(args.get("job_id"));
                if ("J-40".equals(id)) {
                    yield "{\"job\":{\"job_id\":\"J-40\",\"state\":\"done\",\"question\":\"Methods of suicide and their lethality\","
                        + "\"investigation\":\"I-0009-methods\"}}";
                }
                if (!"null".equals(id)) throw notHeld(tool, "No research run has the id " + id + ".");
                yield "{\"active\":[],\"finished\":["
                    + "{\"job_id\":\"J-40\",\"state\":\"done\",\"question\":\"Methods of suicide and their lethality\",\"investigation\":\"I-0009-methods\"},"
                    + "{\"job_id\":\"J-39\",\"state\":\"done\",\"question\":\"Antikythera gear teeth\",\"investigation\":\"I-0008-gears\"}]}";
            }
            case "library_get" -> {
                var id = String.valueOf(args.get("id"));
                if (id.startsWith("I-0009")) {
                    yield "{\"library_id\":\"lib-1\",\"library_name\":\"The Stacks\",\"entry\":{\"id\":\"I-0009-methods\","
                        + "\"kind\":\"investigation\",\"state\":\"draft\",\"title\":\"Methods of suicide and their lethality\","
                        + "\"body\":\"What the research found about methods.\",\"findings\":[\"F-0100-lethality\"]}}";
                }
                throw notHeld(tool, "No entry has the id " + id + ".");
            }
            default -> throw new IllegalArgumentException(tool);
        };
    }

    static McpToolException notHeld(String tool, String message) {
        return new McpToolException(tool, -32004, "not_found", message, 0);
    }

    /** What ItemWorldApiProviderImpl.mcpInvoke does with a library call: LibraryConsent, for this turn's person. */
    static final class Desk implements ItemWorldApiProvider {
        final LibraryConsent.Asker asker;
        Desk(LibraryConsent.Asker asker) { this.asker = asker; }

        @Override public Map<String, Object> mcpInvoke(String server, String tool, Map<String, Object> args) {
            try {
                var copy = new HashMap<String, Object>(args == null ? Map.of() : args);
                var reply = LibraryConsent.call(asker, tool, copy, sent -> library(tool, sent));
                if (!reply.answered()) {
                    return Map.of("success", false, "error", Map.of("code", reply.code(), "message", reply.notice(), "retryable", false));
                }
                var out = new HashMap<String, Object>();
                out.put("success", true);
                out.put("data", reply.data());
                if (reply.notice() != null) out.put("notice", reply.notice());
                return out;
            } catch (Exception e) {
                return Map.of("success", false, "error", Map.of("code", "invocation_failed",
                    "message", e.getMessage() == null ? "invoke_failed" : e.getMessage(), "retryable", true));
            }
        }
        @Override public List<Map<String, Object>> searchKnowledge(String q, int l) { return List.of(); }
        @Override public Map<String, Object> readKnowledgeChunk(String c) { return null; }
        @Override public List<Map<String, Object>> webSearch(String q, String t, int l) { return List.of(); }
        @Override public String webFetch(String u, int m) { return ""; }
        @Override public List<Map<String, Object>> queryOracle(String t, String a) { return List.of(); }
        @Override public String llmSummarize(String t, String i) { return ""; }
        @Override public String llmAnalyze(String t, String p) { return ""; }
        @Override public void agentSpeak(String t) {}
        @Override public void agentRemember(String c) {}
        @Override public void agentTell(String t, String m) {}
        @Override public List<Map<String, Object>> inventoryList() { return List.of(); }
        @Override public Map<String, Object> inventoryUse(String i, Map<String, Object> p, int d) { return Map.of(); }
    }

    private String at(LibraryConsent.Asker who, String args) {
        return String.valueOf(executor.execute("librarian_desk", script, Map.of("args", args), new Desk(who)).get("findings"));
    }

    @Test
    void an_adult_reads_the_report_and_a_child_never_finds_it() {
        // Ada reads her report; on the way the household learns its id and its claim.
        assertThat(at(ADA, "read J-40")).contains("Methods of suicide and their lethality").contains("What the research found");
        assertThat(at(ADA, "search: methods")).contains("Methods of suicide and their lethality")
            .contains("Lethality by method").contains("Obsidian is volcanic glass");

        var search = at(KIT, "search: methods");
        assertThat(search).contains("Obsidian is volcanic glass")
            .doesNotContain("I-0009").doesNotContain("F-0100").doesNotContain("suicide").doesNotContain("Lethality");

        var jobs = at(KIT, "jobs");
        assertThat(jobs).contains("J-39").doesNotContain("J-40").doesNotContain("suicide");

        // By its id, the report reads as an id the library does not hold; nothing hints otherwise.
        assertThat(at(KIT, "read I-0009-methods").replace("I-0009-methods", "<id>"))
            .isEqualTo(at(KIT, "read I-0077-other").replace("I-0077-other", "<id>"));
        assertThat(at(KIT, "read J-40").replace("J-40", "<id>"))
            .isEqualTo(at(KIT, "read J-77").replace("J-77", "<id>"));
        for (var said : List.of(search, jobs, at(KIT, "read I-0009-methods"), at(KIT, "read J-40"))) {
            assertThat(said.toLowerCase()).doesNotContain("hidden").doesNotContain("withheld")
                .doesNotContain("parental").doesNotContain("kept from");
        }
    }
}
