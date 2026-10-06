package org.wyrdsekai.e2e.infra;

import com.fasterxml.jackson.databind.JsonNode;
import org.wyrdsekai.common.util.Json;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A brain that answers the OpenAI chat API over a raw socket, so a test can see exactly what the
 * node's router does on the wire: it tells a language-healer rewrite from any other request, and
 * can hold a rewrite open until the client closes the line (recording when), translate it, or hand
 * it back unchanged. Ordinary requests get a short answer at once. One connection per request.
 */
public final class StandInBrain implements AutoCloseable {

    public enum Mode { HOLD, TRANSLATE, ECHO }

    /** One request seen, with how it ended. */
    public static final class Call {
        public final Instant arrivedAt = Instant.now();
        public final boolean heal;
        public final String note;
        public volatile long closedAfterMs = -1;   // HOLD: when the client closed the line
        Call(boolean heal, String note) { this.heal = heal; this.note = note; }
    }

    /** The healer's own words, in its system prompt (CompanionActor.rerenderNoteInLanguage). */
    static final String HEAL_MARK = "written in the wrong language";

    private final ServerSocket server;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile Mode mode;
    private volatile Map<String, String> translations = Map.of();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    public StandInBrain(Mode mode) throws IOException {
        this.mode = mode;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.submit(this::accept);
    }

    public String url() { return "http://127.0.0.1:" + server.getLocalPort(); }
    public void mode(Mode m) { this.mode = m; }
    public void translations(Map<String, String> t) { this.translations = Map.copyOf(t); }
    public List<Call> calls() { return List.copyOf(calls); }
    public List<Call> healCalls() { return calls.stream().filter(c -> c.heal).toList(); }

    private void accept() {
        while (running.get()) {
            try {
                var s = server.accept();
                pool.submit(() -> serve(s));
            } catch (IOException e) {
                if (running.get()) e.printStackTrace();
            }
        }
    }

    private void serve(Socket s) {
        try (s) {
            s.setSoTimeout(15 * 60 * 1000);
            var in = s.getInputStream();
            var head = readHead(in);
            if (head == null) return;
            var lines = head.split("\r\n");
            var request = lines[0].split(" ");
            var method = request[0];
            var path = request.length > 1 ? request[1] : "/";
            int length = 0;
            for (var l : lines) if (l.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(l.substring(15).strip());
            var body = new byte[length];
            for (int got = 0; got < length; ) { int n = in.read(body, got, length - got); if (n < 0) break; got += n; }
            var out = s.getOutputStream();
            if (method.equals("GET") && path.startsWith("/health")) { respond(out, 200, "{\"status\":\"ok\"}"); return; }
            if (method.equals("GET") && path.startsWith("/v1/models")) { respond(out, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"test-model\",\"object\":\"model\"}]}"); return; }
            if (method.equals("GET") && path.startsWith("/props")) { respond(out, 200, "{\"default_generation_settings\":{\"n_ctx\":16384},\"total_slots\":1}"); return; }
            if (!method.equals("POST") || !path.contains("/chat/completions")) { respond(out, 404, "{\"error\":\"no\"}"); return; }

            JsonNode req = Json.mapper().readTree(body);
            boolean heal = false;
            String note = "";
            for (var m : req.path("messages")) {
                var content = m.path("content").asText("");
                if (content.contains(HEAL_MARK)) heal = true;
                if ("user".equals(m.path("role").asText(""))) note = content;
            }
            var call = new Call(heal, note);
            calls.add(call);
            if (!heal) { respond(out, 200, chat("I'm here.")); return; }
            switch (mode) {
                case HOLD -> {
                    // Generate forever: wait for the client to hang up, and remember when it did.
                    long t0 = System.nanoTime();
                    try { while (in.read() >= 0) { /* nothing expected */ } } catch (IOException ignored) { }
                    call.closedAfterMs = (System.nanoTime() - t0) / 1_000_000L;
                }
                case TRANSLATE -> respond(out, 200, chat(translations.getOrDefault(note.strip(), "I keep this note in English now.")));
                case ECHO -> respond(out, 200, chat(note));
            }
        } catch (IOException | RuntimeException e) {
            // a closed line is the expected end of a held call
        }
    }

    private static String readHead(InputStream in) throws IOException {
        var buf = new ByteArrayOutputStream();
        int c; int state = 0;
        while ((c = in.read()) >= 0) {
            buf.write(c);
            state = (state == 0 && c == '\r') ? 1 : (state == 1 && c == '\n') ? 2 : (state == 2 && c == '\r') ? 3 : (state == 3 && c == '\n') ? 4 : (c == '\r' ? 1 : 0);
            if (state == 4) return buf.toString(StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String chat(String text) {
        try {
            return Json.mapper().writeValueAsString(Map.of(
                "id", "stand-in", "object", "chat.completion", "created", Instant.now().getEpochSecond(), "model", "test-model",
                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", text))),
                "usage", Map.of("prompt_tokens", 8, "completion_tokens", 4, "total_tokens", 12)));
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void respond(OutputStream out, int code, String json) throws IOException {
        var b = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: application/json\r\nContent-Length: "
            + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    @Override public void close() {
        running.set(false);
        try { server.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }
}
