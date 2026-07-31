package org.wyrdsekai.core.substrate;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Ring buffer of training traces for CfC consolidation during Forge sleep.
 *
 * <p>Each vitality tick records the before/after state + input events + deltaTime.
 * These traces are replayed during sleep to train the CfC via full backprop.
 *
 * <p>Capacity: 24h at 1s ticks = 86,400 entries. At ~64 bytes per entry (16 floats
 * before + 16 floats after + 8 floats events + 1 float deltaTime = 41 floats × 4 bytes
 * + overhead) ≈ 5.5MB peak. Flushed after consolidation.
 */
public class TrainingTrace {

    /** A single training sample: state transition with timing. */
    public record Sample(
        float[] tanksBefore,    // 10 values: vitality state before tick (8 original + integrity + disgust)
        float[] drivesBefore,   // 8 values: drive state before tick
        float[] eventVector,    // 10 values: input events (Oracle, interactions, environment)
        float deltaTime,        // seconds since last tick
        float[] tanksAfter,     // 10 values: vitality state after tick
        float[] drivesAfter,    // 8 values: drive state after tick
        long timestampMs        // wall clock for ordering
    ) {
        /** Concatenated input vector: [tanksBefore, drivesBefore, eventVector]. Dynamic size. */
        public float[] input() {
            int tLen = tanksBefore.length;
            int dLen = drivesBefore.length;
            int eLen = eventVector.length;
            float[] in = new float[tLen + dLen + eLen];
            System.arraycopy(tanksBefore, 0, in, 0, tLen);
            System.arraycopy(drivesBefore, 0, in, tLen, dLen);
            System.arraycopy(eventVector, 0, in, tLen + dLen, eLen);
            return in;
        }

        /** Concatenated target vector: [tanksAfter, drivesAfter]. Dynamic size. */
        public float[] target() {
            int tLen = tanksAfter.length;
            int dLen = drivesAfter.length;
            float[] t = new float[tLen + dLen];
            System.arraycopy(tanksAfter, 0, t, 0, tLen);
            System.arraycopy(drivesAfter, 0, t, tLen, dLen);
            return t;
        }
    }

    private static final int DEFAULT_CAPACITY = 86_400; // 24 hours at 1s ticks

    private final Deque<Sample> buffer;
    private final int capacity;

    public TrainingTrace() {
        this(DEFAULT_CAPACITY);
    }

    public TrainingTrace(int capacity) {
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(Math.min(capacity, 8192)); // initial allocation
    }

    /**
     * Record a training sample.
     * Called from CompanionActor.onVitalityTick() after drives and tanks are updated.
     */
    public void record(float[] tanksBefore, float[] drivesBefore, float[] eventVector,
                       float deltaTime, float[] tanksAfter, float[] drivesAfter) {
        var sample = new Sample(
            tanksBefore.clone(), drivesBefore.clone(), eventVector.clone(),
            deltaTime,
            tanksAfter.clone(), drivesAfter.clone(),
            System.currentTimeMillis()
        );
        synchronized (buffer) {
            if (buffer.size() >= capacity) {
                buffer.removeFirst(); // evict oldest
            }
            buffer.addLast(sample);
        }
    }

    /**
     * Convenience: record from double arrays (convert to float).
     */
    public void record(double[] tanksBefore, double[] drivesBefore, double[] eventVector,
                       double deltaTime, double[] tanksAfter, double[] drivesAfter) {
        record(toFloat(tanksBefore), toFloat(drivesBefore), toFloat(eventVector),
               (float) deltaTime, toFloat(tanksAfter), toFloat(drivesAfter));
    }

    /** Get all traces as an immutable list. Typically called during Forge consolidation. */
    public List<Sample> getAll() {
        synchronized (buffer) {
            return List.copyOf(buffer);
        }
    }

    /** Flush all traces (after consolidation). */
    public void clear() {
        synchronized (buffer) {
            buffer.clear();
        }
    }

    /** Number of recorded samples. */
    public int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    /** Whether the buffer has enough data for meaningful training. */
    public boolean hasMinimumTraces(int minimum) {
        return size() >= minimum;
    }

    /** Estimated memory usage in bytes. */
    public long estimatedMemoryBytes() {
        // Each sample: 41 floats × 4 bytes + overhead (~32 bytes object header + refs)
        return (long) size() * (41 * 4 + 64);
    }

    private static float[] toFloat(double[] d) {
        float[] f = new float[d.length];
        for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
        return f;
    }
}
