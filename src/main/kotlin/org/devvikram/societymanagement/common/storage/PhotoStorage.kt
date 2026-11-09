package org.devvikram.societymanagement.common.storage

// Where uploaded photos live. Callers keep only the returned key, so the backing store can change
// (database today, S3 or similar later) without touching the features that use it.
interface PhotoStorage {
    fun save(data: ByteArray, contentType: String): String
    fun load(key: String): Photo?
    fun delete(key: String)
}

class Photo(val data: ByteArray, val contentType: String)

// Image formats we accept, recognised from the file's leading bytes rather than the client-supplied
// Content-Type, so what we store and later serve is what we claim it is.
object ImageFormat {
    const val MAX_BYTES = 5 * 1024 * 1024

    fun detect(data: ByteArray): String? = when {
        data.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        data.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
        data.size >= 12 && data.startsWith(0x52, 0x49, 0x46, 0x46) &&
            data.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> "image/webp"
        else -> null
    }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}

// Supporting documents (bills, receipts, bank proofs): the image formats above, or a PDF. Also recognised
// from the leading bytes. Photos of people (visitors) stay images only and use ImageFormat.
object DocumentFormat {
    const val MAX_BYTES = ImageFormat.MAX_BYTES

    fun detect(data: ByteArray): String? = ImageFormat.detect(data) ?: when {
        data.size >= 5 && String(data, 0, 5, Charsets.US_ASCII) == "%PDF-" -> "application/pdf"
        else -> null
    }
}
