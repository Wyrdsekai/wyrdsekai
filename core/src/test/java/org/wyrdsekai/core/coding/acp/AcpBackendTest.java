package org.wyrdsekai.core.coding.acp;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.AcpBackend;
import org.wyrdsekai.core.coding.BuildArtifact;
import org.wyrdsekai.core.coding.SourceArtifact;
import org.wyrdsekai.core.coding.TaskSpec;
import org.wyrdsekai.core.coding.TaskStatus;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AcpBackend} over the scripted fake agent: artifacts come from
 * the schema'd {@code locations[].path} field, and when the final agent
 * message carries the shared result-JSON block (the CodeZaiku contract),
 * it is promoted to the full source+build sibling pair by the SAME
 * parser the CLI path uses.
 */
class AcpBackendTest {

    private static AcpBackend backend(FakeAcpAgent[] out, FakeAcpAgentConfigurer configure) {
        AcpBackend.TransportFactory factory = () -> {
            var clientToAgent = new PipedOutputStream();
            var agentSees = new PipedInputStream(clientToAgent, 1 << 16);
            var agentToClient = new PipedOutputStream();
            var clientSees = new PipedInputStream(agentToClient, 1 << 16);
            var agent = new FakeAcpAgent(agentSees, agentToClient,
                AcpMethods.PROTOCOL_VERSION, false, false);
            configure.apply(agent);
            out[0] = agent;
            Thread.ofVirtual().name("fake-acp-agent").start(agent);
            return new AcpBackend.Transport(
                new AcpConnection(clientSees, clientToAgent), () -> { });
        };
        return new AcpBackend("goose-acp", List.of("goose", "acp"),
            Duration.ofSeconds(10), factory);
    }

    @FunctionalInterface
    private interface FakeAcpAgentConfigurer { void apply(FakeAcpAgent agent); }

    private static TaskSpec spec() {
        return new TaskSpec(UUID.randomUUID(), "did:test", "implement",
            "weave the loom", "/tmp/ws", List.of(), 0L, null);
    }

    @Test
    void locations_become_typed_source_artifact() throws Exception {
        var agentRef = new FakeAcpAgent[1];
        var backend = backend(agentRef, a -> a
            .withUpdate(FakeAcpAgent.toolCallUpdate("c1", "/ws/a.js"))
            .withUpdate(FakeAcpAgent.toolCallUpdate("c2", "/ws/a.js", "/ws/b.md"))
            .withUpdate(FakeAcpAgent.messageChunk("all done, no JSON here")));
        var s = spec();

        var result = backend.submitTask(s).get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        var artifacts = backend.artifactsFor(s.taskId().toString()).toList();
        assertThat(artifacts).hasSize(1);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).containsExactly("/ws/a.js", "/ws/b.md"); // deduped
        assertThat(src.workspacePath()).isEqualTo("/tmp/ws");
    }

    @Test
    void shared_result_json_block_promotes_to_source_plus_build() throws Exception {
        var agentRef = new FakeAcpAgent[1];
        var resultBlock = """
            {"status":"success","files":["src/loom.js"],"gitRef":"cafe12",
             "workspacePath":"/tmp/ws","testsPassed":2,"testsFailed":0}""";
        var backend = backend(agentRef, a -> a
            .withUpdate(FakeAcpAgent.messageChunk(resultBlock.replace("\n", " "))));
        var s = spec();

        var result = backend.submitTask(s).get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        var artifacts = backend.artifactsFor(s.taskId().toString()).toList();
        assertThat(artifacts).hasSize(2);
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains("src/loom.js");
        assertThat(src.gitRef()).isEqualTo("cafe12");
        assertThat(src.backendMetadata()).containsKey("__sibling_build");
        var build = (BuildArtifact) artifacts.get(1);
        assertThat(build.testsPassed()).isEqualTo(2);
        assertThat(build.status()).isEqualTo("success");
    }

    @Test
    void no_locations_and_no_block_yields_visibly_empty_artifacts() throws Exception {
        var agentRef = new FakeAcpAgent[1];
        var backend = backend(agentRef, a -> a
            .withUpdate(FakeAcpAgent.messageChunk("chatted, touched nothing")));
        var s = spec();

        var result = backend.submitTask(s).get(10, TimeUnit.SECONDS);
        // turn succeeded but artifact list is honestly EMPTY — visible to
        // callers, never a fabricated success payload
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(backend.artifactsFor(s.taskId().toString())).isEmpty();
    }
}
