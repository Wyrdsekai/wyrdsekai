val jacksonVersion: String by extra

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
}

// Generate version properties file at build time. F14: in addition to the
// short buildHash, capture the full 40-char gitSha so peer-version drift
// across the mesh can be displayed unambiguously.
val generateVersionProperties = tasks.register("generateVersionProperties") {
    val outputDir = layout.buildDirectory.dir("generated-resources")
    outputs.dir(outputDir)
    // Bust cache when the CHECKED-OUT COMMIT or dirty state changes. The old
    // input was the .git/HEAD file, which holds "ref: refs/heads/<branch>" —
    // it only changes on branch switch, so new commits on the SAME branch
    // left the stamp frozen at whatever was first built (second-node re-verify
    // 2026-07-11 #29: `wyrd version` said bc38aca2 while the jar content was
    // f2f01e15). Track the RESOLVED sha + dirty flag as input properties via
    // providers.exec — recomputed every build, config-cache safe.
    fun gitOutput(vararg gitArgs: String) = providers.exec {
        workingDir = rootDir
        commandLine("git", *gitArgs)
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
    inputs.property("gitHeadSha", gitOutput("rev-parse", "HEAD"))
    // -uno: TRACKED changes only. Bare --porcelain counts untracked files,
    // and the repo root carries dozens of untracked plan/research docs — so
    // every build stamped gitDirty=true regardless of the actual source
    // state (found 2026-08-16 while verifying the 0.2.0 second-node artifact). A
    // dirty flag that is always on protects nothing.
    inputs.property("gitDirtyState", gitOutput("status", "--porcelain", "-uno").map { it.isNotBlank() })
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val propsFile = File(dir, "wyrdsekai-version.properties")
        fun git(vararg args: String): String = try {
            val p = ProcessBuilder("git", *args).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { "unknown" }
        val gitHashShort = git("rev-parse", "--short", "HEAD")
        val gitSha = git("rev-parse", "HEAD")
        val gitDirty = git("status", "--porcelain", "-uno").isNotBlank()
        val now = System.currentTimeMillis().toString()
        propsFile.writeText(
            "version=${project.version}\n" +
            "buildHash=$gitHashShort\n" +
            "gitSha=$gitSha\n" +
            "gitDirty=$gitDirty\n" +
            "buildTimestamp=$now\n")
    }
}

tasks.named("processResources") {
    dependsOn(generateVersionProperties)
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated-resources"))
}
