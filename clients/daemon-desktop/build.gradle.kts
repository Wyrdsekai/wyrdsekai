plugins {
    application
}

val jacksonVersion: String by extra

dependencies {
    implementation(project(":clients:daemon-common"))

    // Jackson for HTTP response parsing
    implementation("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
}

application {
    mainClass.set("org.wyrdsekai.daemon.desktop.DaemonApp")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.wyrdsekai.daemon.desktop.DaemonApp"
    }
}
