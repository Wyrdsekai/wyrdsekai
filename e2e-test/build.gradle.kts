val pekkoVersion: String by extra
val pekkoScalaSuffix: String by extra
val jacksonVersion: String by extra

dependencies {
    testImplementation(project(":common"))
    testImplementation(project(":core"))
    testImplementation(project(":scripting"))
    testImplementation(project(":between"))
    testImplementation(project(":server"))
    testImplementation(project(":cli"))

    // Pekko (actor testkit + persistence testkit + cluster sharding for RoomCreator)
    testImplementation("org.apache.pekko:pekko-actor-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-persistence-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-persistence-testkit${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-cluster-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-cluster-sharding-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-serialization-jackson${pekkoScalaSuffix}:${pekkoVersion}")

    // Jackson
    testImplementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // Javalin (for TestServerBootstrap)
    testImplementation("io.javalin:javalin:7.1.0")

    // WireMock (deterministic HTTP mocking for inference)
    testImplementation("org.wiremock:wiremock-jetty12:3.13.2")

    // Awaitility (async polling assertions)
    testImplementation("org.awaitility:awaitility:4.3.0")

    // SQLite (in-memory test databases)
    testImplementation("org.xerial:sqlite-jdbc:3.51.2.0")

    // GraalJS (for workbench sandbox tests in AutonomyLiveE2ETest)
    testImplementation("org.graalvm.polyglot:polyglot:25.0.2")

    // NATS client (for Between tier tests)
    testImplementation("io.nats:jnats:2.25.2")

    // Apache SSHD client (for SSH E2E tests — same version as server module)
    testImplementation("org.apache.sshd:sshd-core:2.17.1")
}

tasks.withType<Test> {
    jvmArgs(
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-XX:+UseCompactObjectHeaders"
    )

    // Longer timeouts for real LLM E2E tests — Docker model loading + SGLang kernel
    // compilation can take 3-5 minutes on first start
    systemProperty("junit.jupiter.execution.timeout.default", "600s")

    // Individuality "B build": pin un-archetyped births to "neutral" under test so the
    // suite is deterministic (production default = "particular"). The multi-agent soak's
    // particulars use an explicit archetype="random", which overrides this in code.
    systemProperty("wyrdsekai.birth.mode", "neutral")

    // Forward WYRDSEKAI_* env from the launching shell into the test JVM. The
    // harness (e2e-test.sh) sets WYRDSEKAI_E2E_<SVC>_PORT to a shifted port
    // universe when the standard ports are already bound by a live mesh on the
    // same host (e.g. home-server). DockerInfraExtension + E2eTestSupport read those.
    //
    // Provider-based, NOT System.getenv(): the gradle daemon's JVM env is
    // frozen at daemon-start. Direct System.getenv() in this block (inside or
    // outside doFirst) reads the daemon's env, NOT the client invocation's.
    // After `WYRDSEKAI_E2E_LANG=ja ./gradlew …` against an existing daemon,
    // the new var stays invisible. providers.environmentVariablesPrefixedBy()
    // routes through gradle's build-environment IPC and reads the *client*
    // process env, which is what we want. Verified 2026-05-12 after
    // multi-language Ember runs SKIPPED with stale-daemon env.
    val wyrdsekaiEnv = providers.environmentVariablesPrefixedBy("WYRDSEKAI_")
    doFirst {
        wyrdsekaiEnv.get().forEach { (k, v) -> environment(k, v) }
    }

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    // Report results for each test method as it completes (not buffered to end)
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        println("  ${desc.className}.${desc.name}: ${result.resultType} (${result.endTime - result.startTime}ms)")
    }))
}
