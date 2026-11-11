package com.sparrowwallet.mobile

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a delete must not be overtaken by: session registration, saved sync credentials, a vault re-seal. */
class WalletFilesGuardTest {
    private val dir = java.nio.file.Files.createTempDirectory("wallets").toFile()

    @Test
    fun aWalletStillHereKeepsWhatItSaves() {
        val vault = File(dir, "Main.vault").apply { writeText("sealed") }
        val done = mutableListOf<String>()
        assertTrue(whileVaultExists(vault, gone = { done += "removed" }) { done += "saved" })
        assertEquals(listOf("saved"), done)
    }

    @Test
    fun aWalletDeletedMidDerivationKeepsNothing() {
        val vault = File(dir, "Main.vault").apply { writeText("sealed") }
        // the slow part (the vault opened, keys derived) runs first; the delete lands meanwhile
        val opened = vault.readText()
        assertTrue(vault.delete())
        val done = mutableListOf<String>()
        assertFalse(whileVaultExists(vault, gone = { done += "removed" }) { done += "saved" })
        assertEquals("sealed", opened)
        assertEquals(listOf("removed"), done)
    }

    @Test
    fun aDeleteHoldingTheLockIsWaitedFor() {
        val vault = File(dir, "Main.vault").apply { writeText("sealed") }
        val locked = CountDownLatch(1)
        val deleter = thread {
            synchronized(walletFilesLock) {
                locked.countDown()
                Thread.sleep(200)
                vault.delete()
            }
        }
        assertTrue(locked.await(5, TimeUnit.SECONDS))
        var saved = false
        assertFalse(whileVaultExists(vault) { saved = true })
        assertFalse(saved)
        deleter.join()
    }

    @Test
    fun theWorkerFindsAVaultByTheNameItWasCachedUnder() {
        // a credential saved under the name as typed still finds the wallet's file
        File(dir, "My_Wallet.vault").writeText("sealed")
        assertEquals(File(dir, "My_Wallet.vault"), vaultFileOf(dir, " My/Wallet "))
        assertTrue(whileVaultExists(vaultFileOf(dir, " My/Wallet ")) {})
        val removed = mutableListOf<String>()
        assertFalse(whileVaultExists(vaultFileOf(dir, "Gone"), gone = { removed += "Gone" }) {})
        assertEquals(listOf("Gone"), removed)
    }

    @Test
    fun aLabelReSealIsWrittenInOneStep() {
        val vault = File(dir, "Main.vault").apply { writeText("old") }
        assertFailsWith<IOException> {
            rewriteVault(vault, "new contents".encodeToByteArray()) { f, b -> f.writeBytes(b.copyOf(3)); throw IOException("torn") }
        }
        assertEquals("old", vault.readText())
        assertEquals(listOf("Main.vault"), dir.list()!!.toList())
        rewriteVault(vault, "new".encodeToByteArray())
        assertEquals("new", vault.readText())
        assertEquals(listOf("Main.vault"), dir.list()!!.toList())
    }

    @Test
    fun aDeletedWalletIsNeverWrittenBack() {
        val vault = File(dir, "Main.vault")
        assertFailsWith<WalletGoneException> { rewriteVault(vault, "sealed".encodeToByteArray()) }
        assertEquals(emptyList(), dir.list()!!.toList())
    }
}
