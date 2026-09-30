package org.wyrdsekai.core.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.library.LibraryAllowPolicy;
import org.wyrdsekai.core.library.LibraryConsent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ResearchZosho 0.5.0 researches a question its check flagged only when the call carries
 * {@code "allow": ["self-harm"]}, and asks hosts to send that only on a person's own yes. The
 * gateway is the one door every call to the librarian passes (items, the patron, code-mode, room
 * scripts, outside MCP clients through the household's doors), so it drops {@code allow} from
 * all of them; {@link McpGatewayService#callWithPersonsYes} is the only way through. And
 * {@code library_research} itself goes to the librarian only from {@link LibraryConsent}.
 */
class OnlyAPersonsYesCarriesAllowTest {

    private final List<Map<String, Object>> sent = new ArrayList<>();
    private McpGatewayService gateway;

    @BeforeEach
    void setUp() {
        var registry = new McpServiceRegistry();
        registry.register(new McpServiceConfig("researchzosho", "ResearchZosho", "http",
            "http://127.0.0.1:1/rpc", "local", null, null, true));
        registry.register(new McpServiceConfig("other", "Other", "http",
            "http://127.0.0.1:2/rpc", "local", null, null, true));
        gateway = new McpGatewayService(registry, new McpRateLimiter(100, 1000, 1000), new McpCircuitBreaker(),
            (endpoint, tool, params, auth) -> {
                sent.add(new HashMap<>(params));
                return "{\"job_id\":\"J-1\",\"state\":\"queued\"}";
            });
        gateway.setHouseholdServices(() -> Set.of("researchzosho"));
        LibraryConsent.useLedger(null);
        LibraryConsent.useYesReports(null);
    }

    @AfterEach
    void tearDown() {
        LibraryAllowPolicy.resetForTests();
        LibraryConsent.useLedger(null);
        LibraryConsent.useYesReports(null);
    }

    private static Map<String, Object> research(Object allow) {
        return research(allow, "q");
    }

    private static Map<String, Object> research(Object allow, String question) {
        var args = new HashMap<String, Object>();
        args.put("question", question);
        args.put("mode", "broad");
        args.put("allow", allow);
        return args;
    }

    @Test
    void a_companion_or_item_call_never_carries_allow() throws Exception {
        // Through the library desk's own door (LibraryConsent), as the companion and items call it.
        LibraryConsent.call(LibraryConsent.Asker.NO_ONE, "library_research", research(List.of("self-harm")),
            args -> gateway.call("did:companion", "local", "researchzosho", "library_research", args));
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst()).doesNotContainKey("allow").containsEntry("question", "q");
    }

    @Test
    void research_that_does_not_come_through_the_library_desk_is_refused() {
        // A room script, a skill: straight to the gateway, past the checks, the notice and the count.
        var r = gateway.execute("room:study", "local", "researchzosho", "library_research", research(List.of("self-harm")));
        assertThat(r.success()).isFalse();
        assertThat(r.error()).isEqualTo("research goes through the library desk");
        assertThatThrownBy(() -> gateway.call("did:companion", "local", "researchzosho", "library_research",
                research(List.of())))
            .isInstanceOf(McpGatewayService.Refused.class)
            .hasMessage("research goes through the library desk");
        assertThat(sent).isEmpty();

        // The librarian's other tools, and another service's research, are not the desk's to gate.
        assertThat(gateway.execute("room:study", "local", "researchzosho", "library_ask", Map.of("question", "q")).success()).isTrue();
        assertThat(gateway.execute("room:study", "local", "other", "library_research", Map.of("question", "q")).success()).isTrue();
        assertThat(sent).hasSize(2);
    }

    @Test
    void a_persons_yes_carries_allow_self_harm_and_nothing_else() throws Exception {
        gateway.callWithPersonsYes("did:person", "local", "researchzosho", "library_research", research(List.of("self-harm")));
        assertThat(sent.getFirst()).containsEntry("allow", List.of("self-harm"));

        // A yes is to the one thing the library asked about; any other allow is dropped.
        gateway.callWithPersonsYes("did:person", "local", "researchzosho", "library_research",
            research(List.of("self-harm", "explicit")));
        assertThat(sent.get(1)).doesNotContainKey("allow");
    }

    @Test
    void a_persons_yes_goes_only_on_library_research() {
        assertThatThrownBy(() -> gateway.callWithPersonsYes("did:person", "local", "researchzosho",
            "library_survey", research(List.of("self-harm"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    void the_gateway_asks_the_households_rule_not_the_library() throws Exception {
        LibraryAllowPolicy.setParentalControlsForTests(id -> id.equals("did:child"));

        // A child's yes carries nothing: the household's rule says so, whatever token the library holds.
        gateway.callWithPersonsYes("did:child", "local", "researchzosho", "library_research",
            research(List.of("self-harm"), "q1"));
        assertThat(sent.get(0)).doesNotContainKey("allow").containsEntry("question", "q1");

        // An adult's yes carries self-harm.
        gateway.callWithPersonsYes("did:adult", "local", "researchzosho", "library_research",
            research(List.of("self-harm"), "q2"));
        assertThat(sent.get(1)).containsEntry("allow", List.of("self-harm"));

        // explicit and howto go through no door, a person's yes included.
        gateway.callWithPersonsYes("did:adult", "local", "researchzosho", "library_research",
            research(List.of("explicit"), "q3"));
        gateway.callWithPersonsYes("did:adult", "local", "researchzosho", "library_research",
            research(List.of("howto"), "q4"));
        gateway.call("did:companion", "local", "researchzosho", "library_ask", research(List.of("explicit"), "q5"));
        gateway.execute("room:study", "local", "researchzosho", "library_ask", research(List.of("howto"), "q6"));
        assertThat(sent).hasSize(6);
        assertThat(sent.subList(2, 6)).allSatisfy(args -> assertThat(args).doesNotContainKey("allow"));

        // When who someone is cannot be answered, they are not taken for an adult.
        LibraryAllowPolicy.setParentalControlsForTests(id -> { throw new IllegalStateException("db down"); });
        gateway.callWithPersonsYes("did:adult", "local", "researchzosho", "library_research",
            research(List.of("self-harm"), "q7"));
        assertThat(sent.get(6)).doesNotContainKey("allow");
    }

    @Test
    void another_services_own_allow_argument_is_left_alone() throws Exception {
        gateway.call("did:companion", "local", "other", "filter", Map.of("allow", List.of("x")));
        assertThat(sent.getFirst()).containsEntry("allow", List.of("x"));
    }
}
