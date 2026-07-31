val pekkoVersion: String by extra
val pekkoScalaSuffix: String by extra
val jacksonVersion: String by extra

plugins {
    application
}

application {
    mainClass.set("org.wyrdsekai.server.Main")
    applicationDefaultJvmArgs = listOf(
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-XX:+UseCompactObjectHeaders",
        "--enable-native-access=ALL-UNNAMED",
        // Force IPv4 for NATS — macOS dual-stack (IPv6+IPv4) causes NATS client
        // connection failures on dual-homed machines where IPv6 routes differently
        "-Djava.net.preferIPv4Stack=true"
    )
}

// Guard: refuse to re-stage installDist over a running server's jars. Writing
// the distribution's lib/*.jar in place while a live JVM has them mmapped
// causes NoClassDefFoundError on any lazy inner-class load afterwards
// (observed in ). Operators must `wyrd stop` or
// `wyrd restart` before rebuilding, or pass -PwyrdsekaiForceInstall=true to
// override (CI, disposable test envs).
tasks.named("installDist") {
    doFirst {
        val force = project.findProperty("wyrdsekaiForceInstall")
            ?.toString()?.equals("true", ignoreCase = true) == true
        if (force) return@doFirst
        val pidCandidates = listOfNotNull(
            System.getenv("WYRDSEKAI_DATA_DIR")?.let { File(it, ".server.pid") },
            System.getProperty("user.home")?.let { File("$it/.wyrdsekai/.server.pid") },
            File("/var/lib/wyrdsekai/.server.pid")
        )
        for (pidFile in pidCandidates) {
            if (!pidFile.exists()) continue
            val pid = pidFile.readText().trim()
            if (pid.isEmpty() || !pid.all { it.isDigit() }) continue
            val alive = try {
                ProcessBuilder("kill", "-0", pid)
                    .redirectErrorStream(true).start().waitFor() == 0
            } catch (_: Exception) { false }
            if (alive) {
                throw GradleException(
                    "Refusing installDist: wyrdsekai server is running (pid $pid, recorded at $pidFile). "
                    + "Rewriting lib/*.jar under a live JVM produces NoClassDefFoundError on lazy "
                    + "inner-class loads. Run `wyrd stop` (or `wyrd restart` after the build) first. "
                    + "Override with -PwyrdsekaiForceInstall=true.")
            }
        }
    }
}

// Generate a 'forge' launcher alongside 'server'
tasks.named("installDist") {
    doLast {
        val binDir = layout.buildDirectory.dir("install/server/bin").get().asFile
        val forgeScript = File(binDir, "forge")
        // Copy the server script and replace the main class
        val serverScript = File(binDir, "server")
        forgeScript.writeText(
            serverScript.readText()
                .replace("org.wyrdsekai.server.Main", "org.wyrdsekai.core.soul.SoulForgeCliTool")
                .replace("APP_NAME=\"server\"", "APP_NAME=\"forge\"")
        )
        forgeScript.setExecutable(true)

        // Windows batch file
        val serverBat = File(binDir, "server.bat")
        if (serverBat.exists()) {
            val forgeBat = File(binDir, "forge.bat")
            forgeBat.writeText(
                serverBat.readText()
                    .replace("org.wyrdsekai.server.Main", "org.wyrdsekai.core.soul.SoulForgeCliTool")
                    .replace("APP_NAME=server", "APP_NAME=forge")
            )
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))
    implementation(project(":scripting"))
    implementation(project(":between"))

    // Pekko (for actor system hosting + cluster sharding)
    implementation("org.apache.pekko:pekko-actor-typed${pekkoScalaSuffix}:${pekkoVersion}")
    implementation("org.apache.pekko:pekko-cluster-sharding-typed${pekkoScalaSuffix}:${pekkoVersion}")

    // Javalin (HTTP + WebSocket, embedded Jetty 12)
    implementation("io.javalin:javalin:7.1.0")

    // Apache SSHD (SSH adapter)
    implementation("org.apache.sshd:sshd-core:2.17.1")

    // Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    // ZXing core — ASCII QR rendering for /invite phone
    implementation("com.google.zxing:core:3.5.3")

    // Test
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-persistence-typed${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.apache.pekko:pekko-persistence-jdbc${pekkoScalaSuffix}:1.1.0")
    testImplementation("org.apache.pekko:pekko-serialization-jackson${pekkoScalaSuffix}:${pekkoVersion}")
    testImplementation("org.xerial:sqlite-jdbc:3.51.2.0")
}

tasks.withType<Test> {
    jvmArgs(
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        "-XX:+UseCompactObjectHeaders"
    )
}
