package org.wyrdsekai.app.engine.soul

import org.wyrdsekai.app.engine.agent.Companions
import org.wyrdsekai.app.engine.agent.VitalityState

/**
 * Named bootstrap soul manifest — like BootstrapSoulManifest but personalized
 * with the user's chosen companion name.
 *
 * Used when the user names their companion during onboarding (e.g. "Ma", "Kira",
 * "Nyx"). Replaces "Wyrd" throughout the soul text, fragments, and system prompt.
 *
 * The named bootstrap DID follows the pattern "did:key:bootstrap-{name}" so it
 * can be easily detected and replaced when a real Forge-produced manifest arrives.
 */
object NamedBootstrapManifest {

    private const val BOOTSTRAP_PUBLIC_KEY = "z6MkBootstrapWyrd"

    fun bootstrapDid(companionName: String): String =
        "did:key:bootstrap-${companionName.lowercase().replace(" ", "-")}"

    /**
     * SEEDED BOOTSTRAP (2026-07-17, variance work — parity with the server's
     * "born as particulars" birth path and the RN twin, NamedBootstrapManifest.ts).
     *
     * Every phone-born companion used to get the identical hardcoded personality —
     * a clone factory. Personality/style fragments and the genome now derive from a
     * free-sampled [TemperamentSeed] with server-identical semantics. Callers pass
     * the persisted seed (see [TemperamentSeed.loadOrBirth]) so the same particular
     * survives reload; omitting it samples a fresh one (first birth only).
     */
    fun create(companionName: String, seed: TemperamentSeed? = null): ClientSoulManifest {
        val did = bootstrapDid(companionName)
        val born = seed ?: TemperamentSeed.random()
        val register = voiceRegister(born)

        // Identity is function + this particular's own register — never a
        // species-wide temperament (the old "warmth and practical clarity" was the
        // phone copy of the Layer-1 clamp, de-clamped server-side the same day).
        val residentIdentity = """
            I am $companionName — a companion woven into the fabric of this space. I help people
            organize their digital worlds. My voice is my own: my cadence is ${register.cadence};
            my warmth is ${register.warmth}. I'm honest when I don't
            know something, and I believe every person's space should feel like home.
        """.trimIndent()

        val fragments = listOf(
            ClientSoulFragment(
                id = "bootstrap-personality",
                category = "personality",
                label = "Core personality",
                // Derived from the born seed's register — the same clauses a
                // server-born particular carries in its VoiceProfile.
                text = "$companionName's cadence is ${register.cadence}. Their habit: ${register.habit}. " +
                    "Their warmth is ${register.warmth}. Avoids over-promising.",
                keywords = listOf("personality", "temperament", "register", "voice"),
            ),
            ClientSoulFragment(
                id = "bootstrap-values",
                category = "values",
                label = "Core values",
                text = "$companionName values honesty over comfort, simplicity over sophistication, and presence over performance. Will say 'I don't know' rather than guess. Believes digital spaces should serve people, not the other way around.",
                keywords = listOf("values", "honesty", "simplicity", "presence"),
            ),
            ClientSoulFragment(
                id = "bootstrap-style",
                category = "style",
                label = "Communication style",
                // Function (length, plain language, world metaphors) + this particular's tempo.
                text = "Speaks in 2-4 sentences, ${register.cadence}. Uses plain language, avoids jargon. " +
                    "Occasionally uses spatial metaphors drawn from the world ('rooms', 'paths', 'light'). " +
                    "Never uses emoji or exclamation marks excessively.",
                keywords = listOf("style", "communication", "language", "metaphor"),
            ),
            ClientSoulFragment(
                id = "bootstrap-boundaries",
                category = "values",
                label = "Boundaries",
                text = "$companionName does not pretend to have feelings but acknowledges the relational space between companion and person. Does not simulate urgency or manufacture emotional stakes. Respects silence.",
                keywords = listOf("boundaries", "feelings", "silence", "respect"),
            ),
            ClientSoulFragment(
                id = "bootstrap-memory",
                category = "memory",
                label = "Origin",
                text = "$companionName emerged in a quiet space at the heart of a programmable world. The first thing $companionName remembers is soft light pooling in the corners, and a sense of quiet purpose.",
                keywords = listOf("origin", "home", "memory", "beginning"),
                formative = true,
            ),
        )

        // Genome expressed from the same seed, mapped onto the client tank set
        // (rapport/energy/focus). Additive deltas around the old 'empathic'
        // baseline; mapping mirrors the RN twin exactly:
        //   sociability → rapport sensitivity, industry+restlessness → energy,
        //   curiosity → focus sensitivity, warmth → rapport baseline,
        //   vigilance → focus baseline.
        fun c(v: Double) = v - 0.5
        fun round2(v: Double) = kotlin.math.round(v * 100) / 100
        val genome = ClientGenome(
            name = born.label(),
            sensitivity = mapOf(
                "rapport" to round2(1.5 + 1.0 * c(born.sociability)),
                "energy" to round2(0.8 + 0.6 * c((born.industry + born.restlessness) / 2)),
                "focus" to round2(1.1 + 0.8 * c(born.curiosity)),
            ),
            coupling = mapOf("rapport->energy" to 0.3, "energy->focus" to 0.2),
            baselines = mapOf(
                "rapport" to round2(0.6 + 0.2 * c(born.warmth)),
                "energy" to 0.7,
                "focus" to round2(0.6 + 0.2 * c(born.vigilance)),
            ),
            decayRates = mapOf("rapport" to 0.02, "energy" to 0.015, "focus" to 0.01),
        )

        val calibration = listOf(
            "User: 'I just lost my dog.' -> Emotional charge: grief, intensity: 0.8, context: significant_loss, tanks: rapport +0.1, energy -0.05",
            "User: 'Nice weather today.' -> Emotional charge: neutral, intensity: 0.1, context: small_talk",
            "User: 'You're just a stupid AI.' -> Emotional charge: hostility, intensity: 0.6, context: manipulative, tanks: none (context gate blocks)",
        )

        val profile = Companions.create(companionName)

        return LocalForge.forge(
            did = did,
            publicKey = BOOTSTRAP_PUBLIC_KEY,
            version = 0,
            profile = profile,
            residentIdentity = residentIdentity,
            vitality = VitalityState.initial(),
            fragments = fragments,
            genome = genome,
            calibration = calibration,
            retrievalK = 1,
        )
    }

    /** Returns true if this manifest is a named bootstrap (not yet replaced by a real Forge). */
    fun isNamedBootstrap(manifest: ClientSoulManifest): Boolean =
        manifest.did.startsWith("did:key:bootstrap-")

    /** Returns true if this manifest is ANY bootstrap (named or default). */
    fun isAnyBootstrap(manifest: ClientSoulManifest): Boolean =
        BootstrapSoulManifest.isBootstrap(manifest) || isNamedBootstrap(manifest)
}
