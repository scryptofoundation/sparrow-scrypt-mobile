package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.Abi
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.UInt256

/**
 * The NFT collections the phone knows, each by its address on one network, ported from drongo's KnownCollections. Anyone
 * can deploy a collection with any name, so only the address identifies a known collection. A known collection may
 * carry its own rule for finding a holder's tokens without an explorer, such as the Vault Badge's ids computed from the
 * holder's address. (The desktop's lookalikeOf serves "Add collection", which the phone does not have.)
 */
object KnownCollections {
    /** The Super Litecoin Vault Badge, deployed on LiteForge. */
    val VAULT_BADGE: EvmAddress = EvmAddress.parse("0x3eEd9a4c9AD8a1Ac42BDb9eEE6079c75C8fd51B3")!!

    //The badge has one token per world; its worlds() is the contract's answer, so only this many are looked up
    const val MAX_VAULT_BADGE_WORLDS = 64
    //worlds() answers one word; anything far larger is not a real answer
    private const val WORLDS_RESULT_BYTES = 32 * 4

    /** The token ids a holder may have, and how many more candidates were not looked up (saturating at Long.MAX_VALUE). */
    internal class Candidates(val tokenIds: List<UInt256>, val notLookedUp: Long)

    internal class Known(val network: LitVmNetwork, val contract: EvmAddress, val name: String, val symbol: String,
                         val tokenIds: suspend (LitVmCalls, EvmAddress, EvmAddress) -> Candidates)

    private val KNOWN = listOf(Known(LitVmNetwork.LITEFORGE_TESTNET, VAULT_BADGE, "Super Litecoin Vault Badge", "SLVB", ::vaultBadgeTokenIds))

    /** The known collections on a network. */
    fun on(network: LitVmNetwork): List<EvmAddress> = KNOWN.filter { it.network == network }.map { it.contract }

    /** Whether this address is a known collection on this network: by address only, never by name. */
    fun isKnown(network: LitVmNetwork, contract: EvmAddress): Boolean = known(network, contract) != null

    internal fun known(network: LitVmNetwork, contract: EvmAddress): Known? = KNOWN.firstOrNull { it.network == network && it.contract == contract }

    /** The badge's token id for a world: (uint160(owner) << 8) | world, as VaultBadge.tokenIdOf computes it. */
    fun vaultBadgeTokenId(owner: EvmAddress, world: Int): UInt256 = UInt256.fromBytes(owner.bytes)!!.shl8Or(world)!!

    /** worlds(), read with a small cap and capped at [MAX_VAULT_BADGE_WORLDS]; a revert or a malformed answer throws. */
    private suspend fun vaultBadgeTokenIds(calls: LitVmCalls, contract: EvmAddress, owner: EvmAddress): Candidates {
        val worlds = Abi.uint(calls.call(contract, Abi.selector("worlds()"), WORLDS_RESULT_BYTES))
            ?: throw LitVmException("Invalid worlds() result from $contract")
        //Compared before it is narrowed: a contract can answer up to 2^256 - 1
        val lookedUp = if(worlds > UInt256.of(MAX_VAULT_BADGE_WORLDS.toLong())) MAX_VAULT_BADGE_WORLDS else worlds.saturatedLong().toInt()
        val ids = (1..lookedUp).map { vaultBadgeTokenId(owner, it) }
        return Candidates(ids, worlds.saturatedLong() - lookedUp)
    }
}

/** This number, or Long.MAX_VALUE when it is larger. */
internal fun UInt256.saturatedLong(): Long {
    if(this > UInt256.of(Long.MAX_VALUE)) {
        return Long.MAX_VALUE
    }
    val word = toWord()
    var value = 0L
    for(i in 24 until 32) {
        value = (value shl 8) or (word[i].toLong() and 0xFF)
    }
    return value
}
