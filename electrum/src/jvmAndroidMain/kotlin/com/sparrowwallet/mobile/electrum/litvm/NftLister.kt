package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.NftMetadata
import com.sparrowwallet.mobile.crypto.evm.UInt256
import java.io.IOException

/**
 * Lists the NFTs an account holds in one collection, using only the RPC, ported from drongo's NftLister: a known
 * collection by its own rule (the Vault Badge's computed ids), an enumerable one by index. Any other collection needs an
 * explorer and is not listed.
 *
 * Every token found is confirmed with ownerOf, so a contract that names a token the owner does not hold has it dropped.
 * The work is bounded: at most [MAX_TOKENS] tokens, for each the call that finds it plus ownerOf and tokenURI, and a
 * deadline of 60 seconds from the start of the listing (reading the collection included, when this reads it). No call
 * starts after the deadline and each call's timeout is cut to the time left ([LitVmCalls.until]).
 *
 * Failures: the collection-level reads (the collection itself, worlds(), balanceOf) throw IOException, for the caller to
 * handle per collection. For one token, the contract's own answers (a revert, another owner, an answer over the call's
 * cap, an unreadable or hosted URI) are not failures: the token is dropped or listed without metadata. A failure to get
 * an answer at all (the network, a rate limit, a node fault, a response over the client's cap) counts in failed: on
 * ownerOf the token is dropped, on tokenURI it stays listed without metadata.
 *
 * Where the rest is counted: tokens beyond [MAX_TOKENS] and those after a revert of tokenOfOwnerByIndex (the contract's
 * answer) count in more; those not reached because a call got no answer or the deadline passed count in failed, since a
 * refresh may reach them.
 */
object NftLister {
    const val MAX_TOKENS = 200
    const val DEADLINE_MS = 60_000L

    sealed interface Result

    /** The tokens found; how many more may be held that were not looked up; how many could not be read for want of an answer. */
    data class Listed(val tokens: List<Nft>, val more: Int, val failed: Int = 0) : Result

    /** An ERC-721 collection that neither enumerates its tokens nor is known: listing it needs an explorer. */
    data object NeedsExplorer : Result

    /** The contract is not an ERC-721 collection. */
    data object NotACollection : Result

    /** Whether a collection can be listed without an explorer: it is known on this network, or enumerable. */
    fun canList(network: LitVmNetwork, collection: Erc721Collection): Boolean =
        KnownCollections.isKnown(network, collection.contract) || collection.enumerable

    /** Reads the collection at [contract], then lists it. */
    suspend fun list(calls: LitVmCalls, contract: EvmAddress, owner: EvmAddress): Result {
        val start = calls.clock()
        val bounded = calls.until(start + DEADLINE_MS)
        val collection = Erc721.read(bounded, contract) ?: return NotACollection
        return listCollection(bounded, collection, owner, start)
    }

    /** Lists a collection already read, which is not read again. */
    suspend fun list(calls: LitVmCalls, collection: Erc721Collection, owner: EvmAddress): Result {
        val start = calls.clock()
        return listCollection(calls.until(start + DEADLINE_MS), collection, owner, start)
    }

    private suspend fun listCollection(calls: LitVmCalls, collection: Erc721Collection, owner: EvmAddress, start: Long): Result {
        val known = KnownCollections.known(calls.network, collection.contract)
        if(known != null) {
            return listCandidates(calls, collection, owner, known.tokenIds(calls, collection.contract, owner), start)
        }
        if(collection.enumerable) {
            return listEnumerable(calls, collection, owner, start)
        }
        return NeedsExplorer
    }

    /** What looking up one token gave: kept (with or without its metadata), dropped by the contract's answer, or out of time. */
    private enum class Outcome { KEPT, DROPPED, OUT_OF_TIME }

    private class Tally {
        val tokens = ArrayList<Nft>()
        val seen = HashSet<UInt256>()
        var failed = 0
    }

    private suspend fun listCandidates(calls: LitVmCalls, collection: Erc721Collection, owner: EvmAddress, candidates: KnownCollections.Candidates,
                                       start: Long): Result {
        val tally = Tally()
        val ids = candidates.tokenIds
        var more = candidates.notLookedUp
        for(i in ids.indices) {
            if(tally.tokens.size == MAX_TOKENS) {
                more = saturatingAdd(more, (ids.size - i).toLong())
                break
            }
            if(pastDeadline(calls, start) || (tally.seen.add(ids[i]) && confirm(calls, collection, owner, ids[i], tally) == Outcome.OUT_OF_TIME)) {
                //This candidate and the rest were not reached in time
                tally.failed += ids.size - i
                break
            }
        }
        return Listed(tally.tokens, toInt(more), tally.failed)
    }

    private suspend fun listEnumerable(calls: LitVmCalls, collection: Erc721Collection, owner: EvmAddress, start: Long): Result {
        val balance = Erc721.balanceOf(calls, collection.contract, owner)
        //A contract can answer up to 2^256 - 1: compare before narrowing
        val toVisit = if(balance > UInt256.of(MAX_TOKENS.toLong())) MAX_TOKENS else balance.saturatedLong().toInt()
        val tally = Tally()
        var visited = 0
        var unreached = false
        while(visited < toVisit) {
            if(pastDeadline(calls, start)) {
                unreached = true
                break
            }
            val tokenId = try {
                Erc721.tokenOfOwnerByIndex(calls, collection.contract, owner, UInt256.of(visited.toLong()))
            } catch(e: CallReverted) {
                //A revert is the contract's answer and ends the listing
                break
            } catch(e: IOException) {
                //Any other failure, the deadline included, leaves the rest unreached
                unreached = true
                break
            }
            if(tally.seen.add(tokenId) && confirm(calls, collection, owner, tokenId, tally) == Outcome.OUT_OF_TIME) {
                unreached = true
                break
            }
            visited++
        }
        //Within the cap, what was not visited is unread when the listing could not reach it and not listed when the
        //contract ended it; beyond the cap it is not listed
        val beyondCap = balance.saturatedLong() - toVisit
        val rest = toVisit - visited
        if(unreached) {
            tally.failed += rest
            return Listed(tally.tokens, toInt(beyondCap), tally.failed)
        }
        return Listed(tally.tokens, toInt(saturatingAdd(beyondCap, rest.toLong())), tally.failed)
    }

    /**
     * Confirms a token with ownerOf and reads its metadata, adding it to the tally when the owner holds it. A call that
     * gets no answer counts as failed; one not sent for lack of time leaves the token not looked up, unless ownerOf had
     * already confirmed it, when it is kept without metadata and counted as failed.
     */
    private suspend fun confirm(calls: LitVmCalls, collection: Erc721Collection, owner: EvmAddress, tokenId: UInt256, tally: Tally): Outcome {
        try {
            if(Erc721.ownerOf(calls, collection.contract, tokenId) != owner) {
                return Outcome.DROPPED
            }
        } catch(e: TimedOut) {
            return Outcome.OUT_OF_TIME
        } catch(e: IOException) {
            tally.failed++
            return Outcome.DROPPED
        }
        val metadata = try {
            Erc721.tokenUri(calls, collection.contract, tokenId)?.let { NftMetadata.parse(it) }
        } catch(e: IOException) {
            tally.failed++
            null
        }
        tally.tokens += Nft(collection, tokenId, metadata)
        return Outcome.KEPT
    }

    private fun pastDeadline(calls: LitVmCalls, start: Long): Boolean = calls.clock() - start >= DEADLINE_MS

    private fun saturatingAdd(a: Long, b: Long): Long = if(a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    private fun toInt(count: Long): Int = if(count <= 0) 0 else minOf(count, Int.MAX_VALUE.toLong()).toInt()
}
