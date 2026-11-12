package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.Abi
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.BADGE
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.HOLDER
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.OTHER
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.hex
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.resource
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.string
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.word
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** drongo's NftListerTest, case for case, against [FakeNode]; and the Vault Badge's rule held to the desktop's vectors. */
class NftListerTest {
    private val node = FakeNode()

    private suspend fun listed(contract: EvmAddress, owner: EvmAddress = HOLDER) = assertIs<NftLister.Listed>(NftLister.list(node, contract, owner))

    @Test fun listsTheVaultBadgesAnAccountHolds(): Unit = runBlocking {
        val badge = vaultBadge(4)
        badge.owners[world(HOLDER, 1)] = HOLDER
        badge.owners[world(HOLDER, 3)] = HOLDER
        badge.owners[world(OTHER, 2)] = OTHER
        badge.uris[world(HOLDER, 1)] = Reply.result(resource("vault-badge-world-1-eth-call-result.txt").substring(2))
        node.contract(BADGE, badge::answer)

        val listed = listed(BADGE)
        assertEquals(0, listed.more)
        assertEquals(listOf(world(HOLDER, 1), world(HOLDER, 3)), listed.tokens.map { it.tokenId })
        val first = listed.tokens[0]
        assertEquals("Super Litecoin Vault Badge: World 1", first.metadata!!.name)
        assertEquals("Token ${world(HOLDER, 3)}", listed.tokens[1].metadata!!.name)
        assertEquals(BADGE, first.collection.contract)
        assertTrue(first.collection.soulbound)
        //Read the collection (5 interface queries, name, symbol), worlds(), then ownerOf for 4 worlds and tokenURI for the 2 held
        assertEquals(7 + 1 + 4 + 2, node.calls("eth_call").size)
    }

    @Test fun anAccountWithNoneGetsAnEmptyList(): Unit = runBlocking {
        val badge = vaultBadge(4)
        badge.owners[world(OTHER, 1)] = OTHER
        node.contract(BADGE, badge::answer)
        assertEquals(NftLister.Listed(listOf(), 0), NftLister.list(node, BADGE, HOLDER))

        node.contract(COLLECTION, enumerable(listOf())::answer)
        assertEquals(NftLister.Listed(listOf(), 0), NftLister.list(node, COLLECTION, HOLDER))
    }

    @Test fun enumerableCollectionsListByIndex(): Unit = runBlocking {
        node.contract(COLLECTION, enumerable(ids(1000, 1001, 1002))::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(1000, 1001, 1002), listed.tokens.map { it.tokenId })
        assertEquals("Token 1001", listed.tokens[1].metadata!!.name)
        assertEquals("Drunken Cats", listed.tokens[0].collection.name)
        assertTrue(listed.tokens[0].collection.enumerable)
        assertEquals(0, listed.more)
    }

    @Test fun aTokenTheContractDoesNotSayTheOwnerHoldsIsDropped(): Unit = runBlocking {
        //Index 1 names a token someone else holds, index 2 one that does not exist, index 3 repeats index 0
        val lying = enumerable(ids(1000, 1001, 1002, 1000, 1004))
        lying.owners[UInt256.of(1001)] = OTHER
        lying.owners.remove(UInt256.of(1002))
        node.contract(COLLECTION, lying::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(1000, 1004), listed.tokens.map { it.tokenId })
        assertEquals(0, listed.more)
        //A revert or another owner is the contract's answer, not a failure: nothing is counted as unread
        assertEquals(0, listed.failed)

        //Owner checks also hold for a known collection whose ownerOf names another account
        val badge = vaultBadge(2)
        badge.owners[world(HOLDER, 1)] = OTHER
        node.contract(BADGE, badge::answer)
        assertEquals(NftLister.Listed(listOf(), 0), NftLister.list(node, BADGE, HOLDER))
    }

    @Test fun aCollectionThatIsNeitherNeedsAnExplorer(): Unit = runBlocking {
        val plain = enumerable(ids(1))
        plain.enumerable = false
        node.contract(COLLECTION, plain::answer)
        assertEquals(NftLister.NeedsExplorer, NftLister.list(node, COLLECTION, HOLDER))

        //The badge's computed ids belong to its address on LiteForge: the same code anywhere else is just another collection
        node.contract(COLLECTION, vaultBadge(4)::answer)
        assertEquals(NftLister.NeedsExplorer, NftLister.list(node, COLLECTION, HOLDER))
    }

    @Test fun anErc20IsNotACollection(): Unit = runBlocking {
        node.contract(COLLECTION) { data ->
            when(data.substring(0, 8)) {
                "06fdde03" -> Reply.result(string("Test Token"))
                "313ce567" -> Reply.result(word(18))
                else -> Reply.revert()
            }
        }
        assertEquals(NftLister.NotACollection, NftLister.list(node, COLLECTION, HOLDER))
        assertEquals(NftLister.NotACollection, NftLister.list(node, OTHER, HOLDER))
    }

    @Test fun listingStopsAtTwoHundred(): Unit = runBlocking {
        val many = (0 until 500).map { UInt256.of(5000L + it) }
        node.contract(COLLECTION, enumerable(many)::answer)
        var listed = listed(COLLECTION)
        assertEquals(NftLister.MAX_TOKENS, listed.tokens.size)
        //The cap is the wallet's choice: what lies beyond it counts as not listed
        assertEquals(300, listed.more)
        assertEquals(0, listed.failed)
        //The collection (7), balanceOf, then the index, owner and URI of each of the 200
        assertEquals(7 + 1 + 3 * NftLister.MAX_TOKENS, node.calls("eth_call").size)

        //A balance of 2^256 - 1 is compared before it is narrowed
        val huge = enumerable(many)
        huge.balance = UInt256.MAX
        node.contract(COLLECTION, huge::answer)
        listed = listed(COLLECTION)
        assertEquals(NftLister.MAX_TOKENS, listed.tokens.size)
        assertEquals(Int.MAX_VALUE, listed.more)
    }

    @Test fun worldsIsCapped(): Unit = runBlocking {
        val badge = vaultBadge(0)
        badge.worlds = UInt256.MAX
        badge.owners[world(HOLDER, 64)] = HOLDER
        badge.owners[world(HOLDER, 65)] = HOLDER
        node.contract(BADGE, badge::answer)
        var listed = listed(BADGE)
        assertEquals(listOf(world(HOLDER, 64)), listed.tokens.map { it.tokenId })
        assertEquals(Int.MAX_VALUE, listed.more)
        assertEquals(7 + 1 + 64 + 1, node.calls("eth_call").size)

        //Worlds beyond the cap are not looked up, and count as not listed
        badge.worlds = UInt256.of(70)
        listed = listed(BADGE)
        assertEquals(6, listed.more)
    }

    @Test fun metadataFailureStillListsTheToken(): Unit = runBlocking {
        val collection = enumerable(ids(1, 2, 3, 4))
        collection.uris[UInt256.of(1)] = Reply.revert()
        collection.uris[UInt256.of(2)] = Reply.result(string("https://example.com/2.json"))
        collection.uris[UInt256.of(3)] = Reply.nodeError()
        node.contract(COLLECTION, collection::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(1, 2, 3, 4), listed.tokens.map { it.tokenId })
        assertNull(listed.tokens[0].metadata)
        assertNull(listed.tokens[1].metadata)
        assertNull(listed.tokens[2].metadata)
        assertEquals("Token 4", listed.tokens[3].metadata!!.name)
        //Only the node error is a failure to read; the revert and the hosted URI are the contract's answers
        assertEquals(1, listed.failed)
    }

    @Test fun aFailureOnOneTokenDoesNotEndTheListing(): Unit = runBlocking {
        //A node error (or an EVM fault, which surfaces the same way) on one token's ownerOf drops that token only
        val collection = enumerable(ids(1, 2, 3))
        collection.overrides["6352211e" + word(2)] = Reply.nodeError()
        node.contract(COLLECTION, collection::answer)
        var listed = listed(COLLECTION)
        assertEquals(ids(1, 3), listed.tokens.map { it.tokenId })
        assertEquals(1, listed.failed)

        val badge = vaultBadge(3)
        badge.owners[world(HOLDER, 1)] = HOLDER
        badge.owners[world(HOLDER, 3)] = HOLDER
        badge.overrides["6352211e" + word(world(HOLDER, 1))] = Reply.nodeError()
        node.contract(BADGE, badge::answer)
        listed = listed(BADGE)
        assertEquals(listOf(world(HOLDER, 3)), listed.tokens.map { it.tokenId })
        assertEquals(1, listed.failed)
    }

    @Test fun whenEveryOwnerOfFailsTheTokensAreCountedNotLost(): Unit = runBlocking {
        //A rate limit on every ownerOf must not read as "holds nothing"
        val badge = vaultBadge(3)
        for(w in 1..3) {
            badge.owners[world(HOLDER, w)] = HOLDER
            badge.overrides["6352211e" + word(world(HOLDER, w))] = Reply.nodeError()
        }
        node.contract(BADGE, badge::answer)
        var listed = listed(BADGE)
        assertEquals(listOf(), listed.tokens)
        assertEquals(0, listed.more)
        assertEquals(3, listed.failed)

        val collection = enumerable(ids(1, 2))
        collection.overrides["6352211e" + word(1)] = Reply.nodeError()
        collection.overrides["6352211e" + word(2)] = Reply.nodeError()
        node.contract(COLLECTION, collection::answer)
        listed = listed(COLLECTION)
        assertEquals(listOf(), listed.tokens)
        assertEquals(0, listed.more)
        assertEquals(2, listed.failed)
    }

    @Test fun aRepeatedIdIsListedOnce(): Unit = runBlocking {
        node.contract(COLLECTION, enumerable(ids(7, 7, 8, 7))::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(7, 8), listed.tokens.map { it.tokenId })
        assertEquals(0, listed.more)
        assertEquals(0, listed.failed)
    }

    @Test fun aCollectionAlreadyReadIsNotReadAgain(): Unit = runBlocking {
        node.contract(COLLECTION, enumerable(ids(1, 2))::answer)
        val collection = assertNotNull(Erc721.read(node, COLLECTION))
        val calls = node.calls("eth_call").size
        val codes = node.calls("eth_getCode").size
        val listed = assertIs<NftLister.Listed>(NftLister.list(node, collection, HOLDER))
        assertEquals(ids(1, 2), listed.tokens.map { it.tokenId })
        //balanceOf, then the index, owner and URI of each token: no code, interface, name or symbol queries
        assertEquals(1 + 3 * 2, node.calls("eth_call").size - calls)
        assertEquals(codes, node.calls("eth_getCode").size)
    }

    @Test fun listabilityIsDecidedFromTheCollectionAlone() {
        val network = LitVmNetwork.LITEFORGE_TESTNET
        assertTrue(NftLister.canList(network, Erc721Collection(BADGE, "Super Litecoin Vault Badge", "SLVB", false, true)))
        assertTrue(NftLister.canList(network, Erc721Collection(COLLECTION, "Cats", "CATS", true, false)))
        assertFalse(NftLister.canList(network, Erc721Collection(COLLECTION, "Cats", "CATS", false, false)))
    }

    @Test fun noCallStartsAfterTheDeadline(): Unit = runBlocking {
        node.contract(COLLECTION, enumerable(ids(1))::answer)
        var now = 0L
        node.now = { now }
        val tenSeconds = 10_000L
        val bounded = node.until(tenSeconds)
        assertTrue(bounded.hasCode(COLLECTION))
        now = tenSeconds
        assertFailsWith<TimedOut> { bounded.hasCode(COLLECTION) }
        assertFailsWith<TimedOut> { bounded.call(COLLECTION, Abi.selector("name()"), 100) }
        assertEquals(1, node.calls("eth_getCode").size)
        assertEquals(0, node.calls("eth_call").size)
        //A deadline inside another keeps the earlier one
        assertFailsWith<TimedOut> { node.until(tenSeconds).until(2 * tenSeconds).hasCode(COLLECTION) }
    }

    @Test fun aCollectionLevelFailureIsThrown(): Unit = runBlocking {
        val collection = enumerable(ids(1))
        collection.overrides["70a08231" + "000000000000000000000000" + hex(HOLDER)] = Reply.nodeError()
        node.contract(COLLECTION, collection::answer)
        assertFailsWith<IOException> { NftLister.list(node, COLLECTION, HOLDER) }
        collection.overrides["70a08231" + "000000000000000000000000" + hex(HOLDER)] = Reply.revert()
        assertFailsWith<IOException> { NftLister.list(node, COLLECTION, HOLDER) }

        val badge = vaultBadge(4)
        badge.overrides["23c4f6e3"] = Reply.nodeError()
        node.contract(BADGE, badge::answer)
        assertFailsWith<IOException> { NftLister.list(node, BADGE, HOLDER) }
        badge.overrides["23c4f6e3"] = Reply.result("")
        assertFailsWith<IOException> { NftLister.list(node, BADGE, HOLDER) }
        //worlds() is read with a small cap: an answer far larger than a word is a collection-level failure
        badge.overrides["23c4f6e3"] = Reply.result("00".repeat(129))
        assertFailsWith<IOException> { NftLister.list(node, BADGE, HOLDER) }

        node.override = Reply.http(503)
        assertFailsWith<IOException> { NftLister.list(node, BADGE, HOLDER) }
    }

    @Test fun aRevertFromTokenOfOwnerByIndexEndsTheListing(): Unit = runBlocking {
        val collection = enumerable(ids(1, 2, 3, 4, 5))
        collection.overrides["2f745c59" + "000000000000000000000000" + hex(HOLDER) + word(2)] = Reply.revert()
        node.contract(COLLECTION, collection::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(1, 2), listed.tokens.map { it.tokenId })
        //The contract's own answer: the rest counts as not listed, not as unread
        assertEquals(3, listed.more)
        assertEquals(0, listed.failed)
    }

    @Test fun aFailureFromTokenOfOwnerByIndexCountsTheRestAsFailed(): Unit = runBlocking {
        //No answer at index 2 (a rate limit): the three not reached could not be read, so a refresh may find them
        val collection = enumerable(ids(1, 2, 3, 4, 5))
        collection.overrides["2f745c59" + "000000000000000000000000" + hex(HOLDER) + word(2)] = Reply.nodeError()
        node.contract(COLLECTION, collection::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(1, 2), listed.tokens.map { it.tokenId })
        assertEquals(0, listed.more)
        assertEquals(3, listed.failed)
    }

    @Test fun anOversizedResponseToOwnerOfIsAFailureNotAnAnswer(): Unit = runBlocking {
        //A whole response over the client's cap is the transport's failure: the token is counted, not silently dropped
        val collection = enumerable(ids(1, 2))
        collection.overrides["6352211e" + word(2)] = Reply.result("ab".repeat(600_000))
        node.contract(COLLECTION, collection::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(1), listed.tokens.map { it.tokenId })
        assertEquals(1, listed.failed)
    }

    @Test fun anUnreadableOwnerOfIsAFailureAndATokenUriOverItsCapIsNoUri(): Unit = runBlocking {
        //Ruling M3: an ownerOf answer over its cap (8,192), malformed or empty counts as failed; only a revert or a
        //well-formed answer naming another owner (the zero address included) drops a token silently
        val collection = enumerable(ids(1, 2, 3, 4, 5, 6, 7))
        collection.overrides["6352211e" + word(2)] = Reply.result("ab".repeat(8_193))
        collection.overrides["6352211e" + word(4)] = Reply.result("01" + "00".repeat(11) + hex(HOLDER))
        collection.overrides["6352211e" + word(5)] = Reply.result("")
        collection.overrides["6352211e" + word(6)] = Reply.result(word(0))
        collection.owners[UInt256.of(7)] = OTHER
        //tokenURI over its cap (100,000) is the contract's answer: listed without metadata, not a failure
        collection.uris[UInt256.of(3)] = Reply.result(string("a".repeat(99_937)))
        node.contract(COLLECTION, collection::answer)
        val listed = listed(COLLECTION)
        assertEquals(ids(1, 3), listed.tokens.map { it.tokenId })
        assertNull(listed.tokens[1].metadata)
        assertEquals(3, listed.failed)
        assertEquals(0, listed.more)
        assertFalse(listed.rateLimited)
    }

    @Test fun theListIsReadOnly(): Unit = runBlocking {
        node.contract(COLLECTION, enumerable(ids(1, 2))::answer)
        val listed = listed(COLLECTION)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> { (listed.tokens as MutableList<Nft>).clear() }
        assertEquals(2, listed.tokens.size)
    }

    @Test fun aRateLimitOnTheFirstOwnerOfStopsTheListing(): Unit = runBlocking {
        val badge = vaultBadge(64)
        badge.owners[world(HOLDER, 1)] = HOLDER
        badge.owners[world(HOLDER, 3)] = HOLDER
        badge.rateLimit = { it.startsWith("6352211e") }
        node.contract(BADGE, badge::answer)
        val listed = listed(BADGE)
        //The collection (7), worlds(), the one ownerOf answered 429: nothing after it
        assertEquals(7 + 1 + 1, node.calls("eth_call").size)
        assertEquals(NftLister.Listed(listOf(), 0, 64, rateLimited = true), listed)
    }

    @Test fun aRateLimitOnEveryRequestAfterWorldsStopsTheListing(): Unit = runBlocking {
        val badge = vaultBadge(4)
        badge.owners[world(HOLDER, 2)] = HOLDER
        node.contract(BADGE, badge::answer)
        val collection = assertNotNull(Erc721.read(node, BADGE))
        badge.rateLimit = { !it.startsWith("23c4f6e3") }
        val before = node.calls("eth_call").size
        val listed = assertIs<NftLister.Listed>(NftLister.list(node, collection, HOLDER))
        assertEquals(2, node.calls("eth_call").size - before, "worlds() and the first ownerOf only")
        assertEquals(4, listed.failed)
        assertTrue(listed.rateLimited)
        //A rate limit on worlds() itself is a collection-level failure, thrown
        badge.rateLimit = { it.startsWith("23c4f6e3") }
        assertFailsWith<com.sparrowwallet.mobile.electrum.RateLimitedException> { NftLister.list(node, collection, HOLDER) }
    }

    @Test fun aRateLimitOnATokenUriKeepsThatTokenAndStops(): Unit = runBlocking {
        val badge = vaultBadge(64)
        badge.owners[world(HOLDER, 1)] = HOLDER
        badge.owners[world(HOLDER, 2)] = HOLDER
        badge.rateLimit = { it.startsWith("c87b56dd") }
        node.contract(BADGE, badge::answer)
        val listed = listed(BADGE)
        //The collection (7), worlds(), ownerOf and the tokenURI that answered 429
        assertEquals(7 + 1 + 2, node.calls("eth_call").size)
        assertEquals(listOf(world(HOLDER, 1)), listed.tokens.map { it.tokenId })
        assertNull(listed.tokens[0].metadata)
        //That token's URI and the 63 worlds not reached
        assertEquals(64, listed.failed)
        assertEquals(0, listed.more)
        assertTrue(listed.rateLimited)
    }

    @Test fun aRateLimitInTokenOfOwnerByIndexStopsTheListing(): Unit = runBlocking {
        val collection = enumerable(ids(1, 2, 3, 4, 5))
        collection.rateLimit = { it == "2f745c59" + "000000000000000000000000" + hex(HOLDER) + word(2) }
        node.contract(COLLECTION, collection::answer)
        val listed = listed(COLLECTION)
        //The collection (7), balanceOf, index, owner and URI of two tokens, then the index that answered 429
        assertEquals(7 + 1 + 3 * 2 + 1, node.calls("eth_call").size)
        assertEquals(ids(1, 2), listed.tokens.map { it.tokenId })
        assertEquals(3, listed.failed)
        assertEquals(0, listed.more)
        assertTrue(listed.rateLimited)
        //The same on an owner check of the enumerable path
        val owners = enumerable(ids(1, 2, 3))
        owners.rateLimit = { it == "6352211e" + word(2) }
        node.contract(COLLECTION, owners::answer)
        val stopped = listed(COLLECTION)
        assertEquals(ids(1), stopped.tokens.map { it.tokenId })
        assertEquals(2, stopped.failed)
        assertTrue(stopped.rateLimited)
    }

    @Test fun theListingStopsAtTheDeadline(): Unit = runBlocking {
        val many = (0 until 100).map { UInt256.of(7000L + it) }
        node.contract(COLLECTION, enumerable(many)::answer)
        //Each call the node answers takes a second on this clock
        node.now = { node.calls("eth_call").size * 1_000L }
        val listed = listed(COLLECTION)
        assertTrue(listed.tokens.size < 100)
        //Every token is listed or counted as failed: running out of time is a failure to reach them, which a refresh may
        //cure, not tokens beyond the wallet's cap
        assertEquals(0, listed.more)
        assertTrue(listed.tokens.size + listed.failed >= 100)
        //No call starts once the 60 seconds are spent, the collection's own reads included
        val calls = node.calls("eth_call").size
        assertTrue(calls <= 60, "stopped at the deadline: $calls")
    }

    @Test fun onlyTheBuiltInCollectionIsKnown() {
        //The desktop's lookalikeOf belongs to "Add collection", which the phone does not have
        assertEquals(listOf(BADGE), KnownCollections.on(LitVmNetwork.LITEFORGE_TESTNET))
        assertTrue(KnownCollections.isKnown(LitVmNetwork.LITEFORGE_TESTNET, BADGE))
        assertFalse(KnownCollections.isKnown(LitVmNetwork.LITEFORGE_TESTNET, COLLECTION))
    }

    @Test fun theConstantsAndTheBadgeIdsAreTheDesktops() {
        val root = Json.parseToJsonElement(File("../vectors/evm.json").readText(Charsets.UTF_8)).jsonObject
        val constants = root["constants"]!!.jsonObject
        assertEquals(constants["erc721MaxTokenUriBytes"]!!.jsonPrimitive.int, Erc721.MAX_TOKEN_URI_BYTES)
        assertEquals(constants["collectionMaxNameLength"]!!.jsonPrimitive.int, Erc721Collection.MAX_NAME_LENGTH)
        assertEquals(constants["collectionMaxSymbolLength"]!!.jsonPrimitive.int, Erc721Collection.MAX_SYMBOL_LENGTH)
        assertEquals(constants["vaultBadge"]!!.jsonPrimitive.content, KnownCollections.VAULT_BADGE.toString())
        assertEquals(constants["vaultBadgeMaxWorlds"]!!.jsonPrimitive.int, KnownCollections.MAX_VAULT_BADGE_WORLDS)
        assertEquals(constants["listerMaxTokens"]!!.jsonPrimitive.int, NftLister.MAX_TOKENS)
        val cases = root["tokenIds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(15, cases.size)
        for(case in cases) {
            val owner = EvmAddress.parse(case["owner"]!!.jsonPrimitive.content)!!
            val id = KnownCollections.vaultBadgeTokenId(owner, case["world"]!!.jsonPrimitive.int)
            assertEquals(case["decimal"]!!.jsonPrimitive.content, id.toString())
            assertEquals(case["word"]!!.jsonPrimitive.content, word(id))
        }
    }

    private fun world(owner: EvmAddress, world: Int): UInt256 = UInt256.fromBytes(owner.bytes)!!.shl8Or(world)!!

    private fun ids(vararg values: Long): List<UInt256> = values.map { UInt256.of(it) }

    private fun vaultBadge(worlds: Int) = FakeCollection("Super Litecoin Vault Badge", "SLVB").apply {
        soulbound = true
        this.worlds = UInt256.of(worlds.toLong())
    }

    private fun enumerable(byIndex: List<UInt256>) = FakeCollection("Drunken Cats", "CATS").apply {
        enumerable = true
        this.byIndex.addAll(byIndex)
        balance = UInt256.of(byIndex.size.toLong())
        for(id in byIndex) owners[id] = HOLDER
    }

    /**
     * An ERC-721 contract answering by selector, with calldata read as words written out by hand. Its tokens, owners and
     * URIs are plain maps; a token's default URI is bare JSON naming it.
     */
    private class FakeCollection(val name: String, val symbol: String) {
        var enumerable = false
        var soulbound = false
        var worlds: UInt256? = null
        var balance = UInt256.ZERO
        val byIndex = ArrayList<UInt256>()
        val owners = HashMap<UInt256, EvmAddress>()
        val uris = HashMap<UInt256, Reply>()
        val overrides = HashMap<String, Reply>()
        /** Calldata the server answers with HTTP 429. */
        var rateLimit: (String) -> Boolean = { false }

        fun answer(data: String): Reply {
            if(rateLimit(data)) return Reply.http(429)
            overrides[data]?.let { return it }
            return when(data.substring(0, 8)) {
                "01ffc9a7" -> Reply.result(word(when(data.substring(8, 16)) {
                    "01ffc9a7", "80ac58cd", "5b5e139f" -> 1L
                    "780e9d63" -> if(enumerable) 1L else 0L
                    "b45a3c0e" -> if(soulbound) 1L else 0L
                    else -> 0L
                }))
                "06fdde03" -> Reply.result(string(name))
                "95d89b41" -> Reply.result(string(symbol))
                "23c4f6e3" -> worlds?.let { Reply.result(word(it)) } ?: Reply.revert()
                "70a08231" -> if(data == "70a08231" + "000000000000000000000000" + hex(HOLDER)) Reply.result(word(balance)) else Reply.result(word(0))
                "2f745c59" -> {
                    val index = argument(data, 1)
                    val holder = data.substring(8, 72) == "000000000000000000000000" + hex(HOLDER)
                    if(holder && index < UInt256.of(byIndex.size.toLong())) Reply.result(word(byIndex[index.toString().toInt()])) else Reply.revert()
                }
                "6352211e" -> owners[argument(data, 0)]?.let { Reply.result("000000000000000000000000" + hex(it)) } ?: Reply.revert()
                "c87b56dd" -> {
                    val id = argument(data, 0)
                    uris[id] ?: if(id in owners) Reply.result(string("{\"name\":\"Token $id\"}")) else Reply.revert()
                }
                else -> Reply.revert()
            }
        }

        private fun argument(data: String, index: Int): UInt256 {
            val start = 8 + 64 * index
            return UInt256.fromBytes(data.substring(start, start + 64).hexToBytes())!!
        }
    }

    private companion object {
        val COLLECTION = EvmAddress.parse("0x00000000000000000000000000000000000c0ffe")!!
    }
}
