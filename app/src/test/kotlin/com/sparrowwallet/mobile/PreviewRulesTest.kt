package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.PreviewRules.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewRulesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun be32(v: Int) = bytes(v ushr 24 and 0xff, v ushr 16 and 0xff, v ushr 8 and 0xff, v and 0xff)
    private fun le16(v: Int) = bytes(v and 0xff, v ushr 8 and 0xff)
    private fun le24(v: Int) = bytes(v and 0xff, v ushr 8 and 0xff, v ushr 16 and 0xff)
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun png(w: Int, h: Int): ByteArray =
        bytes(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0d, 0x0a, 0x1a, 0x0a) + be32(13) + ascii("IHDR") + be32(w) + be32(h) + bytes(8, 6, 0, 0, 0)

    private fun jpeg(w: Int, h: Int, padSegments: Int = 1): ByteArray {
        var b = bytes(0xff, 0xd8)
        repeat(padSegments) { b += bytes(0xff, 0xe0, 0x00, 0x06, 0, 0, 0, 0) } // APP0 with 4 payload bytes
        b += bytes(0xff, 0xc0, 0x00, 0x0b, 8, h ushr 8, h and 0xff, w ushr 8, w and 0xff, 1, 1, 0x11, 0)
        return b
    }

    private fun gif(w: Int, h: Int, frames: Int): ByteArray {
        var b = ascii("GIF89a") + le16(w) + le16(h) + bytes(0x00, 0, 0) // no global color table
        repeat(frames) {
            b += bytes(0x21, 0xf9, 0x04, 0, 0, 0, 0, 0)                // graphic control extension
            b += bytes(0x2c) + le16(0) + le16(0) + le16(w) + le16(h) + bytes(0x00) // image descriptor
            b += bytes(0x02, 0x02, 0x44, 0x01, 0x00)                   // LZW min size, one sub-block, terminator
        }
        return b + bytes(0x3b)
    }

    private fun webpVp8(w: Int, h: Int): ByteArray =
        ascii("RIFF") + le24(0) + bytes(0) + ascii("WEBP") + ascii("VP8 ") + bytes(10, 0, 0, 0) +
            bytes(0x10, 0, 0, 0x9d, 0x01, 0x2a) + le16(w) + le16(h)

    private fun webpVp8l(w: Int, h: Int): ByteArray {
        val packed = (w - 1) or ((h - 1) shl 14)
        return ascii("RIFF") + le24(0) + bytes(0) + ascii("WEBP") + ascii("VP8L") + bytes(5, 0, 0, 0) +
            bytes(0x2f, packed and 0xff, packed ushr 8 and 0xff, packed ushr 16 and 0xff, packed ushr 24 and 0xff)
    }

    private fun webpVp8x(w: Int, h: Int): ByteArray =
        ascii("RIFF") + le24(0) + bytes(0) + ascii("WEBP") + ascii("VP8X") + bytes(10, 0, 0, 0) +
            bytes(0, 0, 0, 0) + le24(w - 1) + le24(h - 1)

    @Test
    fun pngHeader() {
        val b = png(120, 80)
        assertEquals(120 to 80, PreviewRules.dimensions(b))
        assertEquals(1, PreviewRules.frames(b))
        assertEquals(Kind.IMAGE, PreviewRules.kind("image/png", null, b))
    }

    @Test
    fun jpegHeaderWalksSegmentsToTheFrameMarker() {
        val b = jpeg(640, 480, padSegments = 3)
        assertEquals(640 to 480, PreviewRules.dimensions(b))
        assertEquals(Kind.IMAGE, PreviewRules.kind("image/jpeg", null, b))
    }

    @Test
    fun gifHeaderAndFrames() {
        val b = gif(64, 32, 3)
        assertEquals(64 to 32, PreviewRules.dimensions(b))
        assertEquals(3, PreviewRules.frames(b))
        assertEquals(Kind.IMAGE, PreviewRules.kind("image/gif", null, b))
    }

    @Test
    fun webpHeaders() {
        assertEquals(300 to 200, PreviewRules.dimensions(webpVp8(300, 200)))
        assertEquals(301 to 201, PreviewRules.dimensions(webpVp8l(301, 201)))
        assertEquals(1000 to 2000, PreviewRules.dimensions(webpVp8x(1000, 2000)))
        assertEquals(Kind.IMAGE, PreviewRules.kind("image/webp", null, webpVp8(300, 200)))
        assertEquals(Kind.IMAGE, PreviewRules.kind("image/webp", null, webpVp8l(10, 10)))
        assertEquals(Kind.IMAGE, PreviewRules.kind("image/webp", null, webpVp8x(10, 10)))
    }

    @Test
    fun htmlAndSvgArePlaceholders() {
        val html = "<html><script>alert(1)</script></html>".toByteArray()
        val svg = "<svg xmlns='http://www.w3.org/2000/svg'><script>1</script></svg>".toByteArray()
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("text/html", null, html))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("text/html;charset=utf-8", null, html))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("image/svg+xml", null, svg))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("application/xml", null, svg))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("video/mp4", null, bytes(0, 0, 0, 0x18)))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("audio/mpeg", null, bytes(0x49, 0x44, 0x33)))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("model/gltf-binary", null, bytes(1, 2, 3)))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("application/pdf", null, ascii("%PDF-1.7")))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("application/octet-stream", null, bytes(1, 2, 3)))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind(null, null, bytes(1, 2, 3)))
    }

    @Test
    fun declaredPngWithOtherBytesIsAPlaceholder() {
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("image/png", null, "not a png".toByteArray()))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("image/png", null, ByteArray(0)))
        // PNG magic with a corrupt header chunk is not trusted either
        val bad = png(10, 10).also { it[12] = 'X'.code.toByte() }
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("image/png", null, bad))
    }

    @Test
    fun imageBytesWinOverADeclaredNonImageType() {
        // Same as the desktop: bytes decide, and the bytes still have to pass safeToDecode
        assertEquals(Kind.IMAGE, PreviewRules.kind("text/plain", null, png(10, 10)))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("text/plain", null, png(5_000, 10)))
    }

    @Test
    fun textTypesAreText() {
        val b = "hello".toByteArray()
        assertEquals(Kind.TEXT, PreviewRules.kind("text/plain", null, b))
        assertEquals(Kind.TEXT, PreviewRules.kind("text/plain;charset=utf-8", null, b))
        assertEquals(Kind.TEXT, PreviewRules.kind("Application/JSON ; charset=utf-8", null, b))
    }

    @Test
    fun oversizedImageIsNotDecoded() {
        assertTrue(PreviewRules.safeToDecode(png(2_048, 2_048)))
        assertFalse(PreviewRules.safeToDecode(png(5_000, 10)))
        assertFalse(PreviewRules.safeToDecode(png(10, 5_000)))
        assertFalse(PreviewRules.safeToDecode(png(3_000, 3_000)))
        assertFalse(PreviewRules.safeToDecode(png(0, 10)))
        assertFalse(PreviewRules.safeToDecode(png(-1, 10)))
        assertFalse(PreviewRules.safeToDecode(png(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertFalse(PreviewRules.safeToDecode(jpeg(65_535, 65_535)))
        assertFalse(PreviewRules.safeToDecode(webpVp8x(16_000_000, 16_000_000)))
        assertTrue(PreviewRules.safeToDecode(gif(1_024, 1_024, 1)))
        assertFalse(PreviewRules.safeToDecode(gif(1_024, 1_024, 8)))
        assertEquals(PreviewRules.Kind.PLACEHOLDER, PreviewRules.kind("image/png", null, png(5_000, 10)))
        assertEquals(PreviewRules.Kind.PLACEHOLDER, PreviewRules.kind("image/gif", null, gif(1_024, 1_024, 8)))
        // A small header followed by too much body
        val big = png(10, 10) + ByteArray(PreviewRules.MAX_CONTENT_BYTES)
        assertFalse(PreviewRules.safeToDecode(big))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("image/png", null, big))
    }

    @Test
    fun truncatedImageIsAPlaceholder() {
        val samples = listOf(png(10, 10), jpeg(10, 10), gif(10, 10, 1), webpVp8(10, 10), webpVp8l(10, 10), webpVp8x(10, 10))
        for (full in samples) {
            for (cut in 0 until 10) {
                val b = full.copyOf(cut)
                assertNull(PreviewRules.dimensions(b), "cut $cut")
                assertFalse(PreviewRules.safeToDecode(b))
            }
        }
        assertNull(PreviewRules.dimensions(png(10, 10).copyOf(23)))
        assertNull(PreviewRules.dimensions(jpeg(10, 10).copyOf(jpeg(10, 10).size - 3)))
        assertNull(PreviewRules.dimensions(webpVp8(10, 10).copyOf(29)))
        assertNull(PreviewRules.dimensions(webpVp8x(10, 10).copyOf(29)))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("image/png", null, png(10, 10).copyOf(23)))
    }

    @Test
    fun hostileHeadersNeverThrow() {
        // JPEG segment length past the end, and a length below 2
        assertNull(PreviewRules.dimensions(bytes(0xff, 0xd8, 0xff, 0xe0, 0xff, 0xff, 0, 0)))
        assertNull(PreviewRules.dimensions(bytes(0xff, 0xd8, 0xff, 0xe0, 0x00, 0x00, 0, 0)))
        assertNull(PreviewRules.dimensions(bytes(0xff, 0xd8, 0xff, 0xe0, 0x00, 0x01, 0, 0)))
        // JPEG that reaches scan data before any frame marker
        assertNull(PreviewRules.dimensions(bytes(0xff, 0xd8, 0xff, 0xda, 0x00, 0x02, 0, 0, 0)))
        // Endless fill bytes
        assertNull(PreviewRules.dimensions(bytes(0xff, 0xd8) + ByteArray(100_000) { 0xff.toByte() }))
        // Thousands of tiny segments never reach a frame marker within the step budget
        var many = bytes(0xff, 0xd8)
        repeat(20_000) { many += bytes(0xff, 0xe0, 0x00, 0x02) }
        assertNull(PreviewRules.dimensions(many + jpeg(5, 5).copyOfRange(2, jpeg(5, 5).size)))
        // GIF sub-block lengths pointing past the end
        val hostileGif = ascii("GIF89a") + le16(10) + le16(10) + bytes(0x00, 0, 0) +
            bytes(0x2c, 0, 0, 0, 0, 10, 0, 10, 0, 0x80) + ByteArray(6) + bytes(0x02, 0xff, 1, 2, 3)
        assertEquals(10 to 10, PreviewRules.dimensions(hostileGif))
        assertEquals(Int.MAX_VALUE, PreviewRules.frames(hostileGif))
        assertFalse(PreviewRules.safeToDecode(hostileGif))
        // PNG with the high bit set in a dimension, GIF with a zero canvas
        assertNull(PreviewRules.dimensions(png(-5, 5)))
        assertNull(PreviewRules.dimensions(gif(0, 0, 1)))
        // WebP VP8 without the start code
        assertNull(PreviewRules.dimensions(webpVp8(10, 10).also { it[23] = 0 }))
        // Unknown bytes
        assertNull(PreviewRules.dimensions(ByteArray(0)))
        assertNull(PreviewRules.dimensions(ByteArray(64) { 0x41 }))
    }

    @Test
    fun encodedContentIsAPlaceholder() {
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("image/png", "br", png(10, 10)))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("text/plain", "gzip", "hi".toByteArray()))
        assertEquals(Kind.PLACEHOLDER, PreviewRules.kind("text/plain", "identity", "hi".toByteArray()))
        assertEquals(Kind.TEXT, PreviewRules.kind("text/plain", null, "hi".toByteArray()))
        assertEquals(Kind.TEXT, PreviewRules.kind("text/plain", "", "hi".toByteArray()))
        assertEquals(Kind.TEXT, PreviewRules.kind("text/plain", "  ", "hi".toByteArray()))
    }

    @Test
    fun textIsCappedAndStripped() {
        val dirty = "a\u0000b\u0007c\u001bd\u202Ee\u2066f\u200Bg\uFEFFh\u0085i\u2028j\u2029k\nl\tm\r"
        assertEquals("abcdefghijk\nl\tm", PreviewRules.text(dirty.toByteArray(Charsets.UTF_8)))
        val long = "x".repeat(10_000)
        assertEquals(PreviewRules.MAX_TEXT_CHARS, PreviewRules.text(long.toByteArray()).length)
        // Invalid UTF-8 does not throw
        assertTrue(PreviewRules.text(bytes(0xff, 0xfe, 0x41, 0xc3)).contains('A'))
        // A surrogate pair is never split at the cap
        val emoji = "\uD83D\uDE00"
        val s = "y".repeat(PreviewRules.MAX_TEXT_CHARS - 1) + emoji
        val out = PreviewRules.text(s.toByteArray(Charsets.UTF_8))
        assertTrue(out.length <= PreviewRules.MAX_TEXT_CHARS)
        assertFalse(out.last().isHighSurrogate())
        assertEquals(PreviewRules.MAX_TEXT_CHARS - 1, out.length)
        assertEquals("", PreviewRules.text(ByteArray(0)))
    }

    @Test
    fun sampleSizeTable() {
        assertEquals(1, PreviewRules.sampleSize(100, 100, 200))
        assertEquals(1, PreviewRules.sampleSize(200, 200, 200))
        assertEquals(1, PreviewRules.sampleSize(399, 399, 200))
        assertEquals(2, PreviewRules.sampleSize(400, 300, 200))
        assertEquals(4, PreviewRules.sampleSize(800, 800, 200))
        assertEquals(8, PreviewRules.sampleSize(2_048, 100, 256))
        assertEquals(16, PreviewRules.sampleSize(4_096, 4_096, 256))
        assertEquals(1, PreviewRules.sampleSize(0, 0, 200))
        assertEquals(1, PreviewRules.sampleSize(-5, 100, 200))
        assertEquals(1, PreviewRules.sampleSize(1_000, 1_000, 0))
        assertEquals(1, PreviewRules.sampleSize(1_000, 1_000, -4))
    }

    @Test
    fun everyCutOfEveryHeaderIsHandled() {
        val needs = listOf(png(10, 10) to 24, jpeg(10, 10) to jpeg(10, 10).size, gif(10, 10, 1) to 10,
            webpVp8(10, 10) to 30, webpVp8l(10, 10) to 25, webpVp8x(10, 10) to 30)
        for ((full, need) in needs) {
            for (cut in 0..full.size) {
                val b = full.copyOf(cut)
                val d = PreviewRules.dimensions(b)
                PreviewRules.frames(b); PreviewRules.safeToDecode(b); PreviewRules.kind("image/png", null, b)
                if (cut < need) assertNull(d, "size ${full.size} cut $cut") else assertEquals(10 to 10, d)
            }
        }
    }

    @Test
    fun gifFrameCountFailsClosed() {
        // Many empty extensions then many tiny frames on a 512 x 512 screen: every frame is counted
        val ext = bytes(0x21, 0xf9, 0x00)
        val extBlock = ByteArray(ext.size * 99_990) { ext[it % ext.size] }
        val frame = bytes(0x2c) + le16(0) + le16(0) + le16(1) + le16(1) + bytes(0x00, 0x02, 0x00)
        val frames = ByteArray(frame.size * 5_000) { frame[it % frame.size] }
        val many = ascii("GIF89a") + le16(512) + le16(512) + bytes(0, 0, 0) + extBlock + frames + bytes(0x3b)
        assertTrue(many.size < PreviewRules.MAX_CONTENT_BYTES)
        assertEquals(5_000, PreviewRules.frames(many))
        assertFalse(PreviewRules.safeToDecode(many))
        // No trailer
        val noTrailer = gif(10, 10, 1).let { it.copyOf(it.size - 1) }
        assertEquals(Int.MAX_VALUE, PreviewRules.frames(noTrailer))
        assertFalse(PreviewRules.safeToDecode(noTrailer))
        // Unknown block
        val unknown = gif(10, 10, 1).let { it.copyOf(it.size - 1) + bytes(0x99, 0x3b) }
        assertFalse(PreviewRules.safeToDecode(unknown))
    }

    @Test
    fun gifFramesMustStayInsideTheScreen() {
        fun withFrame(l: Int, t: Int, w: Int, h: Int) = ascii("GIF89a") + le16(100) + le16(50) + bytes(0, 0, 0) +
            bytes(0x2c) + le16(l) + le16(t) + le16(w) + le16(h) + bytes(0x00, 0x02, 0x00, 0x3b)
        assertTrue(PreviewRules.safeToDecode(withFrame(0, 0, 100, 50)))
        assertTrue(PreviewRules.safeToDecode(withFrame(90, 40, 10, 10)))
        assertFalse(PreviewRules.safeToDecode(withFrame(91, 0, 10, 10)))
        assertFalse(PreviewRules.safeToDecode(withFrame(0, 41, 10, 10)))
        assertFalse(PreviewRules.safeToDecode(withFrame(0, 0, 101, 10)))
        assertFalse(PreviewRules.safeToDecode(withFrame(0, 0, 0, 10)))
        assertFalse(PreviewRules.safeToDecode(withFrame(0, 0, 10, 0)))
        assertFalse(PreviewRules.safeToDecode(withFrame(65_535, 65_535, 65_535, 65_535)))
    }

    @Test
    fun gifWithRealColourTables() {
        val global = ascii("GIF89a") + le16(4) + le16(4) + bytes(0x80, 0, 0) + ByteArray(6) +
            bytes(0x2c) + le16(0) + le16(0) + le16(4) + le16(4) + bytes(0x80) + ByteArray(6) +
            bytes(0x02, 0x03, 1, 2, 3, 0x00, 0x3b)
        assertEquals(1, PreviewRules.frames(global))
        assertTrue(PreviewRules.safeToDecode(global))
    }

    @Test
    fun jpegAndPngRuleEdges() {
        val dht = bytes(0xff, 0xd8, 0xff, 0xc4, 0x00, 0x04, 0x00, 0x00) +
            bytes(0xff, 0xc0, 0x00, 0x0b, 8, 0, 7, 0, 9, 1, 1, 0x11, 0)
        assertEquals(9 to 7, PreviewRules.dimensions(dht))
        val progressive = bytes(0xff, 0xd8, 0xff, 0xc2, 0x00, 0x0b, 8, 0, 7, 0, 9, 1, 1, 0x11, 0)
        assertEquals(9 to 7, PreviewRules.dimensions(progressive))
        val fill = bytes(0xff, 0xd8, 0xff, 0xff, 0xff, 0xc0, 0x00, 0x0b, 8, 0, 7, 0, 9, 1, 1, 0x11, 0)
        assertEquals(9 to 7, PreviewRules.dimensions(fill))
        assertNull(PreviewRules.dimensions(png(-1, 5)))
        assertNull(PreviewRules.dimensions(png(5, -1)))
        assertTrue(PreviewRules.safeToDecode(png(4_096, 512)))
        assertFalse(PreviewRules.safeToDecode(png(4_097, 1)))
        assertTrue(PreviewRules.safeToDecode(png(4_096, 1_024)))
        assertFalse(PreviewRules.safeToDecode(png(4_096, 1_025)))
    }

    @Test
    fun moreInvisibleCharactersAreStripped() {
        val invisibles = "\u3164\u115F\u1160\u2800\u034F\u180B\u180C\u180D\u180E\u180F" +
            (0xFE00..0xFE0F).joinToString("") { it.toChar().toString() } +
            (0xE0100..0xE01EF).joinToString("") { String(Character.toChars(it)) }
        assertEquals("ab", PreviewRules.text(("a" + invisibles + "b").toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun combiningMarkRunsAreLimitedToTwo() {
        val marks = "\u0301\u0302\u0303\u0304\u0305"
        assertEquals("a\u0301\u0302b\u0301", PreviewRules.text(("a" + marks + "b\u0301").toByteArray(Charsets.UTF_8)))
        assertEquals("\u0301\u0302x", PreviewRules.text((marks + "x").toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun textIsCappedAtTwoHundredLines() {
        assertEquals(200, PreviewRules.MAX_TEXT_LINES)
        val many = (1..1_000).joinToString("\n") { "l" }
        assertEquals(200, PreviewRules.text(many.toByteArray()).split("\n").size)
        val exact = (1..200).joinToString("\n") { "l" }
        assertEquals(exact, PreviewRules.text(exact.toByteArray()))
    }
}
