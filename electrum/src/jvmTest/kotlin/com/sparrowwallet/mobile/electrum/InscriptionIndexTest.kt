package com.sparrowwallet.mobile.electrum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InscriptionIndexTest {
    private val id = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0"

    /** ord's /output: inscriptions or runes mean it carries; not yet indexed means unknown, never clean. */
    @Test
    fun ordOutputAnswers() {
        assertEquals(true, InscriptionIndex.parseOrdOutput("""{"indexed":true,"inscriptions":["$id"],"runes":{}}""", runeIndex = true))
        assertEquals(true, InscriptionIndex.parseOrdOutput("""{"indexed":true,"inscriptions":[],"runes":{"A•B":{"amount":5,"divisibility":0}}}""", runeIndex = true))
        assertEquals(true, InscriptionIndex.parseOrdOutput("""{"indexed":true,"inscriptions":[],"runes":[["A•B",{"amount":5}]]}""", runeIndex = true))
        assertEquals(false, InscriptionIndex.parseOrdOutput("""{"indexed":true,"inscriptions":[],"runes":{}}""", runeIndex = true))
        assertNull(InscriptionIndex.parseOrdOutput("""{"indexed":false,"inscriptions":[],"runes":{}}""", runeIndex = true))
        // without a rune index, an empty answer proves nothing
        assertNull(InscriptionIndex.parseOrdOutput("""{"indexed":true,"inscriptions":[],"runes":{}}""", runeIndex = false))
        assertFailsWith<IllegalArgumentException> { InscriptionIndex.parseOrdOutput("[]", runeIndex = true) }
    }

    /** Nintondo's find_meta lists the inscriptions on an output, following them as they move; empty is clean. */
    @Test
    fun findMetaAnswers() {
        assertEquals(true, InscriptionIndex.parseFindMeta("""[{"number":1,"owner":"D","height":2,"genesis":"$id"}]"""))
        assertEquals(false, InscriptionIndex.parseFindMeta("[]"))
        assertFailsWith<IllegalArgumentException> { InscriptionIndex.parseFindMeta("""{"error":1}""") }
    }

    @Test
    fun publicServersPerCoin() {
        assertEquals("https://ordinalslite.com", InscriptionIndex.publicUrl("litecoin", "mainnet"))
        assertEquals("https://bells-mainnet-api.nintondo.io", InscriptionIndex.publicUrl("bellscoin", "mainnet"))
        assertEquals("https://doge-mainnet-api.nintondo.io", InscriptionIndex.publicUrl("dogecoin", "mainnet"))
        assertEquals("https://pepe-testnet-api.nintondo.io", InscriptionIndex.publicUrl("pepecoin", "testnet"))
        assertNull(InscriptionIndex.publicUrl("junkcoin", "mainnet"))
        assertNull(InscriptionIndex.publicUrl("litecoin", "testnet"))
        assertTrue(InscriptionIndex.isNintondo("https://pepe-mainnet-api.nintondo.io"))
    }
}
