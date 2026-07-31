package org.wyrdsekai.scripting.sandbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceLimitsTest {

    @Test void default_limits() {
        var limits = ResourceLimits.DEFAULT;
        assertThat(limits.statementLimit()).isEqualTo(10_000);
        assertThat(limits.cpuTimeoutMs()).isEqualTo(5_000);
        assertThat(limits.heapLimitBytes()).isEqualTo(16_777_216);
        assertThat(limits.stackDepthLimit()).isEqualTo(100);
    }

    @Test void trusted_limits_higher() {
        var limits = ResourceLimits.TRUSTED;
        assertThat(limits.statementLimit()).isGreaterThan(ResourceLimits.DEFAULT.statementLimit());
        assertThat(limits.cpuTimeoutMs()).isGreaterThan(ResourceLimits.DEFAULT.cpuTimeoutMs());
    }

    @Test void strict_limits_lower() {
        var limits = ResourceLimits.STRICT;
        assertThat(limits.statementLimit()).isLessThan(ResourceLimits.DEFAULT.statementLimit());
        assertThat(limits.cpuTimeoutMs()).isLessThan(ResourceLimits.DEFAULT.cpuTimeoutMs());
    }

    @Test void unlimited_has_no_limits() {
        var limits = ResourceLimits.UNLIMITED;
        assertThat(limits.hasStatementLimit()).isFalse();
        assertThat(limits.hasCpuTimeout()).isFalse();
        assertThat(limits.hasHeapLimit()).isFalse();
    }

    @Test void has_checks() {
        assertThat(ResourceLimits.DEFAULT.hasStatementLimit()).isTrue();
        assertThat(ResourceLimits.DEFAULT.hasCpuTimeout()).isTrue();
        assertThat(ResourceLimits.DEFAULT.hasHeapLimit()).isTrue();
    }

    @Test void describe_includes_values() {
        var desc = ResourceLimits.DEFAULT.describe();
        assertThat(desc).contains("10000");
        assertThat(desc).contains("5000ms");
        assertThat(desc).contains("KB");
    }

    @Test void describe_unlimited() {
        var desc = ResourceLimits.UNLIMITED.describe();
        assertThat(desc).contains("unlimited");
    }

    @Test void custom_limits() {
        var limits = new ResourceLimits(500, 1000, 4096, 25);
        assertThat(limits.statementLimit()).isEqualTo(500);
        assertThat(limits.cpuTimeoutMs()).isEqualTo(1000);
        assertThat(limits.heapLimitBytes()).isEqualTo(4096);
        assertThat(limits.stackDepthLimit()).isEqualTo(25);
    }
}
