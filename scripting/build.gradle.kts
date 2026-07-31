dependencies {
    implementation(project(":common"))

    // GraalJS engine
    implementation("org.graalvm.polyglot:polyglot:25.0.2")
    implementation("org.graalvm.polyglot:js:25.0.2")

    // SQLite JDBC for ScriptDatabase (sandboxed DB access for SKILL_DATA+)
    implementation("org.xerial:sqlite-jdbc:3.51.2.0")
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}
