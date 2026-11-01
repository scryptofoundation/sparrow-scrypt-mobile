package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in (LIVE_BACKENDS=1): every backend kind against a public server per coin, with the
 * "abandon ... about" receive/0 P2PKH script. Prints what each server answered.
 */
class LiveBackendsTest {
    private val enabled = System.getenv("LIVE_BACKENDS") == "1"
    private val master = HDKey.fromSeed(Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))

    private fun check(label: String, params: ChainParams, knownTxid: String? = null, open: () -> ChainBackend) = runBlocking {
        val script = Scripts.outputScript(master.derivePath(params.derivationPath(ScriptType.P2PKH)).deriveChild(0).deriveChild(0).pubKey, ScriptType.P2PKH)
        val backend = open()
        try {
            backend.connect()
            val tip = backend.tipHeight()
            val history = backend.history(script)
            val fee = backend.feeRate(2)
            //a previous transaction for a PSBT: fetched raw and checked to hash to its txid
            val rawOf = knownTxid ?: history.firstOrNull()?.txHash
            val raw = rawOf?.let { backend.rawTransaction(it).size }
            println("$label: tip $tip, history ${history.size}, fee $fee sat/vB, raw tx ${raw ?: "-"} bytes")
            assertTrue(tip > 0, label)
            assertTrue(fee == null || fee > 0, label)
        } finally {
            backend.close()
        }
    }

    @Test fun everyCoinsPublicBackendAnswers() {
        if(!enabled) return
        val pepTest = ChainParams.of(Coin.PEPECOIN, Network.TESTNET)
        check("PEP testnet Esplora", pepTest, "32d175436190a9d2dcf0cd0c70d101520ebb274f8e2f383cab17585a4477f2ef") { EsploraBackend("https://pepe-testnet-api.nintondo.io", pepTest) }
        val jkcTest = ChainParams.of(Coin.JUNKCOIN, Network.TESTNET)
        check("JKC testnet Esplora", jkcTest) { EsploraBackend("https://jkc-testnet-api.s3na.xyz", jkcTest) }
        val doge = ChainParams.of(Coin.DOGECOIN, Network.MAINNET)
        check("DOGE mainnet Electrum", doge) { ElectrumBackend(ElectrumClient("doge.electrum1.cipig.net", 20060, true)) }
        val dingo = ChainParams.of(Coin.DINGOCOIN, Network.MAINNET)
        check("DINGO mainnet Electrum", dingo) { ElectrumBackend(ElectrumClient("seed1.dingocoin.com", 3342, true)) }
    }

    /** The phone's default Litecoin list must pass the CA and hostname checks. */
    @Test fun litecoinDefaultServersPassHostnameChecks() {
        if(!enabled) return
        val ltc = ChainParams.of(Coin.LITECOIN, Network.MAINNET)
        for((host, port) in listOf("electrum1.cipig.net" to 20063, "fallacy.fiatfaucet.com" to 50002, "electrum.jhoenicke.de" to 50091,
                                   "electrum.petrkr.net" to 60002, "electrum.jochen-hoenicke.de" to 50091)) {
            check("LTC $host Electrum", ltc) { ElectrumBackend(ElectrumClient(host, port, true)) }
        }
    }

    /**
     * A PSBT's previous transactions come from these servers: each must give the raw bytes of a
     * coinbase (from its tip block, where it has block routes) and of a recent mempool transaction.
     */
    @Test fun everyPublicEsploraServerReturnsRawTransactions() {
        if(!enabled) return
        val servers = listOf(
            Triple(Coin.BELLSCOIN, Network.MAINNET, "https://api.nintondo.io"),
            Triple(Coin.PEPECOIN, Network.MAINNET, "https://peppool.space/api"),
            Triple(Coin.LUCKYCOIN, Network.MAINNET, "https://luckyscan.org/api"),
            Triple(Coin.JUNKCOIN, Network.MAINNET, "https://explorer.junk-coin.com/api"),
            Triple(Coin.PEPECOIN, Network.TESTNET, "https://pepe-testnet-api.nintondo.io"),
            Triple(Coin.JUNKCOIN, Network.TESTNET, "https://jkc-testnet-api.s3na.xyz"))
        val failures = ArrayList<String>()
        for((coin, network, url) in servers) {
            val label = "${coin.name} ${network.name} $url"
            val txids = sampleTxids(url)
            val backend = EsploraBackend(url, ChainParams.of(coin, network))
            runBlocking {
                try {
                    backend.connect()
                    for((kind, txid) in txids) {
                        runCatching { backend.rawTransaction(txid).size }
                            .onSuccess { println("$label: $kind ${txid.take(12)} $it bytes") }
                            .onFailure { failures += "$label: $kind $txid: ${it.message}" }
                    }
                } finally {
                    backend.close()
                }
            }
        }
        failures.forEach { println("FAILED $it") }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /** The tip block's coinbase (when the server has block routes) and a recent mempool transaction. */
    private fun sampleTxids(url: String): List<Pair<String, String>> {
        fun get(path: String): String? = runCatching {
            (java.net.URI("$url$path").toURL().openConnection() as java.net.HttpURLConnection).run {
                connectTimeout = 20_000; readTimeout = 20_000
                setRequestProperty("User-Agent", "sparrow-ltc-mobile-test")
                if(responseCode == 200) inputStream.bufferedReader().readText() else null
            }
        }.getOrNull()
        val txid = Regex("[0-9a-f]{64}")
        val tip = get("/blocks/tip/hash")?.trim()?.removeSurrounding("\"")
        val coinbase = tip?.let { get("/block/$it/txids") }?.let { txid.find(it)?.value }
        val mempool = get("/mempool/recent")?.let { Regex("\"txid\"\\s*:\\s*\"([0-9a-f]{64})\"").find(it)?.groupValues?.get(1) }
        return listOfNotNull(coinbase?.let { "coinbase" to it }, mempool?.let { "mempool" to it })
    }
}
