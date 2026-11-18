package com.sparrowwallet.mobile.crypto

/**
 * What a signature hash starts with on the chain a transaction is meant for. Most chains hash the transaction alone
 * ([NONE]). Luckycoin's nodes have also hashed a 32-byte chain id first, in the legacy and in the witness v0 signature
 * hash alike, since its chain reached block [LUCKYCOIN_REVIVAL_HEIGHT] (Luckycoin Core, LuckycoinFoundation/Luckycoin
 * 8b1fa2b5: `GetChainSigVersion` in validation.cpp, `SignatureHash` in script/interpreter.cpp, `CHAIN_ID` in
 * script/interpreter.h line 111), so a signature made without it is refused there, and one made with it is valid
 * nowhere else. Two heights are asked. A block checks its transactions by its own height (`ConnectBlock`,
 * validation.cpp about line 3198): a transaction mined at height H needs the chain id exactly when H >= 276500. The
 * mempool checks a new transaction by the tip (about line 643): it takes the chain id exactly when the tip is at or
 * above 276500.
 *
 * There is no default anywhere: whoever signs says which, with [of] from the coin, the network and the chain tip.
 */
class ChainSig private constructor(private val id: ByteArray?) {
    /** The bytes a signature hash starts with; empty for [NONE]. A copy each time. */
    val prefix: ByteArray get() = id?.copyOf() ?: ByteArray(0)

    /** Writes the prefix (nothing for [NONE]) where a signature hash's preimage starts. */
    internal fun writeTo(w: ByteWriter): ByteWriter = if(id == null) w else w.bytes(id)

    override fun toString(): String = if(id == null) "ChainSig.NONE" else "ChainSig(${id.toHex()})"

    companion object {
        /** The height from which Luckycoin's nodes require the chain id, on every network (see the class's comment for which height is asked). */
        const val LUCKYCOIN_REVIVAL_HEIGHT = 276_500L

        /** A signature hash of the transaction alone. */
        val NONE = ChainSig(null)

        /**
         * Luckycoin Core's `CHAIN_ID`, `uint256S("0x4c55434b59434f494e5f5245564956414c")` (the ASCII of "LUCKYCOIN_REVIVAL"
         * read as a number), as a uint256 is written into a hash: its 32 bytes little-endian, so the ASCII reversed and
         * then 15 zero bytes.
         */
        private val LUCKYCOIN = ChainSig("4c55434b59434f494e5f5245564956414c".hexToBytes().reversedArray() + ByteArray(15))

        /**
         * Which signature hash a transaction of [params]' coin and network takes, on a chain whose tip is [tip] (null:
         * not known). A send is handed to a node's mempool, which asks whether its tip is at or above
         * [LUCKYCOIN_REVIVAL_HEIGHT]: so from a tip of 276500 on, and on mainnet always, since its chain is long past
         * that height whatever the phone knows of it. A transaction in a mempool goes into a block above the tip, so a
         * block takes what the mempool took, but for one tip: at 276499 the mempool takes only a signature without the
         * chain id and block 276500 only one with it, so a send made at that tip can enter the mempool and is never
         * mined; it matters only on a test chain crossing the height. On the test network (and a regtest chain,
         * which the phone reaches through the test network's parameters) only by the tip; one that is not known is
         * taken as below the height, as a short regtest chain is: should that be wrong, the node refuses the
         * signature and nothing is sent. Every other coin: [NONE].
         */
        fun of(params: ChainParams, tip: Long?): ChainSig = when {
            params.coin != Coin.LUCKYCOIN -> NONE
            params.network == Network.MAINNET -> LUCKYCOIN
            tip != null && tip >= LUCKYCOIN_REVIVAL_HEIGHT -> LUCKYCOIN
            else -> NONE
        }
    }
}
