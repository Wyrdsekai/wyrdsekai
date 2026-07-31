package org.wyrdsekai.core.resilience;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DegradationManagerTest {

    @Test
    void normalAtLowLoad() {
        var dm = new DegradationManager();
        dm.evaluate(30.0, 40.0, 0);
        assertEquals(DegradationManager.Level.NORMAL, dm.getLevel());
    }

    @Test
    void highLoadAt80Percent() {
        var dm = new DegradationManager();
        dm.evaluate(80.0, 50.0, 0);
        assertEquals(DegradationManager.Level.HIGH_LOAD, dm.getLevel());
    }

    @Test
    void overloadedAt90Percent() {
        var dm = new DegradationManager();
        dm.evaluate(90.0, 50.0, 0);
        assertEquals(DegradationManager.Level.OVERLOADED, dm.getLevel());
    }

    @Test
    void criticalAt95Percent() {
        var dm = new DegradationManager();
        dm.evaluate(95.0, 50.0, 0);
        assertEquals(DegradationManager.Level.CRITICAL, dm.getLevel());
    }

    @Test
    void emergencyAbove97() {
        var dm = new DegradationManager();
        // Emergency threshold = critical(95) + (100-95)/2 = 97.5
        dm.evaluate(98.0, 50.0, 0);
        assertEquals(DegradationManager.Level.EMERGENCY, dm.getLevel());
    }

    @Test
    void inferenceQueueAffectsLevel() {
        var dm = new DegradationManager();

        dm.evaluate(30.0, 30.0, 20);
        assertEquals(DegradationManager.Level.HIGH_LOAD, dm.getLevel());

        dm.evaluate(30.0, 30.0, 35);
        assertEquals(DegradationManager.Level.OVERLOADED, dm.getLevel());

        dm.evaluate(30.0, 30.0, 55);
        assertEquals(DegradationManager.Level.CRITICAL, dm.getLevel());

        dm.evaluate(30.0, 30.0, 105);
        assertEquals(DegradationManager.Level.EMERGENCY, dm.getLevel());
    }

    @Test
    void shouldProcessMethods_respectLevel() {
        var dm = new DegradationManager();

        dm.setLevel(DegradationManager.Level.NORMAL);
        assertTrue(dm.shouldProcessAutonomy());
        assertTrue(dm.shouldProcessInference());
        assertTrue(dm.shouldAcceptConnections());
        assertTrue(dm.shouldPublishAmbient());

        dm.setLevel(DegradationManager.Level.HIGH_LOAD);
        assertTrue(dm.shouldProcessAutonomy());
        assertTrue(dm.shouldProcessInference());
        assertTrue(dm.shouldAcceptConnections());
        assertFalse(dm.shouldPublishAmbient());

        dm.setLevel(DegradationManager.Level.OVERLOADED);
        assertFalse(dm.shouldProcessAutonomy());
        assertTrue(dm.shouldProcessInference());
        assertTrue(dm.shouldAcceptConnections());
        assertFalse(dm.shouldPublishAmbient());

        dm.setLevel(DegradationManager.Level.CRITICAL);
        assertFalse(dm.shouldProcessAutonomy());
        assertFalse(dm.shouldProcessInference());
        assertTrue(dm.shouldAcceptConnections());
        assertFalse(dm.shouldPublishAmbient());

        dm.setLevel(DegradationManager.Level.EMERGENCY);
        assertFalse(dm.shouldProcessAutonomy());
        assertFalse(dm.shouldProcessInference());
        assertFalse(dm.shouldAcceptConnections());
        assertFalse(dm.shouldPublishAmbient());
    }

    @Test
    void listenerNotifiedOnLevelChange() {
        var dm = new DegradationManager();
        var notified = new AtomicReference<DegradationManager.Level>();
        dm.addListener(notified::set);

        dm.evaluate(91.0, 50.0, 0);
        assertEquals(DegradationManager.Level.OVERLOADED, notified.get());
    }

    @Test
    void singletonPattern() {
        DegradationManager.init();
        assertNotNull(DegradationManager.get());
    }

    @Test
    void heapPressureAffectsLevel() {
        var dm = new DegradationManager();
        // Emergency threshold for heap = critical(95) + (100-95)/2 = 97.5
        dm.evaluate(30.0, 98.0, 0);
        assertEquals(DegradationManager.Level.EMERGENCY, dm.getLevel());
    }
}
