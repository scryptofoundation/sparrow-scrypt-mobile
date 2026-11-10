package com.sparrowwallet.mobile.crypto.track

import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.hexToBytes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone's tracer against drongo's on real chain data: vectors/tracker/<chain>.json holds, for each private
 * inscription test chain, every answer the chain's server gave drongo's tracer and the result drongo reached for each
 * coin (tools/vectorgen/TrackerRecordingTest.java records them). The phone replays each trace from those answers alone,
 * with the chain's cutoff height and reward rule as drongo had them, and must reach the same result: verdict, found
 * inscriptions and offsets, reason, open pieces and lookup count.
 */
class RecordedChainParityTest {
    companion object {
        /** The chains recorded; a missing recording fails the test. */
        private val CHAINS = listOf("litecoin", "dogecoin", "pepecoin", "junkcoin", "bellscoin")
        private val DIR = File("../vectors/tracker")
    }

    /** Answers only from the recording. A call drongo never made is an Error, which the tracer does not catch. */
    private class ReplaySource(calls: JsonObject) : ChainSource {
        private val transactions = calls.getValue("transaction").jsonObject
        private val blockFees = calls.getValue("blockFees").jsonObject
        private val txidAt = calls.getValue("txidAt").jsonObject
        private val blockHashes = calls.getValue("blockHash").jsonObject
        private val parsed = HashMap<String, ChainTx>()

        init {
            for((txid, answer) in transactions) {
                val obj = answer.jsonObject
                if("error" in obj) {
                    continue
                }
                val tx = RawTx.parse(obj.getValue("hex").jsonPrimitive.content.hexToBytes())
                assertEquals(txid, tx.txid, "recorded transaction parses to another txid")
                val blockHash = obj["blockHash"].let { if(it == null || it is JsonNull) null else it.jsonPrimitive.content }
                parsed[txid] = ChainTx(tx, obj.getValue("height").jsonPrimitive.int, blockHash, obj.getValue("blockTime").jsonPrimitive.long, true)
            }
        }

        private fun answer(calls: JsonObject, kind: String, key: String): JsonElement {
            val answer = calls[key] ?: throw AssertionError("The phone asked $kind $key, which drongo never asked")
            if(answer is JsonObject && "error" in answer) {
                throw IOException(answer.getValue("error").jsonPrimitive.content)
            }
            return answer
        }

        override suspend fun transaction(txid: String): ChainTx {
            answer(transactions, "transaction", txid)
            return parsed.getValue(txid)
        }

        override suspend fun blockFees(height: Int, blockHash: String): List<BlockFee>? {
            val answer = answer(blockFees, "blockFees", "$height:$blockHash")
            return if(answer is JsonNull) null else answer.jsonArray.map {
                BlockFee(it.jsonObject.getValue("txid").jsonPrimitive.content, it.jsonObject.getValue("fee").jsonPrimitive.long)
            }
        }

        override suspend fun txidAt(height: Int, position: Int): String? {
            val answer = answer(txidAt, "txidAt", "$height:$position")
            return if(answer is JsonNull) null else answer.jsonPrimitive.content
        }

        override suspend fun blockHash(height: Int): String? {
            val answer = answer(blockHashes, "blockHash", "$height")
            return if(answer is JsonNull) null else answer.jsonPrimitive.content
        }
    }

    private fun subsidy(rule: JsonObject): (Int) -> Long? = when(val kind = rule.getValue("kind").jsonPrimitive.content) {
        "halving" -> {
            val interval = rule.getValue("interval").jsonPrimitive.int
            val initial = rule.getValue("initial").jsonPrimitive.long
            { height: Int -> val halvings = height / interval; if(halvings >= 64) 0L else initial shr halvings }
        }
        "none" -> { _: Int -> null }
        else -> throw AssertionError("Unknown reward rule $kind")
    }

    /** A result in the recording's form, so drongo's and the phone's compare field for field. */
    private fun describe(verdict: String, found: List<String>, reason: String?, open: List<String>, lookups: Int, usedChain: Boolean): String =
        "verdict=$verdict found=$found reason=$reason open=$open lookups=$lookups usedChain=$usedChain"

    private fun expected(result: JsonObject): String = describe(
        result.getValue("verdict").jsonPrimitive.content,
        result.getValue("found").jsonArray.map {
            val f = it.jsonObject
            "${f.getValue("inscriptionId").jsonPrimitive.content}@${f.getValue("offset").jsonPrimitive.long}#${f.getValue("number").jsonPrimitive.longOrNull}" +
                " mint ${f.getValue("mintTxid").jsonPrimitive.content}/${f.getValue("mintHeight").jsonPrimitive.int}/${f.getValue("mintTime").jsonPrimitive.long}"
        },
        result["reason"].let { if(it == null || it is JsonNull) null else it.jsonPrimitive.content },
        result.getValue("open").jsonArray.map {
            val p = it.jsonObject
            "${p.getValue("txid").jsonPrimitive.content}:${p.getValue("vout").jsonPrimitive.int}[${p.getValue("start").jsonPrimitive.long}-${p.getValue("end").jsonPrimitive.long})@${p.getValue("coinOffset").jsonPrimitive.long}"
        },
        result.getValue("lookups").jsonPrimitive.int,
        result.getValue("usedChain").jsonPrimitive.boolean
    )

    private fun actual(result: TraceResult): String = describe(
        result.verdict.name,
        result.found.map { "${it.inscriptionId}@${it.offset}#${it.number} mint ${it.mintTxid}/${it.mintHeight}/${it.mintTime}" },
        result.reason,
        result.open.map { "${it.txid}:${it.vout}[${it.range.start}-${it.range.end})@${it.coinOffset}" },
        result.lookups,
        result.usedChain
    )

    @Test
    fun phoneTracesLikeTheDesktopOnEveryRecordedChain() {
        val mismatches = ArrayList<String>()
        for(chain in CHAINS) {
            val file = File(DIR, "$chain.json")
            if(!file.isFile) {
                mismatches.add("$chain: no recording at ${file.path}")
                continue
            }
            val recording = Json.parseToJsonElement(file.readText()).jsonObject
            val source = ReplaySource(recording.getValue("calls").jsonObject)
            val cutoff = recording.getValue("firstInscriptionHeight").jsonPrimitive.int
            val subsidy = subsidy(recording.getValue("subsidyRule").jsonObject)
            val verdicts = HashSet<String>()
            val coins = recording.getValue("coins").jsonArray
            assertTrue(coins.isNotEmpty(), "$chain: no coins recorded")
            //Every coin is recorded twice, once at the default budget (200) and once checked deeper (2,000)
            val budgets = HashMap<String, MutableList<Int>>()
            var traces = 0
            for(entry in coins) {
                val coin = entry.jsonObject
                val txid = coin.getValue("txid").jsonPrimitive.content
                val vout = coin.getValue("vout").jsonPrimitive.int
                val budget = coin.getValue("budget").jsonPrimitive.int
                val walkBlocks = coin.getValue("walkBlocks").jsonPrimitive.boolean
                val desktop = coin.getValue("result").jsonObject
                verdicts.add(desktop.getValue("verdict").jsonPrimitive.content)
                //A fresh tracer and cache per trace, as the recording was made
                budgets.getOrPut("$txid:$vout") { ArrayList() }.add(budget)
                val tracer = InscriptionTracer(source, KnownResults.NONE, TxCache(), cutoff, subsidy)
                val phone = runBlocking { tracer.trace(txid, vout, emptyList(), budget, walkBlocks) }
                traces++
                val want = expected(desktop)
                val got = actual(phone)
                if(want != got) {
                    mismatches.add("$chain $txid:$vout budget $budget walk $walkBlocks\n  desktop $want\n  phone   $got")
                }
            }
            for((outpoint, seen) in budgets) {
                if(seen.sorted() != listOf(200, 2_000)) {
                    mismatches.add("$chain $outpoint: recorded at budgets $seen, not once at 200 and once at 2000")
                }
            }
            if(traces != 2 * budgets.size) {
                mismatches.add("$chain: $traces traces for ${budgets.size} coins, not two each")
            }
            if(Verdict.INSCRIBED.name !in verdicts) {
                mismatches.add("$chain: no INSCRIBED coin recorded")
            }
            if(Verdict.CLEAN.name !in verdicts) {
                mismatches.add("$chain: no CLEAN coin recorded")
            }
        }
        assertTrue(mismatches.isEmpty(), "${mismatches.size} problems:\n" + mismatches.joinToString("\n"))
    }
}
