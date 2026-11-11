package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            val totals = mapOf("pub" to 100L, "c" to 7L)
            if(kind == CompanionKind.MWEB) {
                // the previous build's Private pair: kept together as that build kept it (see companionOf), counted once
                assertEquals(listOf("pub"), groupByCoin(onDisk).single().second.map { it.name }, kind.name)
                assertEquals(107L, walletBalance(first, onDisk, totals), kind.name)
            } else {
                assertEquals(listOf("c", "pub"), groupByCoin(onDisk).single().second.map { it.name }.sorted(), kind.name)
                // and the section total counts the companion once, as its own row
                assertEquals(100L, walletBalance(first, onDisk, totals), kind.name)
            }
            assertEquals(107L, sectionTotal(onDisk, totals), kind.name)
        }
    }

    /** The wallet list's section total: each listed card's balance, as the list adds it. */
    private fun sectionTotal(wallets: List<WalletRepository.WalletInfo>, totals: Map<String, Long>): Long? {
        val shown = groupByCoin(wallets).single().second
        return coinTotal(shown, Network.MAINNET) { name -> shown.firstOrNull { it.name == name }?.let { walletBalance(it, wallets, totals) } }
    }

    private fun shown(wallets: List<WalletRepository.WalletInfo>) = groupByCoin(wallets).flatMap { it.second }.map { it.name }.sorted()

    @Test
    fun aWalletNamingItselfAsParentIsListed() {
        val self = w("a", Coin.LITECOIN, "P2TR", links = WalletLinks(taproot = "a", parent = "a"))
        assertEquals(listOf("a"), shown(listOf(self)))
        assertEquals(5L, walletBalance(self, listOf(self), mapOf("a" to 5L)))
    }

    @Test
    fun twoWalletsNamingEachOtherAreListed() {
        val wallets = listOf(
            w("a", Coin.LITECOIN, "MWEB", links = WalletLinks(mweb = "b", parent = "b")),
            w("b", Coin.LITECOIN, "MWEB", links = WalletLinks(mweb = "a", parent = "a"))
        )
        assertEquals(listOf("a", "b"), shown(wallets))
        assertEquals(1L, walletBalance(wallets[0], wallets, mapOf("a" to 1L, "b" to 2L)))
    }

    @Test
    fun aOneSidedLinkHidesNothing() {
        // the companion names the parent, the parent does not name it
        val companionOnly = listOf(w("pub", Coin.LITECOIN, "P2WPKH"), w("tap", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "pub")))
        assertEquals(listOf("pub", "tap"), shown(companionOnly))
        assertEquals(100L, walletBalance(companionOnly[0], companionOnly, mapOf("pub" to 100L, "tap" to 7L)))
        // the parent names the companion, the companion does not name it
        val parentOnly = listOf(w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(taproot = "tap")), w("tap", Coin.LITECOIN, "P2TR"))
        assertEquals(listOf("pub", "tap"), shown(parentOnly))
        assertEquals(100L, walletBalance(parentOnly[0], parentOnly, mapOf("pub" to 100L, "tap" to 7L)))
    }

    @Test
    fun anOlderPrivatePairWrittenOnOneSideStaysTogether() {
        // a stop between the previous build's two link writes: pub names priv, priv names nothing
        val wallets = listOf(w("pub", Coin.LITECOIN, "P2WPKH", linked = "priv"), w("priv", Coin.LITECOIN, "MWEB"))
        assertEquals(listOf("pub"), shown(wallets))
        val totals = mapOf("pub" to 100L, "priv" to 50L)
        assertEquals(150L, walletBalance(wallets[0], wallets, totals))
        // counted once in the section: priv has no row of its own
        val section = groupByCoin(wallets).single().second
        assertEquals(150L, coinTotal(section, Network.MAINNET) { n -> section.firstOrNull { it.name == n }?.let { walletBalance(it, wallets, totals) } })
    }

    @Test
    fun aPrivateWalletNamingAParentThatDoesNotNameItIsListed() {
        // the reverse: priv names pub, pub names nothing (pub was deleted and another wallet saved in its name)
        val wallets = listOf(w("pub", Coin.LITECOIN, "P2WPKH"), w("priv", Coin.LITECOIN, "MWEB", linked = "pub"))
        assertEquals(listOf("priv", "pub"), shown(wallets))
        assertEquals(100L, walletBalance(wallets[0], wallets, mapOf("pub" to 100L, "priv" to 50L)))
    }

    @Test
    fun aPrivateWalletNamedByTwoWalletsOnOneSideIsListed() {
        val wallets = listOf(w("a", Coin.LITECOIN, "P2WPKH", linked = "priv"), w("b", Coin.LITECOIN, "P2WPKH", linked = "priv"),
            w("priv", Coin.LITECOIN, "MWEB"))
        assertEquals(listOf("a", "b", "priv"), shown(wallets))
        assertEquals(100L, walletBalance(wallets[0], wallets, mapOf("a" to 100L, "priv" to 50L)))
    }

    @Test
    fun anOldPrivateWalletOfARelinkedParentIsListed() {
        val wallets = listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(mweb = "priv2")),
            w("priv", Coin.LITECOIN, "MWEB", linked = "pub"),
            w("priv2", Coin.LITECOIN, "MWEB", links = WalletLinks(parent = "pub"))
        )
        assertEquals(listOf("priv", "pub"), shown(wallets))
        assertEquals(102L, walletBalance(wallets[0], wallets, mapOf("pub" to 100L, "priv" to 1L, "priv2" to 2L)))
    }

    @Test
    fun aDowngradeThatDroppedTheTaprootLinkHidesNothing() {
        // an older build rewrote the parent's meta with its MWEB link only; the Taproot wallet still names it
        val wallets = listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", linked = "priv"),
            w("priv", Coin.LITECOIN, "MWEB", linked = "pub"),
            w("tap", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "pub"))
        )
        assertEquals(listOf("pub", "tap"), shown(wallets))
        assertEquals(150L, walletBalance(wallets[0], wallets, mapOf("pub" to 100L, "priv" to 50L, "tap" to 7L)))
    }

    @Test
    fun aParentThatIsItselfACompanionHidesNothing() {
        val wallets = listOf(
            w("top", Coin.LITECOIN, "P2WPKH", links = WalletLinks(taproot = "mid")),
            w("mid", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "top", taproot = "low")),
            w("low", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "mid"))
        )
        assertEquals(listOf("low", "top"), shown(wallets))
    }

    @Test
    fun aCompanionClaimedByTwoParentsCountsOnce() {
        val wallets = listOf(
            w("a", Coin.LITECOIN, "P2WPKH", links = WalletLinks(taproot = "tap")),
            w("b", Coin.LITECOIN, "P2WPKH", links = WalletLinks(taproot = "tap")),
            w("tap", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "a"))
        )
        val totals = mapOf("a" to 1L, "b" to 2L, "tap" to 7L)
        assertEquals(8L, walletBalance(wallets[0], wallets, totals))
        assertEquals(2L, walletBalance(wallets[1], wallets, totals))
        assertEquals(listOf("a", "b"), shown(wallets))
        assertEquals(10L, sectionTotal(wallets, totals))
    }

    @Test
    fun linkedBackNeedsBothSides() {
        assertTrue(linkedBack(family[0], family[1]))
        assertTrue(linkedBack(family[0], family[2]))
        assertFalse(linkedBack(family[1], family[0]))
        assertFalse(linkedBack(family[0], family[3]))
        val self = w("a", Coin.LITECOIN, "P2TR", links = WalletLinks(taproot = "a", parent = "a"))
        assertFalse(linkedBack(self, self))
    }

    @Test
    fun aCompanionOfTheWrongKindIsAnOrdinaryRow() {
        // linked both ways, but in a slot that is not its kind: Home would leave it out, so the list must show it
        val wallets = listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(mweb = "x", taproot = "y")),
            w("x", Coin.LITECOIN, "P2WPKH", links = WalletLinks(parent = "pub")),
            w("y", Coin.LITECOIN, "MWEB", links = WalletLinks(parent = "pub"))
        )
        assertFalse(linkedBack(wallets[0], wallets[1]))
        assertFalse(linkedBack(wallets[0], wallets[2]))
        assertEquals(listOf("pub", "x", "y"), shown(wallets))
    }

    @Test
    fun aWalletIsNeverLinkedToItself() {
        val a = w("a", Coin.LITECOIN, "P2WPKH")
        for(kind in CompanionKind.entries) assertEquals(emptyList(), companionLinkWrites(a, a, kind))
    }

    @Test
    fun aTaprootCompanionHasNoMwebCounterpart() {
        assertNull(family[2].linkedWallet)
        assertEquals("pub", family[1].linkedWallet)
        assertEquals("priv", family[0].linkedWallet)
    }

    @Test
    fun aRowIsTaggedWithTheCompanionsItHas() {
        assertEquals(listOf("Private", "Taproot"), companionTags(family[0], family))
        assertEquals(emptyList(), companionTags(family[3], family))
        // a companion has no row; asked anyway, it has no companions of its own
        assertEquals(emptyList(), companionTags(family[1], family))
        assertEquals(emptyList(), companionTags(family[2], family))
        val onlyTaproot = listOf(family[0].copy(links = WalletLinks(taproot = "tap")), family[2])
        assertEquals(listOf("Taproot"), companionTags(onlyTaproot[0], onlyTaproot))
    }

    @Test
    fun aRowNeverTagsALayerHomeWillNotOpen() {
        // the Taproot wallet does not name it back: listed on its own, no tag
        val oneSided = listOf(family[0], family[1], family[2].copy(links = WalletLinks()))
        assertEquals(listOf("Private"), companionTags(oneSided[0], oneSided))
        // a slot naming a wallet of the other kind
        val crossed = listOf(
            w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(mweb = "tap", taproot = "priv")),
            w("priv", Coin.LITECOIN, "MWEB", links = WalletLinks(parent = "pub")),
            w("tap", Coin.LITECOIN, "P2TR", links = WalletLinks(parent = "pub"))
        )
        assertEquals(emptyList(), companionTags(crossed[0], crossed))
        // a named companion that is not on the phone
        assertEquals(emptyList(), companionTags(family[0], listOf(family[0])))
        // the older Private pair whose link was written on the public side only still counts, as Home opens it
        val older = listOf(w("pub", Coin.LITECOIN, "P2WPKH", linked = "priv"), w("priv", Coin.LITECOIN, "MWEB"))
        assertEquals(listOf("Private"), companionTags(older[0], older))
    }

    @Test
    fun theTagsAreTheLayersHomeOpens() {
        val vaults = mapOf("pub" to "P2WPKH", "priv" to "MWEB", "tap" to "P2TR")
            .mapValues { com.sparrowwallet.mobile.storage.VaultData("abandon", scriptType = it.value) }
        for(wallets in listOf(family, listOf(family[0], family[1], family[2].copy(links = WalletLinks())), listOf(family[0], family[2]))) {
            val opened = WalletFamily.open("pub", vaults.getValue("pub"), wallets) { vaults[it] }
            assertEquals(opened.family.layers.drop(1).map { it.label }, companionTags(wallets[0], wallets))
        }
    }

    @Test
    fun theUnlockHintNamesTheCompanions() {
        assertEquals("Also opens: priv, tap.", unlockHint(family[0], family))
        val onlyTaproot = listOf(family[0].copy(links = WalletLinks(taproot = "tap")), family[2])
        assertEquals("Also opens: tap.", unlockHint(onlyTaproot[0], onlyTaproot))
        val onlyPrivate = listOf(family[0].copy(links = WalletLinks(mweb = "priv")), family[1])
        assertEquals("Also opens: priv.", unlockHint(onlyPrivate[0], onlyPrivate))
    }

    @Test
    fun aWalletWithoutCompanionsKeepsItsOldHint() {
        assertNull(unlockHint(family[3], family))
        assertNull(unlockHint(null, family))
        // an MWEB wallet listed on its own still names the wallet it links to, as before
        val orphan = listOf(w("priv", Coin.LITECOIN, "MWEB", links = WalletLinks(parent = "gone")))
        assertEquals("Also unlocks the linked wallet \"gone\".", unlockHint(orphan[0], orphan))
        // a one-sided Taproot link names nothing
        val oneSided = listOf(w("pub", Coin.LITECOIN, "P2WPKH", links = WalletLinks(taproot = "tap")), w("tap", Coin.LITECOIN, "P2TR"))
        assertNull(unlockHint(oneSided[0], oneSided))
    }

    @Test
    fun linkSendsEveryWalletButATaprootCompanion() {
        assertEquals(listOf("pub", "priv", "solo"), linkSendWallets(family).map { it.name })
        // a Taproot wallet on its own row is still offered
        val loose = listOf(family[0].copy(links = WalletLinks(mweb = "priv")), family[1], family[2].copy(links = WalletLinks()),
            w("standalone", Coin.LITECOIN, "P2TR"))
        assertEquals(listOf("pub", "priv", "tap", "standalone"), linkSendWallets(loose).map { it.name })
        val oneSided = listOf(family[0].copy(links = WalletLinks(mweb = "priv")), family[1], family[2])
        assertEquals(listOf("pub", "priv", "tap"), linkSendWallets(oneSided).map { it.name })
    }
}
