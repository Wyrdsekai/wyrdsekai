val pekkoVersion: String by extra
val pekkoScalaSuffix: String by extra

plugins {
    `java-library`
}

dependencies {
    api(project(":common"))
    implementation(project(":scripting"))

    // GraalJS — the scripting module already depends on these; declared
    // here too so the coding-policy host callbacks (ScriptedCodingBackendProvider)
    // can use @HostAccess.Export without forcing scripting to expose its
    // GraalJS dependency through its public API.
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.polyglot:js:25.0.2")

    // mDNS / Zeroconf for LAN discovery (Tier 3 of household discovery hierarchy).
    // Used by MdnsDiscovery to advertise + browse `_wyrdsekai._tcp.local.`.
    implementation("org.jmdns:jmdns:3.6.3")

    // Pekko typed actors + persistence + cluster — api because RoomActor extends EventSourcedBehavior
    api("org.apache.pekko:pekko-actor-typed${pekkoScalaSuffix}:${pekkoVersion}")
    api("org.apache.pekko:pekko-persistence-typed${pekkoScalaSuffix}:${pekkoVersion}")
    implementation("org.apache.pekko:pekko-cluster-typed${pekkoScalaSuffix}:${pekkoVersion}")
    implementation("org.apache.pekko:pekko-cluster-sharding-typed${pekkoScalaSuffix}:${pekkoVersion}")
    implementation("org.apache.pekko:pekko-serialization-jackson${pekkoScalaSuffix}:${pekkoVersion}")

    // Jackson YAML — parse Goose-compatible recipe YAML + Wyrdsekai recipe manifests
    // jackson-databind comes transitively via pekko-serialization-jackson
    // this adds the YAML dataformat (pulls snakeyaml transitively).
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.21.1")

    // SQLite JDBC (libSQL-compatible)
    implementation("org.xerial:sqlite-jdbc:3.51.2.0")

    // PostgreSQL JDBC (multi-node mode)
    implementation("org.postgresql:postgresql:42.7.10")

    // BCrypt for password hashing
    implementation("at.favre.lib:bcrypt:0.10.2")

    // Email (IMAP/SMTP)
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")

    // MQTT client (Phase T inbound listener — smart-home brokers, IoT)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")

    // SSH server
    implementation("org.apache.sshd:sshd-core:2.17.1")

    // Document extraction
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    implementation("org.apache.poi:poi-ooxml:5.5.1")

    // Parquet reader (for columnar datasets — data dumps, etc.)
    // Uses LocalInputFile — no HDFS, but parquet-avro internally needs hadoop-common Configuration.
    implementation("org.apache.parquet:parquet-avro:1.17.0")
    implementation("org.apache.parquet:parquet-hadoop:1.17.0")
    implementation("org.apache.hadoop:hadoop-common:3.4.1") {
        exclude(group = "org.apache.curator")
        exclude(group = "org.apache.zookeeper")
        exclude(group = "org.apache.kerby")
        exclude(group = "com.google.protobuf")
        exclude(group = "org.eclipse.jetty")
        exclude(group = "javax.servlet")
        exclude(group = "io.netty")
    }
    // XZ/LZMA2 decompression (needed by commons-compress for .7z archives — StackExchange dumps)
    implementation("org.tukaani:xz:1.10")

    // Parquet writer (test only — for creating test fixtures; production only reads)
    testImplementation("org.apache.hadoop:hadoop-mapreduce-client-core:3.4.1") { isTransitive = false }

    // Stripe Issuing
    implementation("com.stripe:stripe-java:31.4.1")

    // RSS/Atom parsing
    implementation("com.rometools:rome:2.1.0")

    // Apache Lucene — embedded vector search + BM25 text search (replaces Milvus for soul/library/world search)
    implementation("org.apache.lucene:lucene-core:10.4.0")
    implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
    implementation("org.apache.lucene:lucene-queryparser:10.4.0")

    // ONNX Runtime — lightweight ML inference for action triage embeddings (all-MiniLM-L6-v2)
    implementation("com.microsoft.onnxruntime:onnxruntime:1.23.2")

    // DJL HuggingFace Tokenizer — Rust JNI, exact parity with Python tokenizers
    implementation("ai.djl.huggingface:tokenizers:0.33.0")

    // Pekko Persistence JDBC
    implementation("org.apache.pekko:pekko-persistence-jdbc${pekkoScalaSuffix}:1.2.0")

    // Sigstore — keyless release-binary signature verification (F2.2 item 4 Phase 1.5).
    // Used by ReleaseVerifier to validate the Fulcio cert chain + Rekor inclusion proof
    // against the embedded trusted root (core/src/main/resources/release/trusted-root.json).
    // Pulls protobuf, grpc, BouncyCastle, gson, google-http-client transitively (~25MB).
    implementation("dev.sigstore:sigstore-java:1.3.0")

    // BouncyCastle secp256k1 + BIP-340 Schnorr math for.
    // Pulled in transitively by sigstore already; declared explicitly so the
    // Nostr keypair + event-signing code doesn't silently break if sigstore
    // ever drops the dependency. Also used by HKDF for DID→Nostr key derive.
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")

    // Test
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-persistence-testkit${pekkoScalaSuffix}:${pekkoVersion}")
    // Fake OpenClaw gateway (WebSocket server) for the live-protocol test.
    testImplementation("io.javalin:javalin:7.1.0")
}

tasks.withType<JavaExec> {
    jvmArgs(
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-XX:+UseCompactObjectHeaders"
    )
}

// ── experimentTest source set ────────────────────────────────────────────
//
// Research / eval code (soul lifecycle experiments, phone-steering, mirror
// resonance, Nemotron, Substrate curves, LFM2) lives here instead of
// src/test/ so:
//
//   1. `:core:test` runs only the shippable unit tests — no slow live-LLM
//      gates, no 19-skip noise, no dependency on SOUL_EXPERIMENT_URL / other
//      research endpoints.
//   2. The OSS release tarball can physically exclude `src/experimentTest/`
//      at packaging time (see packaging/build-dist.sh) — experiments stay
//      in the private branch only.
//
// Run experiments on demand:  ./gradlew :core:experimentTest
// (or with a live endpoint):  SOUL_EXPERIMENT_URL=http://home-server:8200 \
//                               ./gradlew :core:experimentTest
sourceSets {
    create("experimentTest") {
        java.srcDir("src/experimentTest/java")
        resources.srcDir("src/experimentTest/resources")
        compileClasspath += sourceSets["test"].output + sourceSets["main"].output
        runtimeClasspath += sourceSets["test"].output + sourceSets["main"].output
    }
}

// keep the M2 example bank
// in sync between the canonical editable source (scripts/m2/) and the
// classpath-bundled copy (core resources). Production .deb / .pkg deploys
// run from a JVM cwd that is NOT the repo root, so M2PlanScorer.loadDefault()
// must fall back to classpath. This task ensures every build refreshes the
// bundled copy from the canonical source.
val syncM2Bank = tasks.register<Copy>("syncM2Bank") {
    description = "Copy scripts/m2/plan_examples.jsonl into core resources for production bundling."
    group = "build"
    from(rootProject.file("scripts/m2/plan_examples.jsonl"))
    into(layout.projectDirectory.dir("src/main/resources/m2"))
    onlyIf { rootProject.file("scripts/m2/plan_examples.jsonl").exists() }
}
tasks.named("processResources") { dependsOn(syncM2Bank) }

// Sigstore trusted-root JSON for
// ReleaseVerifier. Pinned-on-disk by design: PR review covers any change,
// no network access at build time, build is reproducible. Run manually
// to refresh after Sigstore rotates Fulcio/Rekor keys (~yearly cadence,
// signalled by sigstore-java release notes / TUF root version bump).
//
//   ./gradlew :core:updateTrustedRoot
//
// Then `git diff core/src/main/resources/release/trusted-root.json` to
// review the new keys before committing.
tasks.register<Exec>("updateTrustedRoot") {
    description = "Refresh Sigstore trusted_root.json from public-good TUF repo. Run manually."
    group = "release"
    val outFile = layout.projectDirectory.file("src/main/resources/release/trusted-root.json").asFile
    doFirst { outFile.parentFile.mkdirs() }
    // Sigstore TUF uses consistent snapshots — the trusted_root.json is at
    // targets/<sha256>.trusted_root.json. Find the latest version of the
    // targets metadata, extract the trusted_root hash, then fetch by hash.
    // Using a small shell helper rather than embedding the two-step logic
    // in Kotlin DSL (Gradle's java.net imports aren't available in build
    // scripts without explicit imports).
    commandLine(
        "bash", "-c",
        """
        set -euo pipefail
        BASE=https://tuf-repo-cdn.sigstore.dev
        # Find current targets metadata version (TUF root metadata is versioned).
        # We probe down from a high number until we find one that exists.
        TARGETS_VER=""
        for v in 20 19 18 17 16 15 14 13 12 11 10 9 8 7 6 5 4 3 2 1; do
            if curl -sfI -m 5 "${'$'}BASE/${'$'}v.targets.json" >/dev/null 2>&1; then
                TARGETS_VER=${'$'}v
                break
            fi
        done
        if [ -z "${'$'}TARGETS_VER" ]; then echo "Could not find targets.json version" >&2; exit 1; fi
        echo "Using targets version ${'$'}TARGETS_VER"
        HASH=${'$'}(curl -sf -m 15 "${'$'}BASE/${'$'}TARGETS_VER.targets.json" \
            | python3 -c "import json,sys;d=json.load(sys.stdin);print(d['signed']['targets']['trusted_root.json']['hashes']['sha256'])")
        if [ -z "${'$'}HASH" ]; then echo "Could not extract trusted_root hash" >&2; exit 1; fi
        echo "trusted_root.json sha256: ${'$'}HASH"
        curl -sfL -m 30 -o "${outFile.absolutePath}" \
            "${'$'}BASE/targets/${'$'}HASH.trusted_root.json"
        wc -c "${outFile.absolutePath}"
        """.trimIndent()
    )
}

configurations {
    named("experimentTestImplementation") { extendsFrom(configurations["testImplementation"]) }
    named("experimentTestRuntimeOnly")    { extendsFrom(configurations["testRuntimeOnly"]) }
}

tasks.register<Test>("experimentTest") {
    description = "Runs soul experiments / research eval. Separate from :test so the " +
        "unit-test suite stays fast and free of live-LLM gates."
    group = "verification"
    testClassesDirs = sourceSets["experimentTest"].output.classesDirs
    classpath = sourceSets["experimentTest"].runtimeClasspath
    useJUnitPlatform()
    // Do NOT make :check depend on this — it's opt-in.
}

tasks.withType<Test> {
    jvmArgs(
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-XX:+UseCompactObjectHeaders"
    )
    // Keep PromptAssembler's existing sandwich-layout behaviour under test.
    // Production flips this on via the .deb EnvironmentFile so strict chat
    // templates (Qwen3.5-9B) don't reject multiple consecutive system msgs.
    systemProperty("wyrdsekai.mergeSystemMessages", "false")
    // ConfigApplyCoordinator calls System.exit(2) in source-mode (foreground)
    // when a scroll-of-settings apply is requested. That's correct in prod
    // so a systemd Restart=on-failure kicks in. In tests it kills the
    // gradle worker — flag it off.
    systemProperty("wyrdsekai.configApply.disableExit", "true")
    // Individuality "B build": production births un-archetyped companions as freely
    // sampled particulars (WyrdConfig.birthMode default = "particular"). Pin "neutral"
    // under test so the suite stays deterministic; a test that wants a particular sets
    // an explicit archetype ("random"/a preset), which always overrides this.
    systemProperty("wyrdsekai.birth.mode", "neutral")
    // Tests must be hermetic. If a DJL native lib (libtokenizers.so) or ONNX
    // model isn't already on the box, DJL otherwise BLOCKS on a multi-second
    // network fetch on whatever thread first exercises the classifier/embedder
    // — on a Pekko dispatcher that pushes the first companion inference past a
    // 5s probe and flakes AutonomyIntegrationTest. Offline = fail fast, so the
    // LinkageError/degrade path in EmbeddingService runs immediately instead.
    systemProperty("ai.djl.offline", "true")
    // Opt-in path to a COPY of a real world.db, for rehearsing an identity
    // rebind against production-shaped data. Absent by default, and the tests
    // that use it skip rather than fail — CI must stay green on a machine that
    // has never seen a household node. Never point this at a live database.
    System.getProperty("rehearsalDb")?.let { systemProperty("rehearsalDb", it) }
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
