package org.wyrdsekai.e2e.tier3;

import com.sun.net.httpserver.HttpServer;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.common.event.WorldEvent;
import org.wyrdsekai.core.agent.AgentProfile;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.agent.DecisionCapacity;
import org.wyrdsekai.core.inference.InferenceBackend;
import org.wyrdsekai.core.inference.InferenceClient;
import org.wyrdsekai.core.persistence.SqlDialect;
import org.wyrdsekai.core.persistence.WorldDnaService;
import org.wyrdsekai.core.room.RoomCommand;
import org.wyrdsekai.core.room.RoomNotification;
import org.wyrdsekai.core.room.RoomRegistry;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.PortAllocator;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The surprise loop, measured in real time. Two companions share the nexus and one sits alone in
 * the alcove, all on their own time with no person present, for {@code WYRD_SURPRISE_SECONDS}
 * (default 1200). The model is a stand-in that answers every request at once with a sentence whose
 * first three words are new, so every line a companion says is as novel as it can be: the loop's
 * worst case. Each companion also says a fresh line of its own every 75 seconds (staggered), the
 * way its own-time lines and replies reach the room on a real node; the question is what the
 * proactive gate does in the 20 seconds after it — after her own line, and (in the nexus) after the
 * other's. A narrator's gesture in each room at the start and every five minutes is the world's one
 * outside event. Everything scripted is the same in every run. Every second each companion's surprise and seeking are sampled;
 * every line and emote in both rooms is kept. Observational: it reports and asserts nothing.
 *
 * <p>Run it once on the fixed code and once with the old perception call, and compare. The lone
 * companion shows the loop her own words feed; the pair shows what one companion's words do to the
 * other. Time is not compressed: the loop runs on the one-second tick and surprise's one-minute
 * decay, which a time scale would distort.
 *
 * <pre>
 *   WYRD_SURPRISE_SOAK=1 WYRD_SURPRISE_ARM=fixed WYRD_SURPRISE_SECONDS=1200 \
 *     ./gradlew :e2e-test:test --tests "org.wyrdsekai.e2e.tier3.SurpriseLoopSoakE2ETest"
 * </pre>
 * The report is printed and written to {@code e2e-test/build/surprise-soak-<arm>.txt}, the samples
 * to {@code surprise-soak-<arm>.csv} beside it.
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_SURPRISE_SOAK", matches = "1|true")
class SurpriseLoopSoakE2ETest {

    private static final int WATCH_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_SURPRISE_SECONDS", "1200"));
    private static final String ARM = System.getenv().getOrDefault("WYRD_SURPRISE_ARM", "unlabelled");

    private static final String NEXUS = "nexus";
    private static final String ALCOVE = "alcove";
    /** entity → room; Wyrd is the nexus's default companion, the other two are spawned. */
    private static final Map<String, String> AGENTS = new LinkedHashMap<>();
    static {
        AGENTS.put("companion-wyrd", NEXUS);
        AGENTS.put("companion-vesna", NEXUS);
        AGENTS.put("companion-solo", ALCOVE);
    }

    private record Line(long at, String room, String entity, boolean emote, String text) {}
    private record Sample(long at, String entity, double surprise, double seeking) {}

    private static final List<Line> LINES = Collections.synchronizedList(new ArrayList<>());
    /** The lines this test put in a companion's mouth: entity → when. */
    private static final Map<String, List<Long>> SCRIPTED = new ConcurrentHashMap<>();
    private static final Set<String> SCRIPTED_TEXT = ConcurrentHashMap.newKeySet();
    /** Which of those lines were news: entity → when. */
    private static final Map<String, List<Long>> SCRIPTED_NEWS = new ConcurrentHashMap<>();
    private static HttpServer standIn;
    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        // WYRD_SURPRISE_MODEL_URL: a real model for the companions' speech. The typed surprise question
        // goes to the node's inference address (WYRDSEKAI_INFERENCE_URL, default 127.0.0.1:8200), so
        // point that at the same model. Otherwise the stand-in, which answers every request with a fresh
        // sentence and no log-probabilities, so the surprise question never gets an answer.
        var real = System.getenv("WYRD_SURPRISE_MODEL_URL");
        String url;
        if (real != null && !real.isBlank()) {
            url = real;
        } else {
            standIn = startStandInModel();
            url = "http://127.0.0.1:" + standIn.getAddress().getPort();
        }
        var client = new InferenceClient(url);
        var backend = new InferenceBackend.LlamaServer(real != null && !real.isBlank() ? "real" : "stand-in",
            client, 10, List.of(), null);
        var alcove = new ZoneGuardian.RoomSeed(ALCOVE, "The Alcove",
            "A small, quiet alcove with one window.", List.of(), List.of());
        server = new TestServerBootstrap(List.of(backend), PortAllocator.allocate(), List.of(alcove));
        server.start();
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());

        var dna = new WorldDnaService(server.jdbcUrl(), new SqlDialect.SQLite());
        spawn(dna, "Vesna", "companion-vesna", NEXUS);
        spawn(dna, "Solo", "companion-solo", ALCOVE);
        Thread.sleep(4000);

        ActorRef<RoomNotification> observer = server.system().systemActorOf(
            Behaviors.receiveMessage(n -> {
                var ev = n.event();
                if (ev instanceof WorldEvent.Said s) {
                    LINES.add(new Line(System.currentTimeMillis(), s.roomId(), s.entityId(), false, s.text()));
                } else if (ev instanceof WorldEvent.Emoted e) {
                    LINES.add(new Line(System.currentTimeMillis(), e.roomId(), e.entityId(), true, e.text()));
                }
                return Behaviors.same();
            }),
            "surprise-soak-observer", Props.empty());
        for (var room : List.of(NEXUS, ALCOVE)) {
            var ref = RoomRegistry.get().ref(room);
            assertNotNull(ref, room + " should be registered");
            ref.tell(new RoomCommand.Subscribe(observer));
        }
        Thread.sleep(500);
    }

    private static void spawn(WorldDnaService dna, String name, String entity, String room) throws Exception {
        var profile = new AgentProfile(name, entity, "agent", "A companion",
            "You are " + name + ", a companion in a text-based world. Speak as yourself.", 4096, 512, 0.7);
        server.system().tell(new ZoneGuardian.SpawnCompanion(
            profile, room, server.inferenceRouter(), dna, null, null, null));
        Thread.sleep(2500);
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
        if (standIn != null) standIn.stop(0);
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void theSurpriseLoopWithNoPersonPresent() throws Exception {
        var refs = new LinkedHashMap<String, ActorRef<CompanionActor.Command>>();
        for (var entity : AGENTS.keySet()) {
            var ref = ZoneGuardian.getCompanionRef(null, entity);
            assertNotNull(ref, entity + " should be spawned");
            refs.put(entity, ref);
        }
        for (var ref : refs.values()) {
            // Trusted standing, as the household's companions have: a new companion's tier speaks
            // unprompted only above 0.7, and a surprise spike is 0.6.
            ref.tell(new CompanionActor.ForceDecisionCapacity(DecisionCapacity.experienced()));
            ref.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
            ref.tell(new CompanionActor.ForceEnergy(0.85));
        }
        Thread.sleep(5000);
        LINES.clear();

        var samples = new ArrayList<Sample>();
        long start = System.currentTimeMillis();
        long deadline = start + WATCH_SECONDS * 1000L;
        int tick = 0;
        System.out.printf("[surprise-soak] arm=%s watch=%ds%n", ARM, WATCH_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            long t0 = System.currentTimeMillis();
            for (var e : refs.entrySet()) {
                if (tick % 15 == 0) e.getValue().tell(new CompanionActor.ForceEnergy(0.85));   // awake only
                try {
                    var d = queryState(e.getValue()).drives();
                    if (d != null) samples.add(new Sample(t0, e.getKey(), d.surprise(), d.seeking()));
                } catch (Exception ignored) {
                    // a busy actor misses a sample; the rest carry the curve
                }
            }
            if (tick % 300 == 0) nudge(tick / 300);
            int k = 0;
            for (var entity : AGENTS.keySet()) {
                if ((tick + 25 * k++) % 75 == 0) sayForHer(entity);
            }
            if (++tick % 60 == 0) {
                System.out.printf("[surprise-soak] t+%ds lines=%d samples=%d%n",
                    (t0 - start) / 1000, LINES.size(), samples.size());
            }
            long wait = 1000 - (System.currentTimeMillis() - t0);
            if (wait > 0) Thread.sleep(wait);
        }

        var report = report(new ArrayList<>(LINES), samples, start);
        System.out.println(report);
        var dir = Path.of(System.getProperty("user.dir"), "build");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("surprise-soak-" + ARM + ".txt"), report);
        var csv = new StringBuilder("seconds,entity,surprise,seeking\n");
        for (var s : samples) {
            csv.append(String.format("%.1f,%s,%.4f,%.4f%n", (s.at() - start) / 1000.0, s.entity(), s.surprise(), s.seeking()));
        }
        Files.writeString(dir.resolve("surprise-soak-" + ARM + ".csv"), csv);
    }

    /** News nobody saw coming: every fourth scripted line is one, the same in every run. */
    private static final String[] NEWS = {"Did you hear the library lost power and every lamp went out?",
        "Operator just came home early, he is at the door!", "The garden gate blew open in the storm last night.",
        "Someone left a letter on the table addressed to both of us.", "The river flooded the lower path this morning."};
    private static final AtomicInteger SCRIPTED_COUNT = new AtomicInteger();

    /** A line in her own name, as her own-time lines and replies reach the room: mostly the same subject. */
    private static void sayForHer(String entity) {
        var ref = RoomRegistry.get().ref(AGENTS.get(entity));
        if (ref == null) return;
        int n = SCRIPTED_COUNT.getAndIncrement();
        boolean news = n % 4 == 3;
        String text = "Scripted: " + (news ? NEWS[(n / 4) % NEWS.length] : freshSentence());
        if (news) SCRIPTED_NEWS.computeIfAbsent(entity, e -> Collections.synchronizedList(new ArrayList<>())).add(System.currentTimeMillis());
        SCRIPTED_TEXT.add(text);
        SCRIPTED.computeIfAbsent(entity, e -> Collections.synchronizedList(new ArrayList<>())).add(System.currentTimeMillis());
        ref.tell(new RoomCommand.SayInRoom(entity, entity.substring("companion-".length()), text,
            server.system().ignoreRef()));
        System.out.printf("[surprise-soak] said for %s at %s%n", entity, LocalTime.now());
    }

    private static final String[] NUDGES = {"A door somewhere closes.", "A bell rings far off.",
        "The floor creaks once.", "A bird lands on the sill.", "Rain starts against the glass.",
        "A lamp flickers and steadies."};

    /** The narrator's gesture in both rooms: an event from no one who lives there. */
    private static void nudge(int n) {
        for (var room : List.of(NEXUS, ALCOVE)) {
            var ref = RoomRegistry.get().ref(room);
            if (ref != null) {
                ref.tell(new RoomCommand.BroadcastRemoteEvent(new WorldEvent.Emoted(
                    room, Instant.now(), "narrator", "Narrator", NUDGES[n % NUDGES.length])));
            }
        }
    }

    private static String report(List<Line> lines, List<Sample> samples, long start) {
        var sb = new StringBuilder();
        double minutes = WATCH_SECONDS / 60.0;
        sb.append(String.format("════ SURPRISE LOOP SOAK — arm=%s, %.0f minutes, no person present ════%n", ARM, minutes));
        for (var e : AGENTS.entrySet()) {
            var entity = e.getKey();
            var own = lines.stream().filter(l -> l.entity().equals(entity)).toList();
            long said = own.stream().filter(l -> !l.emote() && !SCRIPTED_TEXT.contains(l.text())).count();
            long emotes = own.stream().filter(Line::emote).count();
            long blinks = own.stream().filter(l -> l.emote() && l.text().toLowerCase().contains("blink")).count();
            var mine = samples.stream().filter(s -> s.entity().equals(entity)).toList();
            double meanSurprise = mine.stream().mapToDouble(Sample::surprise).average().orElse(0);
            double highShare = mine.isEmpty() ? 0 : mine.stream().filter(s -> s.surprise() > 0.5).count() / (double) mine.size();
            double meanSeeking = mine.stream().mapToDouble(Sample::seeking).average().orElse(0);
            // After each of her own lines: the highest surprise sampled in the next 2 seconds, minus
            // the last sample before it; skipped when anyone else in her room said or did anything in
            // that window, so the rise is hers alone.
            var rises = new ArrayList<Double>();
            for (var l : own) {
                if (l.emote()) continue;
                boolean othersActed = lines.stream().anyMatch(o -> o.room().equals(l.room())
                    && !o.entity().equals(entity) && o.at() >= l.at() - 1000 && o.at() <= l.at() + 2000);
                if (othersActed) continue;
                double before = mine.stream().filter(s -> s.at() <= l.at()).reduce((a, b) -> b)
                    .map(Sample::surprise).orElse(Double.NaN);
                double after = mine.stream().filter(s -> s.at() > l.at() && s.at() <= l.at() + 2000)
                    .mapToDouble(Sample::surprise).max().orElse(Double.NaN);
                if (!Double.isNaN(before) && !Double.isNaN(after)) rises.add(after - before);
            }
            double meanRise = rises.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            // Her next act within 45 s of her own previous one, nothing from anyone else between.
            int selfFollow = 0, afterOther = 0;
            var inRoom = lines.stream().filter(l -> l.room().equals(e.getValue())).toList();
            for (int i = 1; i < inRoom.size(); i++) {
                var cur = inRoom.get(i);
                var prev = inRoom.get(i - 1);
                if (!cur.entity().equals(entity) || cur.at() - prev.at() > 45_000) continue;
                if (prev.entity().equals(entity)) selfFollow++;
                else afterOther++;
            }
            // What the gate did in the 20 s after each scripted line: her own acts that the test did
            // not script (proactive lines and emotes), after her own line and after the other's.
            var acts = own.stream().filter(l -> !SCRIPTED_TEXT.contains(l.text())).toList();
            var mineScripted = SCRIPTED.getOrDefault(entity, List.of());
            var othersScripted = SCRIPTED.entrySet().stream()
                .filter(o -> !o.getKey().equals(entity) && AGENTS.get(o.getKey()).equals(e.getValue()))
                .flatMap(o -> o.getValue().stream()).toList();
            long afterOwnScripted = mineScripted.stream()
                .filter(t -> acts.stream().anyMatch(a -> a.at() > t && a.at() <= t + 20_000)).count();
            long afterOtherScripted = othersScripted.stream()
                .filter(t -> acts.stream().anyMatch(a -> a.at() > t && a.at() <= t + 20_000)).count();
            sb.append(String.format(
                "  %-15s reacted within 20 s to %d of her %d own scripted lines, to %d of the other's %d%n",
                entity, afterOwnScripted, mineScripted.size(), afterOtherScripted, othersScripted.size()));
            var othersNews = SCRIPTED_NEWS.entrySet().stream()
                .filter(o -> !o.getKey().equals(entity) && AGENTS.get(o.getKey()).equals(e.getValue()))
                .flatMap(o -> o.getValue().stream()).toList();
            long afterOtherNews = othersNews.stream()
                .filter(t -> acts.stream().anyMatch(a -> a.at() > t && a.at() <= t + 20_000)).count();
            sb.append(String.format("  %-15s   of the other's lines, news: reacted to %d of %d; ordinary: %d of %d%n",
                entity, afterOtherNews, othersNews.size(), afterOtherScripted - afterOtherNews,
                othersScripted.size() - othersNews.size()));
            sb.append(String.format(
                "  %-15s room=%-6s lines=%3d (%.1f/min) emotes=%3d blinks=%3d | surprise mean=%.3f >0.5 %4.1f%% of seconds"
                    + " | rise after her own line=%s (n=%d) | seeking mean=%.3f | follows her own act=%d, the other's=%d%n",
                entity, e.getValue(), said, said / minutes, emotes, blinks, meanSurprise, highShare * 100,
                Double.isNaN(meanRise) ? "n/a" : String.format("%+.3f", meanRise), rises.size(), meanSeeking,
                selfFollow, afterOther));
        }
        // Back-and-forth in the nexus: runs of lines that alternate speaker, each within 30 s.
        var nexus = lines.stream().filter(l -> l.room().equals(NEXUS) && !l.emote()).toList();
        int runs = 0, run = 1, longest = 1;
        for (int i = 1; i < nexus.size(); i++) {
            boolean alternate = !nexus.get(i).entity().equals(nexus.get(i - 1).entity())
                && nexus.get(i).at() - nexus.get(i - 1).at() <= 30_000;
            if (alternate) {
                run++;
                longest = Math.max(longest, run);
            } else {
                if (run >= 4) runs++;
                run = 1;
            }
        }
        if (run >= 4) runs++;
        sb.append(String.format("  nexus back-and-forth runs (>=4 alternating lines, each within 30 s): %d, longest %d%n",
            runs, longest));
        sb.append("  ── last 40 lines ──\n");
        int from = Math.max(0, lines.size() - 40);
        for (var l : lines.subList(from, lines.size())) {
            sb.append(String.format("    +%5.0fs %-6s %-15s %s %s%n", (l.at() - start) / 1000.0, l.room(), l.entity(),
                l.emote() ? "EMOTE" : "SAID ", l.text().length() > 90 ? l.text().substring(0, 90) + "…" : l.text()));
        }
        return sb.toString();
    }

    private static CompanionActor.TestStateResponse queryState(ActorRef<CompanionActor.Command> companion)
            throws Exception {
        return AskPattern.ask(companion,
            (ActorRef<CompanionActor.TestStateResponse> ref) -> new CompanionActor.QueryTestState(ref),
            Duration.ofSeconds(3), server.system().scheduler()
        ).toCompletableFuture().get(4, TimeUnit.SECONDS);
    }

    // ── The stand-in model: every answer a sentence whose first three words are new ──

    private static final String[] ADJ = {"Amber", "Quiet", "Silver", "Pale", "Warm", "Faint", "Soft", "Low",
        "Bright", "Cool", "Grey", "Slow", "Thin", "Deep", "Clear", "Dim", "Green", "Gold", "Blue", "Still"};
    private static final String[] NOUN = {"light", "wind", "rain", "dust", "shadow", "water", "smoke", "music",
        "thread", "sound", "frost", "glow", "mist", "echo", "ribbon", "current", "murmur", "flicker", "tide", "hum"};
    private static final String[] VERB = {"gathers", "drifts", "settles", "lingers", "spreads", "rises", "falls",
        "circles", "gleams", "fades", "wanders", "pools", "trembles", "stirs", "flows", "hovers", "shifts",
        "glides", "sways", "rests"};
    private static final AtomicInteger SAID = new AtomicInteger();

    private static String freshSentence() {
        int i = SAID.getAndIncrement();
        return ADJ[i % 20] + " " + NOUN[(i / 20) % 20] + " " + VERB[(i / 400) % 20]
            + " near the window, and I notice it.";
    }

    private static HttpServer startStandInModel() throws IOException {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out;
            String type = "application/json";
            if (path.endsWith("/chat/completions") || path.endsWith("/completions")) {
                String text = freshSentence().replace("\"", "'");
                if (body.replace(" ", "").contains("\"stream\":true")) {
                    type = "text/event-stream";
                    out = ("data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{\"role\":\"assistant\",\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                } else {
                    out = ("{\"id\":\"s\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"stand-in\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + text
                        + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":12,"
                        + "\"total_tokens\":22}}").getBytes(StandardCharsets.UTF_8);
                }
            } else if (path.endsWith("/models")) {
                out = "{\"data\":[{\"id\":\"stand-in\",\"object\":\"model\"}]}".getBytes(StandardCharsets.UTF_8);
            } else if (path.endsWith("/health")) {
                out = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                out = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, out.length);
            try (var os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        http.setExecutor(Executors.newFixedThreadPool(8));
        http.start();
        return http;
    }
}
