package org.wyrdsekai.server.hermod;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wyrdsekai.hermod.Capability;
import org.wyrdsekai.hermod.GossipTransport;
import org.wyrdsekai.hermod.Mesh;
import org.wyrdsekai.hermod.TaskEnvelope;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The zone stands in for a phone: gossips its heartbeats under the
 * AUTHENTICATED identity, forwards knocks down its channel, and turns
 * silence or absence into declines — never into a failed errand.
 */
class APhoneSpeaksThroughItsProxyDoorTest {

    static final Instant NOW = Instant.parse("2026-08-14T12:00:00Z");

    List<Capability> gossiped;
    Map<String, Mesh.DoorProtocol> servedDoors;
    List<String> sentToPhone;
    PhoneDoorProxy proxy;

    @BeforeEach
    void setUp() {
        gossiped = new ArrayList<>();
        servedDoors = new HashMap<>();
        // Written from the proxy-door thread while the test thread polls.
        sentToPhone = new CopyOnWriteArrayList<>();
        proxy = new PhoneDoorProxy(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMillis(300));
        proxy.attach(new GossipTransport() {
            @Override
            public void publish(Capability c) {
                gossiped.add(c);
            }

            @Override
            public void subscribe(Consumer<Capability> onCapability) {
            }
        }, servedDoors::put, "hh1");
    }

    TaskEnvelope envelope() {
        return new TaskEnvelope("env-1", "hh1", "origin-node", "inference.chat",
            "none", "llm.phone-npu", Map.of("prompt", "hi"), 256,
            NOW, NOW.plusSeconds(60), Optional.empty(), new byte[]{1});
    }

    @Test
    void heartbeatIsGossipedUnderTheAuthenticatedIdentity() {
        proxy.connected("phone-7", sentToPhone::add);
        proxy.message("phone-7", """
            {"type":"heartbeat","capabilityClass":"llm.phone-npu",
             "models":["lfm2-8b-a1b"],"residentDataDomains":["photos"],
             "charging":true,"idle":true,"loadFactor":0.1,
             "deviceId":"someone-else","householdId":"hh-other"}""");
        assertEquals(1, gossiped.size());
        var cap = gossiped.get(0);
        // Stamped from the pairing record, not from anything the phone claims.
        assertEquals("phone-7", cap.deviceId());
        assertEquals("hh1", cap.householdId());
        assertEquals("llm.phone-npu", cap.capabilityClass());
        assertEquals(List.of("photos"), cap.residentDataDomains());
        assertEquals(NOW, cap.advertisedAt());
    }

    @Test
    void aKnockRidesTheChannelAndItsAnswerComesBack() throws Exception {
        proxy.connected("phone-7", sentToPhone::add);
        var door = servedDoors.get("phone-7");
        assertNotNull(door, "connecting serves the phone's door subject");

        var outcome = CompletableFuture.supplyAsync(() -> door.offer(envelope()));
        // The knock appears on the phone's channel (after the hello).
        for (int i = 0; i < 50 && sentToPhone.size() < 2; i++) {
            Thread.sleep(10);
        }
        var knock = sentToPhone.stream().filter(m -> m.contains("\"knock\"")).findFirst().orElseThrow();
        assertTrue(knock.contains("\"envelopeId\":\"env-1\""));
        var knockId = DoorWire.JSON.readTree(knock).get("knockId").asText();

        proxy.message("phone-7", """
            {"type":"answer","knockId":"%s",
             "answer":{"completed":true,"ok":true,"output":"the drafted line"}}""".formatted(knockId));
        var result = outcome.get(2, TimeUnit.SECONDS);
        assertInstanceOf(Mesh.DoorProtocol.Completed.class, result);
        assertEquals("the drafted line", ((Mesh.DoorProtocol.Completed) result).result().output());
    }

    @Test
    void aSilentPhoneDeclinesInsteadOfHangingTheErrand() {
        proxy.connected("phone-7", sentToPhone::add);
        var result = servedDoors.get("phone-7").offer(envelope());
        assertInstanceOf(Mesh.DoorProtocol.Declined.class, result);
        assertEquals("phone did not answer", ((Mesh.DoorProtocol.Declined) result).reason());
    }

    @Test
    void anOfflinePhoneDeclinesImmediately() {
        PhoneDoorProxy.PhoneChannel ch = sentToPhone::add;
        proxy.connected("phone-7", ch);
        proxy.disconnected("phone-7", ch);
        var before = System.nanoTime();
        var result = servedDoors.get("phone-7").offer(envelope());
        assertTrue(System.nanoTime() - before < Duration.ofMillis(250).toNanos(),
            "no channel must not wait out the knock timeout");
        assertInstanceOf(Mesh.DoorProtocol.Declined.class, result);
        assertEquals("phone offline", ((Mesh.DoorProtocol.Declined) result).reason());
    }

    @Test
    void disconnectingMidErrandDeclinesThePendingKnock() throws Exception {
        PhoneDoorProxy.PhoneChannel ch = sentToPhone::add;
        proxy.connected("phone-7", ch);
        var door = servedDoors.get("phone-7");
        var outcome = CompletableFuture.supplyAsync(() -> door.offer(envelope()));
        for (int i = 0; i < 50 && sentToPhone.stream().noneMatch(m -> m.contains("\"knock\"")); i++) {
            Thread.sleep(10);
        }
        proxy.disconnected("phone-7", ch);
        var result = outcome.get(2, TimeUnit.SECONDS);
        assertInstanceOf(Mesh.DoorProtocol.Declined.class, result);
        assertEquals("phone disconnected mid-errand", ((Mesh.DoorProtocol.Declined) result).reason());
    }

    @Test
    void aStaleCloseNeverTearsDownTheRoamedChannel() {
        // LAN leg connects, then the phone roams: relay leg supersedes it.
        PhoneDoorProxy.PhoneChannel lanLeg = sentToPhone::add;
        proxy.connected("phone-7", lanLeg);
        var relayFrames = new CopyOnWriteArrayList<String>();
        PhoneDoorProxy.PhoneChannel relayLeg = relayFrames::add;
        proxy.connected("phone-7", relayLeg);

        // The LAN leg's close arrives LATE — after the roam. It must not
        // rip out the live relay channel.
        proxy.disconnected("phone-7", lanLeg);

        CompletableFuture.runAsync(() -> servedDoors.get("phone-7").offer(envelope()));
        for (int i = 0; i < 50 && relayFrames.stream().noneMatch(m -> m.contains("\"knock\"")); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        }
        assertTrue(relayFrames.stream().anyMatch(m -> m.contains("\"knock\"")),
            "the roamed-to channel must keep receiving knocks after the stale close");
    }

    @Test
    void roamingMidErrandDeclinesTheOldLegsKnockPromptly() throws Exception {
        PhoneDoorProxy.PhoneChannel lanLeg = sentToPhone::add;
        proxy.connected("phone-7", lanLeg);
        var door = servedDoors.get("phone-7");
        var outcome = CompletableFuture.supplyAsync(() -> door.offer(envelope()));
        for (int i = 0; i < 50 && sentToPhone.stream().noneMatch(m -> m.contains("\"knock\"")); i++) {
            Thread.sleep(10);
        }
        // Roam: the answer to the in-flight knock can never come back on the
        // new leg — it must decline NOW, not wait out the knock timeout.
        proxy.connected("phone-7", new CopyOnWriteArrayList<String>()::add);
        var result = outcome.get(2, TimeUnit.SECONDS);
        assertInstanceOf(Mesh.DoorProtocol.Declined.class, result);
        assertEquals("phone changed doors mid-errand", ((Mesh.DoorProtocol.Declined) result).reason());
    }

    @Test
    void reconnectingKeepsOneDoorButSwitchesTheChannel() {
        proxy.connected("phone-7", sentToPhone::add);
        var doorBefore = servedDoors.get("phone-7");
        var second = new CopyOnWriteArrayList<String>();
        proxy.connected("phone-7", second::add);
        assertSame(doorBefore, servedDoors.get("phone-7"), "door subject served once");
        // Knock now rides the NEW channel.
        CompletableFuture.runAsync(() -> doorBefore.offer(envelope()));
        for (int i = 0; i < 50 && second.stream().noneMatch(m -> m.contains("\"knock\"")); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        }
        assertTrue(second.stream().anyMatch(m -> m.contains("\"knock\"")));
    }

    @Test
    void anUnattachedProxyDropsHeartbeatsQuietly() {
        var inert = new PhoneDoorProxy(Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(inert.attached());
        inert.connected("phone-7", sentToPhone::add);
        inert.message("phone-7", """
            {"type":"heartbeat","capabilityClass":"llm.phone-npu","models":[],
             "residentDataDomains":[],"charging":true,"idle":true,"loadFactor":0.0}""");
        assertTrue(gossiped.isEmpty());
    }
}
