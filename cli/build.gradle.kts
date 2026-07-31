val jacksonVersion: String by extra

plugins {
    application
}

application {
    mainClass.set("org.wyrdsekai.cli.Wyrd")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-XX:+UseCompactObjectHeaders"
    )
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

// Track-B B1 — release-time evolution bake task.
// Invoked from packaging/build-evolved-artifact.sh; runs
// RecipeBakeMain for one classifier head against the production
// RecipeService + CodingBackendDispatcher path.
//
// Args: -Phead=<name> [-PbakeArgs="--min-accuracy 0.85 ..."]
tasks.register<JavaExec>("bakeRecipe") {
    group = "release"
    description = "Run retrain-classifier-head bake for one head (B1)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.wyrdsekai.cli.RecipeBakeMain")
    standardInput = System.`in`
    val head = providers.gradleProperty("head").orNull
    val extra = providers.gradleProperty("bakeArgs").orNull
    if (head != null) args("--head", head)
    if (extra != null && extra.isNotBlank()) args(extra.split("\\s+".toRegex()))
    workingDir = rootProject.projectDir
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// /P2 — print the runtime classpath so RecipeValidateServer (the reward
// oracle) can be launched directly: java -cp "$(./gradlew -q :cli:printRuntimeCp)" ...
tasks.register("printRuntimeCp") {
    doLast { println(sourceSets["main"].runtimeClasspath.asPath) }
}

// Gate-runtime parity (2026-07-22): write the runtime classpath to a file so
// the retrain recipe's regression-probe step can run ProbeHeadMain with plain
// `java -cp "$(cat cli/build/probe-classpath.txt)"`. A nested `./gradlew`
// inside the bakeRecipe JVM would contend for the project lock mid-recipe —
// the file sidesteps that. packaging/build-evolved-artifact.sh runs this
// during bake prep.
tasks.register("writeProbeClasspath") {
    inputs.files(sourceSets["main"].runtimeClasspath)
    val outFile = layout.buildDirectory.file("probe-classpath.txt")
    outputs.file(outFile)
    doLast {
        outFile.get().asFile.writeText(sourceSets["main"].runtimeClasspath.asPath)
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))

    // JLine for readline
    implementation("org.jline:jline:4.0.4")

    // jnats: CLI/TUI reach a NAT'd zone by tunneling a full
    // session over the relay's NATS bus (RelayTunnelConnection).
    implementation("io.nats:jnats:2.25.2")

    // Jackson (for protocol message deserialization)
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
}
