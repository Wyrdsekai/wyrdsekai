package org.wyrdsekai.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class QrPrintMainTest {

    @Test
    void the_invite_qr_is_written_as_utf8_whatever_the_console_code_page() {
        var out = new ByteArrayOutputStream();
        var invite = "wyrdphone://relay.example/eyJ6b25lX2lkIjoibWlhIn0";

        assertThat(QrPrintMain.print(invite, out)).isTrue();

        var text = out.toString(StandardCharsets.UTF_8);
        assertThat(text.lines().toList()).isEqualTo(RelayCommandBridge.asciiQr(invite));
        assertThat(text).containsAnyOf("▀", "▄", "█").doesNotContain("?");
    }
}
