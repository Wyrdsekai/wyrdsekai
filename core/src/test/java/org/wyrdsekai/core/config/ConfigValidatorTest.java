package org.wyrdsekai.core.config;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    @Test
    void valid_minimal_config_passes() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                database.backend = "sqlite"
                database.sqlite.path = "/tmp/test.db"
                inference {
                    backends = [{type = "ollama", url = "http://localhost:11434"}]
                    default-model = "qwen3.5:9b"
                }
                telnet.port = 7071
                ssh.port = 7022
                http.port = 7070
            }
            """);

        var errors = ConfigValidator.validate(config);
        var fatals = errors.stream()
            .filter(e -> e.severity() == ConfigValidator.Severity.ERROR)
            .toList();
        assertTrue(fatals.isEmpty(), "Valid config should have no errors: " + fatals);
    }

    @Test
    void invalid_db_backend_produces_error() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                database.backend = "mysql"
                inference.backends = [{type = "ollama", url = "http://localhost:11434"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e ->
            e.path().contains("database.backend") && e.severity() == ConfigValidator.Severity.ERROR));
    }

    @Test
    void invalid_inference_type_produces_error() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                inference.backends = [{type = "gpt4all", url = "http://localhost:8080"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e ->
            e.path().contains("inference") && e.path().contains("type")));
    }

    @Test
    void invalid_port_produces_error() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                telnet.port = 99999
                inference.backends = [{type = "ollama", url = "http://localhost:11434"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e ->
            e.path().contains("telnet.port") && e.severity() == ConfigValidator.Severity.ERROR));
    }

    @Test
    void port_collision_produces_error() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                telnet.port = 7070
                ssh.port = 7070
                http.port = 7070
                inference.backends = [{type = "ollama", url = "http://localhost:11434"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        var collisions = errors.stream()
            .filter(e -> e.message().contains("conflicts"))
            .toList();
        assertFalse(collisions.isEmpty(), "Should detect port collision");
    }

    @Test
    void invalid_nats_url_when_between_enabled() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                between.enabled = true
                between.nats.url = "http://wrong-protocol:4222"
                inference.backends = [{type = "ollama", url = "http://localhost:11434"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e ->
            e.path().contains("nats.url")));
    }

    @Test
    void large_study_doc_size_produces_warning() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                study.max-document-size = 50000000
                inference.backends = [{type = "ollama", url = "http://localhost:11434"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e ->
            e.severity() == ConfigValidator.Severity.WARNING && e.path().contains("study")));
    }

    @Test
    void missing_optional_fields_no_errors() {
        // Config with only inference (minimum viable)
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                inference.backends = [{type = "ollama", url = "http://localhost:11434"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        var fatals = errors.stream()
            .filter(e -> e.severity() == ConfigValidator.Severity.ERROR)
            .toList();
        assertTrue(fatals.isEmpty(), "Missing optional fields should not be errors: " + fatals);
    }

    @Test
    void invalid_backend_url_produces_error() {
        var config = ConfigFactory.parseString("""
            wyrdsekai {
                inference.backends = [{type = "ollama", url = "not-a-url"}]
            }
            """);

        var errors = ConfigValidator.validate(config);
        assertTrue(errors.stream().anyMatch(e ->
            e.path().contains("url") && e.severity() == ConfigValidator.Severity.ERROR));
    }
}
