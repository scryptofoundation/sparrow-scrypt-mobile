package com.sparrowwallet.mobile

/**
 * Decides what an inscription preview may show. The bytes come from the public blockchain, so they are attacker
 * chosen: images are recognised by their bytes (never the declared type) and handed to the platform decoder only when
 * the header declares a modest size; text is shown as plain text with spoofing characters removed; everything else
 * (HTML, SVG, XML, video, audio, models, PDF, encoded or unknown content) is a placeholder. Plain Kotlin, no Android.
 * Mirrors the desktop InscriptionPreview, with stricter limits.
 */
object PreviewRules {
    enum class Kind { IMAGE, TEXT, PLACEHOLDER }

    const val MAX_CONTENT_BYTES = 400_000
    const val MAX_TEXT_CHARS = 4_000
    const val MAX_SIDE = 4_096
    const val MAX_PIXELS = 2_048L * 2_048L

    private const val MAX_JPEG_STEPS = 1_000
    private const val MAX_GIF_STEPS = 100_000

    fun kind(contentType: String?, contentEncoding: String?, body: ByteArray): Kind {
        // Any encoding at all (br, gzip, even identity) means the bytes are not what they appear to be
        if (!contentEncoding.isNullOrBlank()) return Kind.PLACEHOLDER
        // Bytes decide, as on the desktop: a declared image type with other bytes is a placeholder, and image bytes
        // under a non-image declared type are still an image, but only if they pass the size checks
        if (isPng(body) || isJpeg(body) || isGif(body) || isWebp(body)) {
            return if (safeToDecode(body)) Kind.IMAGE else Kind.PLACEHOLDER
        }
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return Kind.PLACEHOLDER
        return if (type == "text/plain" || type == "application/json") Kind.TEXT else Kind.PLACEHOLDER
    }

    /** Width and height declared by the header, or null when unsupported, truncated or not positive. */
    fun dimensions(body: ByteArray): Pair<Int, Int>? {
        val d = try {
            when {
                isPng(body) -> pngDimensions(body)
                isJpeg(body) -> jpegDimensions(body)
                isGif(body) -> gifDimensions(body)
                isWebp(body) -> webpDimensions(body)
                else -> null
            }
        } catch (e: RuntimeException) {
            null
        }
        return if (d != null && d.first > 0 && d.second > 0) d else null
    }

    /** Number of frames in a GIF (at least 1); 1 for every other format. */
    fun frames(body: ByteArray): Int = if (isGif(body)) maxOf(1, gifFrames(body)) else 1

    fun safeToDecode(body: ByteArray): Boolean {
        if (body.size > MAX_CONTENT_BYTES) return false
        val (w, h) = dimensions(body) ?: return false
        if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE) return false
        // Every frame of an animated GIF may be decoded, so the budget covers all of them
        return w.toLong() * h.toLong() * frames(body).toLong() <= MAX_PIXELS
    }

    /** UTF-8 text without control or format characters (bidi overrides, zero width, BOM) except newline and tab, capped. */
    fun text(body: ByteArray): String {
        val decoded = String(body, Charsets.UTF_8)
        val out = StringBuilder()
        var i = 0
        while (i < decoded.length) {
            val cp = decoded.codePointAt(i)
            val n = Character.charCount(cp)
            if (out.length + n > MAX_TEXT_CHARS) break
            if (cp == '\n'.code || cp == '\t'.code || !isStripped(cp)) out.appendCodePoint(cp)
            i += n
        }
        return out.toString()
    }

    /**
     * Largest power of two such that the decoded longest side is still at least [target], so the picture is never
     * upscaled. 1 when the size or the target is not positive.
     */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val side = maxOf(width, height)
        var sample = 1
        while (sample < (1 shl 29) && side / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun isStripped(cp: Int): Boolean {
        if (Character.isISOControl(cp)) return true
        return when (Character.getType(cp)) {
            Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
            else -> false
        }
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff
    private fun be16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun le24(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8) or (u8(b, i + 2) shl 16)
    private fun be32(b: ByteArray, i: Int): Int = (u8(b, i) shl 24) or (u8(b, i + 1) shl 16) or (u8(b, i + 2) shl 8) or u8(b, i + 3)
    private fun tag(b: ByteArray, i: Int, s: String): Boolean {
        if (i < 0 || i + s.length > b.size) return false
        for (k in s.indices) if (b[i + k].toInt() != s[k].code) return false
        return true
    }

    private fun isPng(b: ByteArray) = b.size >= 8 && u8(b, 0) == 0x89 && tag(b, 1, "PNG") &&
        u8(b, 4) == 0x0d && u8(b, 5) == 0x0a && u8(b, 6) == 0x1a && u8(b, 7) == 0x0a
    private fun isJpeg(b: ByteArray) = b.size >= 3 && u8(b, 0) == 0xff && u8(b, 1) == 0xd8 && u8(b, 2) == 0xff
    private fun isGif(b: ByteArray) = tag(b, 0, "GIF87a") || tag(b, 0, "GIF89a")
    private fun isWebp(b: ByteArray) = tag(b, 0, "RIFF") && tag(b, 8, "WEBP")

    private fun pngDimensions(b: ByteArray): Pair<Int, Int>? {
        // The first chunk must be IHDR, 13 bytes long
        if (b.size < 24 || be32(b, 8) != 13 || !tag(b, 12, "IHDR")) return null
        return be32(b, 16) to be32(b, 20)
    }

    private fun gifDimensions(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 10) return null
        return le16(b, 6) to le16(b, 8)
    }

    private fun jpegDimensions(b: ByteArray): Pair<Int, Int>? {
        var pos = 2
        var steps = 0
        while (pos + 2 <= b.size && steps++ < MAX_JPEG_STEPS) {
            if (u8(b, pos) != 0xff) return null
            val marker = u8(b, pos + 1)
            if (marker == 0xff) { pos++; continue }                  // fill byte
            if (marker == 0x00 || marker == 0x01 || marker in 0xd0..0xd8) { pos += 2; continue } // no length field
            if (marker == 0xd9 || marker == 0xda) return null        // end of image or scan data before any frame header
            if (pos + 4 > b.size) return null
            val length = be16(b, pos + 2)
            if (length < 2 || pos + 2 + length > b.size) return null // hostile or truncated segment
            val sof = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
            if (sof) {
                if (length < 8) return null
                return be16(b, pos + 7) to be16(b, pos + 5)          // width, height
            }
            pos += 2 + length
        }
        return null
    }

    private fun webpDimensions(b: ByteArray): Pair<Int, Int>? {
        when {
            tag(b, 12, "VP8 ") -> {
                // 3 byte frame tag, start code 9d 01 2a, then 14 bit width and height
                if (b.size < 30 || u8(b, 23) != 0x9d || u8(b, 24) != 0x01 || u8(b, 25) != 0x2a) return null
                return (le16(b, 26) and 0x3fff) to (le16(b, 28) and 0x3fff)
            }
            tag(b, 12, "VP8L") -> {
                if (b.size < 25 || u8(b, 20) != 0x2f) return null
                val bits = u8(b, 21) or (u8(b, 22) shl 8) or (u8(b, 23) shl 16) or (u8(b, 24) shl 24)
                return ((bits and 0x3fff) + 1) to (((bits ushr 14) and 0x3fff) + 1)
            }
            tag(b, 12, "VP8X") -> {
                if (b.size < 30) return null
                return (le24(b, 24) + 1) to (le24(b, 27) + 1)
            }
        }
        return null
    }

    // Walks the GIF blocks with bounds checks; stops at the trailer, on anything unknown or at the end of the array
    private fun gifFrames(b: ByteArray): Int {
        if (b.size < 13) return 0
        var pos = 13
        val flags = u8(b, 10)
        if (flags and 0x80 != 0) pos += 3 * (1 shl ((flags and 7) + 1))
        var frames = 0
        var steps = 0
        while (pos < b.size && steps++ < MAX_GIF_STEPS) {
            when (u8(b, pos)) {
                0x2c -> {
                    frames++
                    if (pos + 10 > b.size) break
                    val local = u8(b, pos + 9)
                    pos += 10
                    if (local and 0x80 != 0) pos += 3 * (1 shl ((local and 7) + 1))
                    pos++ // LZW minimum code size
                    pos = skipSubBlocks(b, pos)
                }
                0x21 -> pos = skipSubBlocks(b, pos + 2)
                else -> break // trailer (0x3b) or garbage
            }
            if (pos < 0) break
        }
        return frames
    }

    // Returns the position after the terminating zero length block, or -1 when the data runs out first
    private fun skipSubBlocks(b: ByteArray, start: Int): Int {
        var pos = start
        while (pos < b.size) {
            val size = u8(b, pos)
            pos += 1 + size
            if (size == 0) return pos
        }
        return -1
    }
}
