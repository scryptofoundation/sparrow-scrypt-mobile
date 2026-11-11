package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.storage.VaultData
import com.sparrowwallet.mobile.storage.WalletVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The BIP39 test vector seed drongo's vectors/coins.json was generated from; never a real wallet's. */
private const val ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

/** drongo's Electrum seed vector (vectors/electrum-seed-vector.json). */
private const val ELECTRUM = "coach fan denial rifle frost rival join install one wasp cool antique"

private class TestCipher : StoreCipher {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    override fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }
    override fun open(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob.copyOfRange(12, blob.size))
    }
}

/**
 * The wallet files on a temporary directory, laid out as the repository lays them out (<name>.vault sealed by
 * [WalletVault], <name>.meta by [WalletMeta], written with [writeAtomically]), with the inscription results and the
 * sync cache deleted as the repository deletes them. Vaults use few PBKDF2 rounds so the tests stay quick.
 */
private class DirFiles(val dir: File = java.nio.file.Files.createTempDirectory("wallets").toFile()) : CompanionFiles {
    val inscriptions = InscriptionStore(File(dir, "inscriptions"), TestCipher())
    val forgotten = mutableListOf<String>()
    val opened = mutableListOf<String>()
    private var failAt: Int? = null
    private var metaWrites = 0

    /** Makes the [n]th meta write from now (0 the next one) throw, as a full disk or a crash at that point would stop it. */
    fun failMetaWrite(n: Int?) {
        failAt = n
        metaWrites = 0
    }

    private fun vault(name: String) = File(dir, "$name.vault")
    private fun meta(name: String) = File(dir, "$name.meta")

    override fun wallets(): List<WalletRepository.WalletInfo> =
        dir.listFiles { f -> f.name.endsWith(".vault") }.orEmpty().map { it.name.removeSuffix(".vault") }.sorted().map { name ->
            val m = WalletMeta.decode(meta(name).readText())
            WalletRepository.WalletInfo(name, m.network, m.scriptType, m.links, m.coin)
        }

    override fun open(name: String, password: String): VaultData {
        opened += name
        return WalletVault.open(vault(name).readBytes(), password)
    }

    override fun save(name: String, data: VaultData, password: String): String? {
        if(vault(name).exists()) return "A wallet named \"$name\" already exists"
        val coin = Coin.fromId(data.coin)
        val network = Network.fromId(data.network)
        ImportRules.problem(coin, network, data.scriptType, false)?.let { return it }
        writeAtomically(meta(name), WalletMeta.encode(network, coin, data.scriptType).encodeToByteArray())
        writeAtomically(vault(name), WalletVault.seal(data, password, 1000))
        return null
    }

    override fun rewriteMeta(info: WalletRepository.WalletInfo) {
        if(metaWrites++ == failAt) throw IOException("disk full")
        writeAtomically(meta(info.name), WalletMeta.encode(info.network, info.coin, info.scriptType, info.links).encodeToByteArray())
    }

    override fun deleteOne(name: String) {
        forgotten += name
        deleteWalletCaches(name, inscriptions, SyncStore.cacheFile(dir, name))
        vault(name).delete()
        meta(name).delete()
    }

    fun add(name: String, data: VaultData, password: String) = assertNull(save(name, data, password))

    fun info(name: String) = wallets().firstOrNull { it.name == name }

    fun snapshot(): Map<String, List<Byte>> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readBytes().toList() }
}

class TaprootCompanionTest {
    private fun info(name: String = "Main", coin: Coin = Coin.LITECOIN, network: Network = Network.MAINNET,
                     scriptType: String = "P2WPKH", links: WalletLinks = WalletLinks()) =
        WalletRepository.WalletInfo(name, network, scriptType, links, coin)

    private fun seed(coin: Coin = Coin.LITECOIN, network: Network = Network.MAINNET, mnemonic: String = ABANDON,
                     passphrase: String = "", seedType: String = "BIP39", scriptType: ScriptType = ScriptType.P2WPKH,
                     derivation: String = ChainParams.of(coin, network).derivationPath(scriptType)) =
        VaultData(mnemonic, passphrase, seedType, derivation, network.id, scriptType.name, coin = coin.id)

    private fun firstReceive(data: VaultData): String =
        Addresses.forKey(walletAccountKey(data).deriveChild(0).deriveChild(0).pubKey, walletScriptType(data.scriptType),
            walletParams(Coin.fromId(data.coin), Network.fromId(data.network)))

    // ---- where it is offered ----

    @Test
    fun offeredOnlyWhereTaprootWalletsExist() {
        assertTrue(TaprootCompanion.offered(info(), true, "BIP39", emptyList()))
        assertTrue(TaprootCompanion.offered(info(network = Network.TESTNET), true, "BIP39", emptyList()))
        assertTrue(TaprootCompanion.offered(info(coin = Coin.BELLSCOIN), true, "BIP39", emptyList()))
        assertFalse(TaprootCompanion.offered(info(coin = Coin.BELLSCOIN, network = Network.TESTNET), true, "BIP39", emptyList()))
        assertFalse(TaprootCompanion.offered(info(coin = Coin.DOGECOIN, scriptType = "P2PKH"), true, "BIP39", emptyList()))
        assertEquals(TaprootCompanion.NOT_AVAILABLE,
            TaprootCompanion.problem(info(coin = Coin.BELLSCOIN, network = Network.TESTNET), emptyList(), true, "BIP39"))
        assertEquals(TaprootCompanion.NOT_AVAILABLE,
            TaprootCompanion.problem(info(coin = Coin.DOGECOIN, scriptType = "P2PKH"), emptyList(), true, "BIP39"))
        assertEquals("Taproot wallets are not available for this coin.", TaprootCompanion.NOT_AVAILABLE)
        assertNull(TaprootCompanion.problem(info(), listOf(info()), true, "BIP39"))
    }

    @Test
    fun notOfferedToATaprootMwebOrCompanionWallet() {
        assertFalse(TaprootCompanion.offered(info(scriptType = "P2TR"), true, "BIP39", emptyList()))
        assertFalse(TaprootCompanion.offered(info(scriptType = "MWEB"), true, "BIP39", emptyList()))
        assertFalse(TaprootCompanion.offered(info(links = WalletLinks(parent = "Other")), true, "BIP39", emptyList()))
        //a Taproot link that runs both ways
        val linked = info(links = WalletLinks(taproot = "Main Taproot"))
        val tap = info("Main Taproot", scriptType = "P2TR", links = WalletLinks(parent = "Main"))
        assertFalse(TaprootCompanion.offered(linked, true, "BIP39", listOf(linked, tap)))
        assertEquals(TaprootCompanion.nameTaken("Main Taproot"), TaprootCompanion.problem(linked, listOf(linked, tap), true, "BIP39"))
        //an MWEB companion of its own does not stop it
        assertTrue(TaprootCompanion.offered(info(links = WalletLinks(mweb = "Main Private")), true, "BIP39", emptyList()))
        assertEquals(TaprootCompanion.NOT_THIS_WALLET, TaprootCompanion.problem(info(scriptType = "P2TR"), emptyList(), true, "BIP39"))
        assertEquals(TaprootCompanion.NOT_THIS_WALLET, TaprootCompanion.problem(info(scriptType = "MWEB"), emptyList(), true, "BIP39"))
    }

    @Test
    fun notOfferedWithoutASeed() {
        assertFalse(TaprootCompanion.offered(info(), false, null, emptyList()))
        assertEquals("This wallet has no seed on this phone.", TaprootCompanion.problem(info(), emptyList(), false, null))
        //drongo derives the Taproot child from an Electrum seed too (Keystore.fromSeed takes either kind)
        assertTrue(TaprootCompanion.offered(info(), true, "ELECTRUM", emptyList()))
        assertFalse(TaprootCompanion.offered(info(), true, "SLIP39", emptyList()))
        assertEquals(TaprootCompanion.SEED_KIND, TaprootCompanion.problem(info(), emptyList(), true, "SLIP39"))
    }

    @Test
    fun nameFitsTheLimit() {
        assertEquals("Main Taproot", TaprootCompanion.name("Main"))
        val long = "x".repeat(40)
        assertEquals("x".repeat(32) + " Taproot", TaprootCompanion.name(long))
        assertEquals(40, TaprootCompanion.name(long).length)
        assertEquals(TaprootCompanion.name(long), walletFileName(TaprootCompanion.name(long)))
        //a cut that ends in a space leaves no double space
        assertEquals("abc Taproot", TaprootCompanion.name("abc" + " ".repeat(29) + "defghijk"))
        //two long names that share their first 32 characters collide: the second is refused by name
        val a = "y".repeat(32) + "-first"
        val b = "y".repeat(32) + "-other"
        assertEquals(TaprootCompanion.name(a), TaprootCompanion.name(b))
        assertEquals(TaprootCompanion.nameTaken(TaprootCompanion.name(b)),
            TaprootCompanion.problem(info(b), listOf(info(a, links = WalletLinks(taproot = TaprootCompanion.name(a))), info(b),
                info(TaprootCompanion.name(a), scriptType = "P2TR", links = WalletLinks(parent = a))), true, "BIP39"))
        assertEquals("A wallet named Main Taproot already exists.", TaprootCompanion.nameTaken("Main Taproot"))
    }

    @Test
    fun aParentNamedLikeItsOwnCompanionIsRefused() {
        val files = DirFiles()
        val parent = "z".repeat(32) + " Taproot"
        assertEquals(parent, TaprootCompanion.name(parent))
        files.add(parent, seed(), "pw")
        assertEquals(TaprootCompanion.nameTaken(parent), TaprootCompanion.add(files, parent, "pw"))
        assertEquals(listOf(parent), files.wallets().map { it.name })
        assertEquals(WalletLinks(), files.info(parent)!!.links)
    }

    // ---- the same wallet as the desktop ----

    @Test
    fun companionMatchesTheDesktopTaprootChild() {
        val vectors = Json.parseToJsonElement(File("../vectors/coins.json").readText()).jsonObject
        assertEquals(ABANDON, vectors["mnemonic"]!!.jsonPrimitive.content)
        for((coin, network) in listOf(Coin.LITECOIN to Network.MAINNET, Coin.LITECOIN to Network.TESTNET, Coin.BELLSCOIN to Network.MAINNET)) {
            val entry = vectors["entries"]!!.jsonArray.map { it.jsonObject }
                .first { it["coin"]!!.jsonPrimitive.content == coin.name && it["network"]!!.jsonPrimitive.content == network.name }
            val p2tr = entry["scriptTypes"]!!.jsonArray.map { it.jsonObject }.first { it["scriptType"]!!.jsonPrimitive.content == "P2TR" }
            val child = TaprootCompanion.vaultData(seed(coin, network))
            assertEquals("P2TR", child.scriptType)
            assertEquals(p2tr["derivationPath"]!!.jsonPrimitive.content, child.derivationPath)
            assertEquals(p2tr["accountXpub"]!!.jsonPrimitive.content, walletAccountXpub(child), "$coin $network")
            assertEquals(p2tr["receiveAddresses"]!!.jsonArray[0].jsonPrimitive.content, firstReceive(child), "$coin $network")
        }
    }

    @Test
    fun theChildIsAccountZeroOfTheSeedWhateverTheParentsPath() {
        //drongo derives at ScriptType.P2TR.getDefaultDerivation(), so a parent on account 3 or a custom path still gets m/86'/2'/0'
        val onAccount3 = TaprootCompanion.vaultData(seed(derivation = "m/84'/2'/3'"))
        val custom = TaprootCompanion.vaultData(seed(scriptType = ScriptType.P2PKH, derivation = "m/0'"))
        val plain = TaprootCompanion.vaultData(seed())
        assertEquals("m/86'/2'/0'", onAccount3.derivationPath)
        assertEquals(plain, onAccount3)
        assertEquals(plain, custom)
    }

    @Test
    fun theChildKeepsTheSeedKindAndPassphrase() {
        val withPassphrase = TaprootCompanion.vaultData(seed(passphrase = "TREZOR"))
        assertEquals("TREZOR", withPassphrase.passphrase)
        assertNotEquals(firstReceive(TaprootCompanion.vaultData(seed())), firstReceive(withPassphrase))
        //Electrum: the master key is the Electrum seed's (pinned by electrum-seed-vector.json), then BIP86 account 0
        val vector = Json.parseToJsonElement(File("../vectors/electrum-seed-vector.json").readText()).jsonObject
        val electrum = TaprootCompanion.vaultData(seed(mnemonic = ELECTRUM, seedType = "ELECTRUM", derivation = "m/0'"))
        assertEquals("ELECTRUM", electrum.seedType)
        assertEquals("m/86'/2'/0'", electrum.derivationPath)
        assertEquals(vector["extendedMasterPrivateKey"]!!.jsonPrimitive.content, walletMasterKey(electrum).xprv())
        assertEquals(walletMasterKey(electrum).derivePath("m/86'/2'/0'").xpub(), walletAccountXpub(electrum))
        //nothing of the parent's history comes along
        val labelled = seed().copy(txLabels = mapOf("ab" to "rent"))
        assertTrue(TaprootCompanion.vaultData(labelled).txLabels.isEmpty())
    }

    // ---- creating it ----

    @Test
    fun addingCreatesALinkedVaultUnderTheSamePassword() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        assertNull(TaprootCompanion.add(files, "Main", "pw"))
        val companion = files.info("Main Taproot")!!
        assertEquals("P2TR", companion.scriptType)
        assertEquals(WalletLinks(parent = "Main"), companion.links)
        assertEquals(WalletLinks(taproot = "Main Taproot"), files.info("Main")!!.links)
        val data = files.open("Main Taproot", "pw")
        assertEquals(TaprootCompanion.vaultData(seed()), data)
    }

    @Test
    fun addingKeepsTheParentsMwebLink() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        files.add("Main Private", seed(scriptType = ScriptType.P2WPKH).copy(scriptType = "MWEB", derivationPath = "m/1000'"), "pw")
        files.rewriteMeta(files.info("Main")!!.copy(links = WalletLinks(mweb = "Main Private")))
        files.rewriteMeta(files.info("Main Private")!!.copy(links = WalletLinks(parent = "Main")))
        assertNull(TaprootCompanion.add(files, "Main", "pw"))
        assertEquals(WalletLinks(mweb = "Main Private", taproot = "Main Taproot"), files.info("Main")!!.links)
        assertEquals("Main Private", files.info("Main")!!.linkedWallet)
    }

    @Test
    fun wrongPasswordCreatesNothing() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        val before = files.snapshot()
        assertEquals("Wrong password", TaprootCompanion.add(files, "Main", "not it"))
        val after = files.snapshot()
        assertEquals(before.keys, after.keys)
        for(k in before.keys) assertContentEquals(before[k], after[k], k)
        assertEquals(listOf("Main"), files.opened)
    }

    @Test
    fun refusalsAreGivenBeforeThePasswordIsTried() {
        val files = DirFiles()
        files.add("Doge", seed(Coin.DOGECOIN, scriptType = ScriptType.P2PKH), "pw")
        assertEquals(TaprootCompanion.NOT_AVAILABLE, TaprootCompanion.add(files, "Doge", "pw"))
        assertTrue(files.opened.isEmpty())
    }

    @Test
    fun addingTwiceIsRefused() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        assertNull(TaprootCompanion.add(files, "Main", "pw"))
        val before = files.snapshot()
        assertEquals(TaprootCompanion.nameTaken("Main Taproot"), TaprootCompanion.add(files, "Main", "pw"))
        assertEquals(before.keys, files.snapshot().keys)
        assertEquals(listOf("Main", "Main Taproot"), files.wallets().map { it.name })
    }

    @Test
    fun aLinkOnlyTheParentWroteIsFinished() {
        //a crash between the two link writes: the parent names the companion, the companion does not name it back
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        files.add("Main Taproot", TaprootCompanion.vaultData(seed()), "pw")
        files.rewriteMeta(files.info("Main")!!.copy(links = WalletLinks(taproot = "Main Taproot")))
        assertNull(TaprootCompanion.add(files, "Main", "pw"))
        assertEquals(WalletLinks(parent = "Main"), files.info("Main Taproot")!!.links)
    }

    @Test
    fun aHalfLinkedWalletIsOfferedAgainAndFinished() {
        //either write of the two reached disk alone: the parent names the companion, or the companion names the parent
        for(parentWrote in listOf(true, false)) {
            val files = DirFiles()
            files.add("Main", seed(), "pw")
            files.add("Main Taproot", TaprootCompanion.vaultData(seed()), "pw")
            if(parentWrote) files.rewriteMeta(files.info("Main")!!.copy(links = WalletLinks(taproot = "Main Taproot")))
            else files.rewriteMeta(files.info("Main Taproot")!!.copy(links = WalletLinks(parent = "Main")))
            assertTrue(TaprootCompanion.offered(files.info("Main")!!, true, "BIP39", files.wallets()), "parent wrote: $parentWrote")
            assertNull(TaprootCompanion.problem(files.info("Main")!!, files.wallets(), true, "BIP39"), "parent wrote: $parentWrote")
            assertNull(TaprootCompanion.add(files, "Main", "pw"))
            assertEquals(WalletLinks(taproot = "Main Taproot"), files.info("Main")!!.links)
            assertEquals(WalletLinks(parent = "Main"), files.info("Main Taproot")!!.links)
            assertFalse(TaprootCompanion.offered(files.info("Main")!!, true, "BIP39", files.wallets()))
            assertEquals(listOf("Main", "Main Taproot"), files.wallets().map { it.name })
        }
    }

    @Test
    fun anExistingSameSeedTaprootWalletIsLinkedNotDuplicated() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        files.add("Main Taproot", seed(scriptType = ScriptType.P2TR).copy(txLabels = mapOf("ab" to "kept")), "pw")
        val vaultBefore = File(files.dir, "Main Taproot.vault").readBytes()
        assertNull(TaprootCompanion.add(files, "Main", "pw"))
        assertEquals(listOf("Main", "Main Taproot"), files.wallets().map { it.name })
        assertContentEquals(vaultBefore, File(files.dir, "Main Taproot.vault").readBytes())
        assertEquals(WalletLinks(taproot = "Main Taproot"), files.info("Main")!!.links)
        assertEquals(WalletLinks(parent = "Main"), files.info("Main Taproot")!!.links)
    }

    @Test
    fun aNameTakenByAnotherWalletIsRefused() {
        val taken = TaprootCompanion.nameTaken("Main Taproot")
        fun check(other: VaultData, password: String = "pw") {
            val files = DirFiles()
            files.add("Main", seed(), "pw")
            files.add("Main Taproot", other, password)
            val before = files.snapshot()
            assertEquals(taken, TaprootCompanion.add(files, "Main", "pw"))
            val after = files.snapshot()
            assertEquals(before.keys, after.keys)
            for(k in before.keys) assertContentEquals(before[k], after[k], k)
        }
        //another seed's Taproot wallet
        check(seed(mnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow", scriptType = ScriptType.P2TR))
        //the same seed, another script type
        check(seed(scriptType = ScriptType.P2PKH))
        //the same seed with a passphrase
        check(seed(passphrase = "TREZOR", scriptType = ScriptType.P2TR))
        //the same seed's Taproot wallet on another account
        check(seed(scriptType = ScriptType.P2TR, derivation = "m/86'/2'/1'"))
        //the same seed's Taproot wallet, sealed under another password
        check(seed(scriptType = ScriptType.P2TR), "other")
        //the same seed's Taproot wallet on testnet
        check(seed(network = Network.TESTNET, scriptType = ScriptType.P2TR))
    }

    @Test
    fun anotherWalletsCompanionIsNotTaken() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        files.add("Old", seed(scriptType = ScriptType.P2PKH), "pw")
        files.add("Main Taproot", seed(scriptType = ScriptType.P2TR), "pw")
        files.rewriteMeta(files.info("Old")!!.copy(links = WalletLinks(taproot = "Main Taproot")))
        files.rewriteMeta(files.info("Main Taproot")!!.copy(links = WalletLinks(parent = "Old")))
        assertEquals(TaprootCompanion.nameTaken("Main Taproot"), TaprootCompanion.add(files, "Main", "pw"))
        assertEquals(WalletLinks(parent = "Old"), files.info("Main Taproot")!!.links)
    }

    @Test
    fun aFailedLinkLeavesNoVault() {
        //the parent's link write fails, or the companion's
        for(failing in 0..1) {
            val files = DirFiles()
            files.add("Main", seed(), "pw")
            val metaBefore = File(files.dir, "Main.meta").readBytes()
            files.failMetaWrite(failing)
            assertEquals(TaprootCompanion.SAVE_FAILED, TaprootCompanion.add(files, "Main", "pw"))
            files.failMetaWrite(null)
            assertEquals(listOf("Main"), files.wallets().map { it.name }, "write $failing failed")
            assertFalse(File(files.dir, "Main Taproot.vault").exists())
            assertEquals(WalletLinks(), files.info("Main")!!.links)
            if(failing == 0) assertContentEquals(metaBefore, File(files.dir, "Main.meta").readBytes())
        }
    }

    @Test
    fun aFailedLinkToAnExistingWalletKeepsIt() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        files.add("Main Taproot", seed(scriptType = ScriptType.P2TR), "pw")
        files.failMetaWrite(1)
        assertEquals(TaprootCompanion.SAVE_FAILED, TaprootCompanion.add(files, "Main", "pw"))
        files.failMetaWrite(null)
        assertEquals(listOf("Main", "Main Taproot"), files.wallets().map { it.name })
        assertEquals(WalletLinks(), files.info("Main")!!.links)
        assertEquals(WalletLinks(), files.info("Main Taproot")!!.links)
    }

    @Test
    fun anElectrumSeedWalletGetsItsTaprootWallet() {
        val files = DirFiles()
        files.add("Electrum", seed(mnemonic = ELECTRUM, seedType = "ELECTRUM", derivation = "m/0'"), "pw")
        assertNull(TaprootCompanion.add(files, "Electrum", "pw"))
        val data = files.open("Electrum Taproot", "pw")
        assertEquals("ELECTRUM", data.seedType)
        assertEquals("m/86'/2'/0'", data.derivationPath)
    }

    // ---- deleting ----

    private fun family(files: DirFiles) {
        files.add("Main", seed(), "pw")
        files.add("Main Private", seed().copy(scriptType = "MWEB", derivationPath = "m/1000'"), "pw")
        files.add("Other", seed(scriptType = ScriptType.P2PKH), "pw")
        //the MWEB pair as the previous build wrote it: only the old linkedWallet field
        File(files.dir, "Main.meta").writeText("""{"network":"mainnet","coin":"litecoin","scriptType":"P2WPKH","linkedWallet":"Main Private"}""")
        File(files.dir, "Main Private.meta").writeText("""{"network":"mainnet","coin":"litecoin","scriptType":"MWEB","linkedWallet":"Main"}""")
        assertNull(TaprootCompanion.add(files, "Main", "pw"))
        for(name in listOf("Main", "Main Private", "Main Taproot", "Other")) {
            files.inscriptions.save(name, emptyMap())
            SyncStore.cacheFile(files.dir, name).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
    }

    @Test
    fun deletingAParentDeletesItsCompanions() {
        val files = DirFiles()
        family(files)
        assertEquals(listOf("Main Private", "Main Taproot"), CompanionDeletion.deletedWith("Main", files.wallets()))
        CompanionDeletion.delete(files, "Main")
        assertEquals(listOf("Other"), files.wallets().map { it.name })
        for(name in listOf("Main", "Main Private", "Main Taproot")) {
            assertNull(files.inscriptions.loadOrNull(name), name)
            assertFalse(SyncStore.cacheFile(files.dir, name).exists(), name)
            assertFalse(File(files.dir, "$name.meta").exists(), name)
        }
        //companions first, the wallet last
        assertEquals(listOf("Main Private", "Main Taproot", "Main"), files.forgotten)
        assertNotNull(files.inscriptions.loadOrNull("Other"))
        assertTrue(SyncStore.cacheFile(files.dir, "Other").exists())
    }

    @Test
    fun deletingACompanionUnlinksItsParent() {
        val files = DirFiles()
        family(files)
        assertEquals(emptyList(), CompanionDeletion.deletedWith("Main Taproot", files.wallets()))
        CompanionDeletion.delete(files, "Main Taproot")
        assertEquals(listOf("Main", "Main Private", "Other"), files.wallets().map { it.name })
        assertEquals(WalletLinks(mweb = "Main Private"), files.info("Main")!!.links)
        assertNull(files.inscriptions.loadOrNull("Main Taproot"))
        assertEquals(listOf("Main Taproot"), files.forgotten)
        //and it can be added again
        assertTrue(TaprootCompanion.offered(files.info("Main")!!, true, "BIP39", files.wallets()))
        CompanionDeletion.delete(files, "Main Private")
        assertEquals(WalletLinks(), files.info("Main")!!.links)
        assertNull(files.info("Main")!!.linkedWallet)
    }

    @Test
    fun aWalletThatDoesNotNameItsParentBackIsNotDeletedWithIt() {
        val files = DirFiles()
        files.add("Main", seed(), "pw")
        files.add("Main Taproot", seed(scriptType = ScriptType.P2TR), "pw")
        files.rewriteMeta(files.info("Main")!!.copy(links = WalletLinks(taproot = "Main Taproot", mweb = "Gone")))
        assertEquals(emptyList(), CompanionDeletion.deletedWith("Main", files.wallets()))
        CompanionDeletion.delete(files, "Main")
        assertEquals(listOf("Main Taproot"), files.wallets().map { it.name })
    }

    @Test
    fun aCrashMidDeleteLeavesNoHiddenWallet() {
        //the delete stopped at each of its meta writes in turn: whatever is left is listed, or shown under a parent that names it
        //two writes per companion: it drops its parent, then the parent drops it
        for(failing in 0..3) {
            val files = DirFiles()
            family(files)
            files.failMetaWrite(failing)
            assertFailsWith<IOException> { CompanionDeletion.delete(files, "Main") }
            files.failMetaWrite(null)
            val wallets = files.wallets()
            assertTrue("Main" in wallets.map { it.name }, "the wallet goes last")
            val shown = groupByCoin(wallets).flatMap { it.second }.map { it.name }.toSet()
            for(w in wallets) {
                val parent = w.links.parent
                assertTrue(w.name in shown || (parent in shown && wallets.first { it.name == parent }.links.companions.contains(w.name)),
                    "${w.name} hidden when write $failing failed")
            }
        }
    }

    @Test
    fun theConfirmationNamesTheLinkedWallets() {
        assertNull(CompanionDeletion.confirmation(emptyList()))
        assertEquals("This also deletes its linked wallets: Main Private, Main Taproot.",
            CompanionDeletion.confirmation(listOf("Main Private", "Main Taproot")))
    }

    // ---- the atomic meta write ----

    @Test
    fun aTornWriteKeepsTheOldFile() {
        val dir = java.nio.file.Files.createTempDirectory("meta").toFile()
        val file = File(dir, "Main.meta").apply { writeText("old") }
        assertFailsWith<IOException> {
            writeAtomically(file, "new contents".encodeToByteArray()) { f, b -> f.writeBytes(b.copyOf(3)); throw IOException("torn") }
        }
        assertEquals("old", file.readText())
        assertEquals(listOf("Main.meta"), dir.list()!!.toList())
        writeAtomically(file, "new".encodeToByteArray())
        assertEquals("new", file.readText())
        assertEquals(listOf("Main.meta"), dir.list()!!.toList())
    }

    @Test
    fun aLeftoverTemporaryFileIsReplacedAndNeverListed() {
        val dir = java.nio.file.Files.createTempDirectory("vaults").toFile()
        val vault = File(dir, "Main.vault")
        //a crash inside an earlier write left its temporary file
        File(dir, "Main.vault.tmp").writeText("torn")
        writeAtomically(vault, "sealed".encodeToByteArray())
        assertEquals("sealed", vault.readText())
        assertEquals(listOf("Main.vault"), dir.list()!!.toList())
        //and the wallet list reads only vaults
        assertEquals("Main", walletVaultName("Main.vault"))
        assertNull(walletVaultName("Main.vault.tmp"))
        assertNull(walletVaultName("Main.meta.tmp"))
        assertNull(walletVaultName("Main.meta"))
    }
}
