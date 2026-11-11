package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletGroupsTest {
    /** [linked] is the previous build's single link, read as the old meta field is read. */
    private fun w(name: String, coin: Coin, scriptType: String = "P2PKH", linked: String? = null,
                  network: Network = Network.MAINNET, links: WalletLinks? = null) =
        WalletRepository.WalletInfo(name, network, scriptType, links ?: WalletLinks.fromOldField(linked, scriptType), coin)

    @Test
    fun groupsFollowTheCoinOrder() {
        val groups = groupByCoin(listOf(w("d", Coin.DOGECOIN), w("l", Coin.LITECOIN, "P2WPKH"), w("p", Coin.PEPECOIN)))
        assertEquals(listOf(Coin.LITECOIN, Coin.DOGECOIN, Coin.PEPECOIN), groups.map { it.first })
        assertEquals(listOf("d"), groups[1].second.map { it.name })
    }

    @Test
    fun coinsWithoutWalletsAreLeftOut() {
        assertEquals(listOf(Coin.JUNKCOIN), groupByCoin(listOf(w("j", Coin.JUNKCOIN))).map { it.first })
        assertEquals(emptyList(), groupByCoin(emptyList()))
    }

    @Test
    fun linkedMwebWalletStaysUnderItsPublicWallet() {
        val groups = groupByCoin(listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", linked = "priv"),
            w("priv", Coin.LITECOIN, "MWEB", linked = "pub")
        ))
        assertEquals(listOf("pub"), groups.single().second.map { it.name })
    }

    @Test
    fun totalSumsKnownBalances() {
        val wallets = listOf(w("a", Coin.DOGECOIN), w("b", Coin.DOGECOIN), w("c", Coin.DOGECOIN))
        val balances = mapOf("a" to 100L, "c" to 50L)
        assertEquals(150L, coinTotal(wallets, Network.MAINNET) { balances[it] })
        assertNull(coinTotal(wallets, Network.MAINNET) { null })
    }

    @Test
    fun totalsKeepMainnetAndTestnetApart() {
        val wallets = listOf(w("m", Coin.PEPECOIN), w("t", Coin.PEPECOIN, network = Network.TESTNET))
        val balances = mapOf("m" to 100L, "t" to 7L)
        assertEquals(100L, coinTotal(wallets, Network.MAINNET) { balances[it] })
        assertEquals(7L, coinTotal(wallets, Network.TESTNET) { balances[it] })
    }

    @Test
    fun walletBalanceAddsItsLinkedMwebWallet() {
        val wallets = listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", linked = "priv"),
            w("priv", Coin.LITECOIN, "MWEB", linked = "pub"),
            w("other", Coin.LITECOIN, "P2WPKH", linked = "pub2"),
            w("pub2", Coin.LITECOIN, "P2WPKH")
        )
        assertEquals(150L, walletBalance(wallets[0], wallets, mapOf("pub" to 100L, "priv" to 50L)))
        assertEquals(50L, walletBalance(wallets[0], wallets, mapOf("priv" to 50L)))
        assertNull(walletBalance(wallets[0], wallets, emptyMap()))
        assertEquals(5L, walletBalance(wallets[2], wallets, mapOf("other" to 5L, "pub2" to 9L)))
    }

    private val family = listOf(
        w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(mweb = "priv", taproot = "tap")),
        w("priv", Coin.LITECOIN, "MWEB", links = WalletLinks(parent = "pub")),
        w("tap", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "pub")),
        w("solo", Coin.LITECOIN, "P2WPKH")
    )

    @Test
    fun aTaprootCompanionStaysUnderItsParent() {
        assertEquals(listOf("pub", "solo"), groupByCoin(family).single().second.map { it.name })
    }

    @Test
    fun walletBalanceAddsBothCompanions() {
        assertEquals(157L, walletBalance(family[0], family, mapOf("pub" to 100L, "priv" to 50L, "tap" to 7L)))
        assertEquals(7L, walletBalance(family[0], family, mapOf("tap" to 7L)))
        assertEquals(7L, walletBalance(family[2], family, mapOf("tap" to 7L)))
        // a taproot link to a wallet that is not a Taproot wallet adds nothing, as for MWEB
        val odd = listOf(w("a", Coin.LITECOIN, "P2WPKH", links = WalletLinks(taproot = "b")), w("b", Coin.LITECOIN, "P2WPKH"))
        assertEquals(1L, walletBalance(odd[0], odd, mapOf("a" to 1L, "b" to 9L)))
    }

    @Test
    fun aCompanionWhoseParentIsGoneIsListed() {
        val wallets = listOf(
            w("tap", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "gone")),
            w("priv", Coin.LITECOIN, "MWEB", linked = "gone too")
        )
        assertEquals(listOf("priv", "tap"), groupByCoin(wallets).single().second.map { it.name }.sorted())
    }

    @Test
    fun linkingTaprootKeepsTheMwebLink() {
        val pub = w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(mweb = "priv"))
        val tap = w("tap", Coin.LITECOIN, "P2TR")
        val writes = companionLinkWrites(pub, tap, CompanionKind.TAPROOT)
        assertEquals(listOf("pub", "tap"), writes.map { it.name })
        assertEquals(WalletLinks(mweb = "priv", taproot = "tap"), writes[0].links)
        assertEquals(WalletLinks(parent = "pub"), writes[1].links)
    }

    @Test
    fun linkingMwebKeepsTheTaprootLink() {
        val pub = w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(taproot = "tap"))
        val priv = w("priv", Coin.LITECOIN, "MWEB")
        val writes = companionLinkWrites(pub, priv, CompanionKind.MWEB)
        assertEquals(listOf("pub", "priv"), writes.map { it.name })
        assertEquals(WalletLinks(mweb = "priv", taproot = "tap"), writes[0].links)
        assertEquals(WalletLinks(parent = "pub"), writes[1].links)
        assertEquals("priv", writes[0].linkedWallet)
        assertEquals("pub", writes[1].linkedWallet)
    }

    @Test
    fun aCrashBetweenTheLinkWritesHidesNothing() {
        for(kind in CompanionKind.entries) {
            val parent = w("pub", Coin.LITECOIN, "P2WPKH")
            val companion = w("c", Coin.LITECOIN, if(kind == CompanionKind.MWEB) "MWEB" else "P2TR")
            val first = companionLinkWrites(parent, companion, kind).first()
            // only the first write reached disk
            val onDisk = listOf(first, companion)
            assertEquals(listOf("c", "pub"), groupByCoin(onDisk).single().second.map { it.name }.sorted(), kind.name)
        }
    }
}
