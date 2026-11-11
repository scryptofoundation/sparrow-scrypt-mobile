package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.storage.VaultData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun alsoSyncGivesWhatEachLayerSyncsBesideItsOwnWallet() {
        val all = WalletFamily(pub, priv, tap)
        // Public: its Taproot wallet (the Private wallet keeps its own handling, through the MWEB counterpart)
        assertEquals(listOf(tap), all.alsoSync(Layer.PUBLIC))
        assertEquals(listOf(tap), WalletFamily(pub, taproot = tap).alsoSync(Layer.PUBLIC))
        assertEquals(emptyList(), WalletFamily(pub, private = priv).alsoSync(Layer.PUBLIC))
        assertEquals(emptyList(), WalletFamily(pub).alsoSync(Layer.PUBLIC))
        // Private: the linked public wallet, as today; Taproot: nothing
        assertEquals(listOf(pub), all.alsoSync(Layer.PRIVATE))
        assertEquals(emptyList(), all.alsoSync(Layer.TAPROOT))
        assertEquals(emptyList(), WalletFamily(pub, taproot = tap).alsoSync(Layer.TAPROOT))
        // never a wallet outside the family, never the shown one
        for(layer in all.layers) {
            val members = all.layers.mapNotNull { all.member(it) }
            assertTrue(all.alsoSync(layer).all { it in members && it != all.member(layer) }, layer.name)
        }
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
        // the name the caller passed stays the shown wallet's name; the list is looked up by file name ("Main!" is "Main_")
        val wallets = listOf(
            info("Main_", "P2WPKH", WalletLinks(taproot = "Main_ Taproot")),
            info("Main_ Taproot", "P2TR", WalletLinks(parent = "Main_"))
        )
        val unlocked = mutableListOf<String>()
        val opened = WalletFamily.open("Main!", pub.data, wallets) { unlocked += it; tap.data }
        assertEquals(WalletFamily.Opened(WalletFamily(Member("Main!", pub.data), taproot = Member("Main_ Taproot", tap.data)),
            Layer.PUBLIC), opened)
        assertEquals(listOf("Main_ Taproot"), unlocked)
    }

    @Test
    fun aTappedCompanionOfTheWrongKindOpensAloneAndUnlocksNothing() {
        // its meta says Taproot, its vault says MWEB
        val (opened, unlocked) = open("Main Taproot", vaultsByName = vaults + ("Main Taproot" to vault("MWEB")))
        assertEquals(WalletFamily.Opened(WalletFamily(Member("Main Taproot", vault("MWEB"))), Layer.PUBLIC), opened)
        assertEquals(emptyList(), unlocked)
        // its meta is of the wrong kind for the slot its parent names it in
        val wrongMeta = listOf(
            info("Main", "P2WPKH", WalletLinks(taproot = "Main Taproot")),
            info("Main Taproot", "MWEB", WalletLinks(parent = "Main"))
        )
        val (alone, none) = open("Main Taproot", wrongMeta)
        assertEquals(WalletFamily.Opened(WalletFamily(tap), Layer.PUBLIC), alone)
        assertEquals(emptyList(), none)
    }

    @Test
    fun aTappedCompanionWhoseParentDoesNotNameItOpensAlone() {
        val wallets = listOf(
            info("Main", "P2WPKH", WalletLinks(mweb = "Main Private")),
            info("Main Private", "MWEB", WalletLinks(parent = "Main")),
            info("Main Taproot", "P2TR", WalletLinks(parent = "Main"))
        )
        val (opened, unlocked) = open("Main Taproot", wallets)
        assertEquals(WalletFamily.Opened(WalletFamily(tap), Layer.PUBLIC), opened)
        assertEquals(emptyList(), unlocked)
    }

    @Test
    fun aCompanionThatWillNotBeShownIsNeverUnlocked() {
        // a Private slot naming a wallet that is not MWEB, and a Taproot slot naming the same Taproot wallet
        val wallets = listOf(
            info("Main", "P2WPKH", WalletLinks(mweb = "Main Taproot", taproot = "Main Taproot")),
            info("Main Taproot", "P2TR", WalletLinks(parent = "Main"))
        )
        val (opened, unlocked) = open("Main", wallets)
        assertEquals(WalletFamily.Opened(WalletFamily(pub, taproot = tap), Layer.PUBLIC), opened)
        assertEquals(listOf("Main Taproot"), unlocked)
    }

    @Test
    fun anOldMetaPairOpensTogether() {
        // both metas as the previous build wrote them: only the old linkedWallet field
        fun decoded(name: String, json: String) = WalletMeta.decode(json).let {
            WalletRepository.WalletInfo(name, it.network, it.scriptType, it.links, it.coin)
        }
        val wallets = listOf(
            decoded("Main", """{"network":"mainnet","coin":"litecoin","scriptType":"P2WPKH","linkedWallet":"Main Private"}"""),
            decoded("Main Private", """{"network":"mainnet","coin":"litecoin","scriptType":"MWEB","linkedWallet":"Main"}""")
        )
        assertEquals(WalletFamily.Opened(WalletFamily(pub, private = priv), Layer.PUBLIC), open("Main", wallets).first)
        assertEquals(WalletFamily.Opened(WalletFamily(pub, private = priv), Layer.PRIVATE), open("Main Private", wallets).first)
    }

    @Test
    fun anOlderPrivatePairWrittenOnOneSideOpensTogether() {
        // a stop between the previous build's two link writes: Main names its Private wallet, which names nothing
        val wallets = listOf(
            info("Main", "P2WPKH", WalletLinks(mweb = "Main Private", taproot = "Main Taproot")),
            info("Main Private", "MWEB"),
            info("Main Taproot", "P2TR", WalletLinks(parent = "Main"))
        )
        val (opened, unlocked) = open("Main", wallets)
        assertEquals(WalletFamily.Opened(WalletFamily(pub, priv, tap), Layer.PUBLIC), opened)
        assertEquals(listOf("Main Private", "Main Taproot"), unlocked)
    }

    @Test
    fun aPrivateWalletNamingAParentThatDoesNotNameItIsNotOpened() {
        // the reverse: the Private wallet names Main, Main names nothing (Main was deleted and another saved in its name)
        val wallets = listOf(info("Main", "P2WPKH"), info("Main Private", "MWEB", WalletLinks(parent = "Main")))
        val (opened, unlocked) = open("Main", wallets)
        assertEquals(WalletFamily.Opened(WalletFamily(pub), Layer.PUBLIC), opened)
        assertEquals(emptyList(), unlocked)
    }

    @Test
    fun aOneSidedTaprootLinkStaysOut() {
        val wallets = listOf(info("Main", "P2WPKH", WalletLinks(taproot = "Main Taproot")), info("Main Taproot", "P2TR"))
        val (opened, unlocked) = open("Main", wallets)
        assertEquals(WalletFamily.Opened(WalletFamily(pub), Layer.PUBLIC), opened)
        assertEquals(emptyList(), unlocked)
    }

    @Test
    fun printingAHomeShowsNoSeedAndNoPassword() {
        val home = Screen.Home(WalletFamily(pub, priv, tap), Layer.TAPROOT, "hunter2")
        for(text in listOf(home.toString(), home.family.toString(), pub.toString(), Screen.Send(home).toString(),
            WalletFamily.Opened(home.family, home.layer).toString())) {
            assertFalse("abandon" in text, text)
            assertFalse("hunter2" in text, text)
        }
        assertTrue("Main Taproot" in home.toString() && "TAPROOT" in home.toString(), home.toString())
    }

    @Test
    fun theWalletsSendSignAndLabelSyncUseFollowTheLayer() {
        val all = WalletFamily(pub, priv, tap)
        val onPublic = Screen.Home(all, Layer.PUBLIC, "pw")
        val onPrivate = Screen.Home(all, Layer.PRIVATE, "pw")
        val onTaproot = Screen.Home(all, Layer.TAPROOT, "pw")
        // Send: the public and private sides of the shown wallet
        assertEquals(pub.data to priv.data, sendWallets(onPublic))
        assertEquals(pub.data to priv.data, sendWallets(onPrivate))
        assertEquals(tap.data to null, sendWallets(onTaproot))
        assertEquals(pub.data to null, sendWallets(Screen.Home(WalletFamily(pub, taproot = tap), Layer.PUBLIC)))
        assertEquals(null to priv.data, sendWallets(Screen.Home(priv.name, priv.data)))
        // Sign PSBT: the public side, or the shown wallet when it has none
        assertEquals(pub.data, signingWallet(onPublic))
        assertEquals(pub.data, signingWallet(onPrivate))
        assertEquals(tap.data, signingWallet(onTaproot))
        assertEquals(priv.data, signingWallet(Screen.Home(priv.name, priv.data)))
        // label sync: the shown wallet and its MWEB counterpart, and only they take the result
        assertEquals(listOf("Main", "Main Private"), labelSyncWallets(onPublic).map { it.first })
        assertEquals(listOf("Main Private", "Main"), labelSyncWallets(onPrivate).map { it.first })
        assertEquals(listOf("Main Taproot"), labelSyncWallets(onTaproot).map { it.first })
        val labelled = vault("P2TR", label = "x")
        val updated = mapOf("Main" to labelled, "Main Taproot" to labelled)
        assertEquals(onTaproot.withData(mapOf("Main Taproot" to labelled)), afterLabelSync(onTaproot, updated))
        assertEquals(onPublic.withData(mapOf("Main" to labelled)), afterLabelSync(onPublic, updated))
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
