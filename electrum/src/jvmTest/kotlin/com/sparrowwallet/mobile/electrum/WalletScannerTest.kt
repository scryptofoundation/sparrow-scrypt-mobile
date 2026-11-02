package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.toHex
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WalletScannerTest {
    private val master = HDKey.fromSeed(Bip39.seed("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"))

    /** A chain known per script: balances, history, utxos and transactions; records what it was asked. */
    private class FakeBackend : ChainBackend {
        val balances = HashMap<String, Balance>()
        val histories = HashMap<String, List<HistoryEntry>>()
        val utxos = HashMap<String, List<Utxo>>()
        val txs = HashMap<String, TxDetail>()
        val txRequests = ArrayList<String>()

        override suspend fun connect() {}
        override suspend fun balance(script: ByteArray) = balances[script.toHex()] ?: Balance(0, 0)
        override suspend fun history(script: ByteArray) = histories[script.toHex()] ?: emptyList()
        override suspend fun unspent(script: ByteArray) = utxos[script.toHex()] ?: emptyList()
        override suspend fun transaction(txid: String): TxDetail { txRequests += txid; return txs.getValue(txid) }
        override suspend fun rawTransaction(txid: String): ByteArray = error("not used")
        override suspend fun broadcast(txHex: String) = error("not used")
        override suspend fun tipHeight() = 100L
        override suspend fun feeRate(targetBlocks: Int): Double? = null
        override fun close() {}
    }

    private fun script(account: HDKey, chain: Long, index: Long, type: ScriptType) =
        Scripts.outputScript(account.deriveChild(chain).deriveChild(index).pubKey, type).toHex()

    /** receive/0 gets 50,000 in "fund"; "spend" spends it, paying 30,000 away and 20,000 to change/0. */
    private fun fundAndSpend(backend: FakeBackend, account: HDKey, type: ScriptType, withPrevouts: Boolean) {
        val receive0 = script(account, 0, 0, type)
        val change0 = script(account, 1, 0, type)
        val elsewhere = "0014" + "ee".repeat(20)
        backend.txs["fund"] = TxDetail("fund", listOf(TxIn(null, null, null)), listOf(TxOut(receive0, 50_000)), 0, 1_000, 2)
        val spentOut = if(withPrevouts) TxOut(receive0, 50_000) else null
        backend.txs["spend"] = TxDetail("spend", listOf(TxIn("fund", 0, spentOut)),
            listOf(TxOut(elsewhere, 30_000), TxOut(change0, 20_000)), 0, 2_000, 1)
        backend.histories[receive0] = listOf(HistoryEntry("fund", 10), HistoryEntry("spend", 11))
        backend.histories[change0] = listOf(HistoryEntry("spend", 11))
        backend.balances[change0] = Balance(20_000, 0)
    }

    @Test fun electrumStyleDeltasFetchPrevTxs(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        fundAndSpend(backend, account, ScriptType.P2WPKH, withPrevouts = false)
        val snap = WalletScanner(backend).sync(account)
        assertEquals(listOf("spend", "fund"), snap.history.map { it.txid })
        assertEquals(listOf(-30_000L, 50_000L), snap.history.map { it.delta })
        assertEquals(Balance(20_000, 0), snap.balance)
        assertTrue("fund" in backend.txRequests)
    }

    @Test fun esploraStyleDeltasUsePrevouts(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        fundAndSpend(backend, account, ScriptType.P2WPKH, withPrevouts = true)
        val snap = WalletScanner(backend).sync(account)
        assertEquals(listOf(-30_000L, 50_000L), snap.history.map { it.delta })
        assertEquals(1, backend.txRequests.count { it == "fund" }) // fetched once as history, never as a prev tx
    }

    @Test fun p2pkhWalletScansLegacyScripts(): Unit = runBlocking {
        val account = master.derivePath("m/44'/3'/0'")
        val backend = FakeBackend()
        backend.balances[script(account, 0, 0, ScriptType.P2PKH)] = Balance(7_000_000, 0)
        assertEquals(Balance(7_000_000, 0), WalletScanner(backend, ScriptType.P2PKH).balance(account))
        assertEquals(Balance(0, 0), WalletScanner(backend, ScriptType.P2WPKH).balance(account))
    }

    @Test fun esploraStyleUnconfirmedTxCountsAsUnconfirmed(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        val receive1 = script(account, 0, 1, ScriptType.P2WPKH)
        fundAndSpend(backend, account, ScriptType.P2WPKH, withPrevouts = true)
        backend.txs["incoming"] = TxDetail("incoming", listOf(TxIn("ff".repeat(32), 0, TxOut("0014" + "dd".repeat(20), 9_000))),
            listOf(TxOut(receive1, 8_000)), 0, null, 0)
        backend.histories[receive1] = listOf(HistoryEntry("incoming", 0))
        backend.balances[receive1] = Balance(0, 8_000)
        val snap = WalletScanner(backend).sync(account)
        assertEquals("incoming", snap.history.first().txid)
        assertEquals(Balance(20_000, 8_000), snap.balance)
    }

    @Test fun syncReportsProgressPerAddress(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val progress = ArrayList<Int>()
        WalletScanner(FakeBackend()).sync(account, onProgress = { progress += it })
        assertEquals((1..40).toList(), progress)
    }

    @Test fun utxosMarkYoungCoinbasesOnly(): Unit = runBlocking {
        val account = master.derivePath("m/44'/1'/0'")
        val backend = FakeBackend()
        val receive0 = script(account, 0, 0, ScriptType.P2PKH)
        backend.utxos[receive0] = listOf(Utxo("mined", 0, 95, 5_000), Utxo("paid", 0, 96, 7_000), Utxo("oldmined", 0, 10, 9_000))
        backend.txs["mined"] = TxDetail("mined", listOf(TxIn(null, null, null)), listOf(TxOut(receive0, 5_000)), 0, 1_000, 6)
        backend.txs["paid"] = TxDetail("paid", listOf(TxIn("x".repeat(64), 0, null)), listOf(TxOut(receive0, 7_000)), 0, 1_000, 5)
        val scan = WalletScanner(backend, ScriptType.P2PKH).utxos(account, checkCoinbaseAbove = 50)
        assertEquals(mapOf("mined" to true, "paid" to false, "oldmined" to false), scan.utxos.associate { it.utxo.txHash to it.coinbase })
        assertTrue("oldmined" !in backend.txRequests) // too deep to be immature: never looked up
    }
}
