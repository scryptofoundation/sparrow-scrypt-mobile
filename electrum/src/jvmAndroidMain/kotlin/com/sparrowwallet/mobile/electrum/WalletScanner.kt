package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Bip32Pub
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.toHex

/**
 * Scans the wallet's derived addresses through a [ChainBackend], for any single-key script
 * type. Like desktop, each chain is walked until [GAP_LIMIT] unused addresses follow the last
 * used one (and the last address handed out, on the receive chain), so a wallet has no fixed
 * number of addresses.
 */
class WalletScanner(private val backend: ChainBackend, private val scriptType: ScriptType = ScriptType.P2WPKH) {
    companion object {
        /** Unused addresses checked past the last used one, desktop's default gap limit. */
        const val GAP_LIMIT = 20
    }

    private fun script(accountKey: Bip32Pub, chain: Int, index: Int): ByteArray =
        Scripts.outputScript(accountKey.deriveChild(chain.toLong()).deriveChild(index.toLong()).pubKey, scriptType)

    /**
     * Walks one chain from index 0, handing each address and its history to [visit], until [GAP_LIMIT] addresses
     * past both the highest used one and [watchTo] have been checked. Returns the index after the highest used one.
     */
    private suspend fun walk(accountKey: Bip32Pub, chain: Int, watchTo: Int = 0,
                             visit: suspend (index: Int, script: ByteArray, history: List<HistoryEntry>) -> Unit): Int {
        var next = 0
        var i = 0
        while(i < maxOf(next, watchTo) + GAP_LIMIT) {
            val script = script(accountKey, chain, i)
            val history = backend.history(script)
            if(history.isNotEmpty()) {
                next = i + 1
            }
            visit(i, script, history)
            i++
        }
        return next
    }

    suspend fun balance(accountKey: Bip32Pub): Balance {
        var confirmed = 0L
        var unconfirmed = 0L
        for(chain in 0..1) { // 0 = receive, 1 = change
            walk(accountKey, chain) { _, script, history ->
                if(history.isNotEmpty()) {
                    val bal = backend.balance(script)
                    confirmed += bal.confirmed
                    unconfirmed += bal.unconfirmed
                }
            }
        }
        return Balance(confirmed, unconfirmed)
    }

    /**
     * A wallet utxo tagged with the derivation (chain/index) of the address holding it, whether it was mined, and
     * whether it is [held] because it may carry an inscription or tokens.
     */
    class ChainUtxo(val utxo: Utxo, val chain: Int, val index: Int, val coinbase: Boolean = false, val held: Boolean = false)

    class UtxoScan(val utxos: List<ChainUtxo>, val nextChangeIndex: Int)

    /**
     * Collects all spendable utxos over the same walk as [balance], and the change index after the highest used
     * one for the change output. A utxo confirmed above [checkCoinbaseAbove] is looked up to tell whether it is a
     * coinbase, which must mature before it is spent; deeper ones are mature either way and cost no lookup. [isHeld]
     * marks the coins that may carry an inscription or tokens.
     */
    suspend fun utxos(accountKey: Bip32Pub, checkCoinbaseAbove: Int = Int.MAX_VALUE,
                      isHeld: suspend (Utxo) -> Boolean = { false }): UtxoScan {
        val utxos = ArrayList<ChainUtxo>()
        var nextChangeIndex = 0
        for(chain in 0..1) {
            val next = walk(accountKey, chain) { i, script, history ->
                if(history.isNotEmpty()) {
                    for(utxo in backend.unspent(script)) {
                        val young = utxo.height > 0 && utxo.height > checkCoinbaseAbove
                        val coinbase = young && backend.transaction(utxo.txHash).inputs.all { it.prevTxid == null }
                        utxos.add(ChainUtxo(utxo, chain, i, coinbase, isHeld(utxo)))
                    }
                }
            }
            if(chain == 1) {
                nextChangeIndex = next
            }
        }
        return UtxoScan(utxos, nextChangeIndex)
    }

    /**
     * One full wallet sync: balance, transaction history with net wallet deltas, and the
     * receive index after the highest used one. Outputs paying our scripts count in, inputs spending our
     * previous outputs count out. A spent output comes with the input when the server
     * includes it (Esplora), else from the previous transaction, fetched once per sync.
     * Receive addresses below [watchReceiveTo] were handed out, so the walk covers them even unused.
     * [onProgress] gets the running count of addresses checked; rate-limited servers make
     * a first sync take minutes.
     */
    suspend fun sync(accountKey: Bip32Pub, watchReceiveTo: Int = 0, onProgress: (checked: Int) -> Unit = {}): WalletSnapshot {
        val ourScripts = HashSet<String>()
        val usedScripts = ArrayList<ByteArray>()
        var nextReceive = 0
        val heights = LinkedHashMap<String, Int>() // txid → height
        var checked = 0
        for(chain in 0..1) {
            val next = walk(accountKey, chain, if(chain == 0) watchReceiveTo else 0) { _, script, history ->
                ourScripts.add(script.toHex())
                if(history.isNotEmpty()) {
                    usedScripts.add(script)
                }
                onProgress(++checked)
                for(entry in history) {
                    //Addresses are read one at a time, so a block can land between two of them: mined in any view is mined
                    heights[entry.txHash] = maxOf(heights[entry.txHash] ?: entry.height, entry.height)
                }
            }
            if(chain == 0) {
                nextReceive = next
            }
        }

        val txCache = HashMap<String, TxDetail>()
        suspend fun fetchTx(txid: String): TxDetail =
            txCache[txid] ?: backend.transaction(txid).also { txCache[txid] = it }

        val summaries = heights.map { (txid, height) ->
            val tx = fetchTx(txid)
            var delta = 0L
            for(out in tx.outputs) {
                if(ourScripts.contains(out.scriptHex)) {
                    delta += out.value
                }
            }
            for(input in tx.inputs) {
                val prevTxid = input.prevTxid ?: continue // coinbase
                val spent = input.prevOut ?: input.prevVout?.let { fetchTx(prevTxid).outputs.getOrNull(it) } ?: continue
                if(ourScripts.contains(spent.scriptHex)) {
                    delta -= spent.value
                }
            }
            TxSummary(
                txid = txid,
                height = height,
                timestamp = tx.time,
                delta = delta,
                confirmations = tx.confirmations
            )
        }.sortedWith(compareByDescending<TxSummary> { it.height <= 0 }.thenByDescending { it.height })

        return WalletSnapshot(snapshotBalance(summaries, usedScripts), summaries, nextReceive)
    }

    /**
     * The wallet's balance as its transactions add up: each one's net delta counts once, so the total holds
     * even when a block lands between two address reads, which per-address server balances do not (they once
     * summed to a negative total right after a send). When the server cut an address's history short, not every
     * transaction is known, and the server's per-address balances are used instead.
     */
    private suspend fun snapshotBalance(summaries: List<TxSummary>, usedScripts: List<ByteArray>): Balance {
        if(usedScripts.none { backend.historyTruncated(it) }) {
            return Balance(summaries.filter { it.height > 0 }.sumOf { it.delta }, summaries.filter { it.height <= 0 }.sumOf { it.delta })
        }
        var confirmed = 0L
        var unconfirmed = 0L
        for(script in usedScripts) {
            val bal = backend.balance(script)
            confirmed += bal.confirmed
            unconfirmed += bal.unconfirmed
        }
        return Balance(confirmed, unconfirmed)
    }
}
