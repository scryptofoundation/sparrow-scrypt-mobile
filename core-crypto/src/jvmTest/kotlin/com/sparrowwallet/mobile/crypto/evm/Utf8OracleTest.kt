package com.sparrowwallet.mobile.crypto.evm

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The hand-written UTF-8 decoder against the JDK the desktop runs on, as the oracle: decodeLenient against
 * String(bytes, UTF_8) (what drongo's Abi.decodeString does), decodeStrict against a CharsetDecoder set to REPORT (what
 * drongo's NftMetadata does).
 *
 * Every 1, 2 and 3 byte sequence, with an ASCII "a" before and after (lenient in batches, one sequence after another,
 * which decode independently because an ASCII byte is never part of a malformed sequence; strict one by one); then
 * every sequence of 1 to 4 bytes over boundary byte values, alone (so a sequence cut off at the end is covered) and
 * with "a" around it.
 */
class Utf8OracleTest {
    private val boundary = intArrayOf(0x00, 0x41, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xC1, 0xC2, 0xC3, 0xDF, 0xE0, 0xE1, 0xEC, 0xED,
        0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4, 0xF5, 0xF7, 0xF8, 0xFF)

    private val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    private val out = CharBuffer.allocate(64)

    private fun jdkStrict(bytes: ByteArray): String? {
        decoder.reset()
        out.clear()
        if(decoder.decode(ByteBuffer.wrap(bytes), out, true).isError || decoder.flush(out).isError) {
            return null
        }
        out.flip()
        return out.toString()
    }

    private fun label(bytes: ByteArray) = bytes.joinToString(" ") { "%02x".format(it) }

    private fun checkStrict(bytes: ByteArray) {
        val expected = jdkStrict(bytes)
        val actual = Utf8.decodeStrict(bytes)
        if(expected != actual) fail("strict ${label(bytes)}: JDK $expected, phone $actual")
    }

    private fun checkLenient(bytes: ByteArray) {
        val expected = String(bytes, Charsets.UTF_8)
        val actual = Utf8.decodeLenient(bytes, 0, bytes.size)
        if(expected != actual) fail("lenient ${label(bytes)}: JDK ${expected.map { it.code }}, phone ${actual.map { it.code }}")
    }

    @Test
    fun everyShortSequenceMatchesTheJdk() {
        val started = System.nanoTime()
        val a = 'a'.code.toByte()
        for(first in 0..255) {
            //Lenient: all sequences with this first byte, each after an "a", in one buffer
            val batch = java.io.ByteArrayOutputStream(300_000)
            batch.write(a.toInt()); batch.write(first)
            for(second in 0..255) {
                batch.write(a.toInt()); batch.write(first); batch.write(second)
                for(third in 0..255) {
                    batch.write(a.toInt()); batch.write(first); batch.write(second); batch.write(third)
                }
            }
            batch.write(a.toInt())
            val bytes = batch.toByteArray()
            if(String(bytes, Charsets.UTF_8) != Utf8.decodeLenient(bytes, 0, bytes.size)) {
                //Find the sequence that differs
                for(second in -1..255) for(third in -1..255) {
                    if(second < 0 && third >= 0) continue
                    val seq = listOf(first, second, third).filter { it >= 0 }.map { it.toByte() }.toByteArray()
                    checkLenient(byteArrayOf(a) + seq + byteArrayOf(a))
                }
                fail("lenient batch for first byte %02x differs".format(first))
            }
            //Strict: one by one
            checkStrict(byteArrayOf(a, first.toByte(), a))
            for(second in 0..255) {
                checkStrict(byteArrayOf(a, first.toByte(), second.toByte(), a))
                for(third in 0..255) {
                    checkStrict(byteArrayOf(a, first.toByte(), second.toByte(), third.toByte(), a))
                }
            }
        }
        println("every 1 to 3 byte sequence: %.2f s".format((System.nanoTime() - started) / 1e9))
    }

    @Test
    fun boundarySequencesAloneAndInsideText() {
        val started = System.nanoTime()
        var count = 0
        fun check(seq: ByteArray) {
            checkLenient(seq)
            checkStrict(seq)
            val wrapped = byteArrayOf(0x61) + seq + byteArrayOf(0x61)
            checkLenient(wrapped)
            checkStrict(wrapped)
            //A slice of a larger array, as Abi.string decodes one
            val expected = String(seq, Charsets.UTF_8)
            assertEquals(expected, Utf8.decodeLenient(wrapped, 1, 1 + seq.size), "slice ${label(seq)}")
            count++
        }
        for(b1 in boundary) {
            check(byteArrayOf(b1.toByte()))
            for(b2 in boundary) {
                check(byteArrayOf(b1.toByte(), b2.toByte()))
                for(b3 in boundary) {
                    check(byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte()))
                    for(b4 in boundary) {
                        check(byteArrayOf(b1.toByte(), b2.toByte(), b3.toByte(), b4.toByte()))
                    }
                }
            }
        }
        //Every 1 and 2 byte sequence alone, so every lead cut off at the end is covered
        for(b1 in 0..255) {
            check(byteArrayOf(b1.toByte()))
            for(b2 in 0..255) check(byteArrayOf(b1.toByte(), b2.toByte()))
        }
        assertTrue(count > 600_000)
        println("boundary sequences: $count in %.2f s".format((System.nanoTime() - started) / 1e9))
    }
}
