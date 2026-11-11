package com.sparrowwallet.mobile.electrum.indexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ord 0.20 cases of the desktop's OrdClientParseTest, on the same recorded answers, plus the phone's stricter rules. */
class OrdParseTest {
    private val outpoint = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64:0"
    private val inscription = "3b6fb513db37f827cea7c89085a41605ed2776e42e71348156bec9739d3b6c64i0"

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/indexer/$name")) { name }.use { it.readBytes().decodeToString() }

    private fun output(json: String, runeIndex: Boolean = true, at: String = outpoint) = OrdParse.output(at, json, runeIndex)

    @Test fun mainnetOutputWithInscription() {
        val o = output(fixture("output-inscription-mainnet.json"))
        assertEquals(outpoint, o.outpoint)
        assertTrue(o.indexed)
        assertEquals(listOf(inscription), o.inscriptions)
        assertTrue(o.runes.isEmpty())
        assertTrue(o.carries)
        assertEquals(com.sparrowwallet.mobile.electrum.inscriptions.IndexerAnswer(true, listOf(inscription), false, false), o.toAnswer())
    }

    @Test fun postOutputsMatchesByOrder() {
        val json = fixture("outputs-mainnet.json")
        val outputs = OrdParse.outputs(listOf(outpoint), json, true)
        assertEquals(1, outputs.size)
        assertEquals(outpoint, outputs.getValue(outpoint).outpoint)
        assertEquals(listOf(inscription), outputs.getValue(outpoint).inscriptions)
        val other = "00".repeat(32) + ":1"
        assertFailsWith<IllegalArgumentException> { OrdParse.outputs(listOf(outpoint, other), json, true) }
    }

    @Test fun runesAsPairArray() {
        val o = output("""{"indexed":true,"inscriptions":[],"runes":[["A•B",{"amount":12345,"divisibility":2,"symbol":"x"}]],"value":546,"spent":false}""")
        assertEquals(RuneBalance("12345", 2, "x"), o.runes["A•B"])
        assertTrue(o.carries)
        assertFalse(o.unlisted)
    }

    @Test fun runesAsObject() {
        val o = output("""{"indexed":true,"inscriptions":[],"runes":{"THE•OMEGA":{"amount":25,"divisibility":0,"symbol":"B"}},"value":546,"spent":false}""")
        assertEquals(RuneBalance("25", 0, "B"), o.runes["THE•OMEGA"])
        assertTrue(o.toAnswer().runes)
    }

    @Test fun regtestRunes() {
        val o = output(fixture("output-runes-regtest.json"), at = "4cd2a10d346b8aa6df179405e0e96f66dcbbfed266466e8bc7b1145e6fe95f7e:1")
        val balance = o.runes.getValue("TESTRUNE•LITECOIN")
        assertEquals(2, balance.divisibility)
        assertEquals("T", balance.symbol)
        assertEquals("100000", balance.amount)
        assertTrue(o.inscriptions.isEmpty())
        assertTrue(o.carries)
    }

    /** ord answers an output of a transaction not yet mined with indexed false (and spent true): unknown, not empty. */
    @Test fun regtestUnminedIsUnindexed() {
        val o = output(fixture("output-unindexed-regtest.json"), at = "9a9d6ab0b0ecd9979559bcd0fbf03198080b7d906cfaf8b611a60b73511331e6:0")
        assertFalse(o.indexed)
        assertFalse(o.carries)
    }

    @Test fun regtestStatus() {
        val s = OrdParse.status(fixture("status-regtest.json"))
        assertEquals("regtest", s.chain)
        assertTrue(s.runeIndex)
        assertEquals(112L, s.height)
    }

    @Test fun unindexedIsUnknown() {
        assertFalse(output("""{"indexed":false,"inscriptions":[],"runes":{},"value":1000,"spent":false}""").indexed)
        assertFalse(IndexerOutput.unknown(outpoint).indexed)
        assertFalse(IndexerOutput.unknown(outpoint).carries)
    }

    @Test fun statusChains() {
        val s = OrdParse.status(fixture("status-mainnet.json"))
        assertEquals("mainnet", s.chain)
        assertTrue(s.runeIndex)
        assertTrue(s.height!! > 3_000_000)
    }

    @Test fun inscriptionDetails() {
        val i = OrdParse.inscription(fixture("inscription-text-mainnet.json"))
        assertEquals(inscription, i.id)
        assertEquals(26845519L, i.number)
        assertEquals("text/plain;charset=utf-8", i.contentType)
        assertEquals(63L, i.contentLength)
    }

    @Test fun oversizedJsonIsRejected() {
        val json = """{"indexed":true,"pad":"${"a".repeat(OrdParse.MAX_JSON_CHARS)}"}"""
        assertFailsWith<IllegalArgumentException> { output(json) }
        assertFailsWith<IllegalArgumentException> { OrdParse.status(json) }
    }

    @Test fun hostileRuneNameDropped() {
        val o = output("""{"indexed":true,"inscriptions":[],"runes":{"<script>":{"amount":1,"divisibility":0,"symbol":"\u0007"},"OK":{"amount":1,"divisibility":0,"symbol":"\u0007"}},"value":546,"spent":false}""")
        assertFalse(o.runes.containsKey("<script>"))
        assertEquals("¤", o.runes.getValue("OK").symbol)
        //A dropped rune still means the output carries something
        assertTrue(o.carries)
        assertTrue(o.unlisted)
    }

    @Test fun hostileInscriptionIdDropped() {
        val o = output("""{"indexed":true,"inscriptions":["../../x"],"runes":{},"value":546,"spent":false}""")
        assertTrue(o.inscriptions.isEmpty())
        assertTrue(o.carries)
    }

    // The phone's own rules, stricter than the desktop's parser

    /** A server without a rune index reports no runes on every output: nothing shown is unknown (the desktop's forServer). */
    @Test fun noRuneIndexAndNothingShownIsNotIndexed() {
        val empty = """{"indexed":true,"inscriptions":[],"runes":{},"value":546,"spent":false,"transaction":"${outpoint.take(64)}"}"""
        assertFalse(output(empty, runeIndex = false).indexed)
        assertTrue(output(empty, runeIndex = true).indexed)
        assertFalse(output(empty, runeIndex = true).carries)
        val carrying = output(fixture("output-inscription-mainnet.json"), runeIndex = false)
        assertTrue(carrying.indexed)
        assertTrue(carrying.carries)
        assertFalse(OrdParse.outputs(listOf(outpoint), "[$empty]", false).getValue(outpoint).indexed)
    }

    @Test fun fieldsOfTheWrongTypeAreUnlisted() {
        for(json in listOf(
            """{"indexed":true,"inscriptions":"$inscription","runes":{}}""",
            """{"indexed":true,"inscriptions":{"a":"$inscription"},"runes":{}}""",
            """{"indexed":true,"inscriptions":[5],"runes":{}}""",
            """{"indexed":true,"inscriptions":[],"runes":"A"}""",
            """{"indexed":true,"inscriptions":[],"runes":5}""",
            """{"indexed":true,"inscriptions":[],"runes":[["A"]]}""",
            """{"indexed":true,"inscriptions":[],"runes":[5]}"""
        )) {
            val o = output(json)
            assertTrue(o.unlisted, json)
            assertTrue(o.carries, json)
            assertTrue(o.indexed, json)
        }
    }

    /** Anything the server shows as carried stands, indexed and carrying, whatever else is missing or mistyped. */
    @Test fun aCarryingAnswerIsKept() {
        for(json in listOf(
            //ord with a rune index but no inscription index
            """{"indexed":true,"inscriptions":null,"runes":{"AB":{"amount":5,"divisibility":0}},"spent":false}""",
            """{"indexed":"true","inscriptions":["$inscription"],"runes":{}}""",
            """{"indexed":false,"inscriptions":["$inscription"],"runes":{}}""",
            """{"inscriptions":["$inscription"],"spent":true}""",
            """{"indexed":1,"inscriptions":[],"runes":{"<x>":{"amount":5,"divisibility":0}}}"""
        )) {
            for(runeIndex in listOf(true, false)) {
                val o = output(json, runeIndex)
                assertTrue(o.indexed, json)
                assertTrue(o.carries, json)
            }
        }
        assertTrue(OrdParse.outputs(listOf(outpoint), """[{"transaction":"${outpoint.take(64)}","inscriptions":null,"runes":{"AB":{"amount":5,"divisibility":0}}}]""", true)
            .getValue(outpoint).indexed)
    }

    @Test fun missingOrNullListsAreNotIndexed() {
        for(json in listOf(
            """{"indexed":true,"runes":{}}""",
            """{"indexed":true,"inscriptions":null,"runes":{}}""",
            """{"indexed":true,"inscriptions":[]}""",
            """{"indexed":true,"inscriptions":[],"runes":null}"""
        )) {
            val o = output(json)
            assertFalse(o.indexed, json)
            assertFalse(o.carries, json)
        }
        //Runes shown without the inscriptions list still carry: the answer stands
        assertTrue(output("""{"indexed":true,"inscriptions":null,"runes":{"AB":{"amount":1,"divisibility":0}}}""").indexed)
        //A listed inscription stands even when the runes field is missing
        assertTrue(output("""{"indexed":true,"inscriptions":["$inscription"]}""").indexed)
    }

    @Test fun indexedMustBeTheLiteralTrue() {
        for(value in listOf("\"true\"", "1", "null")) {
            assertFalse(output("""{"indexed":$value,"inscriptions":[],"runes":{}}""").indexed, value)
        }
        assertFalse(output("""{"inscriptions":[],"runes":{}}""").indexed)
    }

    /** The wallet asks only about coins it holds unspent: an indexer that calls one spent is behind or on another chain. */
    @Test fun spentOutputIsUnknown() {
        assertFalse(output("""{"indexed":true,"inscriptions":[],"runes":{},"spent":true}""").indexed)
        assertTrue(output("""{"indexed":true,"inscriptions":[],"runes":{},"spent":false}""").indexed)
    }

    /** An empty answer is vouched for only with spent the literal false. */
    @Test fun emptyAnswerNeedsSpentTheLiteralFalse() {
        for(spent in listOf("", ",\"spent\":\"false\"", ",\"spent\":0", ",\"spent\":null", ",\"spent\":\"true\"", ",\"spent\":1")) {
            assertFalse(output("""{"indexed":true,"inscriptions":[],"runes":{}$spent}""").indexed, spent)
        }
    }

    /** kotlinx.serialization reads an unquoted token such as FALSE or TRUE as a literal; it never counts as a boolean here. */
    @Test fun unquotedOddLiteralsAreNotBooleans() {
        val odd = output("""{"indexed":true,"inscriptions":[],"runes":{},"spent":FALSE}""")
        assertFalse(odd.indexed)
        assertFalse(output("""{"indexed":TRUE,"inscriptions":[],"runes":{},"spent":false}""").indexed)
        assertTrue(output("""{"indexed":TRUE,"inscriptions":["$inscription"],"runes":{},"spent":TRUE}""").carries)
    }

    @Test fun badRuneBalancesAreUnlisted() {
        for(balance in listOf(
            """{"amount":-1,"divisibility":0}""",
            """{"amount":1.5,"divisibility":0}""",
            """{"amount":"12a","divisibility":0}""",
            """{"amount":"${"9".repeat(41)}","divisibility":0}""",
            """{"amount":1,"divisibility":39}""",
            """{"amount":1,"divisibility":-1}""",
            """{"amount":1,"divisibility":"2"}""",
            """{"amount":1,"divisibility":2.0}""",
            """{"amount":1}""",
            """{"amount":"١٢","divisibility":0}""",
            "5"
        )) {
            val o = output("""{"indexed":true,"inscriptions":[],"runes":{"AB":$balance}}""")
            assertTrue(o.runes.isEmpty(), balance)
            assertTrue(o.unlisted, balance)
        }
        assertEquals(RuneBalance("123", 38, "¤"), output("""{"indexed":true,"inscriptions":[],"runes":{"AB":{"amount":"123","divisibility":38}}}""").runes["AB"])
    }

    @Test fun symbolsAreKeptOnlyWhenShowable() {
        fun symbol(s: String) = output("""{"indexed":true,"inscriptions":[],"runes":{"AB":{"amount":1,"divisibility":0,"symbol":$s}}}""").runes.getValue("AB").symbol
        assertEquals("\uD83D\uDC15", symbol("\"\uD83D\uDC15\""))
        assertEquals("¤", symbol("\"\\u202e\""))
        assertEquals("¤", symbol("\"ab\""))
        assertEquals("¤", symbol("\"\""))
        assertEquals("¤", symbol("null"))
        assertEquals("¤", symbol("5"))
        assertEquals("¤", symbol("\"\\ue000\""))
        assertEquals("¤", symbol("\"\\ud83d\""))
    }

    @Test fun batchAnswerForAnotherTransactionIsRefused() {
        val json = fixture("outputs-mainnet.json")
        val other = "00".repeat(32) + ":0"
        assertFailsWith<IllegalArgumentException> { OrdParse.outputs(listOf(other), json, true) }
        assertFailsWith<IllegalArgumentException> { OrdParse.outputs(listOf(outpoint), "[5]", true) }
        //a batch entry must name its transaction
        assertFailsWith<IllegalArgumentException> { OrdParse.outputs(listOf(outpoint), """[{"indexed":true,"inscriptions":[],"runes":{},"spent":false}]""", true) }
        assertFailsWith<IllegalArgumentException> { OrdParse.outputs(listOf(outpoint), """[{"transaction":5,"indexed":true,"inscriptions":[],"runes":{}}]""", true) }
        assertFailsWith<IllegalArgumentException> { OrdParse.outputs(listOf(outpoint), "{}", true) }
        assertFailsWith<IllegalArgumentException> { output("[]") }
        assertFailsWith<IllegalArgumentException> { output("<html>") }
    }

    /** Two outputs of one transaction answered in the wrong order are refused when the entries name their outpoints. */
    @Test fun misorderedBatchIsRefused() {
        val txid = outpoint.take(64)
        fun entry(vout: Int, carrying: Boolean) =
            """{"outpoint":"$txid:$vout","transaction":"$txid","indexed":true,"inscriptions":[${if(carrying) "\"$inscription\"" else ""}],"runes":{},"spent":false}"""
        val asked = listOf("$txid:0", "$txid:1")
        assertFailsWith<IllegalArgumentException> { OrdParse.outputs(asked, "[${entry(1, false)},${entry(0, true)}]", true) }
        val inOrder = OrdParse.outputs(asked, "[${entry(0, true)},${entry(1, false)}]", true)
        assertTrue(inOrder.getValue("$txid:0").carries)
        assertFalse(inOrder.getValue("$txid:1").carries)
        assertFailsWith<IllegalArgumentException> { output(entry(1, false)) }
    }

    @Test fun statusMustNameAChain() {
        assertFailsWith<IllegalArgumentException> { OrdParse.status("OK") }
        assertFailsWith<IllegalArgumentException> { OrdParse.status("""{"chain":5}""") }
        assertFailsWith<IllegalArgumentException> { OrdParse.status("""{"height":5}""") }
        val s = OrdParse.status("""{"chain":"mainnet","rune_index":"true"}""")
        assertFalse(s.runeIndex)
        assertNull(s.height)
    }

    @Test fun inscriptionWithABadIdIsRefused() {
        val json = fixture("inscription-text-mainnet.json")
        assertFailsWith<IllegalArgumentException> { OrdParse.inscription(json.replace(inscription, "../status")) }
        assertFailsWith<IllegalArgumentException> { OrdParse.inscription(json.replace(inscription, inscription.uppercase())) }
        val bare = OrdParse.inscription("""{"id":"$inscription","number":"7","content_length":-3}""")
        assertEquals(IndexerInscription(inscription, null, null, null), bare)
    }

    /** Final review M6: at most 100 rune entries are taken from one output; more and it is unlisted (carries), never clean. */
    @Test fun tooManyRunesOnOneOutputCarry() {
        fun name(i: Int) = "R" + ('A' + i / 26) + ('A' + i % 26)
        fun answer(n: Int) = (0 until n).joinToString(",", "{", "}") { "\"${name(it)}\":{\"amount\":1,\"divisibility\":0}" }
        val many = output("""{"indexed":true,"inscriptions":[],"runes":${answer(101)},"spent":false}""")
        assertTrue(many.unlisted)
        assertTrue(many.carries)
        assertTrue(many.runes.size <= 100, "${many.runes.size}")
        val hundred = output("""{"indexed":true,"inscriptions":[],"runes":${answer(100)},"spent":false}""")
        assertFalse(hundred.unlisted)
        assertEquals(100, hundred.runes.size)
    }
}
