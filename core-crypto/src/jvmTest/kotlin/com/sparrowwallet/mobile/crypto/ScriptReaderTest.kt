package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ScriptReaderTest {
    private fun chunks(hex: String) = assertNotNull(ScriptReader.chunks(hex.hexToBytes()))

    @Test
    fun directPushes() {
        val c = chunks("03aabbcc01ff")
        assertEquals(2, c.size)
        assertEquals(0x03, c[0].opcode)
        assertContentEquals("aabbcc".hexToBytes(), c[0].data)
        assertContentEquals("ff".hexToBytes(), c[1].data)
    }

    @Test
    fun pushdata1() {
        val c = chunks("4c02aabb")
        assertEquals(0x4c, c[0].opcode)
        assertContentEquals("aabb".hexToBytes(), c[0].data)
    }

    @Test
    fun pushdata2() {
        val c = chunks("4d0200aabb")
        assertEquals(0x4d, c[0].opcode)
        assertContentEquals("aabb".hexToBytes(), c[0].data)
    }

    @Test
    fun pushdata4() {
        val c = chunks("4e02000000aabb")
        assertEquals(0x4e, c[0].opcode)
        assertContentEquals("aabb".hexToBytes(), c[0].data)
    }

    @Test
    fun op0IsAnEmptyPush() {
        val c = chunks("00")
        assertEquals(0, c[0].opcode)
        assertContentEquals(ByteArray(0), c[0].data)
    }

    @Test
    fun smallNumbersAndOtherOpcodesCarryNoData() {
        val c = chunks("5160ac")
        assertEquals(listOf(0x51, 0x60, 0xac), c.map { it.opcode })
        assertEquals(listOf(null, null, null), c.map { it.data })
    }

    @Test
    fun truncatedPushesReturnNull() {
        assertNull(ScriptReader.chunks("03aabb".hexToBytes()))
        assertNull(ScriptReader.chunks("4c".hexToBytes()))
        assertNull(ScriptReader.chunks("4c05aa".hexToBytes()))
        assertNull(ScriptReader.chunks("4d01".hexToBytes()))
        assertNull(ScriptReader.chunks("4e0100".hexToBytes()))
        assertNull(ScriptReader.chunks("4effffffff".hexToBytes()))
    }
}
