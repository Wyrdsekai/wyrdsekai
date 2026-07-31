val pekkoVersion = "1.4.0"
val pekkoScalaSuffix = "_2.13"
val jacksonVersion = "2.21.1"

subprojects {
    apply(plugin = "java")

    group = "org.wyrdsekai"
    version = "0.1.0-SNAPSHOT"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    repositories {
        mavenCentral()
    }

    // Share version constants via extra properties
    extra["pekkoVersion"] = pekkoVersion
    extra["pekkoScalaSuffix"] = pekkoScalaSuffix
    extra["jacksonVersion"] = jacksonVersion

    dependencies {
        // Shared logging across all modules
        "implementation"("org.slf4j:slf4j-api:2.0.17")
        "implementation"("ch.qos.logback:logback-classic:1.5.16")

        // Shared test deps
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.12.2")
        "testImplementation"("org.assertj:assertj-core:3.27.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    // --- Infra-need test taxonomy ----------------------------------------------------
    // Tests that require external infrastructure carry a `needs-*` JUnit tag describing
    // WHAT they depend on. The default `test` task EXCLUDES all of them, so a bare
    // `./gradlew test` is hermetic + green headless. Run the infra-needing tests
    // deliberately, grouped by need:
    //   ./gradlew test               # (A) hermetic only — fast, no infra
    //   ./gradlew testNats           # (A) one lane: tests tagged needs-nats
    //   ./gradlew testClassifier     #     "          needs-classifier  ... etc
    //   ./gradlew integrationTest    # (B) the full case — every needs-* test
    //   ./gradlew test -PincludeTags=needs-nats,needs-network   # ad-hoc combo
    // A test may carry more than one needs-* tag.
    val needsTags = listOf(
        "needs-classifier", "needs-nats", "needs-inference",
        "needs-gpu", "needs-network", "needs-goose", "needs-datadir"
    )

    tasks.withType<Test> {
        useJUnitPlatform {
            val includeTags = project.findProperty("includeTags") as String?
            val excludeTags = project.findProperty("excludeTags") as String?
            when {
                // Explicit -P overrides always win.
                includeTags != null -> includeTags.split(",").forEach { includeTags(it.trim()) }
                excludeTags != null -> excludeTags.split(",").forEach { excludeTags(it.trim()) }
                // (B) umbrella: every infra-needing test.
                name == "integrationTest" -> needsTags.forEach { includeTags(it) }
                // (A) per-need lanes: testNats -> needs-nats, testClassifier -> needs-classifier ...
                name.startsWith("test") && name != "test" && name != "experimentTest" -> {
                    val tag = "needs-" + name.removePrefix("test").replaceFirstChar { it.lowercaseChar() }
                    if (tag in needsTags) includeTags(tag)
                }
                // Default `test`: hermetic only (skip everything needing infra).
                name == "test" -> needsTags.forEach { excludeTags(it) }
            }
        }
        // Pass SOUL_* env vars to test JVM for experiment tests
        System.getenv().filter { it.key.startsWith("SOUL_") }.forEach { (k, v) ->
            environment(k, v)
        }
    }

    // Register the umbrella + per-need Test lanes (reuse the `test` source set).
    plugins.withId("java") {
        val testSs = the<SourceSetContainer>()["test"]
        fun lane(taskName: String, desc: String) {
            if (tasks.findByName(taskName) != null) return
            tasks.register<Test>(taskName) {
                group = "verification"
                description = desc
                testClassesDirs = testSs.output.classesDirs
                classpath = testSs.runtimeClasspath
            }
        }
        lane("integrationTest", "Runs ALL infra-needing tests (every needs-* tag).")
        needsTags.forEach { tag ->
            val cap = tag.removePrefix("needs-").replaceFirstChar { it.uppercaseChar() }
            lane("test$cap", "Runs tests tagged $tag.")
        }
    }
}
