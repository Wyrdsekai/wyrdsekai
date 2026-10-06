package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancelling the answer a caller holds cancels the HTTP exchange itself: the connection closes, so
 * the model server sees the client gone and can stop generating. Nothing cancelled it before: the
 * router did not keep the call, so a one-shot past its timeout ran on at the backend (a household's
 * 0.5.4 report, 2026-10-05). A raw socket stands in for the model server: a close arrives as the
 * end of its input, and the control shows an uncancelled call keeps the line open.
 */
class CancellingTheCallClosesTheConnectionTest {

    /** Read one whole HTTP request (head and the body its Content-Length names). */
    private static void readRequest(InputStream in) throws IOException {
        var head = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            head.append((char) c);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        int length = 0;
        for (var line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(line.substring(15).strip());
        }
        for (int i = 0; i < length; i++) if (in.read() < 0) break;
    }

    /** What the server sees after the client acted: -1 for a close, or a timeout for a line left open. */
    private static String serverSees(boolean cancel) throws Exception {
        try (var ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var client = new InferenceClient("http://127.0.0.1:" + ss.getLocalPort(), null, Duration.ofSeconds(30));
            CompletableFuture<InferenceClient.ChatResponse> answer = client.chatCompletion(
                new InferenceClient.ChatRequest("m", List.of(new InferenceClient.ChatMessage("user", "hi")), 8, 0.1));
            try (Socket s = ss.accept()) {
                s.setSoTimeout(2500);
                var in = s.getInputStream();
                readRequest(in);                       // the whole request is in; the server is "generating"
                Thread.sleep(150);
                if (cancel) {
                    answer.cancel(true);
                    // The JDK ends the answer with a CancellationException inside a CompletionException,
                    // so isCancelled() reads false; what matters is below: the line is closed.
                    assertThat(answer.isCompletedExceptionally()).isTrue();
                }
                try {
                    return in.read() < 0 ? "closed" : "more bytes";
                } catch (SocketTimeoutException e) {
                    return "open";
                } finally {
                    if (!cancel) answer.cancel(true);  // let the control's client go too
                }
            }
        }
    }

    @Test
    void a_cancelled_call_closes_the_connection_and_an_uncancelled_one_keeps_it() throws Exception {
        assertThat(serverSees(false)).as("control: a call still waited for keeps its line open").isEqualTo("open");
        assertThat(serverSees(true)).as("a cancelled call closes its line").isEqualTo("closed");
    }
}
