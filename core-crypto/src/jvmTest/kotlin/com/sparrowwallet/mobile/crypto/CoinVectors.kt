package com.sparrowwallet.mobile.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import java.io.File

/**
 * Per-coin parity vectors written by drongo's CoinVectorGeneratorTest (copy in tools/vectorgen).
 * Parsed by hand: :core-crypto has the JSON library but not the serialization plugin.
 */
object CoinVectors {
    class Params(
        val p2pkhVersion: Int, val p2shVersion: Int, val wifVersion: Int, val xpubHeader: String, val xprvHeader: String,
        val bech32Hrp: String?, val mwebHrp: String?, val coinType: Int, val recommendedFeeRate: Double, val dustLimit: Long,
        val segwit: Boolean, val mweb: Boolean, val auxPow: Boolean, val ticker: String, val subunit: String,
        val coingeckoId: String, val uriScheme: String
    )

    class ScriptTypeVector(
        val scriptType: String, val derivationPath: String, val accountXpub: String, val receive0Wif: String,
        val receiveAddresses: List<String>, val changeAddresses: List<String>
    )

    class Entry(val coin: String, val network: String, val params: Params, val scriptTypes: List<ScriptTypeVector>)

    class SignedInput(val prevTxid: String, val vout: Long, val value: Long, val keyPath: String, val sequence: Long)
    class SignedOutput(val value: Long, val script: String)
    class SignedTx(
        val coin: String, val network: String, val scriptType: String,
        val inputs: List<SignedInput>, val outputs: List<SignedOutput>, val txHex: String
    )

    class File(val mnemonic: String, val entries: List<Entry>, val signedTxs: List<SignedTx>)

    val file: CoinVectors.File by lazy { parse(java.io.File("../vectors/coins.json").readText()) }

    fun entry(coin: Coin, network: Network): Entry = file.entries.first { it.coin == coin.name && it.network == network.name }

    private fun parse(text: String): CoinVectors.File {
        val root = Json.parseToJsonElement(text).jsonObject
        return File(
            root.str("mnemonic")!!,
            root["entries"]!!.jsonArray.map { e ->
                val o = e.jsonObject
                val p = o["params"]!!.jsonObject
                Entry(
                    o.str("coin")!!, o.str("network")!!,
                    Params(
                        p.int("p2pkhVersion"), p.int("p2shVersion"), p.int("wifVersion"), p.str("xpubHeader")!!, p.str("xprvHeader")!!,
                        p.str("bech32Hrp"), p.str("mwebHrp"), p.int("coinType"), p["recommendedFeeRate"]!!.jsonPrimitive.double,
                        p["dustLimit"]!!.jsonPrimitive.long, p.bool("segwit"), p.bool("mweb"), p.bool("auxPow"),
                        p.str("ticker")!!, p.str("subunit")!!, p.str("coingeckoId")!!, p.str("uriScheme")!!
                    ),
                    o["scriptTypes"]!!.jsonArray.map { t ->
                        val s = t.jsonObject
                        ScriptTypeVector(
                            s.str("scriptType")!!, s.str("derivationPath")!!, s.str("accountXpub")!!, s.str("receive0Wif")!!,
                            s["receiveAddresses"]!!.jsonArray.map { it.jsonPrimitive.content },
                            s["changeAddresses"]!!.jsonArray.map { it.jsonPrimitive.content }
                        )
                    }
                )
            },
            root["signedTxs"]!!.jsonArray.map { t ->
                val o = t.jsonObject
                SignedTx(
                    o.str("coin")!!, o.str("network")!!, o.str("scriptType")!!,
                    o["inputs"]!!.jsonArray.map {
                        val i = it.jsonObject
                        SignedInput(i.str("prevTxid")!!, i["vout"]!!.jsonPrimitive.long, i["value"]!!.jsonPrimitive.long,
                            i.str("keyPath")!!, i["sequence"]!!.jsonPrimitive.long)
                    },
                    o["outputs"]!!.jsonArray.map {
                        val out = it.jsonObject
                        SignedOutput(out["value"]!!.jsonPrimitive.long, out.str("script")!!)
                    },
                    o.str("txHex")!!
                )
            }
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int
    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean
}
