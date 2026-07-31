package org.wyrdsekai.core.external.r;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.external.ExternalAdapterRegistry;

/**
 * Phase R wiring.
 *
 * <p>Registers the AI/ML, smart-home, and media adapters with
 * {@link ExternalAdapterRegistry}. Invoked from
 * {@code CoreServices.init} so every entry point — production {@code Main},
 * test bootstrap, phone node — gets the same surface. Idempotent: a second
 * call replaces existing registrations with fresh instances.</p>
 *
 * <p>Each adapter is constructed with default base URLs; tests that need to
 * point at a mock server can call {@link #registerAll(java.util.function.Function)}
 * with a per-namespace base override, or instantiate adapters directly and
 * register them via {@link ExternalAdapterRegistry#register}.</p>
 */
public final class PhaseRAdaptersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(PhaseRAdaptersBootstrap.class);

    private static volatile boolean initialised = false;

    private PhaseRAdaptersBootstrap() {}

    public static synchronized void init() {
        if (initialised) {
            log.debug("PhaseRAdaptersBootstrap.init() called twice — re-registering");
        }
        var registry = ExternalAdapterRegistry.get();
        // AI/ML (§4.29).
        registry.register(new AnthropicAdapter());
        registry.register(new OpenAIAdapter());
        registry.register(new GeminiAdapter());
        registry.register(new HuggingFaceAdapter());
        registry.register(new ReplicateAdapter());
        registry.register(new ElevenLabsAdapter());
        registry.register(new WhisperAdapter());
        // Smart home (§4.30).
        registry.register(new HomeAssistantAdapter());
        registry.register(new HueAdapter());
        registry.register(new AppleHomeAdapter());
        // Media (§4.31).
        registry.register(new SonosAdapter());
        registry.register(new SpotifyAdapter());
        registry.register(new YouTubeAdapter());
        registry.register(new AppleMusicAdapter());
        initialised = true;
        log.info("Phase R adapters registered: 14 (anthropic/openai/gemini/hf/replicate/"
            + "elevenlabs/whisper/hass/hue/apple_home/sonos/spotify/youtube/apple_music)");
    }

    /** Test-only — allow harnesses to wipe registrations between tests. */
    public static synchronized void resetForTests() {
        initialised = false;
    }

    public static boolean isInitialised() { return initialised; }
}
