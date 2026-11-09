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

    /**
     * A block lands while the wallet's addresses are read one by one: the server answers the spent
     * address from its stale mempool and the change address from the new block. The balance must
     * still be what the wallet's transactions add up to (seen live on Litecoin testnet 2026-11-08:
     * 0.0012 confirmed, 0.00101704 sent, shown as -0.00101704 until a refresh).
     */
    @Test fun balanceComesFromTheTransactionsNotPerAddressServerBalances(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        val receive0 = script(account, 0, 0, ScriptType.P2WPKH)
        val change0 = script(account, 1, 0, ScriptType.P2WPKH)
        val elsewhere = "0014" + "ee".repeat(20)
        backend.txs["fund"] = TxDetail("fund", listOf(TxIn(null, null, null)), listOf(TxOut(receive0, 120_000)), 10, 1_000, 2)
        backend.txs["spend"] = TxDetail("spend", listOf(TxIn("fund", 0, null)),
            listOf(TxOut(elsewhere, 100_000), TxOut(change0, 18_296)), 11, 2_000, 1)
        backend.histories[receive0] = listOf(HistoryEntry("fund", 10), HistoryEntry("spend", 0)) // stale mempool view
        backend.histories[change0] = listOf(HistoryEntry("spend", 11))                           // the new block
        backend.balances[receive0] = Balance(0, -120_000)
        backend.balances[change0] = Balance(18_296, 0)
        val snap = WalletScanner(backend).sync(account)
        assertEquals(Balance(18_296, 0), snap.balance)
        assertEquals(listOf(11, 10), snap.history.map { it.height }) // confirmed wherever any address saw it mined
    }

    /** An address whose history the server cut short: its transactions are not all known, so the server's balances stand. */
    @Test fun truncatedHistoryFallsBackToServerBalances(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = object : ChainBackend by FakeBackend() {
            val inner = FakeBackend()
            override suspend fun balance(script: ByteArray) = inner.balance(script)
            override suspend fun history(script: ByteArray) = inner.history(script)
            override suspend fun transaction(txid: String) = inner.transaction(txid)
            override fun historyTruncated(script: ByteArray) = script.toHex() == script(account, 0, 0, ScriptType.P2WPKH)
        }
        fundAndSpend(backend.inner, account, ScriptType.P2WPKH, withPrevouts = true)
        backend.inner.balances[script(account, 0, 0, ScriptType.P2WPKH)] = Balance(5_000_000, 0) // older coins the history did not reach
        val snap = WalletScanner(backend).sync(account)
        assertEquals(Balance(5_020_000, 0), snap.balance)
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
        backend.histories[script(account, 0, 0, ScriptType.P2PKH)] = listOf(HistoryEntry("fund", 10))
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
        backend.histories[receive0] = listOf(HistoryEntry("mined", 95), HistoryEntry("paid", 96), HistoryEntry("oldmined", 10))
        val scan = WalletScanner(backend, ScriptType.P2PKH).utxos(account, checkCoinbaseAbove = 50)
        assertEquals(mapOf("mined" to true, "paid" to false, "oldmined" to false), scan.utxos.associate { it.utxo.txHash to it.coinbase })
        assertTrue("oldmined" !in backend.txRequests) // too deep to be immature: never looked up
    }

    /** An address used at [index] on [chain], paid [value] by its own transaction. */
    private fun used(backend: FakeBackend, account: HDKey, chain: Long, index: Long, value: Long) {
        val script = script(account, chain, index, ScriptType.P2WPKH)
        val txid = "tx-$chain-$index"
        backend.txs[txid] = TxDetail(txid, listOf(TxIn("ff".repeat(32), 0, TxOut("0014" + "dd".repeat(20), value + 1_000))),
            listOf(TxOut(script, value)), 0, 1_000, 3)
        backend.histories[script] = listOf(HistoryEntry(txid, 50))
        backend.balances[script] = Balance(value, 0)
        backend.utxos[script] = listOf(Utxo(txid, 0, 50, value))
    }

    /** Like desktop, the scan runs on until 20 unused addresses follow the last used one, so no fixed count of addresses. */
    @Test fun syncFindsAddressesPastTheFirstTwenty(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        used(backend, account, 0, 15, 1_000)
        used(backend, account, 0, 34, 2_000)
        val snap = WalletScanner(backend).sync(account)
        assertEquals(Balance(3_000, 0), snap.balance)
        assertEquals(setOf("tx-0-15", "tx-0-34"), snap.history.map { it.txid }.toSet())
        assertEquals(35, snap.firstUnusedReceiveIndex)
    }

    /** Twenty unused addresses in a row end the walk: one used just past them is not reached, as on desktop. */
    @Test fun syncStopsAfterTwentyUnusedInARow(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        used(backend, account, 0, 5, 1_000)
        used(backend, account, 0, 26, 2_000)
        assertEquals(Balance(1_000, 0), WalletScanner(backend).sync(account).balance)
    }

    /** Addresses handed out on the Receive screen stay watched: the walk runs 20 past them even when they are unused. */
    @Test fun syncWatchesHandedOutReceiveAddresses(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        used(backend, account, 0, 55, 4_000)
        val snap = WalletScanner(backend).sync(account, watchReceiveTo = 40)
        assertEquals(Balance(4_000, 0), snap.balance)
        assertEquals(56, snap.firstUnusedReceiveIndex)
    }

    /** The next fresh address follows the highest used one (desktop's fresh node), not a gap below it. */
    @Test fun freshReceiveFollowsTheHighestUsed(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        used(backend, account, 0, 0, 1_000)
        used(backend, account, 0, 2, 1_000)
        assertEquals(3, WalletScanner(backend).sync(account).firstUnusedReceiveIndex)
    }

    @Test fun utxosAndChangePastTheFirstTwenty(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        used(backend, account, 0, 12, 1_000)
        used(backend, account, 0, 30, 5_000)
        used(backend, account, 1, 4, 2_000)
        used(backend, account, 1, 22, 6_000)
        val scan = WalletScanner(backend).utxos(account)
        assertEquals(setOf("tx-0-12" to 12, "tx-0-30" to 30, "tx-1-4" to 4, "tx-1-22" to 22),
            scan.utxos.map { it.utxo.txHash to it.index }.toSet())
        assertEquals(23, scan.nextChangeIndex)
    }

    @Test fun balancePastTheFirstTwenty(): Unit = runBlocking {
        val account = master.derivePath("m/84'/2'/0'")
        val backend = FakeBackend()
        used(backend, account, 0, 10, 1_000)
        used(backend, account, 0, 25, 7_000)
        assertEquals(Balance(8_000, 0), WalletScanner(backend).balance(account))
    }
}
