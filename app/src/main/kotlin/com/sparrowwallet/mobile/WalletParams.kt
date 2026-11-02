package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.SpendableInput
import com.sparrowwallet.mobile.electrum.WalletScanner
import kotlin.math.ceil

/** Per-coin rules the repository and screens share; pure so they are tested on the JVM. */
fun walletParams(coin: Coin, network: Network): ChainParams = ChainParams.of(coin, network)

/** A public wallet's script type. MWEB wallets never reach here; their paths stay Litecoin-only. */
fun walletScriptType(scriptType: String): ScriptType =
    ScriptType.entries.firstOrNull { it.name == scriptType }
        ?: throw IllegalArgumentException("Not a public wallet script type: $scriptType")

/**
 * A server's fee estimate as whole sats (lits) per vbyte, never below the coin's relay floor.
 * The ceiling guards against absurd values while leaving room for coins whose floor is high
 * (Dogecoin estimates near 50,600 sat/vB, Dingocoin's floor is 100,000).
 */
fun clampFeeRate(coin: Coin, satPerVb: Double?): Long {
    val floor = ceil(coin.recommendedFeeRate).toLong()
    if(satPerVb == null || satPerVb.isNaN() || satPerVb <= 0) return if(coin == Coin.LITECOIN) 10L else floor
    return ceil(satPerVb).toLong().coerceIn(floor, maxOf(10_000L, floor * 100))
}

fun feeUnit(coin: Coin): String = if(coin == Coin.LITECOIN) "lit/vB" else "sat/vB"

/**
 * Whether the PSBT, external-signer and air-gapped paths support the wallet: every public script
 * type the coin has. MWEB wallets sign through mwebd and stay out.
 */
fun externalSigningSupported(coin: Coin, scriptType: String): Boolean =
    ScriptType.entries.firstOrNull { it.name == scriptType }?.let { coin.supports(it) } == true

/**
 * Whether an unsigned transaction's txid is the one it will have: only when no input carries a
 * scriptSig, so Native Segwit. Legacy and Nested Segwit inputs get theirs when finalized.
 */
fun txidKnownBeforeSigning(scriptType: String): Boolean = scriptType == ScriptType.P2WPKH.name

/** A scanned coin as Send judges it: [height] 0 or below is unconfirmed; [coinbaseMaturity] only for a mined coin. */
data class ScannedCoin(val value: Long, val height: Int, val coinbaseMaturity: Int? = null, val held: Boolean = false)

/**
 * Whether a coin at [height] can go in the next block: [minConfirmations] deep, and a coinbase also
 * [coinbaseMaturity] deep, or the network refuses the spend.
 */
fun spendableAt(tip: Long, height: Int, minConfirmations: Int, coinbaseMaturity: Int? = null): Boolean {
    if(height <= 0) return false
    val confirmations = tip - height + 1
    return confirmations >= minConfirmations && (coinbaseMaturity == null || confirmations >= coinbaseMaturity)
}

/**
 * The wallet's coins as Send sees them: [spendable] values and the total still [maturing] (newer,
 * unconfirmed, or a coinbase short of its maturity), which Send names so a fresh receive is not
 * read as "not enough funds".
 */
data class SendCoins(val spendable: List<Long>, val maturing: Long, val held: List<Long> = emptyList()) {
    /** The coins Send may use: [held] ones (which may carry an inscription) only when the user includes them. */
    fun usable(includeHeld: Boolean): List<Long> = if(includeHeld) spendable + held else spendable

    companion object {
        /** [held] lists ready coins that may carry an inscription; Send leaves them out by default. */
        fun split(coins: List<ScannedCoin>, tip: Long, minConfirmations: Int): SendCoins {
            val (ready, young) = coins.partition { spendableAt(tip, it.height, minConfirmations, it.coinbaseMaturity) }
            val (held, free) = ready.partition { it.held }
            return SendCoins(free.map { it.value }, young.sumOf { it.value }, held.map { it.value })
        }
    }
}

/** The Send screen's words for the public side; the private (MWEB) side has its own. */
data class SendCopy(
    val subtitle: String,
    val addressLabel: String,
    val toSelf: String,
    val from: String,
    val available: String,
    val maxHint: String,
    val shortfall: String,
    val cannotCover: String,
    val showLayers: Boolean
)

/**
 * A Litecoin wallet is one wallet in two layers, public and private (MWEB), and the Send screen
 * says so. Coins without MWEB have one layer, so none of that wording applies.
 */
fun sendCopy(coin: Coin): SendCopy = if(coin.mweb) {
    SendCopy("ONE WALLET, TWO LAYERS", "Address (public or private)", "→ My Public", "Public layer",
        "Public", "Entire public balance", "not enough funds in either layer", "public coins can't cover this", true)
} else {
    SendCopy(coin.displayName.uppercase(), "Address", "→ My address", "This wallet",
        "Available", "Entire balance", "not enough funds", "not enough funds", false)
}

/**
 * Whether a coin scan taken at [scannedAtMs] can build a send at [nowMs]: the Send screen scans when
 * it opens, and scanning again for Preview doubled a slow server's wait. A coin spent since then
 * makes the broadcast fail, as a race with another wallet does today.
 */
internal fun reusableScan(scannedAtMs: Long?, nowMs: Long, maxAgeMs: Long = 120_000): Boolean =
    scannedAtMs != null && nowMs >= scannedAtMs && nowMs - scannedAtMs <= maxAgeMs

/** Coin scans per wallet, reused while [reusableScan] says they are fresh; never written to disk. */
internal class ScanCache<K, S> {
    private class Entry<S>(val scan: S, val tip: Long, val atMs: Long)
    private val entries = java.util.concurrent.ConcurrentHashMap<K, Entry<S>>()

    fun put(key: K, scan: S, tip: Long, nowMs: Long) {
        entries[key] = Entry(scan, tip, nowMs)
    }

    fun fresh(key: K, nowMs: Long): Pair<S, Long>? =
        entries[key]?.takeIf { reusableScan(it.atMs, nowMs) }?.let { it.scan to it.tip }

    fun clear(key: K) {
        entries.remove(key)
    }

    /** Drops every scan, when what decides a scan's held coins changes. */
    fun clearAll() {
        entries.clear()
    }
}

/**
 * The spendable inputs of a scan (at least [minConfirmations] deep at [tip], and a coinbase as deep
 * as [coinbaseMaturity] gives for its height) as the wallet's script type, and the change script at
 * the first unused change index.
 */
internal fun spendables(scan: WalletScanner.UtxoScan, tip: Long, account: HDKey, scriptType: ScriptType,
                        minConfirmations: Int, coinbaseMaturity: (Int) -> Int = { 100 },
                        includeHeld: Boolean = false): Pair<List<SpendableInput>, ByteArray> {
    //held coins may carry an inscription or tokens: only when the user includes them
    val candidates = scan.utxos
        .filter { spendableAt(tip, it.utxo.height, minConfirmations, if(it.coinbase) coinbaseMaturity(it.utxo.height) else null) }
        .filter { includeHeld || !it.held }
        .map {
            SpendableInput(
                it.utxo.txHash, it.utxo.txPos.toLong(), it.utxo.value,
                account.deriveChild(it.chain.toLong()).deriveChild(it.index.toLong()), scriptType
            )
        }
    val changePub = account.deriveChild(1).deriveChild(scan.nextChangeIndex.toLong()).pubKey
    return candidates to Scripts.outputScript(changePub, scriptType)
}
