package org.wyrdsekai.core.agent.interiority;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** A tank at its own settle point is settled; what pulls is the excursion above it. */
class DrivePullTest {

    private static final Map<String, Double> SETTLE = Map.of("Loneliness", 0.80, "Saudade", 0.80, "Amae", 0.75);

    @Test
    @DisplayName("alone at rest, loneliness and saudade sit under the act threshold instead of over it")
    void restIsNotPull() {
        var raw = new LinkedHashMap<String, Double>();
        raw.put("Loneliness", 0.80); raw.put("Saudade", 0.80); raw.put("Curiosity", 0.35); raw.put("Care", 0.4);
        var pulls = DrivePull.levels(raw, 0.7, SETTLE);
        assertThat(pulls.get("Loneliness")).isLessThan(0.7);
        assertThat(pulls.get("Saudade")).isLessThan(0.7);
        assertThat(WantActBridge.dominantPull(pulls)).isLessThan(WantActBridge.ACT_THRESHOLD);
        assertThat(WantActBridge.decide(null, "be with someone", pulls, WantActBridge.HEURISTIC,
            new RelationalAffordance.Presence(false, false, true)).isDefer())
            .as("the machine forces no relational act at rest").isTrue();
    }

    @Test
    @DisplayName("a real excursion above her resting point pulls, and by how far it is over")
    void excursionPulls() {
        var raw = Map.of("Loneliness", 0.95, "Curiosity", 0.2);
        var pulls = DrivePull.levels(raw, 0.7, SETTLE);
        assertThat(pulls.get("Loneliness")).isCloseTo(0.75, within(1e-9));
        assertThat(WantActBridge.dominantDriveKey(pulls)).isEqualTo("Loneliness");
        assertThat(DrivePull.of("Loneliness", 0.90, 0.7, SETTLE)).isCloseTo(0.70, within(1e-9));
        assertThat(DrivePull.of("Loneliness", 0.89, 0.7, SETTLE)).isLessThan(0.70);
    }

    @Test
    @DisplayName("event drives have no settle point and pull by their level")
    void eventDrivesUnchanged() {
        assertThat(DrivePull.of("Curiosity", 0.8, 0.7, SETTLE)).isEqualTo(0.8);
        assertThat(DrivePull.of("Frustration", 0.72, 0.7, SETTLE)).isEqualTo(0.72);
        var raw = Map.of("Curiosity", 0.8, "Saudade", 0.8);
        assertThat(WantActBridge.dominantDriveKey(DrivePull.levels(raw, 0.7, SETTLE))).isEqualTo("Curiosity");
    }

    @Test
    @DisplayName("her temperament moves the resting point, and rest is still rest")
    void temperamentScales() {
        var settle = Map.of("Saudade", 0.96);   // a temperament that feels it acutely
        assertThat(DrivePull.of("Saudade", 0.96, 0.7, settle)).isLessThan(0.7);
        assertThat(DrivePull.of("Saudade", 1.0, 0.7, settle)).isLessThan(0.7);
        var low = Map.of("Saudade", 0.50);       // an introvert
        assertThat(DrivePull.of("Saudade", 0.65, 0.7, low)).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("no settle points means level is pull, as before")
    void withoutSettlePoints() {
        var raw = Map.of("Loneliness", 0.8);
        assertThat(DrivePull.levels(raw, 0.7, null)).isSameAs(raw);
        assertThat(DrivePull.levels(null, 0.7, SETTLE)).isNull();
    }

    @Test
    @DisplayName("the rule floor seeds no relational want at rest, and does when it pulls")
    void ruleFloorReadsPull() {
        var atRest = DrivePull.levels(Map.of("Loneliness", 0.80, "Saudade", 0.80), 0.7, SETTLE);
        assertThat(DriveWantMapper.orient(atRest, 0.8, 0.7)).isEmpty();
        var pulling = DrivePull.levels(Map.of("Loneliness", 0.95), 0.7, SETTLE);
        assertThat(DriveWantMapper.orient(pulling, 0.8, 0.7))
            .anySatisfy(c -> assertThat(c.driveResonance()).contains("Loneliness"));
    }

    @Test
    @DisplayName("the cadence reads pull: rest keeps the base interval, a level would have shortened it")
    void cadenceReadsPull() {
        var raw = Map.of("Saudade", 0.80, "Curiosity", 0.2);
        var asLevel = CadenceModulator.nextDelay(java.time.Duration.ofMinutes(30), raw, 0.7, 1.0, 0, 0.0);
        var asPull = CadenceModulator.nextDelay(java.time.Duration.ofMinutes(30),
            DrivePull.levels(raw, 0.7, SETTLE), 0.7, 1.0, 0, 0.0);
        assertThat(asLevel).isLessThan(java.time.Duration.ofMinutes(27));
        assertThat(asPull).isEqualTo(java.time.Duration.ofMinutes(30));
    }
}
