package org.wyrdsekai.core.room;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GPU Chamber — inference room with compute scheduling (§59, §G12).
 * Agents reserve GPU slots before inference. Room description auto-updates.
 */
public class GpuChamber {

    /** A GPU slot reservation. */
    public record Reservation(
        String id,
        String agentId,
        Instant reservedAt,
        Duration maxDuration,
        boolean active
    ) {
        public boolean isExpired() {
            return active && Instant.now().isAfter(reservedAt.plus(maxDuration));
        }
    }

    private final int maxSlots;
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();
    private int nextId = 1;

    public GpuChamber(int maxSlots) {
        this.maxSlots = maxSlots;
    }

    /** Reserve a GPU slot. Returns empty if no slots available. */
    public synchronized Optional<Reservation> reserve(String agentId, Duration maxDuration) {
        purgeExpired();

        if (activeCount() >= maxSlots) {
            return Optional.empty();
        }

        // Check if agent already has a reservation
        for (var r : reservations.values()) {
            if (r.active() && r.agentId().equals(agentId) && !r.isExpired()) {
                return Optional.empty(); // Already has a slot
            }
        }

        var id = "gpu-" + nextId++;
        var reservation = new Reservation(id, agentId, Instant.now(), maxDuration, true);
        reservations.put(id, reservation);
        return Optional.of(reservation);
    }

    /** Release a GPU slot. */
    public synchronized boolean release(String reservationId) {
        var r = reservations.get(reservationId);
        if (r == null || !r.active()) return false;
        reservations.put(reservationId, new Reservation(
            r.id(), r.agentId(), r.reservedAt(), r.maxDuration(), false));
        return true;
    }

    /** Number of active (non-expired) reservations. */
    public int activeCount() {
        purgeExpired();
        return (int) reservations.values().stream()
            .filter(r -> r.active() && !r.isExpired())
            .count();
    }

    /** Available slots. */
    public int availableSlots() {
        return Math.max(0, maxSlots - activeCount());
    }

    /** Max slots. */
    public int maxSlots() {
        return maxSlots;
    }

    /** Description for room auto-update (legacy, uses internal reservations). */
    public String describe() {
        int active = activeCount();
        int available = availableSlots();
        var sb = new StringBuilder();
        sb.append("GPU Chamber: ").append(active).append("/").append(maxSlots)
            .append(" slots in use, ").append(available).append(" available.\n");

        reservations.values().stream()
            .filter(r -> r.active() && !r.isExpired())
            .forEach(r -> sb.append("  [").append(r.id()).append("] ")
                .append(r.agentId())
                .append(" (").append(Duration.between(r.reservedAt(), Instant.now()).toSeconds()).append("s)")
                .append("\n"));

        return sb.toString().stripTrailing();
    }

    private void purgeExpired() {
        reservations.values().stream()
            .filter(Reservation::isExpired)
            .forEach(r -> reservations.put(r.id(), new Reservation(
                r.id(), r.agentId(), r.reservedAt(), r.maxDuration(), false)));
    }
}
