package org.wyrdsekai.e2e.infra;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DockerInfraExtension} profile mapping.
 * Does NOT start Docker — validates configuration only.
 */
class DockerInfraExtensionTest {

    @Test
    void all_profiles_have_definitions() {
        var expected = Set.of("nats", "relay", "sglang", "vllm", "llama");
        assertEquals(expected, DockerInfraExtension.PROFILES.keySet());
    }

    @Test
    void nats_profile_has_nats_service_only() {
        var def = DockerInfraExtension.PROFILES.get("nats");
        assertEquals(1, def.services().size());
        assertTrue(def.services().contains("nats"));
        assertTrue(def.composeProfiles().isEmpty(),
            "nats service has no compose profile restriction");
    }

    @Test
    void relay_profile_includes_nats_and_both_llama() {
        var def = DockerInfraExtension.PROFILES.get("relay");
        assertEquals(3, def.services().size());
        assertTrue(def.services().contains("nats"));
        assertTrue(def.services().contains("llama-phone"));
        assertTrue(def.services().contains("llama-laptop"));
        assertEquals(1, def.composeProfiles().size());
        assertEquals("relay", def.composeProfiles().getFirst());
    }

    @Test
    void sglang_profile_includes_nats_and_sglang() {
        var def = DockerInfraExtension.PROFILES.get("sglang");
        assertEquals(2, def.services().size());
        assertTrue(def.services().contains("nats"));
        assertTrue(def.services().contains("sglang"));
    }

    @Test
    void vllm_profile_includes_nats_and_vllm() {
        var def = DockerInfraExtension.PROFILES.get("vllm");
        assertEquals(2, def.services().size());
        assertTrue(def.services().contains("nats"));
        assertTrue(def.services().contains("vllm"));
    }

    @Test
    void llama_profile_includes_nats_and_llama_server() {
        var def = DockerInfraExtension.PROFILES.get("llama");
        assertEquals(2, def.services().size());
        assertTrue(def.services().contains("nats"));
        assertTrue(def.services().contains("llama-server"));
    }

    @Test
    void all_services_have_health_checks() {
        for (var profileDef : DockerInfraExtension.PROFILES.values()) {
            for (var svc : profileDef.services()) {
                assertNotNull(
                    DockerInfraExtension.HEALTH_CHECKS.get(svc),
                    "Missing health check for service: " + svc);
            }
        }
    }

    @Test
    void static_url_accessors_return_expected_ports() {
        assertEquals("nats://localhost:4222", DockerInfraExtension.natsUrl());
        assertEquals("http://localhost:8222", DockerInfraExtension.natsMonitorUrl());
        assertEquals("http://localhost:8000", DockerInfraExtension.sglangUrl());
        assertEquals("http://localhost:8100", DockerInfraExtension.vllmUrl());
        assertEquals("http://localhost:8080", DockerInfraExtension.llamaServerUrl());
        assertEquals("http://localhost:8081", DockerInfraExtension.llamaPhoneUrl());
        assertEquals("http://localhost:8082", DockerInfraExtension.llamaLaptopUrl());
    }
}
