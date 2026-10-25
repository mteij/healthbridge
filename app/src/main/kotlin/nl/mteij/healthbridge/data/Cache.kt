package nl.mteij.healthbridge.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Last good API responses on disk, so screens open instantly and refresh in the background. */
class Cache(context: Context) {
    private val dir = File(context.cacheDir, "api").apply { mkdirs() }

    private fun file(key: String) = File(dir, MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) })

    fun read(key: String): String? = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()

    fun write(key: String, text: String) {
        runCatching { file(key).writeText(text) }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
