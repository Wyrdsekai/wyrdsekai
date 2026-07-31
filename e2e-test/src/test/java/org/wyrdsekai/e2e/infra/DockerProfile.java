package org.wyrdsekai.e2e.infra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Declares which Docker Compose profile a test class requires.
 * Automatically registers {@link DockerInfraExtension} which ensures the
 * required services are running before any tests in the class execute.
 *
 * <p>Available profiles:
 * <ul>
 *   <li>{@code "nats"} — NATS messaging only (tier 3)
 *   <li>{@code "relay"} — NATS + llama-phone (0.6B) + llama-laptop (4B) (tier 4/5)
 *   <li>{@code "llama"} — NATS + llama-server (tier 1/2 with llama backend)
 *   <li>{@code "sglang"} — NATS + SGLang (tier 1/2 with sglang backend)
 *   <li>{@code "vllm"} — NATS + vLLM (tier 1/2 with vllm backend)
 * </ul>
 *
 * <p>Behavior:
 * <ul>
 *   <li>If all required services are already healthy, reuses them (no restart)
 *   <li>If Docker is unavailable, the test is skipped via JUnit assumption
 *   <li>Containers started by the extension are torn down on JVM exit
 *     (suppress with {@code WYRDSEKAI_E2E_DOCKER_PERSIST=true})
 *   <li>Pre-existing containers are never torn down
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * @DockerProfile("relay")
 * class PhoneRelayTest {
 *     @Test void test() {
 *         // NATS at nats://localhost:4222
 *         // llama-phone at http://localhost:8081
 *         // llama-laptop at http://localhost:8082
 *     }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(DockerInfraExtension.class)
public @interface DockerProfile {

    /**
     * The Docker Compose profile to activate.
     * Maps to services defined in {@code docker/docker-compose.e2e.yml}.
     */
    String value();
}
