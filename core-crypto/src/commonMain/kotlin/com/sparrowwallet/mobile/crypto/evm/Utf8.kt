package com.sparrowwallet.mobile.crypto.evm

/**
 * UTF-8 decoding written out here rather than left to the platform, so the phone gives the desktop's answer on every
 * platform for every byte sequence.
 */
internal object Utf8 {
    private const val REPLACEMENT = '�'

    /**
     * Lenient decoding exactly as the desktop's JDK decodes `new String(bytes, UTF_8)` (String.decodeUTF8_UTF16): each
     * malformed sequence becomes one U+FFFD. Its own rules, not Unicode's maximal subpart: a well-formed 3-byte shape
     * holding a surrogate (ED A0 80) is one U+FFFD; a truncated sequence at the end is one U+FFFD.
     */
    fun decodeLenient(src: ByteArray, from: Int, to: Int): String {
        val out = StringBuilder(to - from)
        var sp = from
        val sl = to
        while(sp < sl) {
            val b1 = src[sp++].toInt() //signed, as the JDK reads it
            if(b1 >= 0) {
                out.append(b1.toChar())
            } else if((b1 shr 5) == -2 && (b1 and 0x1E) != 0) {
                //Two bytes, C2..DF
                if(sp < sl) {
                    val b2 = src[sp++].toInt()
                    if(notContinuation(b2)) {
                        out.append(REPLACEMENT)
                        sp--
                    } else {
                        out.append((((b1 and 0x1F) shl 6) or (b2 and 0x3F)).toChar())
                    }
                    continue
                }
                out.append(REPLACEMENT)
                break
            } else if((b1 shr 4) == -2) {
                //Three bytes, E0..EF
                if(sp + 1 < sl) {
                    val b2 = src[sp++].toInt()
                    val b3 = src[sp++].toInt()
                    if(malformed3(b1, b2, b3)) {
                        out.append(REPLACEMENT)
                        sp -= 3
                        sp += malformed3Length(src, sp)
                    } else {
                        val c = ((b1 and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                        out.append(if(c in 0xD800..0xDFFF) REPLACEMENT else c.toChar())
                    }
                    continue
                }
                if(sp < sl && malformed3Prefix(b1, src[sp].toInt())) {
                    out.append(REPLACEMENT)
                    continue
                }
                sp++
                out.append(REPLACEMENT)
                break
            } else if((b1 shr 3) == -2) {
                //Four bytes, F0..F7
                if(sp + 2 < sl) {
                    val b2 = src[sp++].toInt()
                    val b3 = src[sp++].toInt()
                    val b4 = src[sp++].toInt()
                    val uc = ((b1 and 0x07) shl 18) or ((b2 and 0x3F) shl 12) or ((b3 and 0x3F) shl 6) or (b4 and 0x3F)
                    if(notContinuation(b2) || notContinuation(b3) || notContinuation(b4) || uc < 0x10000 || uc > 0x10FFFF) {
                        out.append(REPLACEMENT)
                        sp -= 4
                        sp += malformed4Length(src, sp)
                    } else {
                        out.append((0xD800 + ((uc - 0x10000) ushr 10)).toChar())
                        out.append((0xDC00 + ((uc - 0x10000) and 0x3FF)).toChar())
                    }
                    continue
                }
                val u1 = b1 and 0xFF
                if(u1 > 0xF4 || (sp < sl && malformed4Second(u1, src[sp].toInt() and 0xFF))) {
                    out.append(REPLACEMENT)
                    continue
                }
                sp++
                out.append(REPLACEMENT)
                if(sp < sl && notContinuation(src[sp].toInt())) {
                    continue
                }
                break
            } else {
                out.append(REPLACEMENT)
            }
        }
        return out.toString()
    }

    /**
     * Strict decoding, as the desktop's CharsetDecoder set to REPORT: well-formed UTF-8 only (no overlong forms, no
     * surrogates, nothing above U+10FFFF, nothing truncated), otherwise null.
     */
    fun decodeStrict(src: ByteArray): String? {
        val out = StringBuilder(src.size)
        var i = 0
        while(i < src.size) {
            val b1 = src[i].toInt() and 0xFF
            when {
                b1 < 0x80 -> {
                    out.append(b1.toChar())
                    i++
                }
                b1 in 0xC2..0xDF -> {
                    if(i + 1 >= src.size || notContinuation(src[i + 1].toInt())) return null
                    out.append((((b1 and 0x1F) shl 6) or (src[i + 1].toInt() and 0x3F)).toChar())
                    i += 2
                }
                b1 in 0xE0..0xEF -> {
                    if(i + 2 >= src.size) return null
                    val b2 = src[i + 1].toInt() and 0xFF
                    val b3 = src[i + 2].toInt()
                    val low = if(b1 == 0xE0) 0xA0 else 0x80
                    val high = if(b1 == 0xED) 0x9F else 0xBF
                    if(b2 < low || b2 > high || notContinuation(b3)) return null
                    out.append((((b1 and 0x0F) shl 12) or ((b2 and 0x3F) shl 6) or (b3 and 0x3F)).toChar())
                    i += 3
                }
                b1 in 0xF0..0xF4 -> {
                    if(i + 3 >= src.size) return null
                    val b2 = src[i + 1].toInt() and 0xFF
                    val b3 = src[i + 2].toInt()
                    val b4 = src[i + 3].toInt()
                    val low = if(b1 == 0xF0) 0x90 else 0x80
                    val high = if(b1 == 0xF4) 0x8F else 0xBF
                    if(b2 < low || b2 > high || notContinuation(b3) || notContinuation(b4)) return null
                    val uc = ((b1 and 0x07) shl 18) or ((b2 and 0x3F) shl 12) or ((b3 and 0x3F) shl 6) or (b4 and 0x3F)
                    out.append((0xD800 + ((uc - 0x10000) ushr 10)).toChar())
                    out.append((0xDC00 + ((uc - 0x10000) and 0x3FF)).toChar())
                    i += 4
                }
                else -> return null
            }
        }
        return out.toString()
    }

    /** UTF-8 of one code point that is not a surrogate. */
    fun encodeCodePoint(c: Int, out: ByteSink) {
        when {
            c < 0x80 -> out.write(c)
            c < 0x800 -> {
                out.write(0xC0 or (c shr 6))
                out.write(0x80 or (c and 0x3F))
            }
            c < 0x10000 -> {
                out.write(0xE0 or (c shr 12))
                out.write(0x80 or ((c shr 6) and 0x3F))
                out.write(0x80 or (c and 0x3F))
            }
            else -> {
                out.write(0xF0 or (c shr 18))
                out.write(0x80 or ((c shr 12) and 0x3F))
                out.write(0x80 or ((c shr 6) and 0x3F))
                out.write(0x80 or (c and 0x3F))
            }
        }
    }

    private fun notContinuation(b: Int): Boolean = (b and 0xC0) != 0x80

    private fun malformed3(b1: Int, b2: Int, b3: Int): Boolean =
        (b1 == E0 && (b2 and 0xE0) == 0x80) || notContinuation(b2) || notContinuation(b3)

    private fun malformed3Prefix(b1: Int, b2: Int): Boolean = (b1 == E0 && (b2 and 0xE0) == 0x80) || notContinuation(b2)

    //sp is at the lead byte
    private fun malformed3Length(src: ByteArray, sp: Int): Int {
        val b1 = src[sp].toInt()
        val b2 = src[sp + 1].toInt()
        return if((b1 == E0 && (b2 and 0xE0) == 0x80) || notContinuation(b2)) 1 else 2
    }

    private fun malformed4Second(b1: Int, b2: Int): Boolean =
        (b1 == 0xF0 && (b2 < 0x90 || b2 > 0xBF)) || (b1 == 0xF4 && (b2 and 0xF0) != 0x80) || notContinuation(b2)

    //sp is at the lead byte; the two bytes after it exist
    private fun malformed4Length(src: ByteArray, sp: Int): Int {
        val b1 = src[sp].toInt() and 0xFF
        val b2 = src[sp + 1].toInt() and 0xFF
        if(b1 > 0xF4 || malformed4Second(b1, b2)) {
            return 1
        }
        return if(notContinuation(src[sp + 2].toInt())) 2 else 3
    }

    private const val E0 = -32 //0xE0 as a signed byte
}

/** A growing byte buffer (common code has no ByteArrayOutputStream). */
internal class ByteSink(capacity: Int = 64) {
    private var bytes = ByteArray(maxOf(capacity, 16))

    var size = 0
        private set

    fun write(b: Int) {
        if(size == bytes.size) {
            bytes = bytes.copyOf(bytes.size * 2)
        }
        bytes[size++] = b.toByte()
    }

    fun toByteArray(): ByteArray = bytes.copyOf(size)
}
