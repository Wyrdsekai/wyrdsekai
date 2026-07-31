@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package org.wyrdsekai.app.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs
import platform.darwin.inet_ntop
import platform.posix.AF_INET
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.INET_ADDRSTRLEN
import platform.posix.sockaddr_in
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile
import platform.UIKit.UIApplication
import kotlin.concurrent.AtomicReference

actual object AppProps {
    // Copy-on-write map — props are written at startup/config time and
    // read from many places; this keeps it race-free without locks.
    private val props = AtomicReference<Map<String, String>>(emptyMap())

    actual fun get(key: String): String? = props.value[key]

    actual fun set(key: String, value: String) {
        while (true) {
            val current = props.value
            if (props.compareAndSet(current, current + (key to value))) return
        }
    }
}

actual object AppFiles {
    actual fun mkdirs(dir: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(
            dir, withIntermediateDirectories = true, attributes = null, error = null
        )
    }

    actual fun exists(path: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(path)

    actual fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    actual fun writeTextAtomic(path: String, text: String) {
        @Suppress("CAST_NEVER_SUCCEEDS")
        (text as NSString).writeToFile(
            path, atomically = true, encoding = NSUTF8StringEncoding, error = null
        )
    }

    actual fun appendText(path: String, text: String) {
        val existing = readText(path) ?: ""
        writeTextAtomic(path, existing + text)
    }

    actual fun listFileNames(dir: String): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null)
            ?.filterIsInstance<String>()
            ?: emptyList()

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
}

actual fun sha256(bytes: ByteArray): ByteArray {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    if (bytes.isEmpty()) {
        digest.usePinned { out -> CC_SHA256(null, 0u, out.addressOf(0)) }
    } else {
        bytes.usePinned { pinned ->
            digest.usePinned { out ->
                CC_SHA256(pinned.addressOf(0), bytes.size.convert(), out.addressOf(0))
            }
        }
    }
    return digest.asByteArray()
}

actual fun secureRandomBytes(n: Int): ByteArray {
    if (n <= 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, n.convert(), pinned.addressOf(0))
    }
    return out
}


actual fun localIpv4Addresses(): List<String> = memScoped {
    val out = mutableListOf<String>()
    val ifap = allocPointerTo<ifaddrs>()
    if (getifaddrs(ifap.ptr) != 0) return@memScoped emptyList()
    try {
        var cursor = ifap.value
        while (cursor != null) {
            val ifa = cursor.pointed
            val addr = ifa.ifa_addr
            val flags = ifa.ifa_flags.toInt()
            if (addr != null &&
                addr.pointed.sa_family.toInt() == AF_INET &&
                flags and IFF_UP != 0 &&
                flags and IFF_LOOPBACK == 0
            ) {
                val sin = addr.reinterpret<sockaddr_in>()
                val buf = allocArray<ByteVar>(INET_ADDRSTRLEN)
                if (inet_ntop(AF_INET, sin.pointed.sin_addr.ptr, buf, INET_ADDRSTRLEN.convert()) != null) {
                    val ip = buf.toKString()
                    if (ip.isNotBlank() && ip != "0.0.0.0") out.add(ip)
                }
            }
            cursor = ifa.ifa_next
        }
    } finally {
        freeifaddrs(ifap.value)
    }
    out
}

actual fun readBundledText(resourcePath: String): String? {
    val trimmed = resourcePath.trimStart('/')
    val name = trimmed.substringBeforeLast('.')
    val ext = trimmed.substringAfterLast('.', "")
    // Resources land in the bundle as a real subdirectory (blue folder
    // reference), so try the subpath-in-name form first, then the
    // explicit inDirectory variant.
    val path = NSBundle.mainBundle.pathForResource(name, ofType = ext.ifEmpty { null })
        ?: trimmed.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { dir ->
            val file = trimmed.substringAfterLast('/').substringBeforeLast('.')
            NSBundle.mainBundle.pathForResource(file, ofType = ext.ifEmpty { null }, inDirectory = dir)
        }
        ?: return null
    return NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)
}

actual fun openUrlInBrowser(url: String): Boolean {
    val nsUrl = NSURL.URLWithString(url) ?: return false
    return UIApplication.sharedApplication.openURL(nsUrl)
}
