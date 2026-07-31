package org.wyrdsekai.core.inference;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ApiKeyProvider}, {@link StaticApiKeyProvider}.
 */
class ApiKeyProviderTest {

    @Test void static_provider_returns_configured_key() {
        var provider = new StaticApiKeyProvider(Map.of(
            "openai", "sk-openai-key",
            "anthropic", "sk-anthropic-key"
        ));

        assertThat(provider.getKey("openai")).isEqualTo("sk-openai-key");
        assertThat(provider.getKey("anthropic")).isEqualTo("sk-anthropic-key");
    }

    @Test void static_provider_returns_null_for_unknown_backend() {
        var provider = new StaticApiKeyProvider(Map.of("openai", "sk-xxx"));

        assertThat(provider.getKey("unknown")).isNull();
        assertThat(provider.getKey("")).isNull();
    }

    @Test void static_provider_is_immutable() {
        var original = new HashMap<String, String>();
        original.put("openai", "sk-xxx");
        var provider = new StaticApiKeyProvider(original);

        // Mutating the original map should not affect the provider
        original.put("openai", "sk-changed");
        original.put("new-backend", "sk-new");

        assertThat(provider.getKey("openai")).isEqualTo("sk-xxx");
        assertThat(provider.getKey("new-backend")).isNull();
    }

    @Test void configured_backends_lists_all_keys() {
        var provider = new StaticApiKeyProvider(Map.of(
            "openai", "sk-1",
            "anthropic", "sk-2",
            "deepseek", "sk-3"
        ));

        assertThat(provider.configuredBackends())
            .containsExactlyInAnyOrder("openai", "anthropic", "deepseek");
    }

    @Test void empty_provider_returns_null_for_any_key() {
        var provider = new StaticApiKeyProvider(Map.of());

        assertThat(provider.getKey("openai")).isNull();
        assertThat(provider.configuredBackends()).isEmpty();
    }

    @Test void from_environment_picks_up_prefixed_vars() {
        var env = Map.of(
            "WYRDSEKAI_API_KEY_OPENAI", "sk-openai",
            "WYRDSEKAI_API_KEY_ANTHROPIC_CLOUD", "sk-anthropic",
            "WYRDSEKAI_API_KEY_DEEPSEEK", "sk-deep",
            "HOME", "/home/test",          // should be ignored
            "PATH", "/usr/bin"             // should be ignored
        );

        var provider = StaticApiKeyProvider.fromEnvironment(env);

        assertThat(provider.getKey("openai")).isEqualTo("sk-openai");
        assertThat(provider.getKey("anthropic-cloud")).isEqualTo("sk-anthropic");
        assertThat(provider.getKey("deepseek")).isEqualTo("sk-deep");
        assertThat(provider.getKey("home")).isNull(); // not prefixed
    }

    @Test void from_environment_normalizes_backend_names() {
        var env = Map.of(
            "WYRDSEKAI_API_KEY_MY_BACKEND_NAME", "sk-xxx"
        );

        var provider = StaticApiKeyProvider.fromEnvironment(env);

        // Underscores after prefix become hyphens, all lowercase
        assertThat(provider.getKey("my-backend-name")).isEqualTo("sk-xxx");
    }

    @Test void from_environment_ignores_blank_values() {
        var env = Map.of(
            "WYRDSEKAI_API_KEY_OPENAI", "sk-real",
            "WYRDSEKAI_API_KEY_EMPTY", "   "
        );

        var provider = StaticApiKeyProvider.fromEnvironment(env);

        assertThat(provider.getKey("openai")).isEqualTo("sk-real");
        assertThat(provider.getKey("empty")).isNull();
    }

    @Test void from_environment_empty_env() {
        var provider = StaticApiKeyProvider.fromEnvironment(Map.of());

        assertThat(provider.configuredBackends()).isEmpty();
    }

    @Test void interface_contract_allows_null_return() {
        ApiKeyProvider provider = backendName -> null;

        assertThat(provider.getKey("anything")).isNull();
    }

    @Test void set_key_installs_runtime_value() {
        var provider = new StaticApiKeyProvider();

        assertThat(provider.getKey("openrouter")).isNull();
        provider.setKey("openrouter", "sk-runtime-installed");
        assertThat(provider.getKey("openrouter")).isEqualTo("sk-runtime-installed");
        assertThat(provider.configuredBackends()).contains("openrouter");
    }

    @Test void set_key_overwrites_existing_value() {
        var provider = new StaticApiKeyProvider(Map.of("openai", "sk-old"));

        provider.setKey("openai", "sk-new");
        assertThat(provider.getKey("openai")).isEqualTo("sk-new");
    }

    @Test void set_key_blank_or_null_removes() {
        var provider = new StaticApiKeyProvider(Map.of("openrouter", "sk-x"));

        provider.setKey("openrouter", null);
        assertThat(provider.getKey("openrouter")).isNull();
        assertThat(provider.configuredBackends()).doesNotContain("openrouter");

        provider.setKey("openrouter", "sk-y");
        provider.setKey("openrouter", "   ");
        assertThat(provider.getKey("openrouter")).isNull();
    }

    @Test void active_singleton_round_trip() {
        var prior = StaticApiKeyProvider.getActive();
        try {
            var provider = new StaticApiKeyProvider(Map.of("openai", "sk-active"));
            StaticApiKeyProvider.setActive(provider);

            var found = StaticApiKeyProvider.getActive();
            assertThat(found).isSameAs(provider);
            assertThat(found.getKey("openai")).isEqualTo("sk-active");
        } finally {
            StaticApiKeyProvider.setActive(prior);
        }
    }
}
