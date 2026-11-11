package com.sparrowwallet.mobile.electrum.indexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Nintondo cases of the desktop's OrdClientParseTest, on the same recorded answers, plus the phone's stricter rules. */
class NintondoParseTest {
    private val outpoint = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64:0"
    private val inscription = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0"
    private val dogeId = "b5f9d2759c65df72dff76bbab840ff3d34d99c6679bad26148cb18344ff03f71i0"
    private val pepeId = "d089eab0d91c5889eb2789804498ab51229048451eb9d9f9510ac2b84416c546i0"
    private val bellsId = "554a01b993871859e622b2c6391da63e74393b8cab2958ca09eebf4688af7773i0"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/indexer/$name")) { name }.use { it.readBytes().decodeToString() }

    /**
     * find_meta lists the inscriptions on an output. Its empty answer is also what it gives for an output it has not
     * indexed yet, so on the phone (which has no output height here) an empty answer is unknown, not clean: the desktop
     * reads it as clean and then holds it back by height in CollectiblesManager.indexedBy.
     */
    @Test fun parsesNintondoFindMeta() {
        val carrying = NintondoParse.meta(outpoint,
            """[{"number":47176483,"owner":"DLXoypYdoWu1qodJwEsANXTRAJzvbtm1pn","height":4999503,"genesis":"$inscription"}]""")
        assertTrue(carrying.indexed)
        assertEquals(listOf(inscription), carrying.inscriptions)
        assertTrue(carrying.carries)
        assertFalse(carrying.unlisted)

        val empty = NintondoParse.meta(outpoint, "[]")
        assertFalse(empty.indexed, "an empty find_meta cannot tell clean from not indexed yet")
        assertFalse(empty.carries)
        assertEquals(IndexerOutput.unknown(outpoint), empty)

        val odd = NintondoParse.meta(outpoint, """[{"genesis":"not an id"}]""")
        assertTrue(odd.carries, "an entry without a usable id still carries something")
        assertTrue(odd.indexed)
        assertFailsWith<IllegalArgumentException> { NintondoParse.meta(outpoint, """{"error":1}""") }
    }

    /** Anything listed stands, indexed and carrying, whatever else is missing or mistyped. */
    @Test fun aCarryingFindMetaIsKept() {
        for(json in listOf(
            "[5]",
            "[null]",
            "[{}]",
            """[{"genesis":5}]""",
            """[{"genesis":"${inscription.uppercase()}"}]""",
            """[{"genesis":"../../x"}]""",
            """[["$inscription"]]""",
            """[{"genesis":"$inscription"},{"genesis":null}]"""
        )) {
            val o = NintondoParse.meta(outpoint, json)
            assertTrue(o.indexed, json)
            assertTrue(o.carries, json)
            assertTrue(o.unlisted, json)
            assertTrue(o.runes.isEmpty(), json)
        }
        assertEquals(listOf(inscription), NintondoParse.meta(outpoint, """[{"genesis":"$inscription"},{"genesis":null}]""").inscriptions)
        for(json in listOf("{}", "\"[]\"", "null", "<html>", "")) {
            assertFailsWith<IllegalArgumentException>(json) { NintondoParse.meta(outpoint, json) }
        }
    }

    @Test fun nintondoOutputFixturesParse() {
        for((coin, id) in listOf("doge" to dogeId, "pepe" to pepeId, "bells" to bellsId)) {
            val output = NintondoParse.meta(id.take(64) + ":0", fixture("nintondo-$coin-find-meta.json"))
            assertEquals(listOf(id), output.inscriptions, coin)
            assertTrue(output.indexed, coin)
            assertTrue(output.carries, coin)
        }
    }

    /** Nintondo's status names the chain ("Pepecoin Testnet"); a server for another coin or network is refused. */
    @Test fun nintondoStatusNamesCoinAndNetwork() {
        val bells = NintondoParse.status("""{"network":"Bellscoin","ord_tip":"ab","ord_height":1300750,"tip":"ab","tip_height":1300751}""", "Bellscoin")
        assertEquals(IndexerStatus("mainnet", false, 1300750L), bells)
        assertEquals("testnet", NintondoParse.status("""{"network":"Pepecoin Testnet","ord_height":1200}""", "Pepecoin Testnet").chain)

        val otherCoin = assertFailsWith<IllegalArgumentException> { NintondoParse.status("""{"network":"Dogecoin","ord_height":5}""", "Pepecoin") }
        assertEquals("Inscriptions server is for Dogecoin, wallet is Pepecoin", otherCoin.message)
        assertEquals("Inscriptions server is for Dogecoin Testnet, wallet is Pepecoin",
            assertFailsWith<IllegalArgumentException> { NintondoParse.status("""{"network":"Dogecoin Testnet"}""", "Pepecoin Testnet") }.message)
        assertFailsWith<IllegalArgumentException> { NintondoParse.status("<html>", "Dogecoin") }
        assertEquals("Not a Nintondo indexer", assertFailsWith<IllegalArgumentException> { NintondoParse.status("{}", "Dogecoin") }.message)
        assertEquals("Not a Nintondo indexer", assertFailsWith<IllegalArgumentException> { NintondoParse.status("""{"network":5}""", "Dogecoin") }.message)
        assertFailsWith<IllegalArgumentException> { NintondoParse.status("[]", "Dogecoin") }
        assertFailsWith<IllegalArgumentException> { NintondoParse.status("""{"network":"dogecoin"}""", "Dogecoin") }
    }

    /** The same coin on the other network is refused too: the phone checks coin and network in one step. */
    @Test fun nintondoStatusForTheOtherNetworkIsRefused() {
        assertEquals("Inscriptions server is on mainnet, wallet is on Testnet",
            assertFailsWith<IllegalArgumentException> { NintondoParse.status("""{"network":"Pepecoin","ord_height":5}""", "Pepecoin Testnet") }.message)
        assertEquals("Inscriptions server is on testnet, wallet is on Mainnet",
            assertFailsWith<IllegalArgumentException> { NintondoParse.status("""{"network":"Pepecoin Testnet","ord_height":5}""", "Pepecoin") }.message)
    }

    /** Nintondo indexes no runes or dunes: its status never says it does, and a height it does not give well is left out. */
    @Test fun nintondoStatusNeverIndexesRunes() {
        val s = NintondoParse.status("""{"network":"Dogecoin","ord_height":"5","rune_index":true}""", "Dogecoin")
        assertFalse(s.runeIndex)
        assertNull(s.height)
        assertNull(NintondoParse.status("""{"network":"Dogecoin","ord_height":-1}""", "Dogecoin").height)
    }

    /** Location gives the inscription's current output and offset (the satpoint), as recorded for each coin. */
    @Test fun nintondoLocationGivesSatpoint() {
        assertEquals(Triple("b5f9d2759c65df72dff76bbab840ff3d34d99c6679bad26148cb18344ff03f71", 0, 0L), NintondoParse.location(fixture("nintondo-doge-location.json")))
        assertEquals(Triple("77405cbde12fbd5939fc54f9e041868aabb1a25b9a0b48edad47f051af3f9635", 0, 0L), NintondoParse.location(fixture("nintondo-pepe-location.json")))
        assertEquals(Triple("554a01b993871859e622b2c6391da63e74393b8cab2958ca09eebf4688af7773", 0, 0L), NintondoParse.location(fixture("nintondo-bells-location.json")))
        assertEquals(Triple("ab".repeat(32), 3, 1234L), NintondoParse.location("""{"location":"${"ab".repeat(32)}i3i1234i9","value":1}"""))
    }

    /** Info gives number, type and size, and must be for the inscription asked. */
    @Test fun nintondoInfoDetails() {
        assertEquals(IndexerInscription(dogeId, 180433203L, "text/plain; charset=utf-8", 15L), NintondoParse.info(dogeId, fixture("nintondo-doge-info.json")))
        assertEquals(IndexerInscription(pepeId, 18330851L, "text/html; charset=utf-8", 14346L), NintondoParse.info(pepeId, fixture("nintondo-pepe-info.json")))
        assertEquals(276671008L, NintondoParse.info(bellsId, fixture("nintondo-bells-info.json")).number)
        assertEquals(IndexerInscription(dogeId, null, null, null),
            NintondoParse.info(dogeId, """{"id":"$dogeId","number":"7","mime":5,"file_size":-3}"""))
        assertNull(NintondoParse.info(dogeId, """{"id":"$dogeId","mime":"${"a".repeat(256)}"}""").contentType)
    }

    @Test fun nintondoHostileAnswersAreRefused() {
        for(location in listOf(
            """{"location":"../../x","value":1}""",
            """{"location":"${dogeId}i0","value":1}""",
            """{"value":1}""",
            """{"location":"${"g".repeat(64)}i0i0i1","value":1}""",
            """{"location":"${dogeId.take(64)}i99999999999i0i1"}""",
            """{"location":"${dogeId.take(64).uppercase()}i0i0i1"}""",
            """{"location":"${dogeId.take(64)}i0i0i1x"}""",
            """{"location":5}"""
        )) {
            assertNull(NintondoParse.location(location), location)
        }
        assertFailsWith<IllegalArgumentException> { NintondoParse.location("[]") }
        assertFailsWith<IllegalArgumentException> { NintondoParse.location("<html>") }

        //Info for a different inscription than asked, or naming none, is refused
        val info = fixture("nintondo-doge-info.json")
        assertFailsWith<IllegalArgumentException> { NintondoParse.info(dogeId, info.replace(dogeId, pepeId)) }
        assertFailsWith<IllegalArgumentException> { NintondoParse.info(dogeId, """{"number":1}""") }
        assertFailsWith<IllegalArgumentException> { NintondoParse.info(dogeId, "[]") }
    }

    @Test fun oversizedJsonIsRejected() {
        val json = """[{"genesis":"$inscription","pad":"${"a".repeat(OrdParse.MAX_JSON_CHARS)}"}]"""
        assertFailsWith<IllegalArgumentException> { NintondoParse.meta(outpoint, json) }
        assertFailsWith<IllegalArgumentException> { NintondoParse.status(json, "Dogecoin") }
    }
}
