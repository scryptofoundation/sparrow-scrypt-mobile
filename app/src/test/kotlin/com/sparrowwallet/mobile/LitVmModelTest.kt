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

    private fun read(vararg collections: LitVmCollection, problem: LitVmRead.Problem? = null, detail: LitVmRead.Reason? = null,
                     balance: UInt256? = wei("34600000000000000")): LitVmRead {
        val all = collections.toList()
        val pictures = LitVmModel.shownTokens(all).first.associate { LitVmModel.tileKey(it) to LitVmPictures.prepare(it.metadata, 120.0) }
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
        assertEquals(120.0, drawn.tile!!.size)
        assertEquals(svg, drawn.svg)
        //Only the tile's plan is made in the read; the detail's is made when the detail opens
        assertEquals(240.0, LitVmPictures.plan(drawn, 240.0)!!.size)
        assertEquals(120.0, LitVmPictures.plan(drawn, 120.0)!!.size)
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
        assertEquals(24, LitVmModel.MAX_HOST_CHARS)
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
        val chain = LitVmModel.build(owner, LitVmRead(owner, null, LitVmRead.Problem.CHAIN, LitVmRead.Reason.WRONG_CHAIN, emptyList(), false,
            emptyMap()), airGapped = false)
        assertEquals(LitVmModel.CHAIN_FAILED, chain.status)
        assertEquals("This server is for another chain.", chain.statusDetail)
        assertTrue(chain.tiles.isEmpty())
        assertFalse(chain.empty)
        assertNull(chain.balance)
        val balance = LitVmModel.build(owner, LitVmRead(owner, null, LitVmRead.Problem.BALANCE, LitVmRead.Reason.TIMED_OUT, emptyList(), false,
            emptyMap()), airGapped = false)
        assertEquals(LitVmModel.BALANCE_FAILED, balance.status)
        assertEquals("The LitVM server took too long.", balance.statusDetail)
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
    fun anAddressThatCouldNotBeWorkedOutSaysOnlyThat() {
        val view = LitVmModel.build(null, null, airGapped = false, addressFailed = true)
        assertEquals("The address could not be worked out.", view.status)
        assertNull(view.statusDetail)
        assertNull(view.address)
        assertFalse(view.canRefresh)
        assertFalse(view.empty)
        assertEquals(LitVmModel.ADDRESS_FAILED, LitVmModel.build(null, null, airGapped = true, addressFailed = true).status)
    }

    @Test
    fun aBlankMnemonicHasNoAccount() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { litVmAddressOf(VaultData("", network = "testnet")) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { litVmAddressOf(VaultData("   ", network = "testnet")) }
    }

    /** The master key the address is derived from is the call's own: wiping it changes nothing the wallet derives. */
    @Test
    fun derivingTheAddressTwiceGivesTheSameAndLeavesTheWalletsKeysAlone() {
        val data = VaultData("absent essay fox snake vast pumpkin height crouch silent bulb excuse razor", network = "testnet",
            derivationPath = "m/84'/1'/0'")
        val xpub = walletAccountXpub(data)
        val first = litVmAddressOf(data)
        assertEquals(first, litVmAddressOf(data))
        assertEquals("0x1dCAB80DDf4fc6eFb37aDE36aD784Eda7ac9B681", first.toString())
        assertEquals(xpub, walletAccountXpub(data))
    }

    @Test
    fun theHomeButtonLooksAtTheWalletListOnlyForALitecoinTestnetPublicLayer() {
        assertTrue(LitVmModel.mayOffer(Coin.LITECOIN, Network.TESTNET, "P2WPKH", true, Layer.PUBLIC))
        assertFalse(LitVmModel.mayOffer(Coin.LITECOIN, Network.MAINNET, "P2WPKH", true, Layer.PUBLIC))
        assertFalse(LitVmModel.mayOffer(Coin.DOGECOIN, Network.TESTNET, "P2WPKH", true, Layer.PUBLIC))
        assertFalse(LitVmModel.mayOffer(Coin.LITECOIN, Network.TESTNET, "MWEB", true, Layer.PUBLIC))
        assertFalse(LitVmModel.mayOffer(Coin.LITECOIN, Network.TESTNET, "P2WPKH", true, Layer.TAPROOT))
        assertFalse(LitVmModel.mayOffer(Coin.LITECOIN, Network.TESTNET, "P2WPKH", false, Layer.PUBLIC))
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
        val raster = LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/jpeg", png(64, 32))), 120.0)
        val shown = assertIs<LitVmPicture.Raster>(raster)
        assertEquals(64, shown.width)
        assertEquals(32, shown.height)
        assertContentEquals(png(64, 32), shown.bytes)
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/png",
            "<svg/>".encodeToByteArray())), 120.0))
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/png", png(5000, 10))),
            120.0))
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(null, 120.0))
        assertEquals(LitVmPicture.None, LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.None), 120.0))
        assertEquals(LitVmPicture.Hosted("IPFS"), LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Hosted("IPFS")), 120.0))
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
    fun aDrawnPictureIsCachedByChainContractTokenSourceAndSize() {
        assertEquals("4441:$badge:7:ff:315", LitVmModel.pictureKey(LitVmNetwork.LITEFORGE_TESTNET, badge, UInt256.of(7), 255L, 315.0))
        //FNV-1a 64 of "a" is 0xaf63dc4c8601ec8c
        assertEquals(0xaf63dc4c8601ec8cuL.toLong(), LitVmModel.sourceHash("a".encodeToByteArray()))
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

    /** A new picture for the same token after a Refresh is drawn again: the source is in the key. */
    @Test
    fun aChangedPictureGetsANewKey() {
        val other = svg.replace("#345d9d", "#9d5d34")
        val before = assertIs<LitVmPicture.Drawn>(LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Svg(svg)), 120.0))
        val after = assertIs<LitVmPicture.Drawn>(LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Svg(other)), 120.0))
        val again = assertIs<LitVmPicture.Drawn>(LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Svg(svg)), 120.0))
        val id = UInt256.of(1)
        assertTrue(LitVmModel.pictureKey(LitVmNetwork.LITEFORGE_TESTNET, badge, id, before.source, 120.0) !=
            LitVmModel.pictureKey(LitVmNetwork.LITEFORGE_TESTNET, badge, id, after.source, 120.0))
        assertEquals(before.source, again.source)
        val r1 = assertIs<LitVmPicture.Raster>(LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/png", png(8, 8))),
            120.0))
        val r2 = assertIs<LitVmPicture.Raster>(LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Raster("image/png", png(8, 9))),
            120.0))
        assertTrue(r1.source != r2.source)
    }

    // ---- what one read may hold ----

    /** The reviewer's heavy picture: about 48,000 commands from 19,000 characters, under the per-picture cap. */
    private val heavySvg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><path transform=\"scale(1000000)\" d=\"M0 0" +
        " S999999 999999 1 1".repeat(950) + "\"/></svg>"

    @Test
    fun theBudgetStopsPreparingOnceItIsSpent() {
        val costs = listOf(90, 90, 90, 90, 5, 90)
        var prepared = 0
        val kept = LitVmPictures.budgeted(costs, 200, prepare = { prepared++; it }, cost = { it }, none = { 0 })
        assertEquals(listOf(90, 90, 90, 0, 0, 0), kept)
        assertEquals(3, prepared, "nothing is prepared once the budget is spent")
        assertTrue(kept.sum() <= 200 + 90)
        assertEquals(List(3) { 1 }, LitVmPictures.budgeted(listOf(1, 1, 1), 200, prepare = { it }, cost = { it }, none = { 0 }))
    }

    @Test
    fun heavyPicturesStopAtTheReadsBudgetAndTheRestAreListedWithoutAPicture(): Unit = runBlocking {
        val one = assertIs<LitVmPicture.Drawn>(LitVmPictures.prepare(NftMetadata("", "", emptyList(), NftMetadata.Image.Svg(heavySvg)), 120.0))
        val cost = one.tile!!.cost
        assertTrue(cost > 10_000, "heavy: $cost")
        assertTrue(heavySvg.length < NftMetadata.MAX_JSON_CHARS)
        val tokens = (1..64).map { nft(it, NftMetadata("WORLD $it", "", emptyList(), NftMetadata.Image.Svg(heavySvg))) }
        val pictures = LitVmPictures.prepareAll(tokens, 120.0)
        val kept = pictures.values.sumOf { LitVmPictures.cost(it) }
        assertTrue(kept <= LitVmPictures.READ_BUDGET + cost, "kept $kept")
        assertTrue(pictures.values.count { it == LitVmPicture.None } > 0)
        val view = LitVmModel.build(owner, LitVmRead(owner, UInt256.ZERO, null, null, listOf(listed(*tokens.toTypedArray())), false, pictures), false)
        assertEquals(64, view.tiles.size)
        assertEquals("WORLD 64", view.tiles.last().label)
        assertEquals(LitVmModel.NO_PICTURE, LitVmModel.placeholder(view.tiles.last().picture)!!.text)
    }

    @Test
    fun anyThrowableWhilePreparingIsNoPictureButCancellationGoesOn() {
        assertEquals(LitVmPicture.None, LitVmPictures.guard { throw OutOfMemoryError("test") })
        assertEquals(LitVmPicture.None, LitVmPictures.guard { throw IllegalStateException("test") })
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> { LitVmPictures.guard { throw kotlinx.coroutines.CancellationException("x") } }
    }

    /** Before a Refresh's work the last read's plans are let go; its tiles stay listed, and a picture is planned again from its source. */
    @Test
    fun aReleasedReadHoldsNoPlansButStillShowsItsTiles() {
        val read = read(listed(nft(1), nft(2)))
        val released = LitVmModel.released(read)
        assertTrue(released.pictures.values.all { it is LitVmPicture.Drawn && it.tile == null })
        assertEquals(LitVmModel.build(owner, read, false).tiles.map { it.key }, LitVmModel.build(owner, released, false).tiles.map { it.key })
        val again = released.pictures.values.first() as LitVmPicture.Drawn
        assertEquals(120.0, LitVmPictures.plan(again, 120.0)!!.size)
        assertEquals((read.pictures.values.first() as LitVmPicture.Drawn).source, again.source)
    }

    @Test
    fun aTimeoutMidRequestIsSaidAsTooLong() {
        assertEquals(LitVmRead.Reason.TIMED_OUT, LitVmReader.reason(java.net.SocketTimeoutException("read timed out")))
        assertEquals(LitVmRead.Reason.TIMED_OUT, LitVmReader.reason(com.sparrowwallet.mobile.electrum.HttpEndpoint.TookTooLong(IOException("x"))))
        assertEquals(LitVmRead.Reason.TIMED_OUT, LitVmReader.reason(com.sparrowwallet.mobile.electrum.HttpEndpoint.DeadlinePassed()))
        assertEquals(LitVmRead.Reason.TIMED_OUT, LitVmReader.reason(TimedOut()))
        assertEquals(LitVmRead.Reason.UNREACHABLE, LitVmReader.reason(IOException("Request took too long")))
    }

    // ---- the read ----

    /** A collection's facts are kept for the visit: a Refresh reads only the account's tokens, not the contract again. */
    @Test
    fun aSecondReadReusesTheCollectionsFacts(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = setOf(1, 2))
        val first = LitVmReader.read(chain.root, chain::verify, owner, 120.0)
        val firstCount = chain.log.size
        val facts = LitVmModel.keptFacts(emptyMap(), first)
        assertEquals(setOf(badge), facts.keys)
        chain.log.clear()
        val second = LitVmReader.read(chain.root, chain::verify, owner, 120.0, known = facts)
        assertEquals(firstCount - 8, chain.log.size, "${chain.log}")
        assertTrue(chain.log.none { it.startsWith("code ") || it.contains(" 01ffc9a7") || it.contains(" 06fdde03") || it.contains(" 95d89b41") })
        assertEquals(listOf("verify", "balance"), chain.log.take(2))
        assertEquals(first.collections.single().tokens.map { it.tokenId }, second.collections.single().tokens.map { it.tokenId })
        assertTrue(second.collections.single().collection!!.soulbound)
        //A failed chain check drops what was kept
        val failed = LitVmRead(owner, null, LitVmRead.Problem.CHAIN, LitVmRead.Reason.WRONG_CHAIN, emptyList(), false, emptyMap())
        assertTrue(LitVmModel.keptFacts(facts, failed).isEmpty())
        assertEquals(facts, LitVmModel.keptFacts(facts, null))
        val balanceFailed = LitVmRead(owner, null, LitVmRead.Problem.BALANCE, LitVmRead.Reason.UNREACHABLE, emptyList(), false, emptyMap())
        assertEquals(facts, LitVmModel.keptFacts(facts, balanceFailed))
    }

    /** The read belongs to the visit: opening a detail does not stop it, and coming back never starts one by itself. */
    @Test
    fun theReadIsGatedByAirGapByWhetherOneIsDueAndByWhetherOneRuns() {
        assertTrue(LitVmModel.shouldRead(airGapped = false, requested = 1, done = 0, running = false))
        assertFalse(LitVmModel.shouldRead(airGapped = true, requested = 1, done = 0, running = false))
        assertFalse(LitVmModel.shouldRead(airGapped = false, requested = 1, done = 1, running = false), "back from a detail: no new read")
        assertFalse(LitVmModel.shouldRead(airGapped = false, requested = 1, done = 0, running = true), "back during the read: it goes on")
        assertTrue(LitVmModel.shouldRead(airGapped = false, requested = 2, done = 1, running = false), "Refresh")
    }

    private class FakeClient {
        @Volatile var closed = 0
    }

    @Test
    fun noClientIsMadeWhileAirGapped(): Unit = runBlocking {
        var opened = 0
        val result = LitVmClients.use(true, open = { opened++; FakeClient() }, close = { it.closed++ }) { "read" }
        assertNull(result)
        assertEquals(0, opened)
    }

    @Test
    fun theClientIsClosedWhenTheReadEnds(): Unit = runBlocking {
        val client = FakeClient()
        assertEquals("read", LitVmClients.use(false, open = { client }, close = { it.closed++ }) { "read" })
        assertTrue(client.closed >= 1)
        val failing = FakeClient()
        kotlin.test.assertFailsWith<IllegalStateException> {
            LitVmClients.use(false, open = { failing }, close = { it.closed++ }) { throw IllegalStateException("x") }
        }
        assertTrue(failing.closed >= 1)
    }

    /** A blocked socket read does not see cancellation: only closing the client ends it, and a cancel closes it at once. */
    @Test
    fun cancellingTheReadClosesTheClientWhileItIsBlocked(): Unit = runBlocking {
        val client = FakeClient()
        val started = CompletableDeferred<Unit>()
        val read = async(kotlinx.coroutines.Dispatchers.Default) {
            LitVmClients.use(false, open = { client }, close = { it.closed++ }) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    started.complete(Unit)
                    //As a socket read: it ends only when the client is closed
                    while(client.closed == 0) Thread.sleep(1)
                }
                "read"
            }
        }
        started.await()
        read.cancel()
        kotlinx.coroutines.withTimeout(10_000) { kotlin.runCatching { read.await() } }
        assertTrue(client.closed >= 1)
        assertTrue(read.isCancelled)
    }

    @Test
    fun aReadThatCouldNotStartSaysTheServerCouldNotBeReached() {
        val view = LitVmModel.build(owner, LitVmModel.unreachable(owner), airGapped = false)
        assertEquals(LitVmModel.CHAIN_FAILED, view.status)
        assertEquals(LitVmModel.UNREACHABLE, view.statusDetail)
        assertFalse(view.empty)
        assertTrue(view.tiles.isEmpty())
        assertTrue(view.canRefresh)
    }

    @Test
    fun theChainIsCheckedFirstThenTheBalanceThenTheCollection(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = setOf(1, 2))
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0)
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
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0)
        assertEquals(listOf("verify"), chain.log)
        assertEquals(LitVmRead.Problem.CHAIN, read.problem)
        assertEquals(LitVmRead.Reason.WRONG_CHAIN, read.reason)
        assertEquals("This server is for another chain.", LitVmModel.build(owner, read, false).statusDetail)
        assertTrue(read.collections.isEmpty())
    }

    @Test
    fun anHttpLayerMessageIsNeverShown(): Unit = runBlocking {
        val chain = FakeChain()
        chain.balanceFails = IOException("connect to 10.0.0.1:443 failed: secret detail")
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0)
        assertEquals(LitVmRead.Problem.BALANCE, read.problem)
        assertEquals(LitVmRead.Reason.UNREACHABLE, read.reason)
        assertEquals(LitVmModel.UNREACHABLE, LitVmModel.build(owner, read, false).statusDetail)
        assertTrue(read.collections.isEmpty())
        assertEquals(listOf("verify", "balance"), chain.log)
    }

    private val hostile = "Wallet flagged. Restore your seed at evil.example"

    /** Every string the view holds, so a test can say a text appears nowhere on the screen. */
    private fun strings(view: LitVmView): List<String> = listOfNotNull(view.address, view.balance, view.status, view.statusDetail) +
        view.notes.flatMap { listOfNotNull(it.name, it.text) } +
        view.tiles.flatMap { t ->
            listOf(t.key, t.label, t.collection, t.marker, t.detail.title, t.detail.collection, t.detail.description) +
                t.detail.appRows.flatMap { listOfNotNull(it.label, it.value) } + t.detail.attributes.flatMap { listOf(it.name, it.value) }
        }

    private fun assertOnlyAppWords(view: LitVmView, vararg foreign: String) {
        for(text in strings(view)) for(word in foreign) assertFalse(text.contains(word), "\"$word\" reached the screen in \"$text\"")
        view.status?.let { assertTrue(it in LitVmModel.STATUS_SENTENCES, it) }
        view.statusDetail?.let { assertTrue(it in LitVmModel.STATUS_SENTENCES, it) }
    }

    @Test
    fun aServersErrorTextNeverReachesTheScreen(): Unit = runBlocking {
        val chain = FakeChain()
        chain.verifyFails = LitVmException(hostile)
        val onChain = LitVmModel.build(owner, LitVmReader.read(chain.root, chain::verify, owner, 120.0), false)
        assertEquals(LitVmModel.CHAIN_FAILED, onChain.status)
        assertEquals(LitVmModel.UNREACHABLE, onChain.statusDetail)
        assertOnlyAppWords(onChain, hostile, "evil")

        val balance = FakeChain()
        balance.balanceFails = LitVmException(hostile)
        val onBalance = LitVmModel.build(owner, LitVmReader.read(balance.root, balance::verify, owner, 120.0), false)
        assertEquals(LitVmModel.BALANCE_FAILED, onBalance.status)
        assertEquals(LitVmModel.UNREACHABLE, onBalance.statusDetail)
        assertOnlyAppWords(onBalance, hostile, "evil")

        val collection = FakeChain()
        collection.badges[badge] = FakeBadge(held = setOf(1), error = hostile)
        val onCollection = LitVmModel.build(owner, LitVmReader.read(collection.root, collection::verify, owner, 120.0), false)
        assertEquals(listOf(LitVmNote(LitVmModel.shortAddress(badge), LitVmModel.COULD_NOT_BE_READ)), onCollection.notes)
        assertNull(onCollection.status)
        assertOnlyAppWords(onCollection, hostile, "evil")
    }

    /** The lower modules' own messages ("Invalid worlds() result from 0x...", "Timed out", HTTP codes) are for logs only. */
    @Test
    fun theClientsOwnMessagesNeverReachTheScreen(): Unit = runBlocking {
        val worlds = FakeChain()
        worlds.badges[badge] = FakeBadge(held = setOf(1), worldsAnswer = ByteArray(3))
        val view = LitVmModel.build(owner, LitVmReader.read(worlds.root, worlds::verify, owner, 120.0), false)
        assertEquals(listOf(LitVmNote("Super Litecoin Vault Badge", LitVmModel.COULD_NOT_BE_READ)), view.notes)
        assertOnlyAppWords(view, "Invalid", "worlds()")

        val http = FakeChain()
        http.verifyFails = LitVmException("The LitVM server answered HTTP 502")
        assertOnlyAppWords(LitVmModel.build(owner, LitVmReader.read(http.root, http::verify, owner, 120.0), false), "HTTP", "502")

        val slow = FakeChain()
        slow.verifyFails = TimedOut()
        val timed = LitVmModel.build(owner, LitVmReader.read(slow.root, slow::verify, owner, 120.0), false)
        assertEquals(LitVmModel.CHAIN_FAILED, timed.status)
        assertEquals("The LitVM server took too long.", timed.statusDetail)
        assertOnlyAppWords(timed, "Timed out")
    }

    @Test
    fun theStatusAreaHasAFixedSetOfSentences() {
        assertEquals(listOf("Reading...", "Off in air-gapped mode", "The address could not be worked out.", "Could not check the LitVM chain.",
            "Could not read the zkLTC balance.", "The LitVM server is limiting requests. Refresh later.", "Stopped after 3 minutes. Refresh to continue.",
            "This server is for another chain.", "The LitVM server took too long.", "The LitVM server could not be reached."), LitVmModel.STATUS_SENTENCES)
        for(reason in LitVmRead.Reason.entries) assertTrue(LitVmModel.reasonText(reason) in LitVmModel.STATUS_SENTENCES)
    }

    @Test
    fun eachCollectionHasAMinuteAndTheRefreshThreeMinutes(): Unit = runBlocking {
        val chain = FakeChain()
        chain.step = 30_000
        val contracts = (1..5).map { EvmAddress.parse("0x" + "0".repeat(39) + it)!! }
        for(c in contracts) chain.badges[c] = FakeBadge(held = setOf(1))
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, contracts)
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
    fun theRefreshRunningOutInsideTheLastCollectionIsStoppedButAMinuteRunningOutIsNot(): Unit = runBlocking {
        //verify 0-65, balance 65-130, the collection from 130 has only until 180 (inside its minute): one request, then the
        //refresh's three minutes are up
        val spent = FakeChain()
        spent.step = 65_000
        spent.badges[badge] = FakeBadge(held = setOf(1))
        val read = LitVmReader.read(spent.root, spent::verify, owner, 120.0)
        assertEquals(LitVmCollection.Status.FAILED, read.collections.single().status)
        assertTrue(read.stopped)
        assertEquals(LitVmModel.STOPPED, LitVmModel.build(owner, read, false).status)
        //verify 0-40, balance 40-80, the collection's minute ends at 140, with the refresh's time left: not stopped
        val minute = FakeChain()
        minute.step = 40_000
        minute.badges[badge] = FakeBadge(held = setOf(1))
        val cut = LitVmReader.read(minute.root, minute::verify, owner, 120.0)
        assertEquals(LitVmCollection.Status.FAILED, cut.collections.single().status)
        assertFalse(cut.stopped)
        assertEquals(listOf(LitVmNote("0x3eEd...51B3", LitVmModel.COULD_NOT_BE_READ)), LitVmModel.build(owner, cut, false).notes)
        //verify 0-50, balance 50-100, the collection's minute ends at 160, before the refresh's 180, though the clock is past
        //180 when it does: the minute ended it, not the refresh
        val late = FakeChain()
        late.step = 50_000
        late.badges[badge] = FakeBadge(held = setOf(1))
        val minuteFirst = LitVmReader.read(late.root, late::verify, owner, 120.0)
        assertEquals(LitVmCollection.Status.FAILED, minuteFirst.collections.single().status)
        assertFalse(minuteFirst.stopped)
    }

    /** An early ordinary failure, then the clock passing three minutes after everything was read, is not "stopped". */
    @Test
    fun anOrdinaryFailureIsNotStoppedBecauseTheClockLaterPassedThreeMinutes(): Unit = runBlocking {
        val chain = FakeChain()
        chain.step = 5_000
        val first = EvmAddress.parse("0x0000000000000000000000000000000000000001")!!
        chain.badges[first] = FakeBadge(held = emptySet(), code = false)
        chain.badges[badge] = FakeBadge(held = setOf(1), worlds = 1)
        //The badge's one tokenURI answer takes two minutes: it starts within the minute and ends past three minutes
        chain.slow = { if(it.startsWith("call $badge c87b56dd")) 120_000 else 0 }
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, listOf(first, badge))
        assertTrue(chain.root.clock() >= LitVmModel.REFRESH_MS)
        assertEquals(listOf(LitVmCollection.Status.FAILED, LitVmCollection.Status.LISTED), read.collections.map { it.status })
        assertEquals(0, read.collections[1].failed)
        assertFalse(read.stopped)
        assertNull(LitVmModel.build(owner, read, false).status)
    }

    /** An unreadable ownerOf answer counts as failed: the collection says "Could not be read", never that it is empty. */
    @Test
    fun malformedOwnershipAnswersAreNotAnEmptyAccount(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = setOf(1, 2), badOwner = true)
        val view = LitVmModel.build(owner, LitVmReader.read(chain.root, chain::verify, owner, 120.0), false)
        assertFalse(view.empty)
        assertTrue(view.tiles.isEmpty())
        assertTrue(view.notes.any { it.text == LitVmModel.COULD_NOT_BE_READ })
    }

    @Test
    fun aRateLimitStopsEveryRequestAfterIt(): Unit = runBlocking {
        val chain = FakeChain()
        val other = EvmAddress.parse("0x00000000000000000000000000000000000c0ffe")!!
        chain.badges[badge] = FakeBadge(held = setOf(1, 2, 3))
        chain.badges[other] = FakeBadge(held = setOf(1))
        chain.rateLimitOn = { it.startsWith("call $badge 6352211e") }
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0, listOf(badge, other))
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
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0)
        assertEquals(LitVmRead.Problem.CHAIN, read.problem)
        assertEquals(LitVmRead.Reason.RATE_LIMITED, read.reason)
        assertEquals(LitVmModel.CHAIN_FAILED, LitVmModel.build(owner, read, false).status)
        assertEquals(LitVmModel.RATE_LIMITED, LitVmModel.build(owner, read, false).statusDetail)
        assertEquals(listOf("verify"), chain.log)
    }

    @Test
    fun airGappedModeMidReadIsShownNotThrown(): Unit = runBlocking {
        val chain = FakeChain()
        chain.verifyFails = com.sparrowwallet.mobile.crypto.AirGappedException("LitVM")
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0)
        assertEquals(LitVmRead.Problem.AIR_GAPPED, read.problem)
        assertEquals(LitVmModel.AIR_GAPPED, LitVmModel.build(owner, read, false).status)
    }

    @Test
    fun aBuiltInContractThatIsNotACollectionCouldNotBeRead(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = emptySet(), code = false)
        val read = LitVmReader.read(chain.root, chain::verify, owner, 120.0)
        assertEquals(LitVmCollection.Status.FAILED, read.collections.single().status)
        val view = LitVmModel.build(owner, read, false)
        assertFalse(view.empty)
        assertEquals(LitVmModel.COULD_NOT_BE_READ, view.notes.single().text)
    }

    @Test
    fun anAccountHoldingNoBadgeIsEmpty(): Unit = runBlocking {
        val chain = FakeChain()
        chain.badges[badge] = FakeBadge(held = emptySet())
        val view = LitVmModel.build(owner, LitVmReader.read(chain.root, chain::verify, owner, 120.0), false)
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
    fun aFailureIsSharedWithTheReadWaitingForIt(): Unit = runBlocking {
        val reads = Coalescer<String, Int>()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { kotlin.runCatching { reads.run("w") { runs++; gate.await(); throw IllegalStateException("down") } } }
        yield()
        val second = async { kotlin.runCatching { reads.run("w") { runs++; 8 } } }
        yield()
        gate.complete(Unit)
        assertEquals("down", first.await().exceptionOrNull()!!.message)
        assertEquals("down", second.await().exceptionOrNull()!!.message)
        assertEquals(1, runs)
    }

    /** Reopening after leaving: a read whose owner was cancelled is not joined, even while it is still winding down. */
    @Test
    fun aCancelledReadIsNotJoined(): Unit = runBlocking {
        val reads = Coalescer<String, Int>()
        val stuck = CompletableDeferred<Unit>()
        val first = async(kotlinx.coroutines.Dispatchers.Default) {
            reads.run("w") { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { stuck.await() }; 1 }
        }
        kotlinx.coroutines.delay(100)
        first.cancel()
        assertEquals(2, kotlinx.coroutines.withTimeout(5_000) { reads.run("w") { 2 } })
        stuck.complete(Unit)
        kotlin.runCatching { first.await() }
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

    private class FakeBadge(val held: Set<Int>, val code: Boolean = true, val worlds: Int = 4, val error: String? = null,
                            val worldsAnswer: ByteArray? = null, val badOwner: Boolean = false)

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
        var slow: (String) -> Long = { 0 }
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
                now += step + slow(entry)
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
                badges[address]?.error?.let { throw LitVmException(it) }
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
                    "23c4f6e3" -> fake.worldsAnswer ?: word(UInt256.of(fake.worlds.toLong()))
                    "6352211e" -> {
                        val id = argument()
                        if(fake.badOwner) return ByteArray(5)
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
