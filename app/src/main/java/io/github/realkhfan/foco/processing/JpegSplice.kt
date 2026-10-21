package io.github.realkhfan.foco.processing

/**
 * Inserts an Exif block into a JPEG without re-encoding it. Ultra HDR JPEGs keep working because their
 * gain-map offsets are relative to their own segment.
 */
object JpegSplice {
    /** The APP1 "Exif" segment (marker included) of [jpeg], or null. */
    fun exifSegment(jpeg: ByteArray): ByteArray? {
        var p = 2
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        while (p + 4 <= jpeg.size && jpeg[p] == 0xFF.toByte()) {
            val marker = jpeg[p + 1].toInt() and 0xff
            if (marker == 0xDA || marker == 0xD9) return null
            val len = ((jpeg[p + 2].toInt() and 0xff) shl 8) or (jpeg[p + 3].toInt() and 0xff)
            if (marker == 0xE1 && p + 10 <= jpeg.size && String(jpeg, p + 4, 4, Charsets.US_ASCII) == "Exif") {
                return jpeg.copyOfRange(p, p + 2 + len)
            }
            p += 2 + len
        }
        return null
    }

    /** [jpeg] with [app1] placed after SOI (and after a JFIF APP0 if there is one). Existing Exif is replaced. */
    fun insertExif(jpeg: ByteArray, app1: ByteArray): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG" }
        var insertAt = 2
        if (jpeg[2] == 0xFF.toByte() && (jpeg[3].toInt() and 0xff) == 0xE0) {
            val len = ((jpeg[4].toInt() and 0xff) shl 8) or (jpeg[5].toInt() and 0xff)
            insertAt = 2 + 2 + len
        }
        // Drop an existing Exif segment so there is only one.
        var body = jpeg
        val existing = exifSegment(jpeg)
        if (existing != null) {
            val at = indexOf(jpeg, existing)
            if (at >= 0) {
                body = jpeg.copyOfRange(0, at) + jpeg.copyOfRange(at + existing.size, jpeg.size)
                if (at < insertAt) insertAt -= existing.size
            }
        }
        val out = ByteArray(body.size + app1.size)
        System.arraycopy(body, 0, out, 0, insertAt)
        System.arraycopy(app1, 0, out, insertAt, app1.size)
        System.arraycopy(body, insertAt, out, insertAt + app1.size, body.size - insertAt)
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
