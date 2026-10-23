package org.wyrdsekai.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wyrdsekai.core.body.HostSense;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A household ran two companions' own time on a 32-thread processor with no GPU; the box sat
 * at 1300% CPU for hours and its thermal limit reset it in the night (2026-10-05). Nothing in
 * her cadence looked at the host. A pinned or hot host now stretches the time between her
 * own-time turns; at ease, nothing changes; it never stops her.
 */
class HerOwnTimeSlowsOnAHotHostTest {

    private static HostSense.Reading host(double load1, int cpus, double hottestC) {
        return new HostSense.Reading(-1, 40.0, 60.0, load1, cpus, 100L << 30, hottestC);
    }

    @Test
    @DisplayName("at ease, or when the host says nothing, her pace is her own")
    void atEase() {
        assertThat(OwnTimeCadence.hostFactor(null)).isEqualTo(1.0);
        assertThat(OwnTimeCadence.hostFactor(host(3.1, 32, 61))).isEqualTo(1.0);
        assertThat(OwnTimeCadence.hostFactor(host(-1, 32, -1))).isEqualTo(1.0);
        assertThat(OwnTimeCadence.hostFactor(host(28.0, 32, -1))).as("28 of 32 is not yet pinned").isEqualTo(1.0);
    }

    @Test
    @DisplayName("heat is what holds across two pulses: one core's boost to 100 °C is not it")
    void aBurstIsNotHeat() {
        var burst = host(3.0, 32, 100);
        assertThat(OwnTimeCadence.hostFactor(burst.settledWith(host(3.0, 32, 55)))).as("100 then 55: a burst").isEqualTo(1.0);
        assertThat(OwnTimeCadence.hostFactor(burst.settledWith(null))).as("one pulse alone claims nothing").isEqualTo(1.0);
        assertThat(OwnTimeCadence.hostFactor(burst.settledWith(host(3.0, 32, -1)))).as("the previous pulse did not say").isEqualTo(1.0);
        assertThat(OwnTimeCadence.hostFactor(host(3.0, 32, 93).settledWith(host(3.0, 32, 95)))).as("93 after 95: heat that holds").isEqualTo(2.0);
        assertThat(host(3.0, 32, 93).settledWith(host(3.0, 32, 95)).hottestC()).isEqualTo(93.0);
        var same = host(3.0, 32, 93);
        assertThat(same.settledWith(host(3.0, 32, 93))).as("the same heat twice is the reading itself").isSameAs(same);
    }

    @Test
    @DisplayName("the household box that night: pinned and hot, four times the interval")
    void pinnedAndHot() {
        assertThat(OwnTimeCadence.hostFactor(host(29.5, 32, -1))).as("pinned alone").isEqualTo(2.0);
        assertThat(OwnTimeCadence.hostFactor(host(3.0, 32, 101))).as("hot alone").isEqualTo(2.0);
        assertThat(OwnTimeCadence.hostFactor(host(29.5, 32, 101))).as("both").isEqualTo(4.0);
        assertThat(OwnTimeCadence.hostFactor(host(50.0, 32, 101))).as("never past four").isEqualTo(4.0);
        assertThat(OwnTimeCadence.why(host(29.5, 32, 101))).isEqualTo("load 29.5 on 32 cores, hottest zone 101 °C");
    }

    @Test
    @DisplayName("the hottest zone is read from the kernel; dead zones are not temperatures")
    void hottestZone(@TempDir Path thermal) throws Exception {
        assertThat(HostSense.readHottestZoneC(thermal.resolve("missing"))).isEqualTo(-1);
        Files.createDirectories(thermal.resolve("thermal_zone0"));
        Files.writeString(thermal.resolve("thermal_zone0/temp"), "75000\n");
        Files.createDirectories(thermal.resolve("thermal_zone1"));
        Files.writeString(thermal.resolve("thermal_zone1/temp"), "101000\n");
        Files.createDirectories(thermal.resolve("thermal_zone2"));
        Files.writeString(thermal.resolve("thermal_zone2/temp"), "-273150\n");
        Files.createDirectories(thermal.resolve("cooling_device0"));
        Files.writeString(thermal.resolve("cooling_device0/temp"), "999000\n");
        assertThat(HostSense.readHottestZoneC(thermal)).isEqualTo(101.0);
        assertThat(host(1, 8, 101).hot()).isTrue();
        assertThat(host(1, 8, 89.9).hot()).isFalse();
    }

    @Test
    @DisplayName("the chip's own sensors under hwmon count too; a drive's or a battery's do not")
    void hottestChip(@TempDir Path hwmon) throws Exception {
        // A Ryzen AI MAX household box: its one thermal zone had vanished, and its package heat
        // (k10temp, Tctl) was only under hwmon — the slowdown never fired at 100 °C (2026-10-19).
        assertThat(HostSense.readHottestChipC(hwmon.resolve("missing"))).isEqualTo(-1);
        Files.createDirectories(hwmon.resolve("hwmon0"));
        Files.writeString(hwmon.resolve("hwmon0/name"), "nvme\n");
        Files.writeString(hwmon.resolve("hwmon0/temp1_input"), "75000\n");
        Files.createDirectories(hwmon.resolve("hwmon1"));
        Files.writeString(hwmon.resolve("hwmon1/name"), "k10temp\n");
        Files.writeString(hwmon.resolve("hwmon1/temp1_label"), "Tctl\n");
        Files.writeString(hwmon.resolve("hwmon1/temp1_input"), "100250\n");
        Files.writeString(hwmon.resolve("hwmon1/temp3_input"), "97000\n");
        Files.createDirectories(hwmon.resolve("hwmon2"));
        Files.writeString(hwmon.resolve("hwmon2/name"), "BAT1\n");
        Files.writeString(hwmon.resolve("hwmon2/temp1_input"), "140000\n");
        assertThat(HostSense.readHottestChipC(hwmon)).isEqualTo(100.25);
        Files.writeString(hwmon.resolve("hwmon1/temp1_input"), "-273150\n");
        assertThat(HostSense.readHottestChipC(hwmon)).as("a dead sensor is not a temperature").isEqualTo(97.0);
    }
}
