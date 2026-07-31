package org.wyrdsekai.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/** Application context holder — initialized from MainActivity alongside TokenStore.init. */
object PlatformContext {
    @Volatile
    var app: Context? = null
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }
}

actual object AppProps {
    actual fun get(key: String): String? = System.getProperty(key)
    actual fun set(key: String, value: String) {
        System.setProperty(key, value)
    }
}

actual object AppFiles {
    actual fun mkdirs(dir: String) {
        File(dir).mkdirs()
    }

    actual fun exists(path: String): Boolean = File(path).exists()

    actual fun readText(path: String): String? = try {
        val f = File(path)
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    } catch (_: Exception) {
        null
    }

    actual fun writeTextAtomic(path: String, text: String) {
        val file = File(path)
        val tmp = File("$path.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        tmp.renameTo(file)
    }

    actual fun appendText(path: String, text: String) {
        File(path).appendText(text, Charsets.UTF_8)
    }

    actual fun listFileNames(dir: String): List<String> =
        File(dir).listFiles()?.map { it.name } ?: emptyList()

    actual fun delete(path: String) {
        File(path).delete()
    }
}

actual fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(n: Int): ByteArray =
    ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

actual fun localIpv4Addresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces()
        ?.toList()
        ?.flatMap { it.inetAddresses.toList() }
        ?.filter { !it.isLoopbackAddress && it is Inet4Address }
        ?.mapNotNull { it.hostAddress }
        ?: emptyList()
} catch (_: Exception) {
    emptyList()
}

actual fun readBundledText(resourcePath: String): String? = try {
    AppProps::class.java.getResourceAsStream(resourcePath)
        ?.bufferedReader()
        ?.use { it.readText() }
} catch (_: Exception) {
    null
}

actual fun openUrlInBrowser(url: String): Boolean {
    val ctx = PlatformContext.app ?: return false
    return try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }
}
