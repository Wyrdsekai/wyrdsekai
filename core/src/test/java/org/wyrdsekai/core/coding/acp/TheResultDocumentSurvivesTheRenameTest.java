package org.wyrdsekai.core.coding.acp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.core.coding.AcpBackend;
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
 * CodeZaiku became CodeZaiku, and the ACP result document moved with it:
 * the prompt reply now carries it at {@code _meta.codezaiku} where it used
 * to be {@code _meta.codezaiku}.
 *
 * <p>That key is NOT covered by the environment-variable aliases, and it
 * fails in SILENCE: {@code JsonNode.path} answers a missing key with a
 * MissingNode instead of throwing, and {@code MissingNode.isObject()} is
 * false — so reading only the old spelling against the new binary loses
 * every typed artifact and falls back to scraping the message text. The
 * turn still reports SUCCEEDED. Nothing logs.</p>
 *
 * <p>So both spellings are pinned here: the new one because it is what the
 * binary emits now, the old one because a household may not have upgraded
 * yet. When the old spelling is finally dropped, this test is the thing
 * that has to be deliberately changed.</p>
 */
class TheResultDocumentSurvivesTheRenameTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static AcpBackend backend(String metaKey, ObjectNode doc) {
        AcpBackend.TransportFactory factory = () -> {
            var clientToAgent = new PipedOutputStream();
            var agentSees = new PipedInputStream(clientToAgent, 1 << 16);
            var agentToClient = new PipedOutputStream();
            var clientSees = new PipedInputStream(agentToClient, 1 << 16);
            var agent = new FakeAcpAgent(agentSees, agentToClient,
                AcpMethods.PROTOCOL_VERSION, false, false);
            if (metaKey != null) agent.withResultMeta(metaKey, doc);
            Thread.ofVirtual().name("fake-acp-agent").start(agent);
            return new AcpBackend.Transport(
                new AcpConnection(clientSees, clientToAgent), () -> { });
        };
        return new AcpBackend("codezaiku-acp", List.of("codezaiku", "acp"),
            Duration.ofSeconds(10), factory);
    }

    private static ObjectNode resultDoc(String file) {
        var doc = M.createObjectNode();
        doc.put("status", "success");
        doc.putArray("files").add(file);
        doc.put("gitRef", "cafe12");
        doc.put("workspacePath", "/tmp/ws");
        return doc;
    }

    private static TaskSpec spec() {
        return new TaskSpec(UUID.randomUUID(), "did:test", "implement",
            "weave the loom", "/tmp/ws", List.of(), 0L, null);
    }

    private static void assertTypedArtifactFrom(String metaKey, String file) throws Exception {
        var backend = backend(metaKey, resultDoc(file));
        var s = spec();

        var result = backend.submitTask(s).get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);

        var artifacts = backend.artifactsFor(s.taskId().toString()).toList();
        assertThat(artifacts)
            .describedAs("_meta.%s must yield a typed artifact, not a silent empty", metaKey)
            .isNotEmpty();
        var src = (SourceArtifact) artifacts.get(0);
        assertThat(src.files()).contains(file);
        assertThat(src.gitRef()).isEqualTo("cafe12");
    }

    @Test
    void the_current_spelling_carries_the_result_document() throws Exception {
        assertTypedArtifactFrom("codezaiku", "src/loom.js");
    }

    @Test
    void the_pre_rename_spelling_still_carries_it() throws Exception {
        assertTypedArtifactFrom("codezaiku", "src/legacy.js");
    }

    @Test
    void neither_spelling_is_an_honest_empty_not_a_crash() throws Exception {
        var backend = backend(null, null);
        var s = spec();

        var result = backend.submitTask(s).get(10, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(backend.artifactsFor(s.taskId().toString())).isEmpty();
    }
}
