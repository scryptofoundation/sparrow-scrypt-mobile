package com.sparrowwallet.mobile

import android.content.Context
import com.sparrowwallet.mobile.crypto.Addresses
import com.sparrowwallet.mobile.crypto.Bech32
import com.sparrowwallet.mobile.crypto.Bip39
import com.sparrowwallet.mobile.crypto.CarrierScan
import com.sparrowwallet.mobile.crypto.RawTx
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.ChainParams
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.Bip32Pub
import com.sparrowwallet.mobile.crypto.Psbt
import com.sparrowwallet.mobile.crypto.PsbtReview
import com.sparrowwallet.mobile.crypto.PsbtSigner
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.Scripts
import com.sparrowwallet.mobile.crypto.SpendableInput
import com.sparrowwallet.mobile.crypto.TxBuilder
import com.sparrowwallet.mobile.crypto.UnknownCoinException
import com.sparrowwallet.mobile.crypto.UnsignedBuild
import com.sparrowwallet.mobile.crypto.XpubKey
import com.sparrowwallet.mobile.crypto.hexToBytes
import com.sparrowwallet.mobile.crypto.sha256
import com.sparrowwallet.mobile.crypto.toHex
import com.sparrowwallet.mobile.electrum.ElectrumBackend
import com.sparrowwallet.mobile.electrum.indexer.Indexers
import com.sparrowwallet.mobile.crypto.track.InscriptionContent
import com.sparrowwallet.mobile.electrum.BackendChainSource
import com.sparrowwallet.mobile.electrum.ChainBackend
import com.sparrowwallet.mobile.electrum.SocksProxy
import com.sparrowwallet.mobile.electrum.WalletScanner
import com.sparrowwallet.mobile.electrum.WalletSnapshot
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import com.sparrowwallet.mobile.crypto.track.ChainRules
import com.sparrowwallet.mobile.storage.MwebHistoryEntry
import com.sparrowwallet.mobile.storage.SparrowExport
import com.sparrowwallet.mobile.storage.SparrowImport
import com.sparrowwallet.mobile.storage.SparrowLink
import com.sparrowwallet.mobile.storage.VaultData
import com.sparrowwallet.mobile.storage.VaultException
import com.sparrowwallet.mobile.storage.WalletVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import kotlin.math.ceil

/**
 * Ties the tested modules together into wallet operations the UI can call.
 * Wallets are named vaults under filesDir/wallets/ — <name>.vault (encrypted) plus
 * <name>.meta (plaintext JSON with non-secret display info like the network).
 */
class WalletRepository(context: Context) {
    private val appContext = context.applicationContext
    private val walletsDir = File(context.filesDir, "wallets").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // migrate the single-wallet layout from v0.3
        val legacy = File(context.filesDir, "wallet.vault")
        if(legacy.exists() && listWallets().isEmpty()) {
            writeAtomically(vaultFile("My Wallet"), legacy.readBytes())
            writeMeta("My Wallet", Network.MAINNET)
            legacy.delete()
        }
    }

    data class WalletInfo(
        val name: String,
        val network: Network,
        val scriptType: String = "P2WPKH",
        /** Its companions (Private MWEB, Taproot) or its parent on this phone, by name. */
        val links: WalletLinks = WalletLinks(),
        val coin: Coin = Coin.LITECOIN
    ) {
        /** Name of the paired public/private counterpart wallet on this phone, if linked. */
        val linkedWallet: String? get() = links.mwebCounterpart(scriptType)
    }

    private fun safeName(name: String): String = walletFileName(name)

    private fun vaultFile(name: String) = vaultFileOf(walletsDir, name)

    /** Whether the wallet [name] (as typed, or as the list names it) still has its vault on this phone. */
    fun walletVaultExists(name: String): Boolean = vaultFile(name).exists()
    private fun metaFile(name: String) = File(walletsDir, "${safeName(name)}.meta")

    private fun writeMeta(name: String, network: Network, scriptType: String = "P2WPKH", links: WalletLinks = WalletLinks(),
                          coin: Coin = Coin.LITECOIN) {
        //in one step: a torn rewrite of a parent's links would read as a mainnet Litecoin wallet with none
        writeAtomically(metaFile(name), WalletMeta.encode(network, coin, scriptType, links).encodeToByteArray())
    }

    /** Wallets whose metadata names a coin this build does not know are left out (and left on disk). */
    fun listWallets(): List<WalletInfo> =
        walletsDir.listFiles { f -> walletVaultName(f.name) != null }.orEmpty().sortedBy { it.name }.mapNotNull { f ->
            val name = walletVaultName(f.name)!!
            runCatching { WalletMeta.decode(metaFile(name).readText()) }.fold(
                { WalletInfo(name, it.network, it.scriptType, it.links, it.coin) },
                { if(it is UnknownCoinException) null else WalletInfo(name, Network.MAINNET) }
            )
        }

    fun walletInfo(name: String): WalletInfo? = listWallets().firstOrNull { it.name == safeName(name) }

    /** Records the public↔private pairing in both wallets' metadata. */
    fun linkWallets(publicName: String, mwebName: String) = linkCompanion(publicName, mwebName, CompanionKind.MWEB)

    private fun linkCompanion(parentName: String, companionName: String, kind: CompanionKind) {
        if(safeName(parentName) == safeName(companionName)) return
        val parent = walletInfo(parentName) ?: return
        val companion = walletInfo(companionName) ?: return
        for(w in companionLinkWrites(parent, companion, kind)) writeMeta(w.name, w.network, w.scriptType, w.links, w.coin)
    }

    fun hasWallets(): Boolean = listWallets().isNotEmpty()

    /** Unsigned PSBTs out for external signing, by wallet name. */
    val pendingPsbts = PendingPsbts()

    /**
     * Deletes the wallets [choice] names (see [deleteWallets]); true when all of them went. On false (a write or a file
     * delete failed, or a link changed since the choice was offered) what is left stays listed.
     */
    fun deleteWallets(choice: DeleteChoice): Boolean = synchronized(walletFilesLock) { deleteWallets(companionFiles, choice) }

    /** The delete confirmation's choices for a wallet (see [deleteChoices]); none for a wallet without linked wallets. */
    fun deleteChoices(name: String): List<DeleteChoice> =
        listWallets().let { all -> all.firstOrNull { it.name == safeName(name) }?.let { deleteChoices(it, all) }.orEmpty() }

    /** The wallet's family by name and layer, for the delete confirmation (see [deleteFamily]). */
    fun deleteFamily(name: String): List<Pair<String, Layer>> =
        listWallets().let { all -> all.firstOrNull { it.name == safeName(name) }?.let { deleteFamily(it, all) }.orEmpty() }

    /**
     * Adds the same seed's Taproot wallet beside [parentName], sealed with [password] (see [TaprootCompanion.add]);
     * returns a refusal, or null. The parent's seed is read here and goes only into the new vault. Slow: two vaults
     * are opened or sealed, so call it off the main thread.
     */
    fun addTaprootCompanion(parentName: String, password: String): String? =
        TaprootCompanion.add(companionFiles, parentName, password)

    /**
     * One wallet and everything kept for it; its vault goes after its caches, so a stop midway leaves it listed, and
     * before its meta (see [deleteWalletFiles], which throws when a file will not go).
     */
    private fun deleteOneWallet(name: String) = synchronized(walletFilesLock) {
        //forget first: it stops a running check from writing the file back after it is deleted
        inscriptionChecker.forget(name)
        deleteWalletCaches(safeName(name), inscriptionStore, SyncStore.cacheFile(appContext.filesDir, name))
        sessionTotals.remove(safeName(name))
        sessionNames.values.removeAll { it == safeName(name) }
        pendingPsbts.drop(name)
        deleteWalletFiles(walletsDir, safeName(name))
    }

    /** The wallet files as adding and deleting companions see them. */
    private val companionFiles = object : CompanionFiles {
        override fun wallets(): List<WalletInfo> = listWallets()

        override fun open(name: String, password: String): VaultData = unlock(name, password, session = false)

        override fun save(name: String, data: VaultData, password: String): String? =
            saveWallet(name, data.mnemonic, data.passphrase, password, Network.fromId(data.network),
                derivationOverride = data.derivationPath, scriptType = data.scriptType, seedTypeOverride = data.seedType,
                coin = Coin.fromId(data.coin))

        override fun rewriteMeta(info: WalletInfo) =
            this@WalletRepository.writeMeta(info.name, info.network, info.scriptType, info.links, info.coin)

        override fun deleteOne(name: String) = deleteOneWallet(name)
    }

    /**
     * Detects BIP39 vs Electrum seed and returns the type + default derivation for the coin and
     * script type, or null if invalid. Electrum seeds are Litecoin only.
     */
    fun classifySeed(mnemonic: String, coin: Coin, network: Network,
                     scriptType: ScriptType = ChainParams.of(coin, network).defaultScriptType): Pair<String, String>? = when {
        Bip39.isValid(mnemonic) -> "BIP39" to WalletSetup.derivation(coin, network, scriptType)
        ElectrumSeed.isValid(mnemonic) && WalletSetup.electrumSeedAllowed(coin) ->
            "ELECTRUM" to ElectrumSeed.defaultDerivation(mnemonic)
        else -> null
    }

    fun createNewMnemonic(): String =
        Bip39.entropyToMnemonic(ByteArray(16).also { SecureRandom().nextBytes(it) })

    /**
     * Seals a new named wallet; returns a user-facing error, or null on success.
     * [seedTypeOverride] skips seed re-classification — used by imports, where the desktop
     * file's declared type is authoritative (and may be valid without being recognizable,
     * e.g. Electrum seeds with uncommon version prefixes).
     */
    fun saveWallet(name: String, mnemonic: String, passphrase: String, password: String, network: Network,
                   derivationOverride: String? = null, scriptType: String = "P2WPKH",
                   seedTypeOverride: String? = null,
                   mwebHistory: List<MwebHistoryEntry> = emptyList(),
                   txLabels: Map<String, String> = emptyMap(),
                   coin: Coin = Coin.LITECOIN): String? {
        val clean = safeName(name)
        if(clean.isEmpty()) return "Give the wallet a name"
        if(vaultFile(clean).exists()) return "A wallet named \"$clean\" already exists"
        val publicType = ScriptType.entries.firstOrNull { it.name == scriptType }
        if(publicType == null && !(scriptType == "MWEB" && coin.mweb)) return "${coin.displayName} has no $scriptType wallets"
        if(publicType != null) ImportRules.problem(coin, network, scriptType, false)?.let { return it }
        val classified = classifySeed(mnemonic.trim(), coin, network, publicType ?: ScriptType.P2WPKH)
        val invalid = if(ElectrumSeed.isValid(mnemonic.trim()) && !WalletSetup.electrumSeedAllowed(coin))
            "Electrum seeds are Litecoin only" else "Not a valid BIP39 or Electrum seed"
        val type = seedTypeOverride ?: classified?.first ?: return invalid
        if(seedTypeOverride == null && type == "ELECTRUM" && publicType != ScriptType.P2WPKH) {
            return "Electrum seeds restore as Native Segwit"
        }
        val derivation = derivationOverride ?: classified?.second ?: return invalid
        val data = VaultData(mnemonic.trim(), passphrase, type, derivation, network.id, scriptType, mwebHistory, txLabels, coin.id)
        //meta first: the list reads vaults, so a stop between the two leaves nothing listed rather than a vault without its meta
        writeMeta(clean, network, scriptType, coin = coin)
        writeAtomically(vaultFile(clean), WalletVault.seal(data, password))
        return null
    }

    /**
     * Imports a desktop Sparrow wallet file (encrypted or plain JSON export), re-sealing
     * it as a mobile vault under [devicePassword]. Returns the created wallet's name;
     * throws with a user-facing message on any problem. Argon2 here is memory-hard
     * (256 MB) by design — run off the main thread and expect a few seconds.
     */
    suspend fun importSparrowWallet(
        fileBytes: ByteArray,
        filePassword: String,
        preferredName: String?,
        devicePassword: String
    ): String = withContext(Dispatchers.Default) {
        val imported = SparrowImport.importFile(fileBytes, filePassword)
        val network = validateImported(imported, linkedMweb = false)
        saveImported(imported, network, preferredName, devicePassword)
    }

    /**
     * Imports a public wallet together with its linked private (MWEB) wallet, as pushed by
     * desktop's Link pair transfer. Both files are decrypted and validated before either is
     * saved, then the pairing is recorded in both wallets' metadata.
     */
    suspend fun importSparrowPair(
        payload: SparrowLink.ReceivedPayload,
        filePassword: String,
        linkedFilePassword: String,
        preferredName: String?,
        devicePassword: String
    ): String = withContext(Dispatchers.Default) {
        val linked = payload.linked
            ?: return@withContext importSparrowWallet(payload.wallet.file, filePassword, preferredName, devicePassword)

        val importedPublic = SparrowImport.importFile(payload.wallet.file, filePassword)
        val publicNetwork = try {
            validateImported(importedPublic, linkedMweb = true)
        } catch(e: SparrowImport.ImportException) {
            throw SparrowImport.ImportException("\"${payload.wallet.name}\": ${e.message}", e)
        }
        val importedMweb = try {
            SparrowImport.importFile(linked.file, linkedFilePassword)
        } catch(e: SparrowImport.ImportException) {
            throw SparrowImport.ImportException("Linked private wallet \"${linked.name}\": ${e.message}", e)
        }
        val mwebNetwork = try {
            validateImported(importedMweb, linkedMweb = false)
        } catch(e: SparrowImport.ImportException) {
            throw SparrowImport.ImportException("Linked private wallet \"${linked.name}\": ${e.message}", e)
        }

        val publicName = saveImported(importedPublic, publicNetwork, preferredName, devicePassword)
        val mwebName = try {
            saveImported(importedMweb, mwebNetwork, linked.name, devicePassword)
        } catch(e: Exception) {
            throw SparrowImport.ImportException(
                "\"$publicName\" was imported, but the linked private wallet failed: ${e.message}", e)
        }
        linkWallets(publicName, mwebName)
        publicName
    }

    private fun validateImported(imported: SparrowImport.ImportedWallet, linkedMweb: Boolean): Network {
        val network = when(imported.network) {
            "mainnet" -> Network.MAINNET
            "testnet" -> Network.TESTNET
            else -> throw SparrowImport.ImportException("Unsupported network in wallet file: ${imported.network}")
        }
        ImportRules.problem(imported.coin, network, imported.scriptType, linkedMweb)?.let { throw SparrowImport.ImportException(it) }
        SparrowImport.seedProblem(imported)?.let { throw SparrowImport.ImportException(it) }
        return network
    }

    private fun saveImported(imported: SparrowImport.ImportedWallet, network: Network, preferredName: String?, devicePassword: String): String {
        val name = preferredName?.takeIf { it.isNotBlank() } ?: imported.name ?: "Imported wallet"
        val scriptType = imported.scriptType ?: "P2WPKH"
        val derivation = imported.derivationPath
            ?: if(imported.seedType == "ELECTRUM") ElectrumSeed.defaultDerivation(imported.mnemonic)
               else WalletSetup.importDerivation(imported.coin, network, scriptType)
        saveWallet(name, imported.mnemonic, "", devicePassword, network, derivation,
            scriptType, imported.seedType, imported.mwebHistory, imported.txLabels, imported.coin)
            ?.let { throw SparrowImport.ImportException(it) }
        return safeName(name)
    }

    /**
     * Opens a wallet's vault; throws VaultException on wrong password. A [session] unlock (the
     * wallet being opened to use) lets its syncs record the wallet list's in-memory balance;
     * a password check (Reveal, export, leaving air-gapped mode, adding a Taproot wallet) does not keep the vault and
     * writes nothing, not even the background sync's credentials (those are cached by the session unlock).
     * A session unlock registers the wallet and saves its credentials only if its vault is still there once the slow key
     * derivations are done, under the lock a delete takes; a wallet deleted meanwhile throws, as a failed unlock does.
     */
    fun unlock(name: String, password: String, session: Boolean = true): VaultData {
        val vault = vaultFile(name)
        val data = WalletVault.open(vault.readBytes(), password)
        if(!session) return data
        val creds = runCatching { syncCredentialsFor(name, data) }.getOrNull()
        val kept = whileVaultExists(vault) {
            sessionNames[data] = safeName(name)
            creds?.let { runCatching { syncStore.save(it) } }
        }
        if(!kept) throw VaultException(WalletGoneException().message!!)
        return data
    }

    /**
     * Saves [creds] after a background scan only while the wallet's vault is still on this phone, under the lock a delete
     * takes; otherwise removes them. True when they were saved.
     */
    fun keepSyncCredentials(creds: SyncCredentials): Boolean =
        whileVaultExists(vaultFile(creds.name), gone = { syncStore.delete(creds.name) }) { syncStore.save(creds) }

    /**
     * Seals the vaults of a restore from another wallet app and links a second one (Cake's MWEB
     * wallet) to the first; returns the first wallet's name. If the second fails, the first is kept.
     */
    fun restoreFromApp(vaults: List<OtherWallets.RestoreVault>, password: String): String {
        fun save(v: OtherWallets.RestoreVault): String? = with(v.data) {
            saveWallet(v.name, mnemonic, passphrase, password, Network.fromId(network), derivationPath, scriptType,
                seedType, coin = Coin.fromId(coin))
        }
        OtherWallets.restoreNamesProblem(vaults) { vaultFile(it).exists() }?.let { throw IllegalArgumentException(it) }
        val first = vaults.first()
        save(first)?.let { throw IllegalArgumentException(it) }
        val second = vaults.getOrNull(1) ?: return safeName(first.name)
        save(second)?.let { throw IllegalArgumentException("\"${safeName(first.name)}\" was restored, but its private wallet failed: $it") }
        linkWallets(first.name, second.name)
        return safeName(first.name)
    }

    /** Wallets opened to use this session, so a sync can record its total under the wallet's name. */
    private val sessionNames = java.util.concurrent.ConcurrentHashMap<VaultData, String>()

    /** Last synced balance per wallet name this session; never written to disk. */
    private val sessionTotals = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun sessionTotals(): Map<String, Long> = HashMap(sessionTotals)

    private fun recordTotal(data: VaultData, total: Long) {
        sessionNames[data]?.let { sessionTotals[it] = total }
    }

    val syncStore by lazy { SyncStore(appContext) }

    /** The process's one inscription checker: Home and the background worker each build a repository, but share it. */
    private val inscriptionChecker: InscriptionChecker get() = sharedInscriptionChecker(appContext)

    /** Per-wallet inscription check results, encrypted under their own Keystore key. */
    val inscriptionStore: InscriptionStore get() = inscriptionChecker.store

    /**
     * The wallet's stored inscription checks by outpoint, as held in memory: cheap, never reads the file, so it is safe
     * from composition. Empty until [loadInscriptionChecks] or a check has loaded them; Send asks [loadInscriptionChecks].
     */
    fun inscriptionChecks(data: VaultData): Map<String, InscriptionCheck> =
        sessionNames[data]?.let { inscriptionChecker.cached(it) } ?: emptyMap()

    /**
     * Loads the wallet's stored inscription checks into memory, off the main thread; returns them, possibly empty when a
     * run found nothing to store, or null when the wallet has no readable results file (never checked, or it was lost).
     */
    suspend fun loadInscriptionChecks(data: VaultData): Map<String, InscriptionCheck>? {
        val name = sessionNames[data] ?: return null
        return withContext(Dispatchers.IO) { inscriptionChecker.storedResults(name) }
    }

    /** What Send reads of the wallet's results (rows and kept marks), from one read, off the main thread. */
    private suspend fun loadSendSnapshot(data: VaultData): InscriptionChecker.SendSnapshot? {
        val name = sessionNames[data] ?: return null
        return withContext(Dispatchers.IO) { inscriptionChecker.sendSnapshot(name) }
    }

    /**
     * Starts the check of an open public wallet's coins for inscriptions, in the checker's own scope (leaving Home does
     * not cancel it): on one server connection it lists the unspent coins (the shown receive addresses included, as
     * the sync walks them), then runs the built-in check, with the coins in [deeper] checked deeper. It holds only the
     * account xpub and the shown-receive count, never the wallet's keys, so locking the wallet does not need to stop
     * it. Skipped while one is going for the wallet; [inscriptionProgress] shows it. MWEB wallets, uncovered coins and
     * air-gapped mode start nothing. [account] is the wallet's account key Home holds (its xpub is read from it here,
     * cheaply, so nothing slow runs on the caller's thread).
     */
    fun startInscriptionCheck(data: VaultData, account: HDKey, deeper: Set<String> = emptySet()) {
        val name = sessionNames[data] ?: return
        val coin = coin(data)
        if(!checkApplies(data.scriptType, coin)) {
            return
        }
        val network = network(data)
        val scriptType = data.scriptType
        val xpub = account.xpub(params(data).xpubHeader)
        val receiveTo = receiveShownTo(data)
        inscriptionChecker.start(name) {
            runCheck(name, coin, network, scriptType, deeper, { false }, XpubKey.fromXpub(xpub), receiveTo)
        }
    }

    /** The wallet's running check as (done, total) coins, or null when none is going; read by Home's status line. */
    fun inscriptionProgress(data: VaultData): kotlinx.coroutines.flow.StateFlow<Pair<Int, Int>?>? =
        sessionNames[data]?.let { inscriptionChecker.progress(it) }

    /** The wallet's last or current check run (see [CheckStatus]), for the Collectibles header. */
    fun inscriptionStatus(data: VaultData): kotlinx.coroutines.flow.StateFlow<CheckStatus?>? =
        sessionNames[data]?.let { inscriptionChecker.status(it) }

    /** The outpoints a Check deeper was asked for and has not finished (see [InscriptionChecker.deeperQueued]). */
    fun deeperQueued(data: VaultData): kotlinx.coroutines.flow.StateFlow<Set<String>>? =
        sessionNames[data]?.let { inscriptionChecker.deeperQueued(it) }

    /**
     * Asks for one coin of an open public wallet to be checked deeper: now when no check runs for the wallet, else after
     * the running one (see [InscriptionChecker.requestDeeper]). The run is Home's: the same listing and [checkWithIndexer]
     * path, so the setting, proxy, notice and air-gap rules are read when it runs. Like [startInscriptionCheck] it keeps
     * only the xpub and the shown-receive count. MWEB wallets, uncovered coins and air-gapped mode queue nothing.
     *
     * Cheap on the main thread: the coin shows as queued at once, and the account xpub is derived in the process's own
     * scope (leaving the screen does not drop the request); taps while one derivation for the wallet goes share it.
     */
    fun requestDeeperCheck(data: VaultData, outpoint: String) {
        val name = sessionNames[data] ?: return
        val coin = coin(data)
        val scriptType = data.scriptType
        if(!deeperCheckAllowed(coin, scriptType, NetworkPolicy.blocked)) {
            return
        }
        val network = network(data)
        val receiveTo = receiveShownTo(data)
        val xpub = deriving.computeIfAbsent(name) {
            backgroundScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { accountXpub(data) }
        }
        xpub.invokeOnCompletion { deriving.remove(name, xpub) }
        xpub.start()
        requestDeeperIfAllowed(inscriptionChecker, name, coin, scriptType, NetworkPolicy.blocked, outpoint) { deeper ->
            runCheck(name, coin, network, scriptType, deeper, { false }, XpubKey.fromXpub(xpub.await()), receiveTo)
        }
    }

    /** Account xpubs being derived for Check deeper, by wallet name: only while derivation goes. */
    private val deriving get() = derivingXpubs

    /** The address at a position of the wallet's account ([chain] 0 receive, 1 change). Derives keys: call it off the main thread. */
    fun addressAt(data: VaultData, chain: Int, index: Int): String =
        Addresses.forKey(accountKey(data).deriveChild(chain.toLong()).deriveChild(index.toLong()).pubKey, scriptType(data), params(data))

    /**
     * The background worker's check of a wallet from its cached xpub alone, with the default budgets and the receive
     * addresses Home has shown (kept under the xpub's hash). [timeUp] stops it between coins, saving what it decided.
     * Returns the results, or null when it did not run: skipped (one is going for the wallet, or the wallet has no
     * check) or failed. Never throws but for cancellation.
     */
    suspend fun checkInscriptionsFromXpub(creds: SyncCredentials, timeUp: () -> Boolean = { false }): Map<String, InscriptionCheck>? {
        val checker = inscriptionChecker
        val xpub = creds.xpub
        val coin = Coin.fromId(creds.coin)
        if(xpub == null || !checkApplies(creds.scriptType, coin)) {
            return null
        }
        return withContext(Dispatchers.IO) {
            checker.tryRun(creds.name) {
                runCheck(creds.name, coin, Network.fromId(creds.network), creds.scriptType, emptySet(), timeUp,
                    XpubKey.fromXpub(xpub), prefs.getInt(receiveShownKey(xpub), 0))
            }
        }
    }

    /** The stored results the worker reads to decide whether a wallet needs its check (see [inscriptionCheckNeeded]). */
    suspend fun storedInscriptionResults(name: String): Map<String, InscriptionCheck>? =
        withContext(Dispatchers.IO) { inscriptionChecker.storedResults(name) }

    private fun checkApplies(scriptType: String, coin: Coin): Boolean =
        scriptType != "MWEB" && ChainRules.covers(coin) && !NetworkPolicy.blocked

    private suspend fun runCheck(name: String, coin: Coin, network: Network, scriptType: String, deeper: Set<String>,
                                 timeUp: () -> Boolean, key: Bip32Pub, watchReceiveTo: Int): Map<String, InscriptionCheck> =
        withBackend(coin, network) { client ->
            val listed = WalletScanner(client, walletScriptType(scriptType)).utxos(key, watchReceiveTo = watchReceiveTo).utxos
            //The setting's indexer, opened (and its chain checked) for this run only and closed by a settings change;
            //Built-in and air-gapped open none, and one that cannot be opened is an unreachable indexer, not a failed check
            //The readers' generation first, then the setting and proxy: a change between the two reads is never missed
            val generation = indexerReaders.generation
            val setting = inscriptionSetting(coin, network)
            val proxy = indexerProxy(serverSettings(coin, network))
            checkWithIndexer(inscriptionChecker, name, coin, network, client, listed, setting, proxy, NetworkPolicy.blocked,
                indexerReaders, deeper, timeUp, generation)
        }

    /**
     * The view-only sync credentials (xpub / MWEB scan key, never seeds or spend
     * keys) a session unlock caches under the Android Keystore, so background refresh can check for new funds
     * without the wallet password. Null while background refresh is off.
     */
    private fun syncCredentialsFor(name: String, data: VaultData): SyncCredentials? {
        if(syncStore.intervalMinutes <= 0) return null
        val prior = syncStore.load(name)
        val creds = if(data.scriptType == "MWEB") SyncCredentials(
            name = name, network = data.network, scriptType = "MWEB",
            scanSecretHex = mwebScanSecret(data).toHex(),
            birthHeight = mwebBirthHeight(data),
            baselined = prior?.baselined ?: false,
            knownOutputIds = prior?.knownOutputIds ?: emptyList()
        ) else SyncCredentials(
            name = name, network = data.network, scriptType = data.scriptType, coin = data.coin,
            xpub = accountXpub(data),
            baselined = prior?.baselined ?: false,
            knownTxids = prior?.knownTxids ?: emptyList(),
            inscriptionCheckDue = prior?.inscriptionCheckDue ?: false
        )
        return creds
    }

    /** Balance/history check from a cached xpub alone — the background-sync read path. */
    suspend fun scanXpub(creds: SyncCredentials): WalletSnapshot {
        val xpub = requireNotNull(creds.xpub) { "No xpub" }
        return withBackend(Coin.fromId(creds.coin), Network.fromId(creds.network)) {
            WalletScanner(it, walletScriptType(creds.scriptType)).sync(XpubKey.fromXpub(xpub))
        }
    }

    /** MWEB coin check from a cached scan key alone — the background-sync read path. */
    suspend fun mwebSnapshotFromScanKey(network: Network, scanSecretHex: String, birthHeight: Int): MwebdService.MwebSnapshot {
        val s = litecoinServer(network)
        val service = MwebdService.get(appContext, network, if(s.useProxy) "${s.proxyHost}:${s.proxyPort}" else null)
        return service.snapshot(scanSecretHex.hexToBytes(), birthHeight)
    }

    /**
     * Re-seals an unlocked wallet with an updated label for [txid] (blank deletes).
     * Labels are wallet data, so they live inside the encrypted vault — the session
     * password from unlock re-seals it.
     */
    fun updateLabel(name: String, data: VaultData, password: String, txid: String, label: String): VaultData {
        val clean = label.trim()
        val labels = data.txLabels.toMutableMap()
        if(clean.isEmpty()) labels.remove(txid) else labels[txid] = clean
        val history = data.mwebHistory.map { if(it.txid == txid) it.copy(label = clean) else it }
        val updated = data.copy(txLabels = labels, mwebHistory = history)
        rewriteVault(vaultFile(name), WalletVault.seal(updated, password))
        sessionNames.remove(data)?.let { sessionNames[updated] = it }
        return updated
    }

    /** Bulk label adopt + single re-seal — used by desktop label sync. */
    private fun updateLabels(name: String, data: VaultData, password: String, labels: Map<String, String>): VaultData {
        if(labels.isEmpty()) return data
        val merged = data.txLabels.toMutableMap()
        for((txid, label) in labels) if(label.isNotBlank()) merged[txid] = label.trim()
        val history = data.mwebHistory.map { e -> labels[e.txid]?.let { e.copy(label = it.trim()) } ?: e }
        val updated = data.copy(txLabels = merged, mwebHistory = history)
        rewriteVault(vaultFile(name), WalletVault.seal(updated, password))
        sessionNames.remove(data)?.let { sessionNames[updated] = it }
        return updated
    }

    /** Every label this wallet knows: the vault map plus imported MWEB entry labels. */
    private fun allLabels(data: VaultData): Map<String, String> = buildMap {
        for(e in data.mwebHistory) if(e.label.isNotEmpty()) put(e.txid, e.label)
        putAll(data.txLabels)
    }

    class LabelSyncOutcome(val summary: String, val updated: Map<String, VaultData>)

    /**
     * Phone side of a desktop label sync: matches wallets by master fingerprint, adopts
     * the labels the phone is missing (re-sealing each vault once), sends the desktop
     * what it's missing. Nothing is overwritten — differing labels are kept and counted.
     */
    suspend fun linkSyncLabels(
        pairing: SparrowLink.Pairing,
        wallets: List<Triple<String, VaultData, String>>
    ): LabelSyncOutcome = withContext(Dispatchers.IO) {
        val local = wallets.map { (name, data, _) ->
            SparrowLink.WalletLabels(masterKey(data).fingerprint.toHex(), name, allLabels(data))
        }
        val result = SparrowLink.syncLabels(pairing, local)
        val updated = HashMap<String, VaultData>()
        var applied = 0
        for((name, data, password) in wallets) {
            val fp = masterKey(data).fingerprint.toHex().lowercase()
            result.toPhone[fp]?.let { newLabels ->
                updated[name] = updateLabels(name, data, password, newLabels)
                applied += newLabels.size
            }
        }
        val summary = if(result.matchedWallets.isEmpty()) {
            "The desktop wallet doesn't match any wallet open on this phone (different seed?)"
        } else {
            "Synced ${result.matchedWallets.joinToString()} — $applied label(s) to phone, " +
            "${result.sentToDesktop} to desktop" +
            if(result.conflicts > 0) ". ${result.conflicts} differed and were left unchanged on both." else "."
        }
        LabelSyncOutcome(summary, updated)
    }

    private val contentCache = ContentCache(ContentLoading.CACHE_ENTRIES)

    /**
     * An inscription's content for a preview, read from the chain through the wallet's own server (never an indexer),
     * at most [PreviewRules.MAX_CONTENT_BYTES]. [mintTxid] is the last step of a Doginals chain. Null on any failure and
     * while air-gapped; cancellation propagates. [mintTxid] is passed unchanged as the last step of a Doginals chain. Cached per coin, network and id (see [ContentLoading]).
     */
    suspend fun inscriptionContent(data: VaultData, inscriptionId: String, mintTxid: String): InscriptionContent? {
        val coin = coin(data)
        val network = network(data)
        //Each load opens its own server connection: a screenful of tiles takes turns, a few at a time. Content over the
        //limit comes back as its type and size only (its body is never assembled), so the tile can say what it is
        return ContentLoading.load(contentCache, ContentLoading.key(coin, network, inscriptionId), NetworkPolicy.blocked, contentLoads) {
            withBackend(coin, network) { backend ->
                InscriptionContent.readFromChain(inscriptionId, mintTxid, BackendChainSource(backend), PreviewRules.MAX_CONTENT_BYTES)
            }
        }
    }

    /** Content loads at once, across screens. */
    private val contentLoads = kotlinx.coroutines.sync.Semaphore(2)

    /**
     * The wallet's LitVM account address (see [litVmAddressOf]): derived from its master key, which is zeroed after;
     * only the address comes out. Slow (the seed is stretched): call it off the main thread, when the LitVM screen opens.
     */
    fun litVmAddress(data: VaultData): com.sparrowwallet.mobile.crypto.evm.EvmAddress = litVmAddressOf(data)

    /** LitVM reads by account: a second one asked while one runs shares it. */
    private val litVmReads = Coalescer<String, LitVmRead?>()

    /**
     * Reads [address]'s LitVM account (see [LitVmReader]) through the coin's proxy, on a new client each time (a client
     * remembers a wrong chain) closed when the read ends or is cancelled. Null, with no request, while air-gapped. Only
     * the LitVM screen calls this, during its visit. [known]: the collections' facts kept from the visit's earlier read.
     */
    suspend fun readLitVm(data: VaultData, address: com.sparrowwallet.mobile.crypto.evm.EvmAddress, tilePixels: Double,
                          known: Map<com.sparrowwallet.mobile.crypto.evm.EvmAddress, com.sparrowwallet.mobile.electrum.litvm.Erc721Collection>): LitVmRead? {
        if(NetworkPolicy.blocked) return null
        val proxy = indexerProxy(serverSettings(coin(data), network(data)))
        return litVmReads.run(address.toString()) {
            withContext(Dispatchers.IO) {
                LitVmClients.use(NetworkPolicy.blocked,
                    open = { com.sparrowwallet.mobile.electrum.litvm.LitVmRpc(com.sparrowwallet.mobile.electrum.litvm.LitVmNetwork.LITEFORGE_TESTNET, proxy) },
                    close = { it.close() }) { rpc ->
                    LitVmReader.read(rpc, { (it as com.sparrowwallet.mobile.electrum.litvm.LitVmRpc).verifyChain() }, address, tilePixels, known = known)
                }
            }
        }
    }

    fun network(data: VaultData): Network = Network.fromId(data.network)

    fun coin(data: VaultData): Coin = Coin.fromId(data.coin)

    fun params(data: VaultData): ChainParams = walletParams(coin(data), network(data))

    fun scriptType(data: VaultData): ScriptType = walletScriptType(data.scriptType)

    fun masterKey(data: VaultData): HDKey = walletMasterKey(data)

    fun accountKey(data: VaultData): HDKey = walletAccountKey(data)

    /** The account's derivation as child numbers (hardened ones with the 0x80000000 bit). */
    fun accountPath(data: VaultData): List<Long> = parsePathChildren(data.derivationPath)

    fun receiveAddress(data: VaultData, account: HDKey, index: Int): String =
        Addresses.forKey(account.deriveChild(0).deriveChild(index.toLong()).pubKey, scriptType(data), params(data))

    fun accountXpub(data: VaultData): String = walletAccountXpub(data)

    /** MWEB keys/address, derived exactly like desktop drongo's Keystore (m/1000', scan 0', spend 1'). */
    data class MwebInfo(val firstAddress: String, val scanSecretHex: String, val spendPubKeyHex: String) {
        /** The address only: the scan secret reads every coin of the wallet. */
        override fun toString(): String = "MwebInfo(firstAddress=$firstAddress)"
    }

    // dedicated MWEB wallets use their own account path; other script types pair with
    // the same-seed m/1000' MWEB account, matching desktop's child-wallet convention
    private fun mwebAccountPath(data: VaultData) = if(data.scriptType == "MWEB") data.derivationPath else "m/1000'"

    fun mwebInfo(data: VaultData): MwebInfo {
        val master = masterKey(data)
        val accountPath = mwebAccountPath(data)
        val scanSecret = master.derivePath("$accountPath/0'").privKey
        val spendPubKey = master.derivePath("$accountPath/1'").pubKey
        val first = Addresses.mwebStealth(
            scanSecret, spendPubKey, Addresses.mwebAddressIndex(change = false, nodeIndex = 0), network(data).mwebHrp
        )
        return MwebInfo(first, scanSecret.toHex(), spendPubKey.toHex())
    }

    // ---- Server settings (per coin and network) ----

    /** An Electrum server (host/port) or, with [esploraUrl], an Esplora REST API. */
    data class ServerSettings(
        val host: String,
        val port: Int,
        val useTls: Boolean,
        val allowSelfSigned: Boolean,
        val useProxy: Boolean,
        val proxyHost: String,
        val proxyPort: Int,
        val esploraUrl: String? = null
    )

    /**
     * The preference prefix holding [coin]'s settings: its own, else Litecoin's pre-coin-axis
     * keys when only those exist, so a server saved before the coin axis keeps working.
     */
    private fun settingsPrefix(coin: Coin, network: Network): String {
        val prefix = ServerKeys.prefix(coin, network)
        val legacy = ServerKeys.legacyPrefix(coin, network)
        return if(!prefs.contains("$prefix.host") && legacy != null && prefs.contains("$legacy.host")) legacy else prefix
    }

    /** Whether the user saved their own server; otherwise the public list is used with fallback. */
    fun hasCustomServer(coin: Coin, network: Network): Boolean = prefs.contains("${settingsPrefix(coin, network)}.host")

    private fun lastPublicKey(coin: Coin, network: Network) = "${ServerKeys.prefix(coin, network)}.lastPublic"

    private fun lastPublicHost(coin: Coin, network: Network): String? =
        prefs.getString(lastPublicKey(coin, network), null)
            ?: ServerKeys.legacyPrefix(coin, network)?.let { prefs.getString("$it.lastPublic", null) }

    private fun publicServers(coin: Coin, network: Network) =
        PublicServers.ordered(coin, network, lastPublicHost(coin, network))

    /** The first public server for the coin and network, or null when it has none (your own node only). */
    fun defaultServerSettings(coin: Coin, network: Network): ServerSettings? = publicServers(coin, network).firstOrNull()

    /** The saved server, else the public default; null when the coin needs the user's own server. */
    fun serverSettings(coin: Coin, network: Network): ServerSettings? {
        val p = settingsPrefix(coin, network)
        val d = defaultServerSettings(coin, network)
            ?: if(prefs.contains("$p.host")) ServerSettings("", 50002, true, false, false, "127.0.0.1", 9050)
               else return null
        return ServerSettings(
            prefs.getString("$p.host", d.host)!!,
            prefs.getInt("$p.port", d.port),
            prefs.getBoolean("$p.tls", d.useTls),
            prefs.getBoolean("$p.selfSigned", d.allowSelfSigned),
            prefs.getBoolean("$p.useProxy", d.useProxy),
            prefs.getString("$p.proxyHost", d.proxyHost)!!,
            prefs.getInt("$p.proxyPort", d.proxyPort),
            if(prefs.contains("$p.host")) prefs.getString("$p.esploraUrl", null) else d.esploraUrl
        )
    }

    /** Litecoin always has public servers; MWEB reads its proxy from these settings. */
    private fun litecoinServer(network: Network): ServerSettings =
        serverSettings(Coin.LITECOIN, network) ?: throw IllegalStateException("Litecoin ${network.displayName} has no server")

    fun saveServerSettings(coin: Coin, network: Network, s: ServerSettings) {
        //A proxy change reaches the indexer at once: the readers (a check run's too) and their answers are closed and dropped
        writeServerSettings(prefs, coin, network, s) { indexerReaders.clear() }
    }

    /**
     * Connects with the given settings and returns what identifies the server:
     * [software, protocol] for Electrum, ["Esplora", tip height] for an Esplora API.
     */
    suspend fun testConnection(s: ServerSettings, coin: Coin = Coin.LITECOIN, network: Network = Network.MAINNET): List<String> =
        withContext(Dispatchers.IO) {
            val backend = Backends.open(s, ChainParams.of(coin, network))
            try {
                backend.connect()
                if(backend is ElectrumBackend) backend.serverVersion() else listOf("Esplora", backend.tipHeight().toString())
            } finally {
                backend.close()
            }
        }

    /**
     * Connects to the user's server, or to the first public server that answers (the last one that
     * worked first). Only connecting falls back: once [block] starts, its errors are final, so a
     * broadcast is never sent twice.
     */
    private suspend fun <T> withBackend(coin: Coin, network: Network, block: suspend (ChainBackend) -> T): T =
        withContext(Dispatchers.IO) {
            val custom = hasCustomServer(coin, network)
            val candidates = if(custom) listOfNotNull(serverSettings(coin, network)) else publicServers(coin, network)
            if(candidates.isEmpty()) throw IllegalStateException("${coin.displayName} ${network.displayName} needs your own server")
            val params = ChainParams.of(coin, network)
            val (used, backend) = ServerFallback.firstWorking(candidates) { s ->
                val b = Backends.open(s, params)
                try {
                    b.connect()
                    b
                } catch(e: Exception) {
                    b.close()
                    throw e
                }
            }
            if(!custom) prefs.edit().putString(lastPublicKey(coin, network), used.host).apply()
            try {
                block(backend)
            } finally {
                backend.close()
            }
        }

    /** Full wallet sync: balance, history with deltas, first unused receive index. */
    // ---- last-known snapshot cache: screens render these instantly; auto-refresh is
    // skipped while a snapshot is fresh so switching screens never re-syncs ----
    private val snapshotCache = java.util.concurrent.ConcurrentHashMap<VaultData, WalletSnapshot>()
    private val mwebSnapshotCache = java.util.concurrent.ConcurrentHashMap<VaultData, MwebdService.MwebSnapshot>()
    private val snapshotAt = java.util.concurrent.ConcurrentHashMap<VaultData, Long>()
    private val mwebSnapshotAt = java.util.concurrent.ConcurrentHashMap<VaultData, Long>()

    fun cachedSnapshot(data: VaultData): WalletSnapshot? = snapshotCache[data]
    fun cachedMwebSnapshot(data: VaultData): MwebdService.MwebSnapshot? = mwebSnapshotCache[data]

    /** Forces the next Home visit to refresh — called after a successful send. */
    fun invalidateSnapshots(vararg wallets: VaultData?) {
        for(data in wallets.filterNotNull()) {
            snapshotAt.remove(data)
            mwebSnapshotAt.remove(data)
            lastScan.clear(data)
        }
    }

    /** The Send screen's coin scan, reused by the send it builds while fresh (see [reusableScan]). */
    private val lastScan = ScanCache<VaultData, WalletScanner.UtxoScan>()

    /** Forgets the reused coin scan, after a broadcast failed: the next Preview scans again. */
    fun forgetScan(data: VaultData) = lastScan.clear(data)

    private suspend fun scanCoins(client: ChainBackend, data: VaultData, account: HDKey, scriptType: ScriptType): Pair<WalletScanner.UtxoScan, Long> {
        lastScan.fresh(data, System.currentTimeMillis())?.let { return it }
        val tip = client.tipHeight()
        val scan = WalletScanner(client, scriptType).utxos(account, checkCoinbaseAbove = coinbaseCheckAbove(data, tip))
        lastScan.put(data, scan, tip, System.currentTimeMillis())
        return scan to tip
    }

    /** Transactions never change, so the ones read to decide held coins are kept for the session. */
    private val carrierTxs = java.util.concurrent.ConcurrentHashMap<String, RawTx.Parsed>()
    /**
     * Send's indexer readers, one per coin, network, URL and proxy, opened only through [Indexers.open]; they keep the
     * answers for confirmed coins (see [IndexerReaders]). Cleared when the server or inscription settings change.
     */
    private val indexerReaders get() = sharedIndexerReaders

    /** The inscription settings; saving one clears the indexer readers, so the new choice takes effect at once. */
    private val inscriptionPrefs = InscriptionPrefs(prefs) { indexerReaders.clear() }

    /** The effective setting: Built-in for a coin with nothing saved until the one-time notice is answered. */
    fun inscriptionSetting(coin: Coin, network: Network): InscriptionSetting = inscriptionPrefs.setting(coin, network)

    fun saveInscriptionSetting(coin: Coin, network: Network, setting: InscriptionSetting) {
        inscriptionPrefs.save(coin, network, setting)
        lastScan.clearAll()
    }

    /** The notice's answer ("public" or "builtin"), or null until it is answered. */
    fun inscriptionDefaultChoice(): String? = inscriptionPrefs.defaultChoice()

    fun saveInscriptionDefaultChoice(choice: String) {
        inscriptionPrefs.saveDefaultChoice(choice)
        lastScan.clearAll()
    }

    /** Whether Home shows the one-time notice for this coin and network (see [InscriptionPrefs.noticeDue]). */
    fun inscriptionNoticeDue(coin: Coin, network: Network): Boolean =
        inscriptionPrefs.noticeDue(coin, network)

    /**
     * Decides each ready coin of [scan] (see [classifySend]): from the wallet's stored checks (awaited, so a results file
     * on disk counts after a cold start, and a file holding no results still counts as checked), the effective setting's
     * indexer, asked once for all the ready coins through the coin's proxy, and [CarrierScan] when nothing else decides:
     * its value is the coin's usual postage, or the transaction that made it writes one (a transaction the server cannot
     * give leaves the value rule). Run for every send rather than kept with a reused scan, so a check that finished since
     * counts.
     */
    private suspend fun classifyCoins(client: ChainBackend, data: VaultData, scan: WalletScanner.UtxoScan, tip: Long): CoinStates {
        val coin = coin(data)
        val network = network(data)
        //The rows and the marks the check run kept for coins its last listing left out (such a coin listed here is held
        //by its mark), from one read, so a run saving in between cannot pair one run's rows with another's marks
        val snapshot = if(ChainRules.covers(coin)) loadSendSnapshot(data) else null
        val results = snapshot?.rows
        val marks = snapshot?.marks ?: emptyMap()
        val ready = scan.utxos.filter { spendable(data, tip, it) }.associate { outpoint(it) to it.utxo }
        //The readers' generation first, then the setting and proxy: a change between the two reads is never missed
        val generation = indexerReaders.generation
        val setting = inscriptionSetting(coin, network)
        val proxy = indexerProxy(serverSettings(coin, network))
        return classifySend(ready, results, coin, network, setting, proxy, indexerReaders, NetworkPolicy.blocked,
            { utxo -> creatingTx(client, utxo.txHash)?.let { CarrierScan.hasRunestone(it) || CarrierScan.hasDunestone(it) } == true },
            { utxo -> localMayCarry(coin, client, utxo) }, marks, generation)
    }

    private suspend fun localMayCarry(coin: Coin, client: ChainBackend, utxo: com.sparrowwallet.mobile.electrum.Utxo): Boolean {
        if(utxo.value in CarrierScan.postage(coin)) return true
        return CarrierScan.mayCarry(coin, creatingTx(client, utxo.txHash), utxo.txPos, utxo.value)
    }

    private suspend fun creatingTx(client: ChainBackend, txid: String): RawTx.Parsed? =
        carrierTxs[txid] ?: runCatching { RawTx.parse(client.rawTransaction(txid)) }.getOrNull()?.also { carrierTxs[txid] = it }

    /** Coins confirmed above this height may be immature coinbases; deeper ones are mature either way. */
    private fun coinbaseCheckAbove(data: VaultData, tip: Long): Int =
        (tip - coin(data).longestCoinbaseMaturity(network(data))).toInt()

    /** The send rule for one scanned coin: [minConfirmations] deep, and a coinbase also mature. */
    private fun spendable(data: VaultData, tip: Long, u: WalletScanner.ChainUtxo): Boolean =
        spendableAt(tip, u.utxo.height, minConfirmations, if(u.coinbase) coin(data).coinbaseMaturity(network(data), u.utxo.height) else null)

    fun snapshotFresh(data: VaultData, maxAgeMs: Long = 60_000): Boolean =
        snapshotCache[data] != null &&
            snapshotAt[data]?.let { System.currentTimeMillis() - it < maxAgeMs } == true

    fun mwebSnapshotFresh(data: VaultData, maxAgeMs: Long = 60_000): Boolean =
        mwebSnapshotCache[data] != null &&
            mwebSnapshotAt[data]?.let { System.currentTimeMillis() - it < maxAgeMs } == true

    /** Prefs key per account, from a hash of its xpub so the key itself says nothing about the wallet. */
    private val receiveShownKeys = java.util.concurrent.ConcurrentHashMap<VaultData, String>()

    private fun receiveShownKey(data: VaultData): String = receiveShownKeys.getOrPut(data) { receiveShownKey(accountXpub(data)) }

    /** The same key from the account xpub alone, as the background worker holds it. */
    private fun receiveShownKey(xpub: String): String = "receiveShown." + sha256(xpub.encodeToByteArray()).toHex().take(16)

    /** One past the highest receive index the Receive screen has shown; the sync keeps all of those watched. */
    fun receiveShownTo(data: VaultData): Int = prefs.getInt(receiveShownKey(data), 0)

    fun noteReceiveShown(data: VaultData, index: Int) {
        if(index + 1 > receiveShownTo(data)) {
            prefs.edit().putInt(receiveShownKey(data), index + 1).apply()
        }
    }

    suspend fun syncWallet(data: VaultData, account: HDKey, onProgress: (checked: Int) -> Unit = {}): WalletSnapshot =
        withBackend(coin(data), network(data)) {
            WalletScanner(it, scriptType(data)).sync(account, watchReceiveTo = receiveShownTo(data), onProgress = onProgress)
        }
            .also { snapshotCache[data] = it; snapshotAt[data] = System.currentTimeMillis(); recordTotal(data, it.balance.total) }

    // ---- Sending ----

    /** A signed-but-not-broadcast spend, ready for the user to confirm. */
    data class SendPreview(
        val toAddress: String,
        val amount: Long,       // litoshis actually sent to the destination
        val fee: Long,          // litoshis
        val feeRatePerVb: Long, // litoshis per vbyte used
        val txid: String,
        val txHex: String,
        /** How many of the coins it spends have not been checked for inscriptions; the review warns about them. */
        val unchecked: Int = 0,
        /** How many held coins (included by the user) it spends; the review says so. */
        val held: Int = 0,
        /** The coins it spends, as outpoints, so Sign externally can tell its PSBT spends the same ones. */
        val inputs: List<String> = emptyList()
    )

    /** Returns a user-facing error for an unusable destination address, or null if it's fine. */
    fun validateAddress(address: String, params: ChainParams): String? =
        try {
            Addresses.toScriptPubKey(address, params)
            null
        } catch(e: Exception) {
            e.message ?: "Invalid address"
        }

    /**
     * Scans utxos, selects coins, and builds a fully signed transaction — without
     * broadcasting. The UI shows the resulting fee/amount and only then calls [broadcast].
     * With [sendMax] the whole spendable balance minus fee goes to the destination. Clean coins go first and Unchecked
     * ones only when the Clean ones cannot cover it (a sweep only with [includeUnchecked]); held coins only with
     * [includeHeld] (see [SpendTiers.candidates]).
     */
    suspend fun prepareSend(data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean,
                            includeHeld: Boolean = false, includeUnchecked: Boolean = false): SendPreview {
        val net = network(data)
        val coin = coin(data)
        if(coin == Coin.LITECOIN && isMwebAddress(toAddress, net)) {
            return preparePegIn(data, account, toAddress, amountLitoshis, sendMax, includeUnchecked)
        }
        val params = params(data)
        val destScript = Addresses.toScriptPubKey(toAddress, params)
        return withBackend(coin, net) { client ->
            val feeRate = clampFeeRate(coin, client.feeRate(2))
            val (tiers, changeScript, tip) = collectSpendables(client, data, account, scriptType(data))
            //the tip the coins were listed at says which signature hash the chain takes (Luckycoin's chain id, see ChainSig)
            val built = TxBuilder.build(tiers.candidates(includeHeld, sendMax, includeUnchecked = includeUnchecked), destScript,
                amountLitoshis, feeRate, changeScript,
                sendMax, params, keepOrder = true, chainTip = tip)
            val spent = spentOutpoints(built.tx)
            SendPreview(toAddress, built.amount, built.fee, feeRate, built.tx.txid(), built.hex(), tiers.uncheckedIn(spent), tiers.heldIn(spent), spent)
        }
    }

    /** Coins must have this many confirmations before the wallet will spend them. */
    val minConfirmations = 6

    private fun confirmedEnough(tip: Long, height: Int): Boolean =
        height in 1..(tip - (minConfirmations - 1))

    /** Spendable balance of an MWEB snapshot under the confirmation rule. */
    fun mwebSpendableBalance(snap: MwebdService.MwebSnapshot): Long =
        snap.utxos.filter { !it.spent && confirmedEnough(snap.status.blockHeaderHeight.toLong(), it.height) }
            .sumOf { it.value }

    /**
     * The wallet's ready coins by state (see [classifyCoins]), the change script, and the chain tip the coins were
     * listed at; callers pick from the coins with [SpendTiers.candidates].
     */
    private suspend fun collectSpendables(client: ChainBackend, data: VaultData, account: HDKey,
                                          scriptType: ScriptType): Triple<SpendTiers, ByteArray, Long> {
        val (scan, tip) = scanCoins(client, data, account, scriptType)
        val coin = coin(data)
        val network = network(data)
        val states = classifyCoins(client, data, scan, tip)
        val (tiers, change) = spendables(scan, tip, account, scriptType, minConfirmations, { coin.coinbaseMaturity(network, it) }) { states.state(it) }
        return Triple(tiers, change, tip)
    }

    class UnsignedSendPreview(
        val toAddress: String,
        val amount: Long,
        val fee: Long,
        val feeRatePerVb: Long,
        val txid: String?, // null when signing will change it (see txidKnownBeforeSigning)
        val psbtBase64: String,
        /** Peg-ins: the MWEB extension blob stays here and is re-attached on finalize. */
        val mwebBlob: ByteArray? = null,
        /** How many of the coins it spends have not been checked for inscriptions. */
        val unchecked: Int = 0,
        /** The coins it spends, as outpoints (see [psbtMatchesPreview]). */
        val inputs: List<String> = emptyList()
    )

    /**
     * Builds the same transaction as [prepareSend] but leaves it UNSIGNED, packaged as
     * a BIP174 PSBT for an external signer (desktop Sparrow, hardware wallet). Public
     * destinations only — peg-ins are signed inside the MWEB scanner and can't be
     * externally signed anywhere yet.
     */
    suspend fun prepareSendPsbt(
        data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean, includeHeld: Boolean = false,
        includeUnchecked: Boolean = false
    ): UnsignedSendPreview {
        val net = network(data)
        val coin = coin(data)
        if(coin == Coin.LITECOIN && isMwebAddress(toAddress, net)) {
            return preparePegInPsbt(data, account, toAddress, amountLitoshis, sendMax, includeUnchecked)
        }
        require(externalSigningSupported(coin, net, data.scriptType)) {
            "Signing on another device is not available for this wallet"
        }
        val params = params(data)
        val scriptType = scriptType(data)
        val destScript = Addresses.toScriptPubKey(toAddress, params)
        return withBackend(coin, net) { client ->
            val feeRate = clampFeeRate(coin, client.feeRate(2))
            val (scan, tip) = scanCoins(client, data, account, scriptType)
            val states = classifyCoins(client, data, scan, tip)
            val accountPath = parsePathChildren(data.derivationPath)
            val pathByKey = HashMap<HDKey, List<Long>>()
            //the same tiers as prepareSend: Clean first, Unchecked on shortfall, held only when included
            val tiers = SpendTiers.of(scan.utxos
                .filter { spendable(data, tip, it) }
                .map {
                    val key = account.deriveChild(it.chain.toLong()).deriveChild(it.index.toLong())
                    pathByKey[key] = accountPath + listOf(it.chain.toLong(), it.index.toLong())
                    SpendableInput(it.utxo.txHash, it.utxo.txPos.toLong(), it.utxo.value, key, scriptType) to states.state(it)
                })
            val changeKey = account.deriveChild(1).deriveChild(scan.nextChangeIndex.toLong())
            val selected = TxBuilder.buildUnsigned(
                tiers.candidates(includeHeld, sendMax, includeUnchecked = includeUnchecked), destScript, amountLitoshis, feeRate,
                Scripts.outputScript(changeKey.pubKey, scriptType), sendMax, params, keepOrder = true
            )
            //every chosen coin carries the transaction that made it: Legacy signers need it, and a
            //signer checks a multi-input fee against it (rawTransaction verifies each one's txid).
            //Taproot inputs skip it: the signature commits to the witness utxo, Psbt.create drops it
            val prevTxs = HashMap<String, ByteArray>()
            val withPrevTxs = selected.inputs.map {
                SpendableInput(it.txid, it.vout, it.value, it.key, it.scriptType,
                    if(it.scriptType == ScriptType.P2TR) null
                        else prevTxs.getOrPut(it.txid) { client.rawTransaction(it.txid) })
            }
            val unsigned = UnsignedBuild(selected.tx, withPrevTxs, selected.amount, selected.fee, selected.change, selected.coin)
            val psbt = Psbt.create(
                unsigned, masterKey(data).fingerprint,
                pathFor = { pathByKey.getValue(it.key) },
                changeIndex = if(unsigned.change > 0) 1 else null,
                changePubKey = changeKey.pubKey,
                changePath = accountPath + listOf(1L, scan.nextChangeIndex.toLong())
            )
            UnsignedSendPreview(toAddress, unsigned.amount, unsigned.fee, unsigned.paidFeeRate,
                unsigned.tx.txid().takeIf { txidKnownBeforeSigning(data.scriptType) }, Psbt.toBase64(psbt),
                unchecked = tiers.uncheckedIn(selected.inputs.map { outpoint(it) }), inputs = selected.inputs.map { outpoint(it) })
        }
    }

    /** Peg-in as a PSBT: mwebd builds/signs the MWEB side; the public inputs go out for
     *  external signing; the blob stays in [UnsignedSendPreview.mwebBlob] until finalize. */
    private suspend fun preparePegInPsbt(
        data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean, includeUnchecked: Boolean
    ): UnsignedSendPreview {
        val net = network(data)
        val program = Bech32.decodeWitness(toAddress.trim()).program
        require(program.size == 66) { "Not a valid MWEB address" }
        requirePegInWallet(data)
        val feeRate = withBackend(Coin.LITECOIN, net) { clampFeeRate(Coin.LITECOIN, it.feeRate(2)) }
        val service = mwebd(data)

        val (scan, tip, states) = withBackend(Coin.LITECOIN, net) {
            val tip = it.tipHeight()
            val scan = WalletScanner(it, ScriptType.P2WPKH).utxos(account, checkCoinbaseAbove = coinbaseCheckAbove(data, tip))
            Triple(scan, tip, classifyCoins(it, data, scan, tip))
        }
        val accountPath = parsePathChildren(data.derivationPath)
        val pathByKey = HashMap<HDKey, List<Long>>()
        //The same tiers as preparePegIn: as an ordinary send (Clean first, Unchecked on shortfall, a sweep's only when
        //included); never held ones, since whatever a coin carries cannot follow it into MWEB
        val tiers = SpendTiers.of(scan.utxos
            .filter { spendable(data, tip, it) }
            .map {
                val key = account.deriveChild(it.chain.toLong()).deriveChild(it.index.toLong())
                pathByKey[key] = accountPath + listOf(it.chain.toLong(), it.index.toLong())
                SpendableInput(it.utxo.txHash, it.utxo.txPos.toLong(), it.utxo.value, key, ScriptType.P2WPKH) to states.state(it)
            })
        val candidates = tiers.candidates(includeHeld = false, sendMax = sendMax, pegIn = true, includeUnchecked = includeUnchecked)
        val changeKey = account.deriveChild(1).deriveChild(scan.nextChangeIndex.toLong())
        val changeScript = Scripts.p2wpkhOutput(changeKey.pubKey)

        val mwebFeeEst = MwebdService.estimateMwebFee(
            listOf(MwebdService.OutSpec(0, program)), feeRate * 1000, includeChange = false)
        val probeScriptSize = service.createPegIn(
            mwebScanSecret(data), mwebSpendSecret(data), program, TxBuilder.DUST_LIMIT, feeRate * 1000
        ).peginScript.size

        var effectiveRate = feeRate
        var attempt = 0
        while(true) {
            attempt++
            val amount = if(sendMax) {
                val total = candidates.sumOf { it.value }
                val publicFee = TxBuilder.estimateVsize(candidates.size, listOf(probeScriptSize)) * effectiveRate
                val sweep = total - publicFee - mwebFeeEst
                require(sweep >= TxBuilder.DUST_LIMIT) { "Balance is too small to cover the peg-in fees" }
                sweep
            } else {
                amountLitoshis
            }
            val template = service.createPegIn(
                mwebScanSecret(data), mwebSpendSecret(data), program, amount, feeRate * 1000)
            val unsigned = TxBuilder.buildUnsigned(
                candidates, template.peginScript, template.peginValue, effectiveRate, changeScript, sendMax,
                ChainParams.of(Coin.LITECOIN, net), keepOrder = true)
            if(sendMax) {
                require(unsigned.tx.outputs[0].value == template.peginValue) {
                    "Peg-in max calculation mismatch — enter a specific amount instead"
                }
            }
            val mwebFee = template.peginValue - amount
            val canonicalVsize = TxBuilder.estimateVsize(
                unsigned.inputs.size, unsigned.tx.outputs.map { it.script.size })
            val requiredFee = (canonicalVsize + 41L) * feeRate + mwebFee
            if(unsigned.fee + mwebFee >= requiredFee || attempt >= 4) {
                val psbt = Psbt.create(
                    unsigned, masterKey(data).fingerprint,
                    pathFor = { pathByKey.getValue(it.key) },
                    changeIndex = if(unsigned.change > 0) 1 else null,
                    changePubKey = changeKey.pubKey,
                    changePath = accountPath + listOf(1L, scan.nextChangeIndex.toLong())
                )
                return UnsignedSendPreview(toAddress, amount, unsigned.fee + mwebFee, feeRate,
                    unsigned.tx.txid(), Psbt.toBase64(psbt), template.mwebBlob,
                    unchecked = tiers.uncheckedIn(unsigned.inputs.map { outpoint(it) }), inputs = unsigned.inputs.map { outpoint(it) })
            }
            effectiveRate += (requiredFee - (unsigned.fee + mwebFee) + canonicalVsize - 1) / canonicalVsize
        }
    }

    /**
     * Signs whatever inputs of a PSBT belong to this wallet (matched by master
     * fingerprint and derivation path) — the phone acting as the external signer
     * for a coordinator like desktop Sparrow or a watch-only wallet.
     */
    fun signPsbt(data: VaultData, psbt: ByteArray): SignedPsbt {
        //the phone may sign offline: the tip is the one its last sync saw, if any (ChainSig.of says what an unknown one means)
        val signed = PsbtSigner.sign(Psbt.parse(psbt), masterKey(data), params(data), accountPath(data), scriptType(data),
            chainTip = lastKnownTip(cachedSnapshot(data)))
        return SignedPsbt(signed, Psbt.toBase64(signed))
    }

    /** The highest block a snapshot's history shows (a confirmed entry's height plus its confirmations, less one); null when none. */
    private fun lastKnownTip(snapshot: WalletSnapshot?): Long? =
        snapshot?.history?.filter { it.height > 0 && it.confirmations > 0 }
            ?.maxOfOrNull { it.height.toLong() + it.confirmations - 1 }

    /** What a PSBT does, for the user to check before [signPsbt]. */
    fun reviewPsbt(data: VaultData, psbt: ByteArray): PsbtReview =
        PsbtSigner.review(Psbt.parse(psbt), masterKey(data), params(data), accountPath(data), scriptType(data))

    class SignedPsbt(val bytes: ByteArray, val base64: String)

    private fun parsePathChildren(path: String): List<Long> =
        path.trim().split("/").drop(1).filter { it.isNotEmpty() }.map {
            val hardened = it.endsWith("'") || it.endsWith("h") || it.endsWith("H")
            (if(hardened) it.dropLast(1) else it).toLong() + if(hardened) 0x80000000L else 0L
        }

    /** Finalizes an externally signed PSBT (base64) and broadcasts it; returns the txid.
     *  [mwebBlob] re-attaches a peg-in's MWEB extension that never left the phone. */
    suspend fun finalizeAndBroadcast(coin: Coin, network: Network, psbtBase64: String, mwebBlob: ByteArray? = null): String {
        val tx = Psbt.finalize(Psbt.parse(Psbt.fromBase64(psbtBase64)))
        tx.mwebExtension = mwebBlob
        return broadcast(coin, network, tx.serialize().toHex())
    }

    /**
     * Broadcasts a previously prepared transaction; returns the txid the server accepted. A failure, of the connect as
     * of the broadcast, is a [BroadcastFailedException]: its message is one of the app's own sentences
     * ([BroadcastFailure]), never the server's text, so the Send and PSBT screens can show it as it is.
     */
    suspend fun broadcast(coin: Coin, network: Network, txHex: String): String =
        broadcastWithFixedFailure({ block -> withBackend(coin, network, block) }, txHex)

    /** Current fee rate in the coin's sats (lits) per vbyte, for up-front estimates on the send screens. */
    suspend fun currentFeeRate(coin: Coin, network: Network): Long =
        withBackend(coin, network) { clampFeeRate(coin, it.feeRate(2)) }

    /** Spendable utxo values (confirmation rule applied), largest first — for live estimates. */
    suspend fun sendCoins(data: VaultData, account: HDKey): SendCoins =
        withBackend(coin(data), network(data)) { client ->
            val tip = client.tipHeight()
            val coin = coin(data)
            val scan = WalletScanner(client, scriptType(data)).utxos(account, checkCoinbaseAbove = coinbaseCheckAbove(data, tip))
            lastScan.put(data, scan, tip, System.currentTimeMillis())
            val network = network(data)
            val states = classifyCoins(client, data, scan, tip)
            SendCoins.split(scan.utxos.map {
                ScannedCoin(it.utxo.value, it.utxo.height, if(it.coinbase) coin.coinbaseMaturity(network, it.utxo.height) else null,
                    states.state(it), states.inscribed(it))
            }, tip, minConfirmations)
        }.let {
            it.copy(spendable = it.spendable.sortedDescending(), held = it.held.sortedDescending(), unchecked = it.unchecked.sortedDescending())
        }

    // ---- Sparrow Link (desktop ↔ mobile transfer) ----

    /**
     * Writes this wallet as a desktop-Sparrow-compatible encrypted file for pushing over
     * the link; the vault password doubles as the file password, so the wallet opens on
     * desktop with the same password it has here. Argon2 is memory-hard — a few seconds.
     */
    suspend fun exportSparrowWallet(name: String, data: VaultData, password: String): ByteArray =
        withContext(Dispatchers.Default) {
            val net = network(data)
            SparrowExport.exportFile(
                name = name,
                network = net.id,
                seedType = data.seedType,
                mnemonic = data.mnemonic,
                derivationPath = data.derivationPath,
                masterFingerprint = masterKey(data).fingerprint.toHex(),
                xpub = accountXpub(data),
                password = password,
                coin = coin(data),
                //MWEB wallets keep today's export unchanged; sending them to desktop is a separate question
                scriptType = data.scriptType.takeIf { it != "MWEB" } ?: "P2WPKH"
            )
        }

    /** Phone side of desktop-push: connects and receives the wallet (plus linked private wallet, if sent). */
    suspend fun linkReceiveWallet(pairing: SparrowLink.Pairing): SparrowLink.ReceivedPayload =
        withContext(Dispatchers.IO) { SparrowLink.receiveWallet(pairing) }

    /** Phone side of desktop-pull: connects and sends the exported wallet file. */
    suspend fun linkSendWallet(pairing: SparrowLink.Pairing, name: String, fileBytes: ByteArray) =
        withContext(Dispatchers.IO) { SparrowLink.sendWallet(pairing, name, fileBytes) }

    // ---- MWEB scanning (mwebd on-device) ----

    private suspend fun mwebd(data: VaultData): MwebdService = withContext(Dispatchers.IO) {
        val net = network(data)
        val s = litecoinServer(net)
        MwebdService.get(appContext, net, if(s.useProxy) "${s.proxyHost}:${s.proxyPort}" else null)
    }

    /** Current mwebd sync heights (starts the daemon on first call; it syncs from LTC peers). */
    suspend fun mwebStatus(data: VaultData): MwebdService.SyncStatus = mwebd(data).status()

    /**
     * First block the wallet could have coins in, from the desktop-imported history —
     * lets the coin scan skip trial-decrypting everything older (with a reorg margin).
     */
    private fun mwebBirthHeight(data: VaultData): Int =
        data.mwebHistory.mapNotNull { entry -> entry.height.takeIf { it > 0 } }
            .minOrNull()?.let { (it - 1000).coerceAtLeast(0) } ?: 0

    /** Scans for this wallet's MWEB coins and their spent state, plus sync status. */
    suspend fun mwebSync(data: VaultData): MwebdService.MwebSnapshot {
        val scanSecret = masterKey(data).derivePath("${mwebAccountPath(data)}/0'").privKey
        return mwebd(data).snapshot(scanSecret, mwebBirthHeight(data))
            .also {
                mwebSnapshotCache[data] = it; mwebSnapshotAt[data] = System.currentTimeMillis()
                recordTotal(data, it.confirmed + it.unconfirmed)
            }
    }

    fun isMwebAddress(address: String, network: Network): Boolean =
        address.trim().lowercase().startsWith(network.mwebHrp + "1")

    /** The wallet's MWEB change address (stealth index 0) — distinguishes change from real receives. */
    fun mwebChangeAddress(data: VaultData): String =
        Addresses.mwebStealth(mwebScanSecret(data), mwebSpendPub(data), 0, network(data).mwebHrp)

    private fun mwebScanSecret(data: VaultData) = masterKey(data).derivePath("${mwebAccountPath(data)}/0'").privKey
    private fun mwebSpendSecret(data: VaultData) = masterKey(data).derivePath("${mwebAccountPath(data)}/1'").privKey
    private fun mwebSpendPub(data: VaultData) = masterKey(data).derivePath("${mwebAccountPath(data)}/1'").pubKey

    /** A signed-but-not-broadcast MWEB spend (peg-out or MWEB→MWEB), awaiting confirmation. */
    class MwebSendPreview(
        val toAddress: String,
        val amount: Long,
        val fee: Long,
        val pegOut: Boolean,
        val rawTx: ByteArray
    )

    /**
     * Builds and signs a spend from an MWEB wallet through the on-phone daemon:
     * an LTC destination is a peg-out (funds leave via the kernel and mature on the
     * public side), an MWEB destination stays fully confidential.
     */
    suspend fun prepareMwebSend(data: VaultData, toAddress: String, amountLitoshis: Long, sendMax: Boolean): MwebSendPreview {
        val net = network(data)
        val scanSecret = mwebScanSecret(data)
        val spendPub = mwebSpendPub(data)
        val service = mwebd(data)

        val destScript: ByteArray
        val pegOut: Boolean
        if(isMwebAddress(toAddress, net)) {
            destScript = Bech32.decodeWitness(toAddress.trim()).program
            require(destScript.size == 66) { "Not a valid MWEB address" }
            pegOut = false
        } else {
            destScript = Addresses.toScriptPubKey(toAddress, net)
            pegOut = true
        }

        val feeRatePerKb = withBackend(Coin.LITECOIN, net) { clampFeeRate(Coin.LITECOIN, it.feeRate(2)) } * 1000
        val snapshot = service.snapshot(scanSecret, mwebBirthHeight(data))
        // map each coin's stealth address back to its index (change = 0, receive i = i+1)
        val indexByAddress = buildMap {
            put(Addresses.mwebStealth(scanSecret, spendPub, 0, net.mwebHrp), 0)
            for(i in 0 until 20) {
                put(Addresses.mwebStealth(scanSecret, spendPub, i + 1, net.mwebHrp), i + 1)
            }
        }
        val coins = snapshot.utxos
            .filter { !it.spent && confirmedEnough(snapshot.status.blockHeaderHeight.toLong(), it.height) }
            .mapNotNull { u -> indexByAddress[u.address]?.let { MwebdService.SpendCoin(u.outputId, it, u.value) } }
            .sortedByDescending { it.value }
        val total = coins.sumOf { it.value }
        require(coins.isNotEmpty()) {
            "No private coins with $minConfirmations+ confirmations yet — recent receives need to mature"
        }

        val dest = MwebdService.OutSpec(amountLitoshis, destScript)
        val feeNoChange = MwebdService.estimateMwebFee(listOf(dest), feeRatePerKb, false)
        val feeWithChange = MwebdService.estimateMwebFee(listOf(dest), feeRatePerKb, true)

        val amount = if(sendMax) total - feeNoChange else amountLitoshis
        require(amount > 0) { "Amount too small" }

        val selected = ArrayList<MwebdService.SpendCoin>()
        var selectedTotal = 0L
        for(coin in coins) {
            selected.add(coin)
            selectedTotal += coin.value
            if(selectedTotal >= amount + feeWithChange) break
        }
        if(sendMax) {
            selected.clear(); selected.addAll(coins); selectedTotal = total
        }

        val outputs = ArrayList<MwebdService.OutSpec>()
        outputs.add(MwebdService.OutSpec(amount, destScript))
        var fee = feeNoChange
        val changeCost = feeWithChange - feeNoChange
        val surplus = selectedTotal - amount - feeWithChange
        when {
            sendMax -> { /* exact: amount = total - feeNoChange */ }
            surplus > changeCost -> {
                val changeProgram = Bech32.decodeWitness(
                    Addresses.mwebStealth(scanSecret, spendPub, 0, net.mwebHrp)
                ).program
                outputs.add(MwebdService.OutSpec(surplus, changeProgram))
                fee = feeWithChange
            }
            selectedTotal >= amount + feeNoChange -> fee = selectedTotal - amount // tiny surplus burns as fee
            else -> throw IllegalArgumentException(
                "Insufficient MWEB funds: need ${amount + feeWithChange} litoshis, have $selectedTotal")
        }

        val result = service.create(scanSecret, mwebSpendSecret(data), selected, outputs, feeRatePerKb)
        return MwebSendPreview(toAddress, amount, fee, pegOut, result.rawTx)
    }

    suspend fun broadcastMweb(data: VaultData, rawTx: ByteArray): String = mwebd(data).broadcastRaw(rawTx)

    /**
     * Peg-in: a public wallet paying an MWEB address. mwebd builds the confidential side
     * and hands back a kernel-bound peg-in output, which is then funded and signed like a
     * normal transaction, with the MWEB blob attached.
     */
    /** Peg-ins move Litecoin into MWEB; their fee sizing assumes Native Segwit inputs. */
    private fun requirePegInWallet(data: VaultData) {
        require(coin(data) == Coin.LITECOIN && scriptType(data) == ScriptType.P2WPKH) {
            "Moving coins into MWEB needs a Native Segwit Litecoin wallet"
        }
    }

    private suspend fun preparePegIn(data: VaultData, account: HDKey, toAddress: String, amountLitoshis: Long, sendMax: Boolean,
                                     includeUnchecked: Boolean): SendPreview {
        val net = network(data)
        val program = Bech32.decodeWitness(toAddress.trim()).program
        require(program.size == 66) { "Not a valid MWEB address" }

        requirePegInWallet(data)
        val feeRate = withBackend(Coin.LITECOIN, net) { clampFeeRate(Coin.LITECOIN, it.feeRate(2)) }
        //As an ordinary send: Clean coins, then the unchecked ones (in that order, so only on shortfall; a sweep's only
        //when included); never held ones: whatever a coin carries, ord cannot follow it into MWEB
        val (tiers, changeScript) = withBackend(Coin.LITECOIN, net) { collectSpendables(it, data, account, ScriptType.P2WPKH) }
        val candidates = tiers.candidates(includeHeld = false, sendMax = sendMax, pegIn = true, includeUnchecked = includeUnchecked)
        val service = mwebd(data)

        // Relay rules charge fee on the MWEB blob's bytes too (min relay = size × rate),
        // so the public side must pay for base bytes AND blob bytes. Build, check the fee
        // against the actual serialized size, and bump the effective rate until it clears.
        val mwebFeeEst = MwebdService.estimateMwebFee(
            listOf(MwebdService.OutSpec(0, program)), feeRate * 1000, includeChange = false)
        val probe = service.createPegIn(
            mwebScanSecret(data), mwebSpendSecret(data), program, TxBuilder.DUST_LIMIT, feeRate * 1000)
        val probeScriptSize = probe.peginScript.size

        var effectiveRate = feeRate
        var attempt = 0
        while(true) {
            attempt++
            val amount = if(sendMax) {
                val total = candidates.sumOf { it.value }
                val publicFee = TxBuilder.estimateVsize(candidates.size, listOf(probeScriptSize)) * effectiveRate
                val sweep = total - publicFee - mwebFeeEst
                require(sweep >= TxBuilder.DUST_LIMIT) { "Balance is too small to cover the peg-in fees" }
                sweep
            } else {
                amountLitoshis
            }

            val template = service.createPegIn(
                mwebScanSecret(data), mwebSpendSecret(data), program, amount, feeRate * 1000
            )
            //a peg-in is Litecoin's, which signs with no chain id whatever its tip
            val built = TxBuilder.build(candidates, template.peginScript, template.peginValue,
                effectiveRate, changeScript, sendMax, ChainParams.of(Coin.LITECOIN, net), keepOrder = true, chainTip = null)
            if(sendMax) {
                require(built.tx.outputs[0].value == template.peginValue) {
                    "Peg-in max calculation mismatch — enter a specific amount instead"
                }
            }
            val canonicalVsize = built.vsize.toLong() // before the blob attaches
            built.tx.mwebExtension = template.mwebBlob
            val mwebFee = template.peginValue - amount

            // node policy: a peg-in also pays for the future HogEx input that spends the
            // pegin output (41 vB) — same allowance desktop's MwebFeeEstimator adds
            val requiredFee = (canonicalVsize + 41L) * feeRate + mwebFee
            if(built.fee + mwebFee >= requiredFee || attempt >= 4) {
                return SendPreview(
                    toAddress, amount,
                    built.fee + mwebFee, // public fee (incl HogEx allowance) + mweb fee
                    feeRate, built.tx.txid(), built.hex(),
                    unchecked = tiers.uncheckedIn(spentOutpoints(built.tx)), inputs = spentOutpoints(built.tx)
                )
            }
            val shortfall = requiredFee - (built.fee + mwebFee)
            effectiveRate += (shortfall + canonicalVsize - 1) / canonicalVsize
        }
    }
}

/** The process's own scope for work a screen starts but must not lose by being left (Check deeper's xpub derivation). */
private val backgroundScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
private val derivingXpubs = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<String>>()

private val inscriptionCheckerLock = Any()
@Volatile private var inscriptionCheckerInstance: InscriptionChecker? = null

/** The one [InscriptionChecker] (and so one [InscriptionStore]) of the process. */
private fun sharedInscriptionChecker(context: Context): InscriptionChecker =
    inscriptionCheckerInstance ?: synchronized(inscriptionCheckerLock) {
        inscriptionCheckerInstance ?: InscriptionChecker(
            InscriptionStore(File(context.filesDir, "inscriptions"), KeystoreCipher(InscriptionStore.KEY_ALIAS))
        ).also { inscriptionCheckerInstance = it }
    }

/**
 * Send's indexer readers for the whole process (Home and the background worker each build a repository), so a server or
 * inscription setting saved in one clears the readers the other would use.
 */
private val sharedIndexerReaders = IndexerReaders()

/**
 * Writes a coin's server settings to [prefs], then tells [changed] (the app clears the indexer readers, so a proxy turned
 * on or off reaches the indexer at once).
 */
internal fun writeServerSettings(prefs: android.content.SharedPreferences, coin: Coin, network: Network, s: WalletRepository.ServerSettings,
                                 changed: () -> Unit) {
    val p = ServerKeys.prefix(coin, network)
    prefs.edit()
        .putString("$p.host", s.host).putInt("$p.port", s.port)
        .putBoolean("$p.tls", s.useTls).putBoolean("$p.selfSigned", s.allowSelfSigned)
        .putBoolean("$p.useProxy", s.useProxy)
        .putString("$p.proxyHost", s.proxyHost).putInt("$p.proxyPort", s.proxyPort)
        .putString("$p.esploraUrl", s.esploraUrl)
        .apply()
    changed()
}
