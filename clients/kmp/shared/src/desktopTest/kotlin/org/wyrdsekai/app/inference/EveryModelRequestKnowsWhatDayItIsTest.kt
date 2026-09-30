package org.wyrdsekai.app.inference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every request to a model in the phone client says what it knows about today
 * (the steward's rule, decided 2026-03-28, said again 2026-09-23). Mirrors
 * core's EveryModelRequestKnowsWhatDayItIsTest: a new path that builds
 * CompletionOptions without `now =`, or reaches a model past the send points,
 * fails here with the file and line, before it reaches her.
 *
 * Desktop-only because it reads the source tree.
 */
class EveryModelRequestKnowsWhatDayItIsTest {

    /** Where options are declared, defaulted or passed through, not made for a request. */
    private val plumbing = setOf(
        "InferenceTypes.kt", "InferenceClient.kt", "InferenceRouter.kt", "NowLine.kt",
        "LocalFirstInferenceClient.kt", "LlamaServerManager.kt", "LlamaServerManager.android.kt",
        "LlamaServerManager.desktop.kt", "LlamaServerManager.ios.kt",
    )

    /**
     * Paths that reach a model past [InferenceClient.complete] and
     * [InferenceRouter.complete], each with why it carries what it carries.
     * A new direct path fails the scan until it is named here.
     */
    private val bypass = mapOf(
        "InferenceViewModel.kt" to "the model smoke test (NONE): not her",
        "HermodEngine.kt" to "runs another node's request (NONE): stamped where it was made",
        "NodeManager.android.kt" to "hands hermod's knock to the local model with the knock's own options",
    )

    private val direct = Regex("""\.completeLocal\(|"[^"\n]*/chat/completions""")

    @Test
    fun every_request_to_a_model_says_what_it_knows_about_today() {
        val missing = mutableListOf<String>()
        val directs = mutableListOf<String>()
        for (file in mainSources()) {
            if (file.name in plumbing) continue
            val src = file.readText()
            var at = src.indexOf("CompletionOptions(")
            while (at >= 0) {
                val args = arguments(src, at + "CompletionOptions".length)
                if (!args.contains("now =")) missing += "${file.name}:${line(src, at)}  CompletionOptions$args"
                at = src.indexOf("CompletionOptions(", at + 1)
            }
            for (m in direct.findAll(src)) {
                if (file.name !in bypass) directs += "${file.name}:${line(src, m.range.first)}"
            }
        }
        assertEquals(emptyList(), missing,
            "declare what each request knows about today: now = NowLine.dateTime() (she speaks, " +
                "thinks or acts), NowLine.date() (a single-shot made for her) or NowLine.NONE " +
                "(a rewrite, a classifier, a pass-through)")
        assertEquals(emptyList(), directs,
            "a model call past InferenceClient.complete / InferenceRouter.complete: declare its " +
                "NowLine and name it in bypass, with why it carries what it carries")
    }

    @Test
    fun each_bypass_named_here_still_exists() {
        val byName = mainSources().associateBy { it.name }
        for (name in bypass.keys) {
            val file = byName[name]
            assertTrue(file != null, "$name is gone: drop it from bypass")
            assertTrue(direct.containsMatchIn(file.readText()), "$name no longer calls a model directly")
        }
    }

    @Test
    fun her_turns_carry_date_and_time_and_the_rest_carry_none() {
        val engine = read("commonMain/kotlin/org/wyrdsekai/app/engine/agent/CompanionEngine.kt")
        assertEquals(4, count(engine, "now = NowLine.dateTime()"),
            "study command, quick path, raw remote and acknowledgement are her turns")
        assertEquals(1, count(engine, "now = NowLine.dateTime(asked = asked)"),
            "the offline replay is answered now and says when it was asked")

        for (path in listOf(
            "commonMain/kotlin/org/wyrdsekai/app/engine/agent/TriageClassifier.kt",
            "commonMain/kotlin/org/wyrdsekai/app/engine/soul/LlmExtractor.kt",
            "commonMain/kotlin/org/wyrdsekai/app/engine/soul/IdentityEvolver.kt",
            "commonMain/kotlin/org/wyrdsekai/app/engine/soul/SoulAuthoring.kt",
            "commonMain/kotlin/org/wyrdsekai/app/viewmodel/InferenceViewModel.kt",
            "commonMain/kotlin/org/wyrdsekai/app/hermod/HermodEngine.kt",
        )) {
            assertEquals(1, count(read(path), "now = NowLine.NONE"), path)
        }
    }

    @Test
    fun the_send_points_stamp_and_cannot_be_stepped_around() {
        val client = read("commonMain/kotlin/org/wyrdsekai/app/inference/InferenceClient.kt")
        assertFalse(client.contains("open suspend fun complete("),
            "complete stamps; subclasses override send, so none can skip the stamp")
        // One leading system message first, then the stamp, as core's router does:
        // the stamp is never moved or doubled by the merge.
        assertTrue(client.contains(
            "send(baseUrl, NowLine.stampToday(options.now, consolidateSystemMessages(messages)), options)"))
        // The household leg (completeAt) stamps the same way, and goes over HTTP
        // past send, so the on-device override cannot take it.
        assertTrue(client.contains(
            "sendHttp(baseUrl, NowLine.stampToday(options.now, consolidateSystemMessages(messages)), options)"))
        assertTrue(client.contains("private suspend fun sendHttp("), "no subclass can reroute the household leg")

        val router = read("commonMain/kotlin/org/wyrdsekai/app/inference/InferenceRouter.kt")
        assertTrue(router.contains(
            "val stamped = NowLine.stampToday(options.now, consolidateSystemMessages(messages))"))

        val localFirst = read("androidMain/kotlin/org/wyrdsekai/app/inference/LocalFirstInferenceClient.kt")
        assertTrue(localFirst.contains("override suspend fun send("),
            "the on-device path formats the copy complete() already stamped")

        val time = read("commonMain/kotlin/org/wyrdsekai/app/engine/agent/TimeContext.kt")
        assertFalse(time.contains("Current time"), "the date is not a trimmable layer any more")
    }

    // ── scanning helpers ───────────────────────────────────────────────────

    private fun srcRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src"), File(dir, "shared/src"))) {
                if (File(candidate, "commonMain/kotlin").isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("shared/src not found from ${System.getProperty("user.dir")}")
    }

    private fun mainSources(): List<File> =
        listOf("commonMain", "androidMain", "desktopMain", "iosMain").flatMap { set ->
            File(srcRoot(), "$set/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    private fun read(rel: String) = File(srcRoot(), rel).readText()

    /** The call's argument list from its open paren to the matching close, skipping strings. */
    private fun arguments(src: String, open: Int): String {
        var depth = 0
        var j = open
        while (j < src.length) {
            val c = src[j]
            if (c == '"') {
                j++
                while (j < src.length && src[j] != '"') {
                    if (src[j] == '\\') j++
                    j++
                }
            } else if (c == '(') {
                depth++
            } else if (c == ')') {
                depth--
                if (depth == 0) return src.substring(open, j + 1)
            }
            j++
        }
        return src.substring(open)
    }

    private fun count(src: String, needle: String): Int {
        var n = 0
        var i = src.indexOf(needle)
        while (i >= 0) {
            n++
            i = src.indexOf(needle, i + 1)
        }
        return n
    }

    private fun line(src: String, at: Int): Int = src.substring(0, at).count { it == '\n' } + 1
}
