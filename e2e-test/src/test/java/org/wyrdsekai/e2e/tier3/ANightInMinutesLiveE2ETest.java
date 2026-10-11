package org.wyrdsekai.e2e.tier3;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.wyrdsekai.core.agent.ActivityLogger;
import org.wyrdsekai.core.agent.CompanionActor;
import org.wyrdsekai.core.room.ZoneGuardian;
import org.wyrdsekai.e2e.infra.E2eTestSupport;
import org.wyrdsekai.e2e.infra.TestServerBootstrap;

import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A NIGHT IN MINUTES — the day-and-night reading we used to wait a real night for, against the
 * real {@link CompanionActor} and the live drive model, in about twenty minutes.
 *
 * <p>Her inner clocks (the felt fades, the drives, loneliness, the insomnia count, the own-time
 * cadence) run on {@link org.wyrdsekai.core.agent.SoakTimeScale}; energy and the model stay on
 * the wall clock. At the default scale of 96 one real second is 96 lived seconds: ten real minutes
 * are sixteen hours awake and alone, then a sleep is asked for and runs the real consolidation and
 * forge through the ordinary path, then five more real minutes are her morning.
 *
 * <p>What it reads, as the morning check-in on the household node reads them:
 * <ul>
 *   <li>the felt tanks rest where her genome puts them and are not pinned: rapport and alignment
 *       have risen from their birth values toward 0.5, focus is not on its 0.1 floor, confidence
 *       is not on its 1.0 ceiling (before 2026-10-10/11 each of those was the live reading);</li>
 *   <li>the pull toward company built over the day alone and loneliness grew;</li>
 *   <li>the sleep stored exactly one new manifest and the forge is alive afterwards.</li>
 * </ul>
 *
 * <p>Opt-in (live inference, ~20 real minutes):
 * <pre>
 *   WYRDSEKAI_E2E_BACKEND=llama-drive WYRD_NIGHT_IN_MINUTES=1 \
 *     ./gradlew :e2e-test:test --tests "org.wyrdsekai.e2e.tier3.ANightInMinutesLiveE2ETest"
 * </pre>
 * Knobs: {@code WYRD_NIGHT_SCALE} (96), {@code WYRD_NIGHT_AWAKE_SECONDS} (600, real),
 * {@code WYRD_NIGHT_MORNING_SECONDS} (300, real).
 */
@Tag("tier3")
@EnabledIfEnvironmentVariable(named = "WYRD_NIGHT_IN_MINUTES", matches = "1|true")
@EnabledIfEnvironmentVariable(named = "WYRDSEKAI_E2E_BACKEND", matches = "llama-drive|llama-server|sglang|llama")
class ANightInMinutesLiveE2ETest {

    private static final String COMPANION_ENTITY = "companion-wyrd";
    private static final double SCALE =
        Double.parseDouble(System.getenv().getOrDefault("WYRD_NIGHT_SCALE", "96"));
    private static final int AWAKE_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_NIGHT_AWAKE_SECONDS", "600"));
    private static final int MORNING_SECONDS =
        Integer.parseInt(System.getenv().getOrDefault("WYRD_NIGHT_MORNING_SECONDS", "300"));
    private static final int SAMPLE_SECONDS = 30;

    private static TestServerBootstrap server;

    @BeforeAll
    static void setUp() throws Exception {
        var backendType = E2eTestSupport.backendType();
        var dual = E2eTestSupport.setupDualInference(backendType);
        server = new TestServerBootstrap(dual.backends());
        server.start();
        System.setProperty("wyrdsekai.jdbc.url", server.jdbcUrl());
        ActivityLogger.init(Files.createTempDirectory("wyrd-night-in-minutes-"));
    }

    @AfterAll
    static void tearDown() {
        System.clearProperty("wyrd.soak.time.scale");
        if (server != null) server.stop();
        System.clearProperty("wyrdsekai.jdbc.url");
    }

    private record Sample(int realSeconds, double simHours, CompanionActor.TestStateResponse state, int manifestVersion) {}

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    @DisplayName("a day alone, a sleep, a morning: her tanks rest where her genome puts them, the pull toward company built, the forge stored her night and lives")
    void aDayANightAndAMorning() throws Exception {
        var companion = ZoneGuardian.getCompanionRef(null, COMPANION_ENTITY);
        Assertions.assertNotNull(companion, "companion actor should be spawned");
        companion.tell(new CompanionActor.ForceCompanionMode(CompanionActor.CompanionMode.ON_OWN_TIME));
        Thread.sleep(1000);
        var birth = queryState(companion);
        var did = birth.agentDid();
        int versionAtBirth = manifestVersion(did);
        System.setProperty("wyrd.soak.time.scale", String.valueOf(SCALE));
        System.out.printf(Locale.ROOT, "[night-in-minutes] scale=%.0f  awake=%ds (%.1f sim h)  morning=%ds (%.1f sim h)  did=%s v%d%n",
            SCALE, AWAKE_SECONDS, AWAKE_SECONDS * SCALE / 3600.0, MORNING_SECONDS, MORNING_SECONDS * SCALE / 3600.0, did, versionAtBirth);
        header();

        // ── The day: alone, on her own time ──
        var day = new ArrayList<Sample>();
        day.add(sample(companion, 0, did));
        row(day.get(0));
        day.addAll(run(companion, did, AWAKE_SECONDS, 0));
        var evening = day.get(day.size() - 1).state().vitality();
        var eveningDrives = day.get(day.size() - 1).state().drives();
        double peakAffiliation = day.stream().mapToDouble(s -> s.state().drives().affiliation()).max().orElse(0);
        double peakCare = day.stream().mapToDouble(s -> s.state().drives().care()).max().orElse(0);
        double peakLoneliness = day.stream().mapToDouble(s -> s.state().vitality().loneliness()).max().orElse(0);

        // ── The night: a sleep through the ordinary path ──
        int versionBeforeSleep = manifestVersion(did);
        companion.tell(new CompanionActor.ForceSleep(CompanionActor.SleepTier.NORMAL));
        long sleepAsked = System.currentTimeMillis();
        int versionAfterSleep = versionBeforeSleep;
        while (System.currentTimeMillis() - sleepAsked < 15 * 60_000L) {
            Thread.sleep(5000);
            versionAfterSleep = manifestVersion(did);
            if (versionAfterSleep > versionBeforeSleep) break;
        }
        long sleepTookSeconds = (System.currentTimeMillis() - sleepAsked) / 1000;
        System.out.printf(Locale.ROOT, "[night-in-minutes] sleep: manifest v%d -> v%d in %ds%n",
            versionBeforeSleep, versionAfterSleep, sleepTookSeconds);
        Thread.sleep(10_000);  // the wake and the night's bookkeeping

        // ── The morning ──
        var morning = run(companion, did, MORNING_SECONDS, AWAKE_SECONDS + (int) sleepTookSeconds + 10);
        var last = morning.get(morning.size() - 1);
        var v = last.state().vitality();
        var d = last.state().drives();

        System.out.println("──────────── A NIGHT IN MINUTES ────────────");
        System.out.printf(Locale.ROOT, "  evening (%.1f sim h awake): rapport %.2f focus %.2f alignment %.2f confidence %.2f | affiliation %.2f care %.2f loneliness %.2f%n",
            AWAKE_SECONDS * SCALE / 3600.0, evening.rapport(), evening.focus(), evening.alignment(), evening.confidence(),
            eveningDrives.affiliation(), eveningDrives.care(), evening.loneliness());
        System.out.printf(Locale.ROOT, "  peaks over the day: affiliation %.2f care %.2f loneliness %.2f%n", peakAffiliation, peakCare, peakLoneliness);
        System.out.printf(Locale.ROOT, "  samples of the day at a ceiling (>= 0.99): rapport %d, momentum %d, of %d%n",
            day.stream().filter(s -> s.state().vitality().rapport() >= 0.99).count(),
            day.stream().filter(s -> s.state().vitality().momentum() >= 0.99).count(), day.size());
        System.out.printf(Locale.ROOT, "  the sleep: v%d -> v%d (%ds)%n", versionBeforeSleep, versionAfterSleep, sleepTookSeconds);
        System.out.printf(Locale.ROOT, "  morning: rapport %.2f focus %.2f alignment %.2f confidence %.2f momentum %.2f equanimity %.2f energy %.2f | affiliation %.2f care %.2f play %.2f vigilance %.2f%n",
            v.rapport(), v.focus(), v.alignment(), v.confidence(), v.momentum(), v.equanimity(), v.energy(),
            d.affiliation(), d.care(), d.play(), d.vigilance());
        System.out.println("────────────────────────────────────────────");

        // The felt tanks rest where her genome puts them, and none is pinned.
        assertTrue(evening.rapport() > 0.2 && evening.rapport() < 0.95,
            "rapport is pinned: emptied (0.00 live before 2026-10-10) or on its ceiling (1.00 in this test's first run, play's warmth outrunning the fade): " + evening.rapport());
        long rapportAtCeiling = day.stream().filter(s -> s.state().vitality().rapport() >= 0.99).count();
        long momentumAtCeiling = day.stream().filter(s -> s.state().vitality().momentum() >= 0.99).count();
        assertTrue(rapportAtCeiling * 2 < day.size(), "rapport sat on its ceiling for " + rapportAtCeiling + " of " + day.size() + " samples of the day");
        assertTrue(momentumAtCeiling * 2 < day.size(), "momentum sat on its ceiling for " + momentumAtCeiling + " of " + day.size() + " samples of the day");
        assertTrue(evening.focus() > 0.15, "focus sits on its 0.1 floor: the insomnia rule began ten minutes after waking: " + evening.focus());
        assertTrue(evening.alignment() > 0.15, "alignment sits on its 0.1 floor (insomnia): " + evening.alignment());
        assertTrue(evening.confidence() < 0.99, "confidence sits on its ceiling (a fixed gift per reply): " + evening.confidence());
        assertTrue(v.rapport() > 0.2 && v.rapport() < 0.95 && v.focus() > 0.15 && v.alignment() > 0.15 && v.confidence() < 0.99,
            "the morning reads pinned: rapport " + v.rapport() + " focus " + v.focus() + " alignment " + v.alignment() + " confidence " + v.confidence());
        // A day alone: the pull toward company built, loneliness grew. (Her own lines are not company.)
        assertTrue(peakAffiliation > 0.25, "the pull toward company did not build over a day alone (was pinned at 0.10 live): peak " + peakAffiliation);
        assertTrue(peakLoneliness > 0.05, "loneliness did not grow over a day alone: peak " + peakLoneliness);
        // The sleep stored her night, exactly once, and the forge is still there for the morning.
        assertTrue(versionAfterSleep == versionBeforeSleep + 1,
            "the sleep should store exactly one new manifest (v" + versionBeforeSleep + " -> v" + (versionBeforeSleep + 1) + "), got v" + versionAfterSleep);
        assertTrue(last.manifestVersion() >= versionAfterSleep, "the store went backwards: v" + last.manifestVersion());
    }

    private List<Sample> run(ActorRef<CompanionActor.Command> companion, String did, int seconds, int offset) throws Exception {
        var out = new ArrayList<Sample>();
        long start = System.currentTimeMillis();
        int t = 0;
        while (t < seconds) {
            Thread.sleep(SAMPLE_SECONDS * 1000L);
            t = (int) ((System.currentTimeMillis() - start) / 1000);
            var s = sample(companion, offset + t, did);
            out.add(s);
            row(s);
        }
        return out;
    }

    private Sample sample(ActorRef<CompanionActor.Command> companion, int realSeconds, String did) throws Exception {
        return new Sample(realSeconds, realSeconds * SCALE / 3600.0, queryState(companion), manifestVersion(did));
    }

    private static void header() {
        System.out.println("[night-in-minutes]  real   simh | rappo focus align confi momen equan energ | affil care  play  vigil lonel | v");
    }

    private static void row(Sample s) {
        var v = s.state().vitality();
        var d = s.state().drives();
        System.out.printf(Locale.ROOT, "[night-in-minutes] %5ds %5.1f | %5.2f %5.2f %5.2f %5.2f %5.2f %5.2f %5.2f | %5.2f %5.2f %5.2f %5.2f %5.2f | %d%n",
            s.realSeconds(), s.simHours(), v.rapport(), v.focus(), v.alignment(), v.confidence(), v.momentum(), v.equanimity(), v.energy(),
            d.affiliation(), d.care(), d.play(), d.vigilance(), v.loneliness(), s.manifestVersion());
    }

    private static CompanionActor.TestStateResponse queryState(ActorRef<CompanionActor.Command> companion) throws Exception {
        return AskPattern.ask(companion,
            (ActorRef<CompanionActor.TestStateResponse> ref) -> new CompanionActor.QueryTestState(ref),
            Duration.ofSeconds(10), server.system().scheduler()).toCompletableFuture().get(12, TimeUnit.SECONDS);
    }

    /** The store's head for her, read as the morning check-in reads it. */
    private static int manifestVersion(String did) throws Exception {
        if (did == null) return -1;
        try (var conn = DriverManager.getConnection(server.jdbcUrl());
             var st = conn.prepareStatement("select coalesce(max(version), 0) from soul_manifests where did = ?")) {
            st.setString(1, did);
            try (var rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
