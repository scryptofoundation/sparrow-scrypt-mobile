package com.sparrowwallet.mobile.electrum.litvm

import com.sparrowwallet.mobile.crypto.evm.Abi
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.NftMetadata
import com.sparrowwallet.mobile.crypto.evm.NftPicturePlan
import com.sparrowwallet.mobile.crypto.evm.SvgSubset
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.electrum.RateLimitedException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Opt-in (LIVE_LITVM=1), read only: the phone's LitVM reader against the public LiteForge testnet RPC, for the public
 * throwaway test account that holds the World 1 and World 2 Vault Badges. The same steps the LitVM screen takes: the
 * chain check, the balance, the collection, the listing, then each picture parsed and laid out. Also asks ownerOf for a
 * world the account does not hold. Only eth_chainId, eth_getBalance, eth_getCode and eth_call are sent; nothing is
 * signed or broadcast. A 429, an HTTP error answer or an unreachable server skips with a message.
 */
class LiveLitVmTest {
    private val enabled = System.getenv("LIVE_LITVM") == "1"

    private class Skip(message: String) : Exception(message)

    /** Counts every request made through these calls and every view of them made by [until]. */
    private class Counting(private val inner: LitVmCalls, val count: AtomicInteger) : LitVmCalls {
        override val network: LitVmNetwork get() = inner.network
        override val clock: () -> Long get() = inner.clock

        override suspend fun call(to: EvmAddress, data: ByteArray, maxResultBytes: Int): ByteArray {
            count.incrementAndGet()
            return inner.call(to, data, maxResultBytes)
        }

        override suspend fun balance(address: EvmAddress): UInt256 {
            count.incrementAndGet()
            return inner.balance(address)
        }

        override suspend fun hasCode(address: EvmAddress): Boolean {
            count.incrementAndGet()
            return inner.hasCode(address)
        }

        override fun until(deadline: Long): LitVmCalls = Counting(inner.until(deadline), count)
    }

    @Test fun theTestAccountHoldsBothVaultBadges() {
        if(!enabled) return
        val rpc = LitVmRpc(LitVmNetwork.LITEFORGE_TESTNET, null)
        val count = AtomicInteger()
        val calls = Counting(rpc, count)
        try {
            runBlocking {
                val started = System.nanoTime()
                //The chain id: one request, made once per client
                rpc.verifyChain()
                count.incrementAndGet()

                val balance = calls.balance(OWNER)
                println("LIVELITVM balance wei=$balance")
                assertTrue(balance > UInt256.ZERO, "the account's balance is positive")

                val collection = assertNotNull(Erc721.read(calls, KnownCollections.VAULT_BADGE), "the badge contract reads as a collection")
                println("LIVELITVM collection name=\"${collection.name}\" symbol=\"${collection.symbol}\" enumerable=${collection.enumerable} " +
                    "soulbound=${collection.soulbound}")
                assertFalse(collection.enumerable, "the badge is not enumerable")
                assertTrue(collection.soulbound, "the badge is soulbound")
                assertEquals("Super Litecoin Vault Badge", collection.name)

                val listed = assertIs<NftLister.Listed>(NftLister.list(calls, collection, OWNER))
                if(listed.rateLimited) throw Skip("rate limited during the listing")
                val elapsed = (System.nanoTime() - started) / 1_000_000
                println("LIVELITVM listed tokens=${listed.tokens.size} more=${listed.more} failed=${listed.failed} rateLimited=${listed.rateLimited}")
                println("LIVELITVM whole read: requests=${count.get()} millis=$elapsed")
                assertEquals(0, listed.failed, "failed")
                assertEquals(0, listed.more, "more")

                val byId = listed.tokens.associateBy { it.tokenId }
                for(world in 1..2) {
                    val nft = assertNotNull(byId[KnownCollections.vaultBadgeTokenId(OWNER, world)], "World $world is listed")
                    val metadata = assertNotNull(nft.metadata, "World $world has metadata")
                    assertEquals("Super Litecoin Vault Badge: World $world", metadata.name)
                    println("LIVELITVM world $world id=${nft.tokenId} name=\"${metadata.name}\" attributes=${metadata.attributes}")
                    val svg = assertIs<NftMetadata.Image.Svg>(metadata.image, "World $world's picture is an SVG").source
                    val drawing = assertNotNull(SvgSubset.parse(svg), "World $world's SVG parses")
                    val plan = assertNotNull(NftPicturePlan.prepare(drawing, NftPicturePlan.DETAIL_SIZE), "World $world's picture prepares at 240 px")
                    val shapes = drawing.shapes.groupingBy { it::class.simpleName }.eachCount()
                    val texts = plan.items.filterIsInstance<NftPicturePlan.TextItem>().map { it.content }
                    println("LIVELITVM world $world svg chars=${svg.length} viewBox=${drawing.width}x${drawing.height} shapes=$shapes " +
                        "planItems=${plan.items.size} cost=${plan.cost} clip=${plan.clip} texts=$texts")
                    assertTrue("WORLD $world" in texts, "World $world's picture says WORLD $world")
                }

                //A world the account does not hold: the contract reverts, which reads as no owner
                val unheld = KnownCollections.vaultBadgeTokenId(OWNER, 4)
                val reverted = try {
                    calls.call(KnownCollections.VAULT_BADGE, Abi.call("ownerOf(uint256)", unheld))
                    false
                } catch(e: CallReverted) {
                    true
                }
                assertTrue(reverted, "ownerOf for World 4 reverts")
                assertNull(Erc721.ownerOf(calls, KnownCollections.VAULT_BADGE, unheld), "World 4 has no owner")
                println("LIVELITVM world 4 ownerOf reverted; with it: requests=${count.get()} millis=${(System.nanoTime() - started) / 1_000_000}")
            }
        } catch(e: Skip) {
            println("LIVELITVM SKIPPED: ${e.message}")
        } catch(e: RateLimitedException) {
            println("LIVELITVM SKIPPED (rate limited): ${e.message}")
        } catch(e: LitVmException) {
            if(e.message?.startsWith("The LitVM server answered HTTP") != true) throw e
            println("LIVELITVM SKIPPED (HTTP error): ${e.message}")
        } catch(e: IOException) {
            println("LIVELITVM SKIPPED (server unreachable): $e")
        } finally {
            rpc.close()
        }
    }

    private companion object {
        //The public throwaway test account (the Litecoin testnet end-to-end seed at m/44'/60'/0'/0/0)
        val OWNER: EvmAddress = EvmAddress.parse("0x840e41D4484041A43f9307a90389edeB3d111fb3")!!
    }
}
