package com.sparrowwallet.mobile.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * "Sign externally" for every coin and script type: the PSBT the phone makes, signed by the phone's
 * PSBT signer and finalized, is byte for byte the transaction direct signing makes for the same coins.
 */
class PsbtRoundTripTest {
    private val master = PsbtFixtures.master
    private val h = PsbtFixtures.HARDENED

    private fun prevTx(script: ByteArray, value: Long, salt: String) =
        Tx(2, listOf(TxInput(salt.repeat(32), 0, 0xFFFFFFFFL, ByteArray(0))), listOf(TxOutput(value, script)))

    private fun roundTrip(coin: Coin, type: ScriptType) {
        val params = ChainParams.of(coin, Network.MAINNET)
        val accountPath = listOf(type.purpose.toLong() + h, coin.coinType.toLong() + h, 0L + h)
        var account = master
        for(child in accountPath) account = account.deriveChild(child)
        val keys = listOf(0L, 1L).map { account.deriveChild(0).deriveChild(it) }
        val inputs = keys.mapIndexed { i, key ->
            val prev = prevTx(Scripts.outputScript(key.pubKey, type), 50_000_000_000, if(i == 0) "aa" else "bb")
            SpendableInput(prev.txid(), 0, 50_000_000_000, key, type, prev.serialize())
        }
        val dest = Scripts.outputScript(account.deriveChild(0).deriveChild(9).pubKey, type)
        val changeKey = account.deriveChild(1).deriveChild(0)
        val change = Scripts.outputScript(changeKey.pubKey, type)
        val rate = kotlin.math.ceil(coin.recommendedFeeRate).toLong()

        val direct = TxBuilder.build(inputs, dest, 60_000_000_000, rate, change, params = params, chainTip = null)
        val unsigned = TxBuilder.buildUnsigned(inputs, dest, 60_000_000_000, rate, change, params = params)
        val psbt = Psbt.create(unsigned, master.fingerprint,
            pathFor = { input -> accountPath + listOf(0L, keys.indexOfFirst { it.pubKey.contentEquals(input.key.pubKey) }.toLong()) },
            changeIndex = 1, changePubKey = changeKey.pubKey, changePath = accountPath + listOf(1L, 0L))
        val signed = PsbtSigner.sign(Psbt.parse(psbt), master, params, accountPath, type, chainTip = null)
        val final = Psbt.finalize(Psbt.parse(signed))
        assertContentEquals(direct.tx.serialize(), final.serialize(), "$coin $type")
        assertEquals(direct.tx.txid(), final.txid(), "$coin $type")
    }

    @Test
    fun psbtPathMatchesDirectSigningForEveryScriptType() {
        roundTrip(Coin.LITECOIN, ScriptType.P2WPKH)
        roundTrip(Coin.LITECOIN, ScriptType.P2SH_P2WPKH)
        roundTrip(Coin.LITECOIN, ScriptType.P2PKH)
        roundTrip(Coin.DOGECOIN, ScriptType.P2PKH)
        roundTrip(Coin.BELLSCOIN, ScriptType.P2WPKH)
        roundTrip(Coin.DINGOCOIN, ScriptType.P2PKH)
    }
}
