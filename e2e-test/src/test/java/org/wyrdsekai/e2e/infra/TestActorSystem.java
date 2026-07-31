package org.wyrdsekai.e2e.infra;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.persistence.testkit.javadsl.EventSourcedBehaviorTestKit;

/**
 * Shared ActorTestKit factory with all serialization bindings
 * required for Wyrdsekai domain types.
 *
 * <p>Usage: Create one per test class via {@code @BeforeAll}, shut down via {@code @AfterAll}.
 * Includes Jackson serialization bindings for Room and CountingHouse types,
 * plus EventSourcedBehaviorTestKit configuration for persistence tests.
 */
public final class TestActorSystem {

    /** All serialization bindings needed for the full Wyrdsekai domain. */
    private static final String SERIALIZATION_CONFIG = """
        pekko.actor.serialization-bindings {
          "org.wyrdsekai.core.room.RoomEvent" = jackson-json
          "org.wyrdsekai.core.room.RoomState" = jackson-json
          "org.wyrdsekai.core.room.RoomCommand" = jackson-json
          "org.wyrdsekai.core.room.RoomNotification" = jackson-json
          "org.wyrdsekai.core.room.RoomResponse" = jackson-json
          "org.wyrdsekai.core.economy.CountingHouseCommand" = jackson-json
          "org.wyrdsekai.core.economy.CountingHouseEvent" = jackson-json
          "org.wyrdsekai.core.economy.CountingHouseState" = jackson-json
        }
        """;

    /** Suppress cluster and remoting warnings in test output. */
    private static final String QUIET_CONFIG = """
        pekko.loglevel = WARNING
        pekko.actor.provider = local
        """;

    private TestActorSystem() {}

    /**
     * Create an ActorTestKit with all serialization bindings and persistence testkit config.
     * Suitable for EventSourcedBehaviorTestKit tests.
     */
    public static ActorTestKit create() {
        return ActorTestKit.create(config());
    }

    /**
     * Create an ActorTestKit with a custom name (useful for multi-system tests).
     */
    public static ActorTestKit create(String name) {
        return ActorTestKit.create(name, config());
    }

    /**
     * Create an ActorTestKit with additional config merged on top.
     */
    public static ActorTestKit create(Config additionalConfig) {
        return ActorTestKit.create(additionalConfig.withFallback(config()));
    }

    /**
     * Create an ActorTestKit with a custom name and additional config.
     */
    public static ActorTestKit create(String name, Config additionalConfig) {
        return ActorTestKit.create(name, additionalConfig.withFallback(config()));
    }

    /**
     * The base config with all serialization bindings + persistence testkit.
     */
    public static Config config() {
        return ConfigFactory.parseString(SERIALIZATION_CONFIG + QUIET_CONFIG)
            .withFallback(EventSourcedBehaviorTestKit.config());
    }
}
