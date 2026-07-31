val pekkoVersion: String by extra
val pekkoScalaSuffix: String by extra
val jacksonVersion: String by extra

plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))

    // NATS client — api so consumers (server) can use the Connection type
    // returned by NatsBridge.rawConnection() and by NatsPeerTrainingTransport's
    // public constructor.
    api("io.nats:jnats:2.25.2")

    // mDNS discovery
    implementation("org.jmdns:jmdns:3.6.3")

    // Pekko (for BetweenActor, ProbeActor, ClusterFormation)
    implementation("org.apache.pekko:pekko-actor-typed${pekkoScalaSuffix}:${pekkoVersion}")
    implementation("org.apache.pekko:pekko-cluster-typed${pekkoScalaSuffix}:${pekkoVersion}")

    // Jackson (message serialization)
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // Test
    testImplementation("org.xerial:sqlite-jdbc:3.51.2.0")
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed${pekkoScalaSuffix}:${pekkoVersion}")
}
