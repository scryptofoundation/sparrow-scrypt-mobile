package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.storage.VaultData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletFamilyTest {
    private fun info(name: String, scriptType: String, links: WalletLinks = WalletLinks()) =
        WalletRepository.WalletInfo(name, Network.MAINNET, scriptType, links, Coin.LITECOIN)

    private fun vault(scriptType: String, label: String = "") =
        VaultData(mnemonic = "abandon", scriptType = scriptType, txLabels = if(label.isEmpty()) emptyMap() else mapOf("t" to label))

    private val pub = Member("Main", vault("P2WPKH"))
    private val priv = Member("Main Private", vault("MWEB"))
    private val tap = Member("Main Taproot", vault("P2TR"))

    /** Main with both companions, each linked both ways. */
    private val family = listOf(
        info("Main", "P2WPKH", WalletLinks(mweb = "Main Private", taproot = "Main Taproot")),
        info("Main Private", "MWEB", WalletLinks(parent = "Main")),
        info("Main Taproot", "P2TR", WalletLinks(parent = "Main"))
    )
    private val vaults = mapOf("Main" to pub.data, "Main Private" to priv.data, "Main Taproot" to tap.data)

    /** Opens [tapped] among [wallets], recording every unlock; [fails] will not unlock. */
    private fun open(tapped: String, wallets: List<WalletRepository.WalletInfo> = family, fails: Set<String> = emptySet(),
                     vaultsByName: Map<String, VaultData> = vaults): Pair<WalletFamily.Opened, List<String>> {
        val unlocked = mutableListOf<String>()
        val opened = WalletFamily.open(tapped, vaultsByName.getValue(tapped), wallets) { n ->
            unlocked += n
            if(n in fails) throw IllegalStateException("Wrong password")
            vaultsByName.getValue(n)
        }
        return opened to unlocked
    }

    @Test
    fun layersFollowTheMembers() {
        assertEquals(listOf(Layer.PUBLIC), WalletFamily(pub).layers)
        assertEquals(listOf(Layer.PUBLIC, Layer.PRIVATE), WalletFamily(pub, private = priv).layers)
        assertEquals(listOf(Layer.PUBLIC, Layer.TAPROOT), WalletFamily(pub, taproot = tap).layers)
        val all = WalletFamily(pub, priv, tap)
        assertEquals(listOf(Layer.PUBLIC, Layer.PRIVATE, Layer.TAPROOT), all.layers)
        assertEquals(pub, all.member(Layer.PUBLIC))
        assertEquals(priv, all.member(Layer.PRIVATE))
        assertEquals(tap, all.member(Layer.TAPROOT))
        assertNull(WalletFamily(pub, taproot = tap).member(Layer.PRIVATE))
    }

    @Test
    fun theSwitchHasOneSegmentPerLayerAndHidesForOne() {
        assertEquals(emptyList(), switchLayers(WalletFamily(pub).layers))
        assertEquals(listOf("Public", "Private"), switchLayers(WalletFamily(pub, private = priv).layers).map { it.label })
        assertEquals(listOf("Public", "Taproot"), switchLayers(WalletFamily(pub, taproot = tap).layers).map { it.label })
        assertEquals(listOf("Public", "Private", "Taproot"),
            switchLayers(listOf(Layer.TAPROOT, Layer.PUBLIC, Layer.PRIVATE)).map { it.label })
    }

    @Test
    fun linkedMeansTheMwebCounterpart() {
        val all = WalletFamily(pub, priv, tap)
        // showing Public: the Private wallet
        Screen.Home(all, Layer.PUBLIC).let { assertEquals("Main Private", it.linkedName); assertEquals(priv.data, it.linkedData) }
        // showing Private: the Public wallet
        Screen.Home(all, Layer.PRIVATE).let { assertEquals("Main", it.linkedName); assertEquals(pub.data, it.linkedData) }
        // showing Taproot: none
        Screen.Home(all, Layer.TAPROOT).let { assertNull(it.linkedName); assertNull(it.linkedData) }
        // a Public wallet with only a Taproot companion: none
        Screen.Home(WalletFamily(pub, taproot = tap), Layer.PUBLIC).let { assertNull(it.linkedName); assertNull(it.linkedData) }
        // a wallet on its own: none
        Screen.Home("Solo", vault("P2WPKH")).let { assertNull(it.linkedName); assertNull(it.linkedData) }
    }

    @Test
    fun homeShowsTheMemberOfItsLayer() {
        val all = WalletFamily(pub, priv, tap)
        assertEquals("Main Taproot", Screen.Home(all, Layer.TAPROOT).name)
        assertEquals(tap.data, Screen.Home(all, Layer.TAPROOT).data)
        assertEquals("Main Private", Screen.Home(all, Layer.PRIVATE).name)
        assertEquals("Main", Screen.Home(all, Layer.PUBLIC).name)
    }

    @Test
    fun shownMovesToAnotherLayerOfTheSameFamily() {
        val home = Screen.Home(WalletFamily(pub, taproot = tap), Layer.PUBLIC, "pw")
        assertEquals(Screen.Home(WalletFamily(pub, taproot = tap), Layer.TAPROOT, "pw"), home.shown(Layer.TAPROOT))
        assertNull(home.shown(Layer.PRIVATE))
        assertNull(home.shown(Layer.PUBLIC))
    }

    @Test
    fun aFamilyOpensFromAnyMember() {
        val full = WalletFamily(pub, priv, tap)
        val (fromPublic, unlockedFromPublic) = open("Main")
        assertEquals(WalletFamily.Opened(full, Layer.PUBLIC), fromPublic)
        assertEquals(listOf("Main Private", "Main Taproot"), unlockedFromPublic)

        val (fromPrivate, unlockedFromPrivate) = open("Main Private")
        assertEquals(WalletFamily.Opened(full, Layer.PRIVATE), fromPrivate)
        assertEquals(listOf("Main", "Main Taproot"), unlockedFromPrivate)

        val (fromTaproot, unlockedFromTaproot) = open("Main Taproot")
        assertEquals(WalletFamily.Opened(full, Layer.TAPROOT), fromTaproot)
        assertEquals(listOf("Main", "Main Private"), unlockedFromTaproot)
    }

    @Test
    fun aCompanionThatWillNotUnlockIsLeftOut() {
        val (opened, _) = open("Main", fails = setOf("Main Taproot"))
        assertEquals(WalletFamily.Opened(WalletFamily(pub, private = priv), Layer.PUBLIC), opened)

        // a companion whose parent will not unlock opens on its own, as the Private wallet does today
        val (alone, unlocked) = open("Main Taproot", fails = setOf("Main"))
        assertEquals(WalletFamily.Opened(WalletFamily(tap), Layer.PUBLIC), alone)
        assertEquals(listOf("Main"), unlocked)

        // the parent opens, the sibling does not: the tapped companion stays with its parent
        val (pair, _) = open("Main Taproot", fails = setOf("Main Private"))
        assertEquals(WalletFamily.Opened(WalletFamily(pub, taproot = tap), Layer.TAPROOT), pair)
    }

    @Test
    fun aOneSidedLinkIsLeftOutAndNeverUnlocked() {
        // Main names its Taproot wallet, which does not name Main back
        val wallets = listOf(
            info("Main", "P2WPKH", WalletLinks(mweb = "Main Private", taproot = "Main Taproot")),
            info("Main Private", "MWEB", WalletLinks(parent = "Main")),
            info("Main Taproot", "P2TR")
        )
        val (opened, unlocked) = open("Main", wallets)
        assertEquals(WalletFamily.Opened(WalletFamily(pub, private = priv), Layer.PUBLIC), opened)
        assertEquals(listOf("Main Private"), unlocked)

        // tapped on its own, it opens alone and nothing else is unlocked
        val (alone, none) = open("Main Taproot", wallets)
        assertEquals(WalletFamily.Opened(WalletFamily(tap), Layer.PUBLIC), alone)
        assertEquals(emptyList(), none)
    }

    @Test
    fun aCompanionOfTheWrongKindIsLeftOut() {
        // the vaults decide: a Private slot holds only an MWEB wallet, a Taproot slot only a Taproot one
        val swapped = vaults + mapOf("Main Private" to vault("P2WPKH"), "Main Taproot" to vault("MWEB"))
        val (opened, _) = open("Main", vaultsByName = swapped)
        assertEquals(WalletFamily.Opened(WalletFamily(pub), Layer.PUBLIC), opened)
    }

    @Test
    fun onlyThePrivateLayerShowsAnMwebWallet() {
        // the theme and the Home screen follow the shown wallet's kind, so only the Private layer is copper
        val (opened, _) = open("Main")
        val home = Screen.Home(opened.family, opened.layer)
        assertEquals(listOf(Layer.PRIVATE),
            opened.family.layers.filter { l -> (home.shown(l) ?: home).data.scriptType == "MWEB" })
    }

    @Test
    fun anMwebWalletOnItsOwnOpensAsToday() {
        // an MWEB wallet with no public wallet: shown on its own, no switch, no counterpart
        val wallets = listOf(info("Lone", "MWEB"))
        val (opened, unlocked) = open("Lone", wallets, vaultsByName = mapOf("Lone" to vault("MWEB")))
        val home = Screen.Home(opened.family, opened.layer)
        assertEquals(listOf(Layer.PUBLIC), opened.family.layers)
        assertEquals("MWEB", home.data.scriptType)
        assertNull(home.linkedName)
        assertNull(home.shown(Layer.PUBLIC))
        assertEquals(emptyList(), unlocked)
    }

    @Test
    fun theTappedNameIsKeptAsGiven() {
        // the name the unlock screen passed stays the shown wallet's name; the list is looked up by file name
        val (opened, _) = open("Main", listOf(info("Main", "P2WPKH")), vaultsByName = mapOf("Main" to pub.data))
        assertEquals("Main", opened.family.public.name)
    }

    @Test
    fun aLabelSavedOnTheTaprootLayerUpdatesTheTaprootMember() {
        val home = Screen.Home(WalletFamily(pub, priv, tap), Layer.TAPROOT, "pw")
        val labelled = vault("P2TR", label = "rent")
        val updated = home.withData(mapOf("Main Taproot" to labelled))
        assertEquals(labelled, updated.data)
        assertEquals(Layer.TAPROOT, updated.layer)
        assertEquals(pub.data, updated.family.public.data)
        assertEquals(priv.data, updated.family.private?.data)
        assertEquals("pw", updated.password)
        // a name outside the family changes nothing
        assertEquals(home, home.withData(mapOf("Elsewhere" to labelled)))
    }

    @Test
    fun aPendingPsbtStaysUnderThePublicNameOfAnMwebPairAndItsOwnNameOnTaproot() {
        val all = WalletFamily(pub, priv, tap)
        assertEquals("Main", pendingPsbtKey(Screen.Home(all, Layer.PUBLIC)))
        assertEquals("Main", pendingPsbtKey(Screen.Home(all, Layer.PRIVATE)))
        assertEquals("Main Taproot", pendingPsbtKey(Screen.Home(all, Layer.TAPROOT)))
        assertEquals("Solo", pendingPsbtKey(Screen.Home("Solo", vault("P2WPKH"))))
        assertEquals("Lone", pendingPsbtKey(Screen.Home("Lone", vault("MWEB"))))
    }
}
