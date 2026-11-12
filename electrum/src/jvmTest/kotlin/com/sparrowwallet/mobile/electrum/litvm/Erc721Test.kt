package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.Abi
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.BADGE
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.HOLDER
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.OTHER
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.codePoints
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.hex
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.resource
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.string
import com.sparrowwallet.mobile.electrum.litvm.FakeNode.Companion.word
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** drongo's Erc721Test, case for case, against [FakeNode]. */
class Erc721Test {
    private val node = FakeNode()

    @Test fun readsAVaultBadgeLikeContract(): Unit = runBlocking {
        node.contract(BADGE, vaultBadge())
        val collection = assertNotNull(Erc721.read(node, BADGE))
        assertEquals(BADGE, collection.contract)
        assertEquals("Super Litecoin Vault Badge", collection.name)
        assertEquals("SLVB", collection.symbol)
        assertFalse(collection.enumerable)
        assertTrue(collection.soulbound)

        assertEquals(HOLDER, Erc721.ownerOf(node, BADGE, WORLD_1))
        assertEquals(UInt256.ONE, Erc721.balanceOf(node, BADGE, HOLDER))
        //The tokenURI answer is the one LiteForge gave for World 1, read once and recorded
        assertEquals(resource("vault-badge-world-1-token-uri.txt"), Erc721.tokenUri(node, BADGE, WORLD_1))
    }

    @Test fun anErc20IsNotACollection(): Unit = runBlocking {
        //An ERC-20 has no supportsInterface: the call falls through and reverts
        node.contract(OTHER) { data ->
            when(data) {
                NAME -> Reply.result(string("Test Token"))
                SYMBOL -> Reply.result(string("TT"))
                "313ce567" -> Reply.result(word(18))
                else -> Reply.revert()
            }
        }
        assertNull(Erc721.read(node, OTHER))

        //One that does answer ERC-165, but not for ERC-721
        node.contract(OTHER) { data ->
            when {
                data == IF_ERC165 -> Reply.result(word(1))
                data == NAME -> Reply.result(string("Test Token"))
                data.startsWith("01ffc9a7") -> Reply.result(word(0))
                else -> Reply.revert()
            }
        }
        assertNull(Erc721.read(node, OTHER))
    }

    @Test fun aContractThatAnswersTrueToEverythingIsRefused(): Unit = runBlocking {
        node.contract(OTHER) { Reply.result(word(1)) }
        assertNull(Erc721.read(node, OTHER))

        //ERC-165 says a failed call on 0xffffffff also means the contract does not implement it
        node.contract(OTHER) { data -> if(data == IF_INVALID) Reply.revert() else Reply.result(word(1)) }
        assertNull(Erc721.read(node, OTHER))

        //A word that is not a bool is a malformed answer, not a yes
        node.contract(OTHER, withAnswer(vaultBadge(), IF_ERC721, Reply.result(word(2))))
        assertNull(Erc721.read(node, OTHER))
        node.contract(OTHER, withAnswer(vaultBadge(), IF_ERC721, Reply.result("")))
        assertNull(Erc721.read(node, OTHER))

        //ERC-165 itself must be claimed
        node.contract(OTHER, withAnswer(vaultBadge(), IF_ERC165, Reply.result(word(0))))
        assertNull(Erc721.read(node, OTHER))
    }

    @Test fun aRevertOnSupportsInterfaceIsNotACollection(): Unit = runBlocking {
        for(query in listOf(IF_ERC165, IF_ERC721)) {
            node.contract(OTHER, withAnswer(vaultBadge(), query, Reply.revert()))
            assertNull(Erc721.read(node, OTHER))
            node.contract(OTHER, withAnswer(vaultBadge(), query, Reply.nitroRevert()))
            assertNull(Erc721.read(node, OTHER))
        }
        //A revert on an optional interface only means the contract lacks it
        node.contract(OTHER, withAnswer(withAnswer(vaultBadge(), IF_EIP5192, Reply.revert()), IF_ENUMERABLE, Reply.revert()))
        val collection = assertNotNull(Erc721.read(node, OTHER))
        assertFalse(collection.enumerable)
        assertFalse(collection.soulbound)
    }

    @Test fun anAddressWithoutCodeIsNotACollection(): Unit = runBlocking {
        assertNull(Erc721.read(node, HOLDER))
        assertTrue(node.calls("eth_call").isEmpty(), "an account without code is not called")
    }

    @Test fun tokenUriOverTheCapIsEmpty(): Unit = runBlocking {
        //One byte more than fits: an ABI string of 99,937 bytes needs a 100,064-byte result
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result(string("a".repeat(99_937)))))
        assertNull(Erc721.tokenUri(node, BADGE, WORLD_1))

        //Exactly the cap: a 99,936-byte string is a 100,000-byte result
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result(string("a".repeat(99_936)))))
        assertEquals(99_936, Erc721.tokenUri(node, BADGE, WORLD_1)!!.length)

        //A whole response over the client's cap is the transport's failure, not the contract's answer: it throws
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result(string("a".repeat(600_000)))))
        assertThrowsIo { Erc721.tokenUri(node, BADGE, WORLD_1) }

        //A length word that claims more than the data holds is refused before anything is allocated for it
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1,
            Reply.result(word(32) + "000000000000000000000000000000000000000000000000000000007fffffff" + "61".repeat(32))))
        assertNull(Erc721.tokenUri(node, BADGE, WORLD_1))
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.result("ff".repeat(32) + word(4) + "61".repeat(32))))
        assertNull(Erc721.tokenUri(node, BADGE, WORLD_1))
    }

    @Test fun tokenUriRevertIsEmpty(): Unit = runBlocking {
        node.contract(BADGE, vaultBadge())
        //The badge reverts NoSuchToken() for a token nobody holds
        assertNull(Erc721.tokenUri(node, BADGE, WORLD_1_PLUS_ONE))
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.nitroRevert()))
        assertNull(Erc721.tokenUri(node, BADGE, WORLD_1))
    }

    @Test fun ownerOfRevertIsEmpty(): Unit = runBlocking {
        node.contract(BADGE, vaultBadge())
        assertNull(Erc721.ownerOf(node, BADGE, WORLD_1_PLUS_ONE))
        node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.nitroRevert()))
        assertNull(Erc721.ownerOf(node, BADGE, WORLD_1))
        //The zero address owns nothing, and a word with bits above the address is not an address
        node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.result(word(0))))
        assertNull(Erc721.ownerOf(node, BADGE, WORLD_1))
        node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.result("01" + "00".repeat(11) + hex(HOLDER))))
        assertNull(Erc721.ownerOf(node, BADGE, WORLD_1))
    }

    @Test fun namesAreSanitised(): Unit = runBlocking {
        val hostileName = codePoints(0x07) + "Vault" + codePoints(0x202E, 0x200B) + " Badge\n" + "x".repeat(500)
        val hostileSymbol = "SL" + codePoints(0x2066) + "VB" + "y".repeat(500)
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), NAME, Reply.result(string(hostileName))), SYMBOL, Reply.result(string(hostileSymbol))))
        val collection = assertNotNull(Erc721.read(node, BADGE))
        assertEquals(64, collection.name.length)
        //The line feed separates words, as in every single-line field
        assertTrue(collection.name.startsWith("Vault Badge xxx"), collection.name)
        assertEquals(16, collection.symbol.length)
        assertTrue(collection.symbol.startsWith("SLVByyy"), collection.symbol)

        //Name and symbol are optional in ERC-721: a revert or nonsense leaves them blank
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), NAME, Reply.revert()), SYMBOL, Reply.result(word(32) + word(1_000_000))))
        val unnamed = assertNotNull(Erc721.read(node, BADGE))
        assertEquals("", unnamed.name)
        assertEquals("", unnamed.symbol)

        //Line and paragraph separators go too, and the cap counts code points and never leaves half of a surrogate pair
        val emoji = codePoints(0x1F600)
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), NAME, Reply.result(string("x".repeat(64) + emoji))),
            SYMBOL, Reply.result(string("SL" + codePoints(0x2028) + "V" + codePoints(0x2029) + "B" + codePoints(0x2065, 0xFE0F)))))
        val capped = assertNotNull(Erc721.read(node, BADGE))
        assertEquals("x".repeat(64), capped.name)
        assertEquals("SLVB", capped.symbol)
        assertEquals("x".repeat(63) + emoji, Erc721Collection(BADGE, "x".repeat(63) + emoji, "", false, false).name)
    }

    @Test fun aMalformedResultIsAnIoFailure(): Unit = runBlocking {
        //Not hex, an odd number of digits, no 0x, not a string
        for(result in listOf("\"0xzz\"", "\"0x123\"", "\"1234\"", "12", "null")) {
            val malformed = Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":${Reply.ID},\"result\":$result}")
            node.contract(BADGE, withAnswer(withAnswer(withAnswer(withAnswer(vaultBadge(), IF_ERC721, malformed), OWNER_OF_WORLD_1, malformed),
                TOKEN_URI_WORLD_1, malformed), BALANCE_OF_HOLDER, malformed))
            assertThrowsIo { Erc721.read(node, BADGE) }
            assertThrowsIo { Erc721.ownerOf(node, BADGE, WORLD_1) }
            assertThrowsIo { Erc721.tokenUri(node, BADGE, WORLD_1) }
            assertThrowsIo { Erc721.balanceOf(node, BADGE, HOLDER) }
        }
    }

    @Test fun tokenOfOwnerByIndexSendsTheOwnerAndTheIndex(): Unit = runBlocking {
        //tokenOfOwnerByIndex(address,uint256): selector, then the owner and the index, each a full word
        val exact = "2f745c59" + "000000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb3" +
            "0000000000000000000000000000000000000000000000000000000000000007"
        node.contract(BADGE) { data -> if(data == exact) Reply.result(word(42)) else Reply.revert() }
        assertEquals(UInt256.of(42), Erc721.tokenOfOwnerByIndex(node, BADGE, HOLDER, UInt256.of(7)))
        assertFailsWith<CallReverted> { Erc721.tokenOfOwnerByIndex(node, BADGE, HOLDER, UInt256.of(8)) }
    }

    @Test fun balanceOfThrowsOnARevert(): Unit = runBlocking {
        node.contract(BADGE, withAnswer(vaultBadge(), BALANCE_OF_HOLDER, Reply.revert()))
        assertFailsWith<CallReverted> { Erc721.balanceOf(node, BADGE, HOLDER) }
        node.contract(BADGE, withAnswer(vaultBadge(), BALANCE_OF_HOLDER, Reply.result("")))
        assertThrowsIo { Erc721.balanceOf(node, BADGE, HOLDER) }
    }

    @Test fun aRevertWithAReasonStringIsARevert(): Unit = runBlocking {
        //Error(string) "No such token", as require(..., "No such token") reverts
        val reason = "08c379a0" + string("No such token")
        val withReason = Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":${Reply.ID},\"error\":{\"code\":3,\"message\":\"execution reverted: No such token\",\"data\":\"0x$reason\"}}")
        node.contract(BADGE, withAnswer(withAnswer(vaultBadge(), OWNER_OF_WORLD_1, withReason), TOKEN_URI_WORLD_1, withReason))
        assertNull(Erc721.ownerOf(node, BADGE, WORLD_1))
        assertNull(Erc721.tokenUri(node, BADGE, WORLD_1))
        val revert = assertFailsWith<CallReverted> { node.call(BADGE, OWNER_OF_WORLD_1.hexToBytes()) }
        assertEquals("execution reverted: No such token", revert.message)
    }

    @Test fun aTransportFailureThrows(): Unit = runBlocking {
        node.contract(BADGE, vaultBadge())

        node.override = Reply.http(502)
        assertThrowsIo { Erc721.read(node, BADGE) }
        assertThrowsIo { Erc721.ownerOf(node, BADGE, WORLD_1) }
        assertThrowsIo { Erc721.tokenUri(node, BADGE, WORLD_1) }

        //A body that is not JSON-RPC at all is the endpoint's fault, not the contract's
        node.override = Reply(200, "<html>bad gateway</html>")
        assertThrowsIo { Erc721.read(node, BADGE) }
        assertThrowsIo { Erc721.tokenUri(node, BADGE, WORLD_1) }

        //A node error that is not a revert (a rate limit) must not read as "not a collection"
        node.override = null
        node.contract(BADGE, withAnswer(vaultBadge(), IF_ERC721, Reply.nodeError()))
        assertThrowsIo { Erc721.read(node, BADGE) }
        node.contract(BADGE, withAnswer(vaultBadge(), OWNER_OF_WORLD_1, Reply.nodeError()))
        assertThrowsIo { Erc721.ownerOf(node, BADGE, WORLD_1) }
        node.contract(BADGE, withAnswer(vaultBadge(), TOKEN_URI_WORLD_1, Reply.nodeError()))
        assertThrowsIo { Erc721.tokenUri(node, BADGE, WORLD_1) }

        //Nobody listening
        node.unreachable = true
        assertThrowsIo { Erc721.read(node, BADGE) }
        assertThrowsIo { Erc721.tokenUri(node, BADGE, WORLD_1) }
    }

    @Test fun aRevertIsToldFromANodeErrorWithoutChangingTheMessage(): Unit = runBlocking {
        node.contract(BADGE, withAnswer(vaultBadge(), NAME, Reply.revert()))
        assertEquals("execution reverted", assertFailsWith<CallReverted> { node.call(BADGE, NAME.hexToBytes()) }.message)

        node.contract(BADGE, withAnswer(vaultBadge(), NAME, Reply.nitroRevert()))
        assertFailsWith<CallReverted> { node.call(BADGE, NAME.hexToBytes()) }

        node.contract(BADGE, withAnswer(vaultBadge(), NAME, Reply.nodeError()))
        val limited = assertFailsWith<LitVmException> { node.call(BADGE, NAME.hexToBytes()) }
        assertFalse(limited is CallReverted)
        assertEquals("request limit exceeded", limited.message)
        assertFalse(LitVmException("x") is CallReverted)
    }

    @Test fun theCallKeepsItsCapAndTheBoundedCallNeverExceedsOneHundredThousand(): Unit = runBlocking {
        //The desktop refuses a cap over 100,000 with IllegalArgumentException; the phone holds it to 100,000
        node.contract(BADGE) { Reply.result("ab".repeat(8_193)) }
        assertFailsWith<ResultTooLarge> { node.call(BADGE, NAME.hexToBytes()) }
        node.contract(BADGE) { Reply.result("ab".repeat(8_192)) }
        assertEquals(8_192, node.call(BADGE, NAME.hexToBytes()).size)

        node.contract(BADGE) { Reply.result("ab".repeat(100_000)) }
        assertEquals(100_000, node.call(BADGE, NAME.hexToBytes(), 100_000).size)
        assertFailsWith<ResultTooLarge> { node.call(BADGE, NAME.hexToBytes(), 99_999) }
        node.contract(BADGE) { Reply.result("ab".repeat(100_001)) }
        assertFailsWith<ResultTooLarge> { node.call(BADGE, NAME.hexToBytes(), 100_000) }
        assertFailsWith<ResultTooLarge> { node.call(BADGE, NAME.hexToBytes(), 100_001) }
    }

    @Test fun decodeStringHonoursTheCallersLimit() {
        val eleven = string("hello world").hexToBytes()
        assertEquals("hello world", Abi.string(eleven, 11))
        assertNull(Abi.string(eleven, 10))
        //The default limit for existing callers is unchanged
        val long4097 = string("z".repeat(4_097)).hexToBytes()
        assertNull(Abi.string(long4097))
        assertEquals(4_097, Abi.string(long4097, Erc721.MAX_TOKEN_URI_BYTES)!!.length)
        //Offsets and lengths are checked against the data actually present
        assertNull(Abi.string((word(32) + word(33) + "61".repeat(32)).hexToBytes(), 100_000))
        assertNull(Abi.string((word(64) + word(0)).hexToBytes(), 100_000))
        assertNull(Abi.string((word(32) + "ff".repeat(32)).hexToBytes(), 100_000))
    }

    private suspend fun assertThrowsIo(call: suspend () -> Any?) {
        val e = try {
            call()
            null
        } catch(e: IOException) {
            e
        }
        assertNotNull(e, "expected an IOException")
        assertFalse(e is CallReverted, "a failure is not a revert")
    }

    companion object {
        //tokenIdOf(HOLDER, 1) = (uint160(HOLDER) << 8) | 1
        val WORLD_1 = UInt256.fromBytes("840e41d4484041a43f9307a90389edeb3d111fb301".hexToBytes())!!
        val WORLD_1_PLUS_ONE = UInt256.fromBytes("840e41d4484041a43f9307a90389edeb3d111fb302".hexToBytes())!!

        //Calldata written out by hand, so an encoding mistake in Abi cannot agree with itself
        private const val PAD = "00000000000000000000000000000000000000000000000000000000"
        const val IF_ERC165 = "01ffc9a7" + "01ffc9a7" + PAD
        const val IF_INVALID = "01ffc9a7" + "ffffffff" + PAD
        const val IF_ERC721 = "01ffc9a7" + "80ac58cd" + PAD
        const val IF_METADATA = "01ffc9a7" + "5b5e139f" + PAD
        const val IF_ENUMERABLE = "01ffc9a7" + "780e9d63" + PAD
        const val IF_EIP5192 = "01ffc9a7" + "b45a3c0e" + PAD
        const val NAME = "06fdde03"
        const val SYMBOL = "95d89b41"
        const val OWNER_OF_WORLD_1 = "6352211e" + "0000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb301"
        const val TOKEN_URI_WORLD_1 = "c87b56dd" + "0000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb301"
        const val BALANCE_OF_HOLDER = "70a08231" + "000000000000000000000000840e41d4484041a43f9307a90389edeb3d111fb3"

        //A contract that answers like the deployed VaultBadge, for World 1 held by HOLDER
        fun vaultBadge(): (String) -> Reply = { data ->
            when(data) {
                IF_ERC165, IF_ERC721, IF_METADATA, IF_EIP5192 -> Reply.result(word(1))
                NAME -> Reply.result(string("Super Litecoin Vault Badge"))
                SYMBOL -> Reply.result(string("SLVB"))
                OWNER_OF_WORLD_1 -> Reply.result("00".repeat(12) + hex(HOLDER))
                BALANCE_OF_HOLDER -> Reply.result(word(1))
                TOKEN_URI_WORLD_1 -> Reply.result(resource("vault-badge-world-1-eth-call-result.txt").substring(2))
                else -> if(data.startsWith("01ffc9a7")) Reply.result(word(0)) else Reply.revert()
            }
        }

        fun withAnswer(contract: (String) -> Reply, data: String, reply: Reply): (String) -> Reply = { call -> if(call == data) reply else contract(call) }
    }
}
