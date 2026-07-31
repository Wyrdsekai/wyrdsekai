plugins {
    `java-library`
}

val jacksonVersion: String by extra

dependencies {
    // NATS client (same version as between module) — exposed as api
    // since DaemonNatsClient returns io.nats.client types to consumers
    api("io.nats:jnats:2.20.5")

    // Jackson (wire-compatible JSON with InferenceGossip)
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
}
