rootProject.name = "wyrdsekai"

include("common", "core", "scripting", "between", "server", "cli", "e2e-test", "rendezvous")

// Inference daemon modules (desktop only — Android daemon is a separate Gradle project)
include("clients:daemon-common", "clients:daemon-desktop")
