package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.storage.VaultData
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletDeletionTest {
    private fun seed(scriptType: String = "P2WPKH"): VaultData {
        val type = ScriptType.entries.firstOrNull { it.name == scriptType } ?: ScriptType.P2WPKH
        val path = if(scriptType == "MWEB") "m/1000'" else ChainParams.of(Coin.LITECOIN, Network.MAINNET).derivationPath(type)
        return VaultData(ABANDON, "", "BIP39", path, "mainnet", scriptType)
    }

    /** Main with its Private and Taproot wallets as [withPrivate] and [withTaproot] say, and an unrelated Other. */
    private fun family(withPrivate: Boolean = true, withTaproot: Boolean = true): DirFiles {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        files.add("Other", seed("P2PKH"), "other")
        if(withPrivate) {
            files.add("Main Private", seed("MWEB"), "pw")
            //the MWEB pair as the previous build wrote it: only the old linkedWallet field
            File(files.dir, "Main.meta").writeText("""{"network":"mainnet","coin":"litecoin","scriptType":"P2WPKH","linkedWallet":"Main Private"}""")
            File(files.dir, "Main Private.meta").writeText("""{"network":"mainnet","coin":"litecoin","scriptType":"MWEB","linkedWallet":"Main"}""")
        }
        if(withTaproot) assertNull(TaprootCompanion.add(files, "Main", "pw"))
        for(w in files.wallets()) {
            files.inscriptions.save(w.name, emptyMap())
            SyncStore.cacheFile(files.dir, w.name).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        return files
    }

    private fun DirFiles.choices(tapped: String) = deleteChoices(info(tapped)!!, wallets())

    private fun DirFiles.names() = wallets().map { it.name }

    /** Every wallet is listed, or shown under a listed parent that it is linked with both ways. */
    private fun DirFiles.noneHidden() {
        val wallets = wallets()
        val shown = groupByCoin(wallets).flatMap { it.second }
        for(w in wallets) {
            assertTrue(w in shown || shown.any { linkedBack(it, w) }, "${w.name} is hidden")
        }
        //and no vault is left without its meta
        for(n in names()) assertTrue(File(dir, "$n.meta").exists(), "$n has no meta")
    }

    // ---- the choices ----

    @Test
    fun aWalletWithoutLinkedWalletsHasNoChoices() {
        val files = family(withPrivate = false, withTaproot = false)
        assertEquals(emptyList(), files.choices("Main"))
        assertEquals(emptyList(), deleteFamily(files.info("Main")!!, files.wallets()))
        assertEquals(emptyList(), family().choices("Other"))
    }

    @Test
    fun aPublicAndPrivatePair() {
        val files = family(withTaproot = false)
        val expected = listOf(
            DeleteChoice("Only Main", listOf("Main"), "Main"),
            DeleteChoice("Only Main Private", listOf("Main Private"), "Main"),
            DeleteChoice("Main and its linked wallets", listOf("Main Private", "Main"), "Main")
        )
        assertEquals(expected, files.choices("Main"))
        assertEquals(expected, files.choices("Main Private"))
        assertEquals(listOf("Main" to Layer.PUBLIC, "Main Private" to Layer.PRIVATE), deleteFamily(files.info("Main Private")!!, files.wallets()))
    }

    @Test
    fun aPublicAndTaprootPair() {
        val files = family(withPrivate = false)
        val expected = listOf(
            DeleteChoice("Only Main", listOf("Main"), "Main"),
            DeleteChoice("Only Main Taproot", listOf("Main Taproot"), "Main"),
            DeleteChoice("Main and its linked wallets", listOf("Main Taproot", "Main"), "Main")
        )
        assertEquals(expected, files.choices("Main"))
        assertEquals(expected, files.choices("Main Taproot"))
    }

    @Test
    fun aFamilyOfThree() {
        val files = family()
        val expected = listOf(
            DeleteChoice("Only Main", listOf("Main"), "Main"),
            DeleteChoice("Only Main Private", listOf("Main Private"), "Main"),
            DeleteChoice("Only Main Taproot", listOf("Main Taproot"), "Main"),
            DeleteChoice("Main and its linked wallets", listOf("Main Private", "Main Taproot", "Main"), "Main")
        )
        for(tapped in listOf("Main", "Main Private", "Main Taproot")) assertEquals(expected, files.choices(tapped), tapped)
        assertEquals(listOf("Main" to Layer.PUBLIC, "Main Private" to Layer.PRIVATE, "Main Taproot" to Layer.TAPROOT),
            deleteFamily(files.info("Main Taproot")!!, files.wallets()))
        assertEquals("Linked wallets can have their own seed words.", LINKED_SEED_WORDS)
    }

    @Test
    fun aCompanionWhoseParentIsGoneHasNoChoices() {
        val files = family(withPrivate = false)
        File(files.dir, "Main.vault").delete()
        File(files.dir, "Main.meta").delete()
        assertEquals(listOf("Main Taproot", "Other"), files.names())
        assertEquals(emptyList(), files.choices("Main Taproot"))
    }

    @Test
    fun aOneSidedLinkOffersNoFamilyChoice() {
        val files = family(withPrivate = false)
        files.rewriteMeta(files.info("Main Taproot")!!.copy(links = WalletLinks()))
        assertEquals(emptyList(), files.choices("Main"))
        assertEquals(emptyList(), files.choices("Main Taproot"))
        val other = family(withPrivate = false)
        other.rewriteMeta(other.info("Main")!!.copy(links = WalletLinks()))
        assertEquals(emptyList(), other.choices("Main"))
        assertEquals(emptyList(), other.choices("Main Taproot"))
    }

    // ---- what each choice deletes ----

    @Test
    fun eachChoiceDeletesExactlyItsNames() {
        for(choice in family().choices("Main")) {
            val files = family()
            val before = files.names()
            assertTrue(deleteWallets(files, choice), choice.label)
            assertEquals(before - choice.names.toSet(), files.names(), choice.label)
            for(n in choice.names) {
                assertNull(files.inscriptions.loadOrNull(n), n)
                assertFalse(SyncStore.cacheFile(files.dir, n).exists(), n)
                assertFalse(File(files.dir, "$n.meta").exists(), n)
            }
            for(n in files.names()) assertNotNull(files.inscriptions.loadOrNull(n), n)
            files.noneHidden()
        }
    }

    @Test
    fun onlyThePublicWalletLeavesItsCompanionsAsOrdinaryRows() {
        val files = family()
        assertTrue(deleteWallets(files, files.choices("Main").first { it.label == "Only Main" }))
        assertEquals(listOf("Main Private", "Main Taproot", "Other"), groupByCoin(files.wallets()).flatMap { it.second }.map { it.name }.sorted())
        for(n in listOf("Main Private", "Main Taproot")) {
            assertEquals(WalletLinks(), files.info(n)!!.links, n)
            assertNull(files.info(n)!!.linkedWallet, n)
            assertFalse(File(files.dir, "$n.meta").readText().contains("linkedWallet"), n)
            //opened with their own password, as before
            assertEquals(n, WalletFamily.open(n, files.open(n, "pw"), files.wallets()) { null }.family.public.name)
        }
    }

    @Test
    fun onlyACompanionLeavesTheRestOfTheFamily() {
        val files = family()
        assertTrue(deleteWallets(files, files.choices("Main").first { it.label == "Only Main Taproot" }))
        assertEquals(WalletLinks(mweb = "Main Private"), files.info("Main")!!.links)
        assertEquals(listOf("Main", "Other"), groupByCoin(files.wallets()).flatMap { it.second }.map { it.name }.sorted())
        //and the Taproot wallet can be added again
        assertTrue(TaprootCompanion.offered(files.info("Main")!!, true, "BIP39", files.wallets()))
        assertNull(TaprootCompanion.add(files, "Main", "pw"))
        assertTrue(deleteWallets(files, files.choices("Main Private").first { it.label == "Only Main Private" }))
        assertEquals(WalletLinks(taproot = "Main Taproot"), files.info("Main")!!.links)
        assertNull(files.info("Main")!!.linkedWallet)
    }

    @Test
    fun aWalletOnItsOwnIsDeletedAlone() {
        val files = family()
        assertTrue(deleteWallets(files, DeleteChoice.only("Other")))
        assertEquals(listOf("Main", "Main Private", "Main Taproot"), files.names())
    }

    @Test
    fun aNameNoLongerLinkedIsKeptNotDeleted() {
        val files = family()
        val all = files.choices("Main").last()
        //the Taproot wallet's link changed between the confirmation and the delete
        files.rewriteMeta(files.info("Main Taproot")!!.copy(links = WalletLinks()))
        assertFalse(deleteWallets(files, all))
        assertEquals(listOf("Main Taproot", "Other"), files.names())
        files.noneHidden()
    }

    // ---- stops midway ----

    @Test
    fun aStopAtAnyWriteLeavesNothingHidden() {
        for(choice in family().choices("Main")) {
            for(failing in 0..5) {
                val files = family()
                files.failMetaWrite(failing)
                val done = deleteWallets(files, choice)
                files.failMetaWrite(null)
                files.noneHidden()
                if(!done) assertTrue(files.names().isNotEmpty())
            }
        }
    }

    @Test
    fun aFileThatWillNotGoIsAFailureAndKeepsItsMeta() {
        val files = family()
        //a vault the file system will not delete
        File(files.dir, "Main Private.vault").delete()
        File(files.dir, "Main Private.vault").mkdir()
        File(files.dir, "Main Private.vault/x").writeText("x")
        assertFalse(deleteWallets(files, DeleteChoice("Only Main Private", listOf("Main Private"), "Main")))
        assertTrue(File(files.dir, "Main Private.meta").exists())
    }

    @Test
    fun aWalletsLeftoverTemporaryFilesGoWithIt() {
        val dir = java.nio.file.Files.createTempDirectory("wallets").toFile()
        for(n in listOf("Main.vault", "Main.meta", "Main.vault.tmp", "Main.vault.123.tmp", "Main.meta.tmp", "Main Taproot.vault.tmp", "Other.vault"))
            File(dir, n).writeText("x")
        deleteWalletFiles(dir, "Main")
        assertEquals(listOf("Main Taproot.vault.tmp", "Other.vault"), dir.list()!!.sorted())
    }
}
