package org.wyrdsekai.cli;

import org.junit.jupiter.api.Test;
import org.wyrdsekai.common.protocol.S2CMessage;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RendererTest {

    private String render(S2CMessage msg, boolean accessible, boolean verbose) {
        var baos = new ByteArrayOutputStream();
        var renderer = new Renderer(new PrintStream(baos), accessible, verbose);
        renderer.render(msg);
        return baos.toString();
    }

    // --- Priority rendering ---

    @Test void normal_priority_renders_text() {
        var output = render(
            new S2CMessage.Prose(1, "narrator", "Hello world", List.of(), null, "normal"),
            false, false);
        assertThat(output).contains("Hello world");
    }

    @Test void critical_priority_renders_bold_text() {
        var output = render(
            new S2CMessage.Prose(1, "narrator", "Alert!", List.of(), null, "critical"),
            false, false);
        assertThat(output).contains("Alert!");
        // Bold+Red ANSI codes
        assertThat(output).contains("\033[1m");
    }

    @Test void ambient_suppressed_in_non_verbose() {
        var output = render(
            new S2CMessage.Prose(1, "narrator", "Background noise", List.of(), null, "ambient"),
            false, false);
        assertThat(output).isEmpty();
    }

    @Test void ambient_shown_in_verbose_mode() {
        var output = render(
            new S2CMessage.Prose(1, "narrator", "Background noise", List.of(), null, "ambient"),
            false, true);
        assertThat(output).contains("Background noise");
    }

    @Test void critical_accessible_mode_includes_bell() {
        var output = render(
            new S2CMessage.Prose(1, "system", "Security alert!", List.of(), null, "critical"),
            true, false);
        assertThat(output).contains("\007");
        assertThat(output).contains("[CRITICAL]");
        assertThat(output).contains("Security alert!");
    }

    @Test void null_priority_treated_as_normal() {
        var output = render(
            new S2CMessage.Prose(1, "narrator", "Default", List.of(), null, null),
            false, false);
        assertThat(output).contains("Default");
    }
}
