plugins {
    application
}

val jacksonVersion: String by extra

application {
    mainClass.set("org.wyrdsekai.rendezvous.RendezvousMain")
    applicationName = "wyrd-rendezvous"
}

dependencies {
    implementation(project(":common"))
    implementation(project(":core"))

    // HTTP + SSE. Same Javalin version the main server uses.
    implementation("io.javalin:javalin:7.1.0")

    // JSON + serialization.
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")

    // Lucene — keyword search with BM25 + tokenization + multi-field boosts.
    // Same version the main project uses (core module pulls it for Library/Study).
    implementation("org.apache.lucene:lucene-core:10.4.0")
    implementation("org.apache.lucene:lucene-analysis-common:10.4.0")
    implementation("org.apache.lucene:lucene-queryparser:10.4.0")

    testImplementation("org.xerial:sqlite-jdbc:3.51.2.0")
}
