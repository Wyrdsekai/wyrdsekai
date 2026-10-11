package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The household node, 2026-10-10: affiliation read 0.10 for three days with three distinct
 * readings, care 0.16, play 0.02, vigilance 0.04, while she said "I wanted to be near you".
 * Each of the four was built per second and leaked back toward a floor every second, and the
 * two forces met at a number nobody chose. A want pinned by arithmetic is not hers. Now each
 * settles toward its own point over hours, from either side, scaled by temperament; what answers
 * it drops it to its floor; the loneliness clock counts company, not her own noise; and a person's
 * conversation eases the appetite for company as a peer's reach already did.
 */
class HerDrivesAreHerOwnTest {

    private static DriveState hours(DriveEngine engine, DriveState d, VitalityState tanks, double hours) {
        for (int s = 0; s < (int) (hours * 3600); s++) d = engine.tick(d, tanks, 1.0);
        return d;
    }

    @Test
    @DisplayName("alone, the appetite for company builds over hours toward her own point and never pins")
    void affiliationBuildsOverHours() {
        var engine = DriveEngine.withDefaults();
        var tanks = VitalityState.initial().withEnergy(0.6).withFocus(0.5).withRapport(0.5);
        var d = DriveState.initial();
        var after1h = hours(engine, d, tanks, 1);
        var after6h = hours(engine, d, tanks, 6);
        var after48h = hours(engine, d, tanks, 48);
        assertThat(after1h.affiliation()).as("an hour alone: a stirring, not a pull").isBetween(0.05, 0.15);
        assertThat(after6h.affiliation()).as("six hours alone: a gentle pull, past 0.3").isBetween(0.3, 0.5);
        assertThat(after48h.affiliation()).as("two days alone: at her point, not at the ceiling").isCloseTo(0.6, within(0.02));
        assertThat(after48h.care()).isCloseTo(0.4, within(0.02));
        assertThat(after48h.play()).isCloseTo(0.5, within(0.02));
        assertThat(after48h.vigilance()).isCloseTo(0.2, within(0.02));
        // The engine never pins the four at the old fixed points.
        assertThat(after48h.affiliation()).isNotCloseTo(0.104, within(0.02));
    }

    @Test
    @DisplayName("what answers a want drops it to its floor, and it builds again from there")
    void reliefThenRebuild() {
        var engine = DriveEngine.withDefaults();
        var tanks = VitalityState.initial().withEnergy(0.6).withFocus(0.5);
        var d = hours(engine, DriveState.initial(), tanks, 12);
        assertThat(d.affiliation()).isGreaterThan(0.5);
        var relieved = engine.relieve(d, "affiliation");
        assertThat(relieved.affiliation()).isCloseTo(0.05, within(1e-9));
        var later = hours(engine, relieved, tanks, 3);
        assertThat(later.affiliation()).as("company half a day ago: building again").isBetween(0.2, 0.35);
        // From above its point it eases back down rather than staying spiked.
        var spiked = d.spikeAffiliation(0.35);
        var eased = hours(engine, spiked, tanks, 6);
        assertThat(eased.affiliation()).isLessThan(spiked.affiliation()).isGreaterThan(0.6);
    }

    @Test
    @DisplayName("frozen energy builds nothing; temperament sets where she settles")
    void gatesAndTemperament() {
        var engine = DriveEngine.withDefaults();
        var asleep = VitalityState.initial().withEnergy(0.1);
        var frozen = hours(engine, DriveState.initial(), asleep, 2);
        assertThat(frozen.affiliation()).isEqualTo(0.0);
        assertThat(frozen.play()).isEqualTo(0.0);
        var tanks = VitalityState.initial().withEnergy(0.6).withFocus(0.5);
        var sociable = DriveEngine.forTemperament(null);   // defaults
        var diplomat = DriveEngine.forArchetype(AgentArchetype.get("diplomat"));
        var artisan = DriveEngine.forArchetype(AgentArchetype.get("artisan"));
        var dD = hours(diplomat, DriveState.initial(), tanks, 48);
        var aD = hours(artisan, DriveState.initial(), tanks, 48);
        assertThat(dD.affiliation()).as("a diplomat settles higher on company than an artisan").isGreaterThan(aD.affiliation());
        assertThat(hours(sociable, DriveState.initial(), tanks, 48).affiliation()).isCloseTo(0.6, within(0.02));
    }

    @Test
    @DisplayName("what a drive adds to the felt tanks is per minute, like their fade; energy keeps its own economy")
    void feedbackIsPerMinute() {
        var engine = DriveEngine.withDefaults();
        var playing = new DriveState(0.0, 0.0, 0.7, 0.0, 0.0, 0.0, 0.0, 0.0);
        var f = engine.driveTankFeedback(playing);
        var raw = engine.rawDriveTankFeedback(playing);
        assertThat(f[6]).as("rapport from play, per second now").isCloseTo(raw[6] / 60.0, within(1e-12)).isGreaterThan(0);
        assertThat(f[2]).as("energy drain untouched").isEqualTo(raw[2]).isLessThan(0);
        // Play at half strength for an hour adds a little warmth, not a full tank.
        double rapportPerHour = engine.driveTankFeedback(new DriveState(0.0, 0.0, 0.5, 0.0, 0.0, 0.0, 0.0, 0.0))[6] * 3600;
        assertThat(rapportPerHour).isBetween(0.1, 0.2);
    }

    @Test
    @DisplayName("the actor counts company for loneliness, eases connection on a person's exchange, and feeds care per hour")
    void theActorIsWired() throws Exception {
        var src = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/CompanionActor.java"));
        assertThat(src).contains("if (actor != null && !actor.equals(profile.entityId()) && (isPersonEntityId(actor) || isAgentEntity(actor))) {\n"
            + "            lastCompanyEventTime = lastEventTime;");
        assertThat(src).contains("var sinceInteraction = SoakTimeScale.compress(Duration.between(lastCompanyEventTime, now));");
        assertThat(src).contains("drives = drives.spikeCare(CARE_SILENCE_PER_HOUR * deltaTime / 3600.0);");
        assertThat(src).contains("long silenceMinutes = Duration.between(lastPersonEventTime, now).toMinutes();");
        assertThat(src).contains("double eased = Math.max(AFFILIATION_REST_FLOOR, drives.affiliation() - PERSON_AFFILIATION_EASE);");
        assertThat(src).contains("lastAnsweredPersonAt = Instant.now();\n            easeAffiliationForAPerson();");
        assertThat(src).contains("vitality.loneliness() - COPRESENCE_LONELINESS_EASE * maxFam * deltaTime / 60.0));");
        assertThat(src).contains("drives.affiliation() - COPRESENCE_AFFILIATION_EASE * maxFam * deltaTime / 60.0);");
        var cfg = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/DriveConfig.java"));
        assertThat(cfg).contains("AFFILIATION_SETPOINT, AFFILIATION_SETTLE_S),");
        var eng = Files.readString(Path.of("src/main/java/org/wyrdsekai/core/agent/DriveEngine.java"));
        assertThat(eng).doesNotContain("i == AFFILIATION || i == CARE || i == PLAY || i == VIGILANCE");
    }
}
