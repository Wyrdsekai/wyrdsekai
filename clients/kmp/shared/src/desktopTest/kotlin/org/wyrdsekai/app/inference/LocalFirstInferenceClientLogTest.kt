package org.wyrdsekai.app.inference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LocalFirstInferenceClient (Android) logs a request the way CompanionEngine's
 * debugLog does: one line, a kotlin.time instant, into wyrdsekai.data.dir, and
 * nothing when that is unset. It wrote two java.util.Date lines per request and
 * fell back to /tmp.
 *
 * A source scan: androidMain has no unit-test source set and the class cannot be
 * loaded on the desktop JVM.
 */
class LocalFirstInferenceClientLogTest {

    @Test
    fun it_logs_one_line_into_the_data_dir_and_nothing_without_one() {
        val src = File(srcRoot(), "androidMain/kotlin/org/wyrdsekai/app/inference/LocalFirstInferenceClient.kt")
            .readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/") }
            .joinToString("\n")
        assertFalse(src.contains("/tmp"), "no /tmp fallback")
        assertFalse(src.contains("java.util.Date"), "the engine's timestamp, not java.util.Date")
        assertFalse(src.contains("java.io.File"), "through AppFiles, as the engine writes")
        assertTrue(src.contains("val dir = AppProps.get(\"wyrdsekai.data.dir\") ?: return"),
            "no data dir, no log")
        assertTrue(src.contains("\"\${Clock.System.now()}: LocalFirstInferenceClient: \$msg\\n\""))

        val send = src.substringAfter("override suspend fun send(").substringBefore("private fun log(")
        assertEquals(2, Regex("""\blog\(""").findAll(send).count(), "one line on each branch, JNI or HTTP")
    }

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
}
