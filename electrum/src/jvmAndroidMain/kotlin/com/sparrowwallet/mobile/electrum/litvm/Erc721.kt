package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.Abi
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.NftMetadata
import com.sparrowwallet.mobile.crypto.evm.TextRules
import com.sparrowwallet.mobile.crypto.evm.UInt256

/**
 * An ERC-721 collection: its contract address and what it says about itself. Name and symbol come from a contract anyone
 * can deploy, so they are display hints only, cleaned by [TextRules] as NFT metadata text is; the address is the
 * collection's identity. [enumerable]: it claims ERC-721 Enumerable; [soulbound]: it claims EIP-5192 (its tokens cannot
 * be transferred).
 */
class Erc721Collection(val contract: EvmAddress, name: String, symbol: String, val enumerable: Boolean, val soulbound: Boolean,
                        /** Every optional fact (enumerable, soulbound, name, symbol) was answered, none reverted or unreadable. */
                        val complete: Boolean = true) {
    val name: String = TextRules.singleLine(name, MAX_NAME_LENGTH)
    val symbol: String = TextRules.singleLine(symbol, MAX_SYMBOL_LENGTH)

    override fun equals(other: Any?): Boolean = other is Erc721Collection && contract == other.contract && name == other.name &&
        symbol == other.symbol && enumerable == other.enumerable && soulbound == other.soulbound

    override fun hashCode(): Int = contract.hashCode()

    override fun toString(): String = "Erc721Collection($contract, $name, $symbol, enumerable=$enumerable, soulbound=$soulbound)"

    companion object {
        const val MAX_NAME_LENGTH = 64
        const val MAX_SYMBOL_LENGTH = 16
    }
}

/**
 * One NFT an account holds, identified by its collection's contract and its token id, never by anything in its
 * metadata. The metadata is null when the token URI could not be read.
 */
data class Nft(val collection: Erc721Collection, val tokenId: UInt256, val metadata: NftMetadata?)

/**
 * Read-only access to an ERC-721 contract, ported from drongo's Erc721. Nothing here signs or sends.
 *
 * Every answer comes from a contract anyone can deploy, so each is checked and bounded. The contract's own outcomes (a
 * revert, an answer that is malformed or over its cap) give null or false; a failure of the network, the server or the
 * node throws IOException, so a broken connection is never mistaken for "not a collection".
 */
object Erc721 {
    /** The largest tokenURI result read, in bytes of call result. */
    const val MAX_TOKEN_URI_BYTES = LitVmCalls.MAX_RESULT_BYTES
    //Every other call here returns a word or a short string
    private const val MAX_RESULT_BYTES = 8_192

    private val ERC165 = byteArrayOf(0x01, -1, -55, -89)                  //0x01ffc9a7
    private val NOT_AN_INTERFACE = byteArrayOf(-1, -1, -1, -1)              //0xffffffff
    private val ERC721 = byteArrayOf(-128, -84, 0x58, -51)                  //0x80ac58cd
    private val ERC721_ENUMERABLE = byteArrayOf(0x78, 0x0e, -99, 0x63)      //0x780e9d63
    private val EIP5192_SOULBOUND = byteArrayOf(-76, 0x5a, 0x3c, 0x0e)      //0xb45a3c0e

    private enum class Answer { YES, NO, FAILED }

    /**
     * The collection at this address, or null when it is not an ERC-721 contract. ERC-165 detection as the standard
     * describes it: supportsInterface(0x01ffc9a7) answers true, supportsInterface(0xffffffff) answers false, and
     * supportsInterface(0x80ac58cd) answers true. A contract that answers true to everything, or reverts or answers
     * nonsense on any of these, is not a collection.
     */
    suspend fun read(calls: LitVmCalls, contract: EvmAddress): Erc721Collection? {
        if(!calls.hasCode(contract)) {
            return null
        }
        if(supports(calls, contract, ERC165) != Answer.YES || supports(calls, contract, NOT_AN_INTERFACE) != Answer.NO ||
            supports(calls, contract, ERC721) != Answer.YES) {
            return null
        }
        val enumerable = supports(calls, contract, ERC721_ENUMERABLE)
        val soulbound = supports(calls, contract, EIP5192_SOULBOUND)
        //Name and symbol belong to the optional metadata extension: absent or nonsense leaves them blank
        val name = optionalString(calls, contract, "name()")
        val symbol = optionalString(calls, contract, "symbol()")
        val complete = enumerable != Answer.FAILED && soulbound != Answer.FAILED && name != null && symbol != null
        return Erc721Collection(contract, name ?: "", symbol ?: "", enumerable == Answer.YES, soulbound == Answer.YES, complete)
    }

    /** How many tokens the owner holds. A revert ([CallReverted]) or a malformed answer throws: a collection must answer this. */
    suspend fun balanceOf(calls: LitVmCalls, contract: EvmAddress, owner: EvmAddress): UInt256 =
        uint(calls, contract, Abi.call("balanceOf(address)", owner), "balanceOf")

    /**
     * The token's owner, or null when the contract reverts (no such token) or names the zero address: the contract's
     * answers. An answer over the cap ([ResultTooLarge]), malformed or empty throws, so a token is never dropped as "not
     * held" on an answer that could not be read.
     */
    suspend fun ownerOf(calls: LitVmCalls, contract: EvmAddress, tokenId: UInt256): EvmAddress? {
        val result = try {
            calls.call(contract, Abi.call("ownerOf(uint256)", tokenId), MAX_RESULT_BYTES)
        } catch(e: CallReverted) {
            return null
        }
        val owner = Abi.address(result) ?: throw LitVmException("Invalid ownerOf result from $contract")
        return if(owner.bytes.all { it.toInt() == 0 }) null else owner
    }

    /** The token's metadata URI as the contract returns it, or null on a revert, a malformed answer or one over [MAX_TOKEN_URI_BYTES]. */
    suspend fun tokenUri(calls: LitVmCalls, contract: EvmAddress, tokenId: UInt256): String? {
        val result = call(calls, contract, Abi.call("tokenURI(uint256)", tokenId), MAX_TOKEN_URI_BYTES) ?: return null
        return Abi.string(result, MAX_TOKEN_URI_BYTES)
    }

    /** The id of the owner's token at this index (ERC-721 Enumerable). A revert ([CallReverted]) or a malformed answer throws. */
    suspend fun tokenOfOwnerByIndex(calls: LitVmCalls, contract: EvmAddress, owner: EvmAddress, index: UInt256): UInt256 =
        uint(calls, contract, Abi.call("tokenOfOwnerByIndex(address,uint256)", owner, index), "tokenOfOwnerByIndex")

    private suspend fun supports(calls: LitVmCalls, contract: EvmAddress, interfaceId: ByteArray): Answer {
        //bytes4, left-aligned in its word
        val result = call(calls, contract, Abi.call("supportsInterface(bytes4)", interfaceId), MAX_RESULT_BYTES) ?: return Answer.FAILED
        return when(Abi.bool(result)) {
            true -> Answer.YES
            false -> Answer.NO
            null -> Answer.FAILED
        }
    }

    //The string, or null when the contract reverted or answered something that is not one
    private suspend fun optionalString(calls: LitVmCalls, contract: EvmAddress, signature: String): String? {
        val result = call(calls, contract, Abi.selector(signature), MAX_RESULT_BYTES) ?: return null
        return Abi.string(result)
    }

    private suspend fun uint(calls: LitVmCalls, contract: EvmAddress, data: ByteArray, function: String): UInt256 {
        val result = calls.call(contract, data, MAX_RESULT_BYTES)
        return Abi.uint(result) ?: throw LitVmException("Invalid $function result from $contract")
    }

    /**
     * The call's return data, or null when the contract reverted or answered more than maxBytes. Anything else (the
     * network, HTTP, a malformed response, a node error that is not a revert) throws.
     */
    private suspend fun call(calls: LitVmCalls, contract: EvmAddress, data: ByteArray, maxBytes: Int): ByteArray? = try {
        calls.call(contract, data, maxBytes)
    } catch(e: CallReverted) {
        null
    } catch(e: ResultTooLarge) {
        null
    }
}
