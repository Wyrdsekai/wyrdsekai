package org.wyrdsekai.core.observability;

import java.time.Instant;
import java.util.*;

/**
 * CodeZaiku ER bridge (§105.6).
 * Connects Wyrdsekai ER to CodeZaiku's richer diagnostic infrastructure
 * when available. Standalone mode when not linked.
 */
public class ErBridge {

    /** Bridge status. */
    public record BridgeStatus(
        boolean linked,
        Instant linkedSince,
        String codezaikuFqdn,
        List<String> availableCapabilities
    ) {}

    /** Infrastructure alert from CodeZaiku. */
    public record InfraAlert(
        String alertId,
        AlertSeverity severity,
        String component,
        String message,
        Instant occurredAt,
        boolean acknowledged
    ) {}

    public enum AlertSeverity {
        INFO, WARNING, CRITICAL, FATAL
    }

    /** Enriched vitality data when CodeZaiku bridge is active. */
    public record EnrichedVitality(
        String tankName,
        double value,
        double velocity,
        double acceleration,
        Double predictedCriticalAt
    ) {}

    private BridgeStatus status;
    private final List<InfraAlert> alerts = new ArrayList<>();
    private int nextId = 1;

    public ErBridge() {
        this.status = new BridgeStatus(false, null, null, List.of());
    }

    /** Link to CodeZaiku ER. */
    public BridgeStatus link(String codezaikuFqdn, List<String> capabilities) {
        this.status = new BridgeStatus(true, Instant.now(), codezaikuFqdn,
            capabilities != null ? List.copyOf(capabilities) : List.of());
        return this.status;
    }

    /** Unlink from CodeZaiku. */
    public BridgeStatus unlink() {
        this.status = new BridgeStatus(false, null, null, List.of());
        return this.status;
    }

    /** Receive infrastructure alert from CodeZaiku. */
    public InfraAlert receiveAlert(AlertSeverity severity, String component, String message) {
        if (!status.linked()) return null;
        var alert = new InfraAlert("infra-" + nextId++, severity, component,
            message, Instant.now(), false);
        alerts.add(alert);
        return alert;
    }

    /** Acknowledge an alert. */
    public InfraAlert acknowledge(String alertId) {
        for (int i = 0; i < alerts.size(); i++) {
            var alert = alerts.get(i);
            if (alert.alertId().equals(alertId)) {
                var acked = new InfraAlert(alert.alertId(), alert.severity(),
                    alert.component(), alert.message(), alert.occurredAt(), true);
                alerts.set(i, acked);
                return acked;
            }
        }
        return null;
    }

    /** Translate CodeZaiku infra alert into agent-relevant vitality effect. */
    public Map<String, Double> translateToVitalityEffect(InfraAlert alert) {
        return switch (alert.severity()) {
            case FATAL -> Map.of("energy", -0.5, "focus", -0.3, "error_pressure", 0.8);
            case CRITICAL -> Map.of("energy", -0.3, "focus", -0.2, "error_pressure", 0.5);
            case WARNING -> Map.of("energy", -0.1, "error_pressure", 0.2);
            case INFO -> Map.of();
        };
    }

    /** Enrich a simple tank value with CodeZaiku derivative data. */
    public EnrichedVitality enrich(String tankName, double value,
                                    double velocity, double acceleration) {
        if (!status.linked()) {
            return new EnrichedVitality(tankName, value, 0.0, 0.0, null);
        }
        Double predictedCritical = null;
        if (velocity < 0 && value > 0) {
            predictedCritical = value / Math.abs(velocity);
        }
        return new EnrichedVitality(tankName, value, velocity, acceleration, predictedCritical);
    }

    /** Get unacknowledged critical+ alerts. */
    public List<InfraAlert> criticalAlerts() {
        return alerts.stream()
            .filter(a -> !a.acknowledged())
            .filter(a -> a.severity() == AlertSeverity.CRITICAL
                      || a.severity() == AlertSeverity.FATAL)
            .toList();
    }

    public BridgeStatus status() { return status; }
    public boolean isLinked() { return status.linked(); }
    public int alertCount() { return alerts.size(); }
}
