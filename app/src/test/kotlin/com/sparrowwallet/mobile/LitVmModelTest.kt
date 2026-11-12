package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.NftMetadata
import com.sparrowwallet.mobile.crypto.evm.NftPicturePlan
import com.sparrowwallet.mobile.crypto.evm.SvgSubset
import com.sparrowwallet.mobile.crypto.evm.UInt256
import com.sparrowwallet.mobile.crypto.toHex
import com.sparrowwallet.mobile.electrum.RateLimitedException
import com.sparrowwallet.mobile.electrum.litvm.CallReverted
import com.sparrowwallet.mobile.electrum.litvm.Erc721Collection
import com.sparrowwallet.mobile.electrum.litvm.KnownCollections
import com.sparrowwallet.mobile.electrum.litvm.LitVmCalls
import com.sparrowwallet.mobile.electrum.litvm.LitVmException
import com.sparrowwallet.mobile.electrum.litvm.LitVmNetwork
import com.sparrowwallet.mobile.electrum.litvm.Nft
import com.sparrowwallet.mobile.electrum.litvm.TimedOut
import com.sparrowwallet.mobile.storage.VaultData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LitVmModelTest {
    private val owner = EvmAddress.parse("0x840e41D4484041A43f9307a90389edeB3d111fb3")!!
    private val badge = KnownCollections.VAULT_BADGE
    private val badgeCollection = Erc721Collection(badge, "Super Litecoin Vault Badge", "SLVB", enumerable = false, soulbound = true)

    // ---- who gets the button ----

    private fun info(name: String = "t", network: Network = Network.TESTNET, scriptType: String = "P2WPKH", coin: Coin = Coin.LITECOIN,
                     links: WalletLinks = WalletLinks()) = WalletRepository.WalletInfo(name, network, scriptType, links, coin)

    @Test
    fun theButtonIsForTheLitecoinTestnetPublicLayerWithASeed() {
        val wallet = info()
        assertTrue(LitVmModel.available(wallet, true, listOf(wallet), Layer.PUBLIC))
        assertFalse(LitVmModel.available(wallet, false, listOf(wallet), Layer.PUBLIC), "no seed on the phone")
        assertFalse(LitVmModel.available(null, true, listOf(wallet), Layer.PUBLIC), "not listed")
        val main = info(network = Network.MAINNET)
        assertFalse(LitVmModel.available(main, true, listOf(main), Layer.PUBLIC), "mainnet")
        val doge = info(coin = Coin.DOGECOIN)
        assertFalse(LitVmModel.available(doge, true, listOf(doge), Layer.PUBLIC), "another coin")
        val mweb = info(scriptType = "MWEB")
        assertFalse(LitVmModel.available(mweb, true, listOf(mweb), Layer.PUBLIC), "MWEB")
        assertFalse(LitVmModel.available(wallet, true, listOf(wallet), Layer.TAPROOT), "Taproot layer")
        assertFalse(LitVmModel.available(wallet, true, listOf(wallet), Layer.PRIVATE), "Private layer")
    }

    @Test
    fun aCompanionHasNoButtonOfItsOwn() {
        val parent = info("p", links = WalletLinks(taproot = "p Taproot"))
        val taproot = info("p Taproot", scriptType = "P2TR", links = WalletLinks(parent = "p"))
        val wallets = listOf(parent, taproot)
        assertTrue(LitVmModel.available(parent, true, wallets, Layer.PUBLIC))
        assertFalse(LitVmModel.available(taproot, true, wallets, Layer.PUBLIC))
    }

    // ---- the address ----

    /** The repository's master key is the depth-0 key of the wallet's own seed, so the address is the desktop's. */
    @Test
    fun theAddressIsTheDesktopsSameSeedAccount() {
        val vectors = Json.parseToJsonElement(File("../vectors/evm.json").readText()).jsonObject["addresses"]!!.jsonArray.map { it.jsonObject }
        assertTrue(vectors.any { it["seedType"]!!.jsonPrimitive.content == "ELECTRUM" })
        assertTrue(vectors.any { it["passphrase"]!!.jsonPrimitive.content.isNotEmpty() })
        for(v in vectors) {
            val data = VaultData(v["mnemonic"]!!.jsonPrimitive.content, v["passphrase"]!!.jsonPrimitive.content, v["seedType"]!!.jsonPrimitive.content,
                network = "testnet")
            assertEquals(0, walletMasterKey(data).depth)
            val first = v["accounts"]!!.jsonArray.first { it.jsonObject["index"]!!.jsonPrimitive.int == 0 }.jsonObject
            assertEquals(first["address"]!!.jsonPrimitive.content, litVmAddressOf(data).toString(), v["name"]!!.jsonPrimitive.content)
        }
    }

    // ---- the balance ----

    private fun wei(text: String) = UInt256.parseDecimal(text)!!

    @Test
    fun theBalanceHasUpToSixDecimalsCutNotRounded() {
        assertEquals("0 zkLTC", LitVmModel.balanceText(UInt256.ZERO))
        assertEquals("1.5 zkLTC", LitVmModel.balanceText(wei("1500000000000000000")))
        assertEquals("0.0346 zkLTC", LitVmModel.balanceText(wei("34600000000000000")))
        assertEquals("1.234567 zkLTC", LitVmModel.balanceText(wei("1234567999999999999")))
        assertEquals("12 zkLTC", LitVmModel.balanceText(wei("12000000000000000001")))
        assertEquals("Less than 0.000001 zkLTC", LitVmModel.balanceText(UInt256.ONE))
        assertEquals("0.000001 zkLTC", LitVmModel.balanceText(wei("1000000000000")))
        assertEquals(UInt256.MAX.toString().dropLast(18) + ".584007 zkLTC", LitVmModel.balanceText(UInt256.MAX))
    }

    // ---- what a read shows ----

    private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\"><rect width=\"64\" height=\"64\" fill=\"#345d9d\"/></svg>"

    private fun nft(world: Int, metadata: NftMetadata? = NftMetadata("WORLD $world", "A badge", emptyList(), NftMetadata.Image.Svg(svg)),
                    collection: Erc721Collection = badgeCollection) =
        Nft(collection, KnownCollections.vaultBadgeTokenId(owner, world), metadata)

    private fun listed(vararg tokens: Nft, more: Int = 0, failed: Int = 0, contract: EvmAddress = badge, collection: Erc721Collection? = badgeCollection) =
        LitVmCollection(contract, collection, LitVmCollection.Status.LISTED, tokens.toList(), more, failed)

    private fun read(vararg collections: LitVmCollection, problem: LitVmRead.Problem? = null, detail: String? = null,
                     balance: UInt256? = wei("34600000000000000")): LitVmRead {
        val all = collections.toList()
        val pictures = LitVmModel.shownTokens(all).first.associate { LitVmModel.tileKey(it) to LitVmPictures.prepare(it.metadata, 120.0, 240.0) }
        return LitVmRead(owner, balance, problem, detail, all, stopped = false, pictures = pictures)
    }

    @Test
    fun anAccountWithNothingIsEmpty() {
        val view = LitVmModel.build(owner, read(listed()), airGapped = false)
        assertTrue(view.empty)
        assertTrue(view.tiles.isEmpty())
        assertTrue(view.notes.isEmpty())
        assertNull(view.status)
        assertEquals("0.0346 zkLTC", view.balance)
        assertEquals(owner.toString(), view.address)
        assertTrue(view.canRefresh)
    }

    @Test
    fun oneBadge() {
        val view = LitVmModel.build(owner, read(listed(nft(1))), airGapped = false)
        assertFalse(view.empty)
        val tile = view.tiles.single()
        assertEquals("WORLD 1", tile.label)
        assertEquals("Super Litecoin Vault Badge", tile.collection)
        assertEquals(LitVmModel.BUILT_IN, tile.marker)
        assertEquals("$badge/${KnownCollections.vaultBadgeTokenId(owner, 1)}", tile.key)
        val drawn = assertIs<LitVmPicture.Drawn>(tile.picture)
        assertEquals(120.0, drawn.tile.size)
        assertEquals(240.0, drawn.detail.size)
        assertNull(LitVmModel.placeholder(tile.picture))
    }

    @Test
    fun twoBadgesInTokenOrder() {
        val view = LitVmModel.build(owner, read(listed(nft(2), nft(1))), airGapped = false)
        assertEquals(listOf("WORLD 1", "WORLD 2"), view.tiles.map { it.label })
    }

    @Test
    fun aTokenWithoutANameShowsItsIdCutInTheMiddle() {
        val view = LitVmModel.build(owner, read(listed(nft(1, metadata = null))), airGapped = false)
        val id = KnownCollections.vaultBadgeTokenId(owner, 1).toString()
        assertEquals("#" + id.take(6) + "..." + id.takeLast(6), view.tiles.single().label)
        assertEquals("#$id", view.tiles.single().detail.title)
        assertEquals(LitVmModel.NO_PICTURE, LitVmModel.placeholder(view.tiles.single().picture)!!.text)
        assertEquals("#7", LitVmModel.tileLabel(Nft(badgeCollection, UInt256.of(7), null)))
    }

    @Test
    fun aCollectionThatCouldNotBeReadSaysSoAndIsNotEmpty() {
        val failed = LitVmCollection(badge, null, LitVmCollection.Status.FAILED, emptyList(), 0, 0)
        val view = LitVmModel.build(owner, read(failed), airGapped = false)
        assertFalse(view.empty)
        assertEquals(listOf(LitVmNote(LitVmModel.shortAddress(badge), LitVmModel.COULD_NOT_BE_READ)), view.notes)
        //The last name read, when there is one
        val named = LitVmModel.build(owner, read(failed), airGapped = false, names = mapOf(badge to "Super Litecoin Vault Badge"))
        assertEquals(listOf(LitVmNote("Super Litecoin Vault Badge", LitVmModel.COULD_NOT_BE_READ)), named.notes)
        assertEquals("0x3eEd...51B3", LitVmModel.shortAddress(badge))
    }

    @Test
    fun moreAndFailedTokensAreSaidBesideTheOnesRead() {
        val view = LitVmModel.build(owner, read(listed(nft(1), more = 3, failed = 2)), airGapped = false)
        assertEquals(1, view.tiles.size)
        assertFalse(view.empty)
        assertEquals(listOf(LitVmNote("Super Litecoin Vault Badge", "3 more not shown"),
            LitVmNote("Super Litecoin Vault Badge", LitVmModel.COULD_NOT_BE_READ)), view.notes)
        assertFalse(LitVmModel.build(owner, read(listed(more = 1)), airGapped = false).empty)
        assertFalse(LitVmModel.build(owner, read(listed(failed = 1)), airGapped = false).empty)
    }

    @Test
    fun airGappedShowsTheAddressAndNothingRead() {
        val view = LitVmModel.build(owner, read(listed(nft(1))), airGapped = true)
        assertEquals(LitVmModel.AIR_GAPPED, view.status)
        assertEquals(owner.toString(), view.address)
        assertNull(view.balance)
        assertTrue(view.tiles.isEmpty())
        assertFalse(view.empty)
        assertFalse(view.canRefresh)
        assertEquals(LitVmModel.AIR_GAPPED, LitVmModel.build(owner, null, airGapped = true).status)
    }

    @Test
    fun aHostedPictureNamesItsHostShortenedFromTheFront() {
        val hosted = nft(1, NftMetadata("WORLD 1", "", emptyList(), NftMetadata.Image.Hosted("example.com")))
        val placeholder = LitVmModel.placeholder(LitVmModel.build(owner, read(listed(hosted)), airGapped = false).tiles.single().picture)!!
        assertEquals("Picture not shown: hosted at example.com", placeholder.text + " " + placeholder.host)
        val long = "a".repeat(50) + ".example.com"
        val short = LitVmModel.placeholder(LitVmPicture.Hosted(long))!!.host!!
        assertEquals(LitVmModel.MAX_HOST_CHARS, short.length)
        assertTrue(short.startsWith("..."))
        assertTrue(long.endsWith(short.removePrefix("...")))
    }

    @Test
    fun anSvgOutsideTheSubsetHasNoPicture() {
        val script = nft(1, NftMetadata("WORLD 1", "", emptyList(), NftMetadata.Image.Svg("<svg xmlns=\"http://www.w3.org/2000/svg\"><script/></svg>")))
        val view = LitVmModel.build(owner, read(listed(script)), airGapped = false)
        assertEquals(LitVmPicture.None, view.tiles.single().picture)
        assertEquals(LitVmModel.NO_PICTURE, LitVmModel.placeholder(view.tiles.single().picture)!!.text)
        assertNull(LitVmModel.placeholder(view.tiles.single().picture)!!.host)
    }

    @Test
    fun atMostNinetySixTiles() {
        val many = (1..100).map { Nft(badgeCollection, UInt256.of(it.toLong()), null) }.reversed()
        val view = LitVmModel.build(owner, read(listed(*many.toTypedArray())), airGapped = false)
        assertEquals(LitVmModel.MAX_TILES, view.tiles.size)
        assertEquals("#1", view.tiles.first().label)
        assertEquals("#96", view.tiles.last().label)
        assertEquals(listOf(LitVmNote(null, "4 more not shown")), view.notes)
    }

    @Test
    fun theAppsRowsComeFirstAndASpoofingAttributeStaysInTheCollectionsBox() {
        val spoof = NftMetadata("WORLD 1", "Line one\nLine two", listOf(NftMetadata.Attribute("Contract", "0x000000000000000000000000000000000000dEaD"),
            NftMetadata.Attribute("Soulbound", "no"), NftMetadata.Attribute("World", "1")), NftMetadata.Image.Svg(svg))
        val detail = LitVmModel.build(owner, read(listed(nft(1, spoof))), airGapped = false).tiles.single().detail
        val id = KnownCollections.vaultBadgeTokenId(owner, 1).toString()
        assertEquals(listOf(LitVmAppRow(LitVmAppRow.Kind.TOKEN_ID, "Token ID", id), LitVmAppRow(LitVmAppRow.Kind.CONTRACT, "Contract", badge.toString()),
            LitVmAppRow(LitVmAppRow.Kind.MARKER, null, LitVmModel.BUILT_IN), LitVmAppRow(LitVmAppRow.Kind.SOULBOUND, null, LitVmModel.SOULBOUND)),
            detail.appRows)
        assertEquals("WORLD 1", detail.title)
        assertEquals("Super Litecoin Vault Badge", detail.collection)
        assertEquals("Line one\nLine two", detail.description)
        assertEquals(spoof.attributes, detail.attributes)
        //Not soulbound: no such row, whatever the token says
        val plain = Erc721Collection(badge, "Super Litecoin Vault Badge", "SLVB", enumerable = false, soulbound = false)
        val rows = LitVmModel.build(owner, read(listed(nft(1, spoof, plain), collection = plain)), airGapped = false).tiles.single().detail.appRows
        assertEquals(listOf(LitVmAppRow.Kind.TOKEN_ID, LitVmAppRow.Kind.CONTRACT, LitVmAppRow.Kind.MARKER), rows.map { it.kind })
    }

    @Test
    fun aFailedChainCheckOrBalanceListsNothingAndSaysSo() {
        val chain = LitVmModel.build(owner, LitVmRead(owner, null, LitVmRead.Problem.CHAIN, "This server is for another chain.", emptyList(), false, emptyMap()),
            airGapped = false)
        assertEquals(LitVmModel.CHAIN_FAILED, chain.status)
        assertEquals("This server is for another chain.", chain.statusDetail)
        assertTrue(chain.tiles.isEmpty())
        assertFalse(chain.empty)
        assertNull(chain.balance)
        val balance = LitVmModel.build(owner, LitVmRead(owner, null, LitVmRead.Problem.BALANCE, LitVmModel.UNREACHABLE, emptyList(), false, emptyMap()),
            airGapped = false)
        assertEquals(LitVmModel.BALANCE_FAILED, balance.status)
        assertFalse(balance.empty)
    }

    @Test
    fun beforeAReadAndWhileReading() {
        val first = LitVmModel.build(owner, null, airGapped = false, reading = true)
        assertEquals(LitVmModel.READING, first.status)
        assertFalse(first.empty)
        assertFalse(first.canRefresh)
        val again = LitVmModel.build(owner, read(listed(nft(1))), airGapped = false, reading = true)
        assertEquals(1, again.tiles.size)
        assertEquals(LitVmModel.READING, again.status)
        assertFalse(LitVmModel.build(null, null, airGapped = false).canRefresh)
    }

    @Test
    fun theStoppedLineAndTheRateLimitLine() {
        val notReached = LitVmCollection(badge, null, LitVmCollection.Status.NOT_REACHED, emptyList(), 0, 0)
        val stopped = LitVmModel.build(owner, LitVmRead(owner, UInt256.ZERO, null, null, listOf(notReached), stopped = true, pictures = emptyMap()), false)
        assertEquals("Stopped after 3 minutes. Refresh to continue.", stopped.status)
        assertEquals(listOf(LitVmNote(LitVmModel.shortAddress(badge), LitVmModel.COULD_NOT_BE_READ)), stopped.notes)
        assertFalse(stopped.empty)
        val limited = LitVmModel.build(owner, LitVmRead(owner, UInt256.ZERO, LitVmRead.Problem.RATE_LIMITED, null, listOf(listed(failed = 1)), false,
            emptyMap()), false)
        assertEquals(LitVmModel.RATE_LIMITED, limited.status)
        assertFalse(limited.empty)
    }

    @Test
    fun theNamesSeenAreKeptForLater() {
        assertEquals(mapOf(badge to "Super Litecoin Vault Badge"), LitVmModel.names(read(listed())))
        val symbolOnly = Erc721Collection(badge, "", "SLVB", false, false)
        assertEquals(mapOf(badge to "SLVB"), LitVmModel.names(read(listed(collection = symbolOnly))))
    }

    @Test
    fun theCopyIsTheDesktopsAndThePhonesOwn() {
        assertEquals("No collectibles in this account.", LitVmModel.EMPTY)
        assertEquals("Built in", LitVmModel.BUILT_IN)
        assertEquals("Soulbound: cannot be sent", LitVmModel.SOULBOUND)
        assertEquals("From the collection (not checked)", LitVmModel.FROM_COLLECTION)
        assertEquals("No picture", LitVmModel.NO_PICTURE)
        assertEquals("Could not be read", LitVmModel.COULD_NOT_BE_READ)
        assertEquals("Stopped after 3 minutes. Refresh to continue.", LitVmModel.STOPPED)
        assertEquals("Anyone can send an NFT to any account, and a collection's name proves nothing. Only the collections built in are listed, " +
            "by contract address. Nothing here sends or signs.", LitVmModel.FOOTNOTE)
        assertEquals("LitVM", LitVmModel.BUTTON)
        assertEquals("LitVM LiteForge Testnet", LitVmModel.TITLE)
        assertEquals("Test zkLTC and test tokens have no value.", LitVmModel.NO_VALUE)
        assertEquals("Copy address", LitVmModel.COPY_ADDRESS)
        assertEquals("Off in air-gapped mode", LitVmModel.AIR_GAPPED)
        assertEquals("Collectibles", LitVmModel.SECTION)
        assertEquals("N more not shown".replace("N", "5"), LitVmModel.moreLine(5))
        assertNull(LitVmModel.moreLine(0))
    }

    // ---- pictures ----

    private fun png(w: Int, h: Int): ByteArray {
        fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a) + be32(13) +
            "IHDR".encodeToByteArray() + be32(w) + be32(h) + byteArrayOf(8, 6, 0, 0, 0)
    }

    @Test
    fun aRasterIsDecidedByItsBytesNotItsLabel() {
        val raster = LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/jpeg", png(64, 32))), 120.0, 240.0)
        val shown = assertIs<LitVmPicture.Raster>(raster)
        assertEquals(64, shown.width)
        assertEquals(32, shown.height)
        assertContentEquals(png(64, 32), shown.bytes)
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/png",
            "<svg/>".encodeToByteArray())), 120.0, 240.0))
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/png", png(5000, 10))),
            120.0, 240.0))
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(null, 120.0, 240.0))
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.None), 120.0, 240.0))
        assertEquals(LitVmPicture.Hosted("IPFS"), LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Hosted("IPFS")), 120.0, 240.0))
    }

    @Test
    fun theMatrixHandedToAndroidIsTheContractsRowOrder() {
        val values = LitVmDrawing.matrixValues(SvgSubset.Matrix(2.0, 3.0, 5.0, 7.0, 11.0, 13.0))!!
        assertContentEquals(floatArrayOf(2f, 5f, 11f, 3f, 7f, 13f, 0f, 0f, 1f), values)
        assertNull(LitVmDrawing.matrixValues(SvgSubset.Matrix(1.0, 2.0, 2.0, 4.0, 0.0, 0.0)), "determinant 0")
        assertNull(LitVmDrawing.matrixValues(SvgSubset.Matrix(Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0)))
        assertNull(LitVmDrawing.matrixValues(SvgSubset.Matrix(1e300, 0.0, 0.0, 1e300, 0.0, 0.0)), "determinant not finite")
        assertNull(LitVmDrawing.matrixValues(SvgSubset.Matrix(1e-30, 0.0, 0.0, 1e-30, 0.0, 0.0)), "determinant 0 as a float")
    }

    @Test
    fun alphaIsRoundedAndStrokesOfZeroWidthAreSkipped() {
        assertEquals(0x80345d9d.toInt(), LitVmDrawing.color(0xff345d9d.toInt(), 0.5))
        assertEquals(0xff345d9d.toInt(), LitVmDrawing.color(0xff345d9d.toInt(), 1.0))
        assertEquals(0x00345d9d, LitVmDrawing.color(0xff345d9d.toInt(), 0.0))
        assertEquals(0x40000000, LitVmDrawing.color(0x80000000.toInt(), 0.5))
        assertEquals(0x02000000, LitVmDrawing.color(0x03000000, 0.5), "1.5 rounds half up")
        assertTrue(LitVmDrawing.strokeShown(1.0))
        assertFalse(LitVmDrawing.strokeShown(0.0))
        assertFalse(LitVmDrawing.strokeShown(1e-50), "0 as a float is a hairline on Android")
        assertEquals(2f, LitVmDrawing.textStrokeWidth(1.0, 10.0, 20.0), "shape units under the text's own scale")
    }

    @Test
    fun aDrawnPictureIsCachedByChainContractTokenAndSize() {
        assertEquals("4441:$badge:7:315", LitVmModel.pictureKey(LitVmNetwork.LITEFORGE_TESTNET, badge, UInt256.of(7), 315.0))
        val cache = SizedLru<String>(2, 10) { it.length.toLong() }
        cache.put("a", "1234")
        cache.put("b", "1234")
        assertEquals("1234", cache.get("a"))
        cache.put("c", "12")
        assertNull(cache.get("b"), "the least recently used goes first")
        cache.put("d", "123456789")
        assertNull(cache.get("a"), "over the byte bound")
        assertEquals("123456789", cache.get("d"))
        cache.put("e", "12345678901")
        assertNull(cache.get("e"), "larger than the bound: not kept")
        assertTrue(cache.bytes() <= 10)
    }

    // ---- the read ----

    @Test
    fun theReadIsGatedByAirGapAndByWhetherOneIsDue() {
        assertTrue(LitVmModel.shouldRead(airGapped = false, requested = 1, done = 0))
        assertFalse(LitVmModel.shouldRead(airGapped = true, requested = 1, done = 0))
        assertFalse(LitVmModel.shouldRead(airGapped = false, requested = 1, done = 1), "back from a detail: no new read")
        assertTrue(LitVmModel.shouldRead(airGapped = false, requested = 2, done = 1), "Refresh")
    }

    @Test
    fun theChainIsCheckedFirstThenTheBalanceThenTheCollection(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = setOf(1, 2))
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0)
        assertEquals(listOf("verify", "balance"), chain.log.take(2))
        assertTrue(chain.log.drop(2).all { it.startsWith("call ") || it.startsWith("code ") })
        assertNull(read.problem)
        assertEquals(chain.balanceWei, read.balance)
        val collection = read.collections.single()
        assertEquals(LitVmCollection.Status.LISTED, collection.status)
        assertEquals(listOf(1, 2).map { KnownCollections.vaultBadgeTokenId(owner, it) }, collection.tokens.map { it.tokenId })
        assertTrue(collection.collection!!.soulbound)
        assertEquals(2, read.pictures.size)
        val view = LitVmModel.build(owner, read, false)
        assertEquals(listOf("WORLD 1", "WORLD 2"), view.tiles.map { it.label })
        assertTrue(view.tiles.all { it.picture is LitVmPicture.Drawn })
    }

    @Test
    fun aWrongChainStopsBeforeAnythingElse(): Unit = runBlocking {
        val chain = FakeChain()
        chain.verifyFails = LitVmException("This server is for another chain.")
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0)
        assertEquals(listOf("verify"), chain.log)
        assertEquals(LitVmRead.Problem.CHAIN, read.problem)
        assertEquals("This server is for another chain.", read.detail)
        assertTrue(read.collections.isEmpty())
    }

    @Test
    fun anHttpLayerMessageIsNeverShown(): Unit = runBlocking {
        val chain = FakeChain()
        chain.balanceFails = IOException("connect to 10.0.0.1:443 failed: secret detail")
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0)
        assertEquals(LitVmRead.Problem.BALANCE, read.problem)
        assertEquals(LitVmModel.UNREACHABLE, read.detail)
        assertTrue(read.collections.isEmpty())
        assertEquals(listOf("verify", "balance"), chain.log)
    }

    @Test
    fun aServerMessageIsCleanedAndCapped(): Unit = runBlocking {
        val chain = FakeChain()
        chain.verifyFails = LitVmException("bad‮thing " + "x".repeat(300))
        val detail = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0).detail!!
        assertFalse(detail.contains('‮'))
        assertTrue(detail.length <= LitVmModel.MAX_MESSAGE)
    }

    @Test
    fun eachCollectionHasAMinuteAndTheRefreshThreeMinutes(): Unit = runBlocking {
        val chain = FakeChain()
        chain.step = 30_000
        val contracts = (1..5).map { EvmAddress.parse("0x" + "0".repeat(39) + it)!! }
        for(c in contracts) chain.badges[c] = FakeBadge(held = setOf(1))
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0, contracts)
        //verify 0-30, balance 30-60; then each collection gets 60 seconds: two requests
        val starts = chain.times
        assertTrue(starts.all { it < LitVmModel.REFRESH_MS }, "no request starts after three minutes: $starts")
        assertEquals(listOf(LitVmCollection.Status.FAILED, LitVmCollection.Status.FAILED, LitVmCollection.Status.NOT_REACHED,
            LitVmCollection.Status.NOT_REACHED, LitVmCollection.Status.NOT_REACHED), read.collections.map { it.status })
        assertEquals(2, chain.log.count { it.contains(contracts[0].toString()) })
        assertEquals(2, chain.log.count { it.contains(contracts[1].toString()) })
        assertTrue(read.stopped)
        assertEquals(LitVmModel.STOPPED, LitVmModel.build(owner, read, false).status)
        assertTrue(LitVmModel.build(owner, read, false).notes.all { it.text == LitVmModel.COULD_NOT_BE_READ })
    }

    @Test
    fun aRateLimitStopsEveryRequestAfterIt(): Unit = runBlocking {
        val chain = FakeChain()
        val other = EvmAddress.parse("0x00000000000000000000000000000000000c0ffe")!!
        chain.badges[badge] = FakeBadge(held = setOf(1, 2, 3))
        chain.badges[other] = FakeBadge(held = setOf(1))
        chain.rateLimitOn = { it.startsWith("call $badge 6352211e") }
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0, listOf(badge, other))
        val at = chain.log.indexOfFirst { it.startsWith("call $badge 6352211e") }
        assertEquals(at, chain.log.size - 1, "nothing after the 429: ${chain.log}")
        assertEquals(LitVmRead.Problem.RATE_LIMITED, read.problem)
        assertEquals(LitVmCollection.Status.FAILED, read.collections[1].status)
        val view = LitVmModel.build(owner, read, false)
        assertEquals(LitVmModel.RATE_LIMITED, view.status)
        assertTrue(view.notes.any { it.text == LitVmModel.COULD_NOT_BE_READ && it.name == "Super Litecoin Vault Badge" })
        assertTrue(view.notes.any { it.text == LitVmModel.COULD_NOT_BE_READ && it.name == LitVmModel.shortAddress(other) })
    }

    @Test
    fun aRateLimitOnTheChainCheckIsSaidPlainly(): Unit = runBlocking {
        val chain = FakeChain()
        chain.verifyFails = RateLimitedException("429")
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0)
        assertEquals(LitVmRead.Problem.RATE_LIMITED, read.problem)
        assertEquals(LitVmModel.RATE_LIMITED, LitVmModel.build(owner, read, false).status)
        assertEquals(listOf("verify"), chain.log)
    }

    @Test
    fun airGappedModeMidReadIsShownNotThrown(): Unit = runBlocking {
        val chain = FakeChain()
        chain.verifyFails = com.sparrowwallet.mobile.crypto.AirGappedException("LitVM")
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0)
        assertEquals(LitVmRead.Problem.AIR_GAPPED, read.problem)
        assertEquals(LitVmModel.AIR_GAPPED, LitVmModel.build(owner, read, false).status)
    }

    @Test
    fun aBuiltInContractThatIsNotACollectionCouldNotBeRead(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = emptySet(), code = false)
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0)
        assertEquals(LitVmCollection.Status.FAILED, read.collections.single().status)
        val view = LitVmModel.build(owner, read, false)
        assertFalse(view.empty)
        assertEquals(LitVmModel.COULD_NOT_BE_READ, view.notes.single().text)
    }

    @Test
    fun anAccountHoldingNoBadgeIsEmpty(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = emptySet())
        val view = LitVmModel.build(owner, LitVmReader.read(chain.root, chain::verify, owner, 120.0, 240.0), false)
        assertTrue(view.empty)
        assertNull(view.status)
    }

    @Test
    fun aSecondReadWhileOneRunsIsCoalesced(): Unit = runBlocking {
        val reads = Coalescer<String, Int>()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { reads.run("w") { runs++; gate.await(); 7 } }
        yield()
        val second = async { reads.run("w") { runs++; 8 } }
        yield()
        gate.complete(Unit)
        assertEquals(7, first.await())
        assertEquals(7, second.await())
        assertEquals(1, runs)
        assertEquals(9, reads.run("w") { runs++; 9 }, "a later read runs again")
        assertEquals(2, runs)
    }

    @Test
    fun aCancelledReadDoesNotCancelTheOneWaitingForIt(): Unit = runBlocking {
        val reads = Coalescer<String, Int>()
        val gate = CompletableDeferred<Unit>()
        val first = async { reads.run("w") { gate.await(); 1 } }
        yield()
        val second = async { reads.run("w") { 2 } }
        yield()
        first.cancel()
        assertEquals(2, second.await())
    }

    // ---- a fake chain ----

    private class FakeBadge(val held: Set<Int>, val code: Boolean = true, val worlds: Int = 4)

    private inner class FakeChain {
        var now = 0L
        var step = 1L
        val log = ArrayList<String>()
        val times = ArrayList<Long>()
        val badges = HashMap<EvmAddress, FakeBadge>()
        var balanceWei = UInt256.parseDecimal("34600000000000000")!!
        var verifyFails: Exception? = null
        var balanceFails: Exception? = null
        var rateLimitOn: (String) -> Boolean = { false }
        val root = View(null)

        suspend fun verify(calls: LitVmCalls) {
            (calls as View).request("verify")
            verifyFails?.let { throw it }
        }

        /** These calls with a deadline, which keeps the earlier one as the real client's does. */
        inner class View(private val deadline: Long?) : LitVmCalls {
            override val network = LitVmNetwork.LITEFORGE_TESTNET
            override val clock: () -> Long = { now }

            fun request(entry: String) {
                deadline?.let { if(now >= it) throw TimedOut() }
                times.add(now)
                log.add(entry)
                now += step
                if(rateLimitOn(entry)) throw RateLimitedException("429")
            }

            override fun until(deadline: Long): LitVmCalls = View(this.deadline?.let { minOf(it, deadline) } ?: deadline)

            override suspend fun balance(address: EvmAddress): UInt256 {
                request("balance")
                balanceFails?.let { throw it }
                return balanceWei
            }

            override suspend fun hasCode(address: EvmAddress): Boolean {
                request("code $address")
                return badges[address]?.code == true
            }

            override suspend fun call(to: EvmAddress, data: ByteArray, maxResultBytes: Int): ByteArray {
                val hex = data.toHex()
                request("call $to $hex")
                val fake = badges[to] ?: throw CallReverted("execution reverted")
                fun word(v: UInt256) = v.toWord()
                fun string(s: String): ByteArray {
                    val bytes = s.encodeToByteArray()
                    val padded = ByteArray((bytes.size + 31) / 32 * 32)
                    bytes.copyInto(padded)
                    return word(UInt256.of(32)) + word(UInt256.of(bytes.size.toLong())) + padded
                }
                fun argument(): UInt256 = UInt256.fromBytes(data.copyOfRange(4, 36))!!
                return when(hex.substring(0, 8)) {
                    "01ffc9a7" -> word(UInt256.of(when(hex.substring(8, 16)) {
                        "01ffc9a7", "80ac58cd", "b45a3c0e" -> 1L
                        else -> 0L
                    }))
                    "06fdde03" -> string("Super Litecoin Vault Badge")
                    "95d89b41" -> string("SLVB")
                    "23c4f6e3" -> word(UInt256.of(fake.worlds.toLong()))
                    "6352211e" -> {
                        val id = argument()
                        if(fake.held.any { KnownCollections.vaultBadgeTokenId(owner, it) == id }) ByteArray(12) + owner.bytes
                        else throw CallReverted("execution reverted")
                    }
                    "c87b56dd" -> {
                        val world = fake.held.first { KnownCollections.vaultBadgeTokenId(owner, it) == argument() }
                        string("{\"name\":\"WORLD $world\",\"image\":\"data:image/svg+xml;base64," +
                            java.util.Base64.getEncoder().encodeToString(svg.encodeToByteArray()) + "\"}")
                    }
                    else -> throw CallReverted("execution reverted")
                }
            }
        }
    }
}
