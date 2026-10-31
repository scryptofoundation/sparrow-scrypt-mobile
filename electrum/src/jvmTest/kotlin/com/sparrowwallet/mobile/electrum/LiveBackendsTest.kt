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

    private fun check(label: String, params: ChainParams, open: () -> ChainBackend) = runBlocking {
        val script = Scripts.outputScript(master.derivePath(params.derivationPath(ScriptType.P2PKH)).deriveChild(0).deriveChild(0).pubKey, ScriptType.P2PKH)
        val backend = open()
        try {
            backend.connect()
            val tip = backend.tipHeight()
            val history = backend.history(script)
            val fee = backend.feeRate(2)
            println("$label: tip $tip, history ${history.size}, fee $fee sat/vB")
            assertTrue(tip > 0, label)
            assertTrue(fee == null || fee > 0, label)
        } finally {
            backend.close()
        }
    }

    @Test fun everyCoinsPublicBackendAnswers() {
        if(!enabled) return
        val pepTest = ChainParams.of(Coin.PEPECOIN, Network.TESTNET)
        check("PEP testnet Esplora", pepTest) { EsploraBackend("https://pepe-testnet-api.nintondo.io", pepTest) }
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
}
