package com.sparrowwallet.mobile.electrum

import com.sparrowwallet.mobile.crypto.Bip32Pub
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.toHex

/**
 * Scans the wallet's derived addresses through a [ChainBackend], for any single-key script
 * type. A pragmatic fixed lookahead per chain rather than a growing gap-limit walk; enough
 * for early wallets, revisit when an address explorer screen lands.
 */
class WalletScanner(private val backend: ChainBackend, private val scriptType: ScriptType = ScriptType.P2WPKH) {
    private fun script(accountKey: Bip32Pub, chain: Int, index: Int): ByteArray =
        Scripts.outputScript(accountKey.deriveChild(chain.toLong()).deriveChild(index.toLong()).pubKey, scriptType)

    suspend fun balance(accountKey: Bip32Pub, addressesPerChain: Int = 20): Balance {
        var confirmed = 0L
        var unconfirmed = 0L
        for(chain in 0..1) { // 0 = receive, 1 = change
            for(i in 0 until addressesPerChain) {
                val bal = backend.balance(script(accountKey, chain, i))
                confirmed += bal.confirmed
                unconfirmed += bal.unconfirmed
            }
        }
        return Balance(confirmed, unconfirmed)
    }

    /** A wallet utxo tagged with the derivation (chain/index) of the address holding it, and whether it was mined. */
    class ChainUtxo(val utxo: Utxo, val chain: Int, val index: Int, val coinbase: Boolean = false)

    class UtxoScan(val utxos: List<ChainUtxo>, val nextChangeIndex: Int)

    /**
     * Collects all spendable utxos over the same lookahead as [balance], and finds the
     * first never-used change index for the change output. A utxo confirmed above
     * [checkCoinbaseAbove] is looked up to tell whether it is a coinbase, which must mature
     * before it is spent; deeper ones are mature either way and cost no lookup.
     */
    suspend fun utxos(accountKey: Bip32Pub, addressesPerChain: Int = 20, checkCoinbaseAbove: Int = Int.MAX_VALUE): UtxoScan {
        val utxos = ArrayList<ChainUtxo>()
        var nextChangeIndex = addressesPerChain
        for(chain in 0..1) {
            for(i in 0 until addressesPerChain) {
                val script = script(accountKey, chain, i)
                for(utxo in backend.unspent(script)) {
                    val young = utxo.height > 0 && utxo.height > checkCoinbaseAbove
                    val coinbase = young && backend.transaction(utxo.txHash).inputs.all { it.prevTxid == null }
                    utxos.add(ChainUtxo(utxo, chain, i, coinbase))
                }
                if(chain == 1 && i < nextChangeIndex && backend.history(script).isEmpty()) {
                    nextChangeIndex = i
                }
            }
        }
        return UtxoScan(utxos, nextChangeIndex)
    }

    /**
     * One full wallet sync: balance, transaction history with net wallet deltas, and the
     * first unused receive index. Outputs paying our scripts count in, inputs spending our
     * previous outputs count out. A spent output comes with the input when the server
     * includes it (Esplora), else from the previous transaction, fetched once per sync.
     * [onProgress] gets the running count of addresses checked; rate-limited servers make
     * a first sync take minutes.
     */
    suspend fun sync(accountKey: Bip32Pub, addressesPerChain: Int = 20, onProgress: (checked: Int) -> Unit = {}): WalletSnapshot {
        val ourScripts = HashSet<String>()
        var confirmed = 0L
        var unconfirmed = 0L
        var firstUnusedReceive = addressesPerChain
        val heights = LinkedHashMap<String, Int>() // txid → height
        var checked = 0
        for(chain in 0..1) {
            for(i in 0 until addressesPerChain) {
                val script = script(accountKey, chain, i)
                ourScripts.add(script.toHex())
                val bal = backend.balance(script)
                confirmed += bal.confirmed
                unconfirmed += bal.unconfirmed
                onProgress(++checked)
                val history = backend.history(script)
                if(chain == 0 && i < firstUnusedReceive && history.isEmpty()) {
                    firstUnusedReceive = i
                }
                for(entry in history) {
                    heights[entry.txHash] = entry.height
                }
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

        return WalletSnapshot(Balance(confirmed, unconfirmed), summaries, firstUnusedReceive)
    }
}
