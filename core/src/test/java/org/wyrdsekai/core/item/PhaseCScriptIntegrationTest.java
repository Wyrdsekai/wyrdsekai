package org.wyrdsekai.core.item;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.scripting.api.ItemCapabilitySet;
import org.wyrdsekai.scripting.api.ItemManifest;
import org.wyrdsekai.scripting.sandbox.ItemScriptExecutor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JS-side integration: a real item
 * script written against {@code world.fs.*}, {@code world.mailbox.*}, and
 * {@code world.drive.*} round-trips through the executor + provider.
 */
class PhaseCScriptIntegrationTest {

    private ItemScriptExecutor executor;
    private ItemWorldApiProviderImpl provider;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        MailboxService.resetForTests();
        executor = new ItemScriptExecutor();
        provider = new ItemWorldApiProviderImpl(
            null, null, null, null,
            "did:wyrd:tester", "Tester",
            t -> {}, t -> {}, (a, b) -> {},
            null, null);
        provider.setSandboxedFs(new SandboxedFs(tmp, "did:wyrd:tester"));
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.close();
        MailboxService.resetForTests();
    }

    @Test
    void fs_write_then_read_through_script() {
        var script = """
            function invoke(p) {
              world.fs.write('note.txt', p.content);
              var back = world.fs.read('note.txt');
              return { ok: true, content: back };
            }
            """;
        var caps = capsWithDeclared("fs.read", "fs.write");
        var res = executor.execute("scribe", script,
            Map.of("content", "remember this"), provider, caps);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(res.get("content")).isEqualTo("remember this");
    }

    @Test
    void fs_path_traversal_caught_by_provider() {
        var script = """
            function invoke(p) {
              return world.fs.write('../escape.txt', 'evil');
            }
            """;
        var caps = capsWithDeclared("fs.write");
        var res = executor.execute("evil", script, Map.of(), provider, caps);
        assertThat(res.get("ok")).isEqualTo(false);
        assertThat(String.valueOf(res.get("error"))).contains("..");
    }

    @Test
    void mailbox_send_through_script() {
        var script = """
            function invoke(p) {
              return world.mailbox.send(p.to, p.subject, p.body);
            }
            """;
        var caps = capsWithDeclared("agent.mailbox.send");
        var res = executor.execute("courier", script,
            Map.of("to", "did:wyrd:tester", "subject", "hi", "body", "from script"),
            provider, caps);
        assertThat(res.get("ok")).isEqualTo(true);
        assertThat(provider.mailboxInbox(null)).hasSize(1);
    }

    @Test
    void drive_mark_capability_required_in_script() {
        var script = """
            function invoke(p) {
              return world.drive.mark('seeking', 0.1);
            }
            """;
        // No drive.mark cap — denial bubbles up.
        var caps = capsWithDeclared(); // empty
        var res = executor.execute("driver", script, Map.of(), provider, caps);
        assertThat(res).containsKey("capability_denied");
        assertThat(res.get("capability_denied")).isEqualTo("drive.mark");
    }

    @Test
    void web_post_blocked_when_no_domain_allowlisted() {
        var script = """
            function invoke(p) {
              return world.web.post('https://example.com/hook', p.body);
            }
            """;
        // declare cap, but no external_domains in the manifest
        var caps = capsWithCapsAndDomains(List.of("web.post"), List.of());
        var res = executor.execute("poster", script,
            Map.of("body", "x"), provider, caps);
        assertThat(res.get("error")).isEqualTo("domain_not_allowed");
    }

    private static ItemCapabilitySet capsWithDeclared(String... declared) {
        return capsWithCapsAndDomains(List.of(declared), List.of());
    }

    private static ItemCapabilitySet capsWithCapsAndDomains(
            List<String> declared, List<String> domains) {
        var m = new ItemManifest("test", "1.0.0", "T.", "did:wyrd:author",
            declared, Map.of(), "low", List.of(),
            domains, List.of(), List.of(),
            null, null, null, null, null);
        return ItemCapabilitySet.from(m);
    }
}
