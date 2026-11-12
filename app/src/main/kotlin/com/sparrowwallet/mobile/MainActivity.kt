package com.sparrowwallet.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import com.sparrowwallet.mobile.crypto.BackendKind
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Checkbox
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sparrowwallet.mobile.crypto.Coin
import com.sparrowwallet.mobile.crypto.ElectrumSeed
import com.sparrowwallet.mobile.crypto.ScriptType
import com.sparrowwallet.mobile.crypto.HDKey
import com.sparrowwallet.mobile.crypto.Network
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.PaymentUri
import com.sparrowwallet.mobile.crypto.Psbt
import com.sparrowwallet.mobile.electrum.WalletSnapshot
import com.sparrowwallet.mobile.storage.SparrowLink
import com.sparrowwallet.mobile.storage.VaultData
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirGapMode.load(getSharedPreferences("settings", MODE_PRIVATE))
        if(applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            com.sparrowwallet.mobile.electrum.HttpEndpoint.timingLog = { android.util.Log.d("SparrowHttp", it) }
        }
        val repo = WalletRepository(applicationContext)
        SyncWorker.schedule(applicationContext, repo.syncStore.intervalMinutes)
        setContent { App(repo) }
    }
}

internal sealed interface Screen {
    data object WalletList : Screen
    data class Create(val mnemonic: String) : Screen {
        /** Without the new seed words. */
        override fun toString(): String = "Create"
    }
    data object Restore : Screen
    data object RestoreFromApp : Screen
    data class ImportSparrow(val payload: SparrowLink.ReceivedPayload) : Screen
    data class LinkReceive(val pairing: SparrowLink.Pairing) : Screen
    data class LinkSend(val pairing: SparrowLink.Pairing) : Screen
    data class Unlock(val name: String) : Screen
    /**
     * A wallet family unlocks together and shows one member, the one on [layer]. [linkedName]/[linkedData] are the
     * shown member's MWEB counterpart (see [WalletFamily.mwebCounterpart]), as the linked pair's were.
     */
    data class Home(
        val family: WalletFamily,
        val layer: Layer,
        /** Session password from unlock — needed to re-seal the vault on label edits. */
        val password: String = ""
    ) : Screen {
        /** A wallet on its own. */
        constructor(name: String, data: VaultData, password: String = "") :
            this(WalletFamily(Member(name, data)), Layer.PUBLIC, password)

        init { require(family.member(layer) != null) { "No wallet on the $layer layer" } }

        val name: String get() = family.member(layer)!!.name
        val data: VaultData get() = family.member(layer)!!.data
        val linkedName: String? get() = family.mwebCounterpart(layer)?.name
        val linkedData: VaultData? get() = family.mwebCounterpart(layer)?.data

        /** The same family on another of its layers; null for the shown layer or one it does not have. */
        fun shown(layer: Layer): Home? = if(layer != this.layer && family.member(layer) != null) copy(layer = layer) else null

        /** This Home with each member named in [updated] holding its new vault. */
        fun withData(updated: Map<String, VaultData>): Home = copy(family = family.withData(updated))

        /** Names and layer only: the vaults hold the seeds, and the password is the session's. */
        override fun toString(): String = "Home($family, layer=$layer)"
    }
    data class Send(val home: Home) : Screen
    data class Receive(val home: Home, val startIndex: Int) : Screen
    data class Server(val home: Home) : Screen
    data class Details(val home: Home) : Screen
    data class TxDetail(val home: Home, val tx: TxInfo) : Screen
    data class LabelSync(val home: Home, val pairing: SparrowLink.Pairing) : Screen
    data class PsbtSign(val home: Home, val unsigned: WalletRepository.UnsignedSendPreview) : Screen
    data class SignPsbt(val home: Home) : Screen
    data class ExportToDesktop(val home: Home) : Screen
    data class Collectibles(val home: Home) : Screen
    data class Inscription(val home: Home, val tile: CollectibleTile) : Screen
    /** The wallet's read-only LitVM account; [LitVmNft] is one of its NFTs, by tile key (the read stays in the visit). */
    data class LitVm(val home: Home) : Screen
    data class LitVmNft(val home: Home, val tileKey: String) : Screen
}

/** A wallet's pending PSBT is kept under its public side's name: a peg-in starts from the private side. */
internal fun pendingPsbtKey(home: Screen.Home): String =
    if(home.data.scriptType == "MWEB") home.linkedName ?: home.name else home.name

/** The wallets Send pays from, (public, private): both layers of an MWEB pair, else the shown wallet alone. */
internal fun sendWallets(home: Screen.Home): Pair<VaultData?, VaultData?> =
    (if(home.data.scriptType != "MWEB") home.data
     else home.linkedData?.takeIf { it.scriptType != "MWEB" }) to
    (if(home.data.scriptType == "MWEB") home.data
     else home.linkedData?.takeIf { it.scriptType == "MWEB" })

/** The wallet Sign PSBT signs with: the public side of an MWEB pair, else the shown wallet. */
internal fun signingWallet(home: Screen.Home): VaultData =
    if(home.data.scriptType != "MWEB") home.data
    else home.linkedData?.takeIf { it.scriptType != "MWEB" } ?: home.data

/** The wallets label sync sends, as (name, vault, password): the shown wallet, then its MWEB counterpart. */
internal fun labelSyncWallets(home: Screen.Home): List<Triple<String, VaultData, String>> = buildList {
    add(Triple(home.name, home.data, home.password))
    val linkedName = home.linkedName
    val linkedData = home.linkedData
    if(linkedName != null && linkedData != null) {
        add(Triple(linkedName, linkedData, home.password))
    }
}

/** Home after a label sync: only the wallets it sent ([labelSyncWallets]) take the vaults that came back. */
internal fun afterLabelSync(home: Screen.Home, updated: Map<String, VaultData>): Screen.Home =
    home.withData(updated.filterKeys { it == home.name || it == home.linkedName })

/** Where the system Back gesture goes: a wallet's screens return to its Home, the rest to the list. */
internal fun backTarget(screen: Screen): Screen? = when(screen) {
    is Screen.WalletList -> null
    is Screen.Home -> Screen.WalletList
    is Screen.Send -> screen.home
    is Screen.Receive -> screen.home
    is Screen.Server -> screen.home
    is Screen.Details -> screen.home
    is Screen.TxDetail -> screen.home
    is Screen.LabelSync -> screen.home
    is Screen.PsbtSign -> screen.home
    is Screen.SignPsbt -> screen.home
    is Screen.ExportToDesktop -> screen.home
    is Screen.Collectibles -> screen.home
    is Screen.Inscription -> Screen.Collectibles(screen.home)
    is Screen.LitVm -> screen.home
    is Screen.LitVmNft -> Screen.LitVm(screen.home)
    is Screen.Create, is Screen.Restore, is Screen.RestoreFromApp, is Screen.ImportSparrow, is Screen.LinkReceive,
    is Screen.LinkSend, is Screen.Unlock -> Screen.WalletList
}

/**
 * The wallet whose Collectibles visit [screen] belongs to: the grid and its detail screens share one visit (scroll
 * position, failed previews); any other screen ends it. Null outside the visit.
 */
internal fun collectiblesVisit(screen: Screen): String? = when(screen) {
    is Screen.Collectibles -> screen.home.name
    is Screen.Inscription -> screen.home.name
    else -> null
}

/** The wallet whose LitVM visit [screen] belongs to: the account screen and its NFT details share one (the read, the grid's place). */
internal fun litVmVisit(screen: Screen): String? = when(screen) {
    is Screen.LitVm -> screen.home.name
    is Screen.LitVmNft -> screen.home.name
    else -> null
}

@Composable
private fun App(repo: WalletRepository) {
    var screen by remember { mutableStateOf<Screen>(Screen.WalletList) }
    // held above the grid and the detail screen, so going back to the grid keeps its place and its failed previews
    val visitWallet = collectiblesVisit(screen)
    val visit = remember(visitWallet) { visitWallet?.let { CollectiblesVisit() } }
    val litVmWallet = litVmVisit(screen)
    val litVm = remember(litVmWallet) { litVmWallet?.let { LitVmVisit() } }
    // leaving the LitVM visit (or the app) cancels its read and closes its client
    androidx.compose.runtime.DisposableEffect(litVm) { onDispose { litVm?.close() } }
    var airGapped by remember { mutableStateOf(NetworkPolicy.blocked) }
    // work that outlives the screen that started it (a new Taproot wallet's first sync)
    val appScope = rememberCoroutineScope()
    BackHandler(enabled = backTarget(screen) != null) { backTarget(screen)?.let { screen = it } }

    // one password opens a wallet family: silently unlock the other members with the same
    // password so switching between layers needs no re-entry
    fun homeFor(name: String, data: VaultData, password: String): Screen.Home {
        val opened = WalletFamily.open(name, data, repo.listWallets()) { runCatching { repo.unlock(it, password) }.getOrNull() }
        // a member deleted while the family was opened is not shown: what its Home keeps would write a deleted wallet back
        if(opened.family.names.any { !repo.walletVaultExists(it) }) throw WalletGoneException()
        return Screen.Home(opened.family, opened.layer, password)
    }

    // the layer the current screen belongs to drives the whole color world:
    // Litecoin blue for public, copper for private (MWEB)
    val privateLayer = when(val s = screen) {
        is Screen.Home -> s.data.scriptType == "MWEB"
        is Screen.Send -> s.home.data.scriptType == "MWEB"
        is Screen.Receive -> s.home.data.scriptType == "MWEB"
        is Screen.Server -> s.home.data.scriptType == "MWEB"
        is Screen.Details -> s.home.data.scriptType == "MWEB"
        is Screen.TxDetail -> s.tx.mweb
        is Screen.LabelSync -> s.home.data.scriptType == "MWEB"
        is Screen.PsbtSign -> false
        is Screen.SignPsbt -> false
        else -> false
    }
    val darkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    MaterialTheme(colorScheme = sparrowColorScheme(privateLayer, darkTheme)) {
    // edge-to-edge: the themed background runs under the system bars, but content is
    // inset so it never collides with the status bar or gesture area
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
    if(airGapped) {
        val radios by rememberRadios()
        Box(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp)) { AirGapPill(radioWarning(radios)) }
    }
    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        when(val s = screen) {
            is Screen.WalletList -> WalletListScreen(
                repo = repo,
                airGapped = airGapped,
                onAirGapChanged = { airGapped = it },
                onOpen = { screen = Screen.Unlock(it) },
                onCreate = { screen = Screen.Create(repo.createNewMnemonic()) },
                onRestore = { screen = Screen.Restore },
                onRestoreFromApp = { screen = Screen.RestoreFromApp },
                onImportFile = { bytes ->
                    screen = Screen.ImportSparrow(
                        SparrowLink.ReceivedPayload(SparrowLink.ReceivedWallet("", bytes), null)
                    )
                },
                onLink = { pairing ->
                    screen = if(pairing.mode == "push") Screen.LinkReceive(pairing) else Screen.LinkSend(pairing)
                }
            )
            is Screen.ImportSparrow -> ImportSparrowScreen(
                repo = repo,
                payload = s.payload,
                // the new wallet and its family are unlocked off the main thread, as on the unlock screen
                openFamily = { name, password -> homeFor(name, repo.unlock(name, password), password) },
                onImported = { screen = it },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.LinkReceive -> LinkReceiveScreen(
                repo = repo,
                pairing = s.pairing,
                onReceived = { screen = Screen.ImportSparrow(it) },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.LinkSend -> LinkSendScreen(
                repo = repo,
                pairing = s.pairing,
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Create -> SaveWalletScreen(
                title = "New wallet",
                mnemonic = s.mnemonic,
                mnemonicEditable = false,
                repo = repo,
                onSaved = { name, data, password -> screen = Screen.Home(name, data, password = password) },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Restore -> SaveWalletScreen(
                title = "Restore from seed",
                mnemonic = "",
                mnemonicEditable = true,
                repo = repo,
                onSaved = { name, data, password -> screen = Screen.Home(name, data, password = password) },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.RestoreFromApp -> OtherWalletRestoreScreen(
                repo = repo,
                openFamily = { name, password -> homeFor(name, repo.unlock(name, password), password) },
                onRestored = { screen = it },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Unlock -> UnlockScreen(
                name = s.name,
                repo = repo,
                // the wallet and its family are unlocked off the main thread (each unlock is a slow key derivation)
                unlockFamily = { password -> homeFor(s.name, repo.unlock(s.name, password), password) },
                onUnlocked = { screen = it },
                onDeleted = { screen = Screen.WalletList },
                onBack = { screen = Screen.WalletList }
            )
            is Screen.Home -> if(s.data.scriptType == "MWEB" && airGapped) {
                // the private layer is scanned and spent through mwebd, which needs peers
                ScreenColumn {
                    Text(s.name, style = MaterialTheme.typography.headlineSmall)
                    Text("Private (MWEB) coins need the network. Turn off air-gapped mode to use them.",
                        style = MaterialTheme.typography.bodyMedium)
                    s.shown(Layer.PUBLIC)?.let { paired ->
                        OutlinedButton(onClick = { screen = paired }, modifier = Modifier.fillMaxWidth()) {
                            Text("Public wallet")
                        }
                    }
                    BackButton(onClick = { screen = Screen.WalletList })
                }
            } else if(s.data.scriptType == "MWEB") {
                MwebHomeScreen(
                    repo, s.name, s.data, s.linkedData,
                    onSend = { screen = Screen.Send(s) },
                    onReceive = { screen = Screen.Receive(s, 0) },
                    onDetails = { screen = Screen.Details(s) },
                    onSwitch = { screen = Screen.WalletList },
                    layers = s.family.layers, layer = s.layer, onLayer = { l -> s.shown(l)?.let { screen = it } },
                    onTx = { tx -> screen = Screen.TxDetail(s, tx) },
                    onSyncLabels = { pairing -> screen = Screen.LabelSync(s, pairing) }
                )
            } else androidx.compose.runtime.key(s.name) {
                // the Public and Taproot layers share this screen: a fresh one per wallet, so no state or sync carries over
                HomeScreen(
                    repo, s.name, s.data, s.linkedData,
                    onSend = { screen = Screen.Send(s) },
                    onReceive = { index -> screen = Screen.Receive(s, index) },
                    onServer = { screen = Screen.Server(s) },
                    onDetails = { screen = Screen.Details(s) },
                    onSwitch = { screen = Screen.WalletList },
                    layers = s.family.layers, layer = s.layer, onLayer = { l -> s.shown(l)?.let { screen = it } },
                    alsoSync = s.family.alsoSync(s.layer).map { it.data },
                    onTx = { tx -> screen = Screen.TxDetail(s, tx) },
                    onSyncLabels = { pairing -> screen = Screen.LabelSync(s, pairing) },
                    onSignPsbt = { screen = Screen.SignPsbt(s) },
                    onExport = { screen = Screen.ExportToDesktop(s) },
                    onCollectibles = { screen = Screen.Collectibles(s) },
                    onLitVm = { screen = Screen.LitVm(s) },
                    onResumePsbt = repo.pendingPsbts[pendingPsbtKey(s)]?.let { unsigned -> { screen = Screen.PsbtSign(s, unsigned) } }
                )
            }
            is Screen.Receive -> ReceiveScreen(repo, s.home, s.startIndex, onBack = { screen = s.home })
            is Screen.Send -> SendScreen(
                repo, s.home,
                onSignExternally = { unsigned ->
                    repo.pendingPsbts.keep(pendingPsbtKey(s.home), unsigned)
                    screen = Screen.PsbtSign(s.home, unsigned)
                },
                onBack = { screen = s.home }
            )
            is Screen.PsbtSign -> PsbtSignScreen(repo, s.home, s.unsigned, onBack = { screen = s.home })
            is Screen.SignPsbt -> SignPsbtScreen(repo, s.home, onBack = { screen = s.home })
            is Screen.ExportToDesktop -> ExportToDesktopScreen(repo, s.home.data, onBack = { screen = s.home })
            is Screen.Collectibles -> CollectiblesScreen(repo, s.home, visit ?: remember { CollectiblesVisit() },
                onTile = { tile -> screen = Screen.Inscription(s.home, tile) },
                onBack = { screen = s.home },
                // the typed password only adds the wallet; the family reopens with the session's (see addTaprootAndReopen)
                addTaproot = { typed -> addTaprootAndReopen(s.home, typed, repo::addTaprootCompanion, ::homeFor) },
                onAdded = { next ->
                    screen = next
                    // no Home runs here, so the new Taproot wallet's sync and check start now, as its Home would start them
                    for(member in joinedAlsoSync(s.home.family, next.home.family, next.home.layer, NetworkPolicy.blocked)) {
                        appScope.launch { syncFamilyMember(repo, member.data) }
                    }
                })
            is Screen.Inscription -> InscriptionScreen(repo, s.home, s.tile, visit ?: remember { CollectiblesVisit() },
                onBack = { screen = Screen.Collectibles(s.home) })
            is Screen.LitVm -> LitVmScreen(repo, s.home, checkNotNull(litVm) { "A LitVM screen has its visit" },
                onTile = { key -> screen = Screen.LitVmNft(s.home, key) },
                onBack = { screen = s.home })
            is Screen.LitVmNft -> LitVmNftScreen(s.tileKey, checkNotNull(litVm) { "A LitVM screen has its visit" },
                onBack = { screen = Screen.LitVm(s.home) })
            is Screen.Server -> ServerScreen(repo, s.home.data, onBack = { screen = s.home })
            is Screen.Details -> DetailsScreen(repo, s.home.name, s.home.data,
                onExport = { screen = Screen.ExportToDesktop(s.home) },
                onBack = { screen = s.home })
            is Screen.TxDetail -> TxDetailScreen(
                s.tx, repo.coin(s.home.data), repo.network(s.home.data),
                onSaveLabel = if(s.home.password.isNotEmpty()) { label: String ->
                    // a wallet no longer on this phone is not written back: back to the list
                    val newData = try {
                        repo.updateLabel(s.home.name, s.home.data, s.home.password, s.tx.txid, label)
                    } catch(e: WalletGoneException) {
                        null
                    }
                    val cleaned = label.trim().ifEmpty { null }
                    screen = if(newData == null) Screen.WalletList else Screen.TxDetail(
                        s.home.withData(mapOf(s.home.name to newData)),
                        s.tx.copy(label = cleaned, kind = kindFor(s.tx.delta, cleaned))
                    )
                } else null,
                onBack = { screen = s.home }
            )
            is Screen.LabelSync -> LabelSyncScreen(
                repo, s.home, s.pairing,
                onDone = { updated ->
                    // label sync sends the shown wallet and its MWEB counterpart, so only they come back
                    screen = afterLabelSync(s.home, updated)
                },
                onBack = { screen = s.home }
            )
        }
    }
    }
    }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) { content() }
}

@Composable
private fun NetworkBadge(network: Network) {
    val color = if(network == Network.MAINNET) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
    Text(
        network.displayName.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = color
    )
}

@Composable
private fun WalletListScreen(
    repo: WalletRepository,
    airGapped: Boolean,
    onAirGapChanged: (Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onRestoreFromApp: () -> Unit,
    onImportFile: (ByteArray) -> Unit,
    onLink: (SparrowLink.Pairing) -> Unit
) {
    val wallets = remember { repo.listWallets() }
    val context = LocalContext.current
    var pickError by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if(bytes == null || bytes.isEmpty()) pickError = "Could not read that file" else onImportFile(bytes)
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            try {
                onLink(SparrowLink.parseQr(text))
            } catch(e: Exception) {
                pickError = e.message ?: "Not a Sparrow Link QR code"
            }
        }
    }
    ScreenColumn {
        Text("Sparrow-Scrypt", style = MaterialTheme.typography.headlineMedium)
        if(wallets.isEmpty()) {
            Text("Litecoin, Dogecoin and five more coins, in sync with your desktop.", style = MaterialTheme.typography.bodyMedium)
        } else {
            // one section per coin; a linked pair is one wallet with two layers, shown as one card
            val groups = remember(wallets) { groupByCoin(wallets) }
            val prices = remember { mutableStateMapOf<Coin, Double>() }
            LaunchedEffect(groups, airGapped) {
                if(airGapped) return@LaunchedEffect
                for((coin, _) in groups) PriceService.currentUsd(coin)?.let { prices[coin] = it }
            }
            val totals = remember(wallets) { repo.sessionTotals() }
            for((coin, coinWallets) in groups) {
                val balanceOf = { name: String -> coinWallets.firstOrNull { it.name == name }?.let { walletBalance(it, wallets, totals) } }
                val total = coinTotal(coinWallets, Network.MAINNET, balanceOf)
                val testTotal = coinTotal(coinWallets, Network.TESTNET, balanceOf)
                Text(
                    listOfNotNull(
                        coin.displayName,
                        total?.let { formatAmountShort(coin, it) },
                        total?.let { t -> prices[coin]?.let { formatUsd(t, it) } },
                        testTotal?.let { "testnet ${formatAmountShort(coin, it)}" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium
                )
                for(w in coinWallets) {
                    // the companions Home opens with it, as Home judges them
                    val tags = remember(wallets, w) { companionsOf(w, wallets).map { it.first } }
                    OutlinedButton(onClick = { onOpen(w.name) }, modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(w.name)
                                WalletTags(modifier = Modifier.padding(top = 5.dp)) {
                                    if(coin.mweb) LayerTag(privateLayer = w.scriptType == "MWEB")
                                    for(tag in tags) LayerTag(tag)
                                }
                            }
                            NetworkBadge(w.network)
                        }
                    }
                }
            }
        }
        var restoreOpen by remember { mutableStateOf(false) }
        Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create new wallet") }
        OutlinedButton(onClick = { restoreOpen = !restoreOpen }, modifier = Modifier.fillMaxWidth()) {
            Text(if(restoreOpen) "Restore  ▴" else "Restore  ▾")
        }
        if(restoreOpen) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from seed")
                }
                OutlinedButton(onClick = onRestoreFromApp, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from another wallet app")
                }
                OutlinedButton(
                    onClick = {
                        scanner.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Scan the QR from desktop Sparrow: Tools → Link with Mobile")
                            setBeepEnabled(false)
                            setOrientationLocked(true)
                        })
                    },
                    enabled = !airGapped,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Import wallet from desktop") }
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Import wallet from file…")
                }
                Text("Desktop import: on the PC open Tools → Link with Mobile → Push or Receive. " +
                    "Same WiFi, or turn on USB tethering.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        pickError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        val syncStore = remember { repo.syncStore }
        AirGapSetting(repo, wallets, airGapped, onAirGapChanged)
        if(airGapped) return@ScreenColumn

        // background refresh: scheduled view-only checks + received-funds notifications
        var interval by remember { mutableStateOf(syncStore.intervalMinutes) }
        var notify by remember { mutableStateOf(syncStore.notifyEnabled) }
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        Text("BACKGROUND REFRESH", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for((label, minutes) in listOf("Off" to 0, "15m" to 15, "30m" to 30, "1h" to 60, "6h" to 360)) {
                val select = {
                    interval = minutes
                    syncStore.intervalMinutes = minutes
                    SyncWorker.schedule(context, minutes)
                    if(minutes > 0 && android.os.Build.VERSION.SDK_INT >= 33) {
                        notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                if(interval == minutes) {
                    Button(onClick = select, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                } else {
                    OutlinedButton(onClick = select, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Notify on received funds", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = notify, onCheckedChange = { notify = it; syncStore.notifyEnabled = it })
        }
        if(interval > 0) {
            TextButton(onClick = { SyncWorker.runNow(context) }) { Text("Check now") }
            Text("Checks use view-only keys sealed in this phone's secure keystore — they can see " +
                "incoming funds but can never spend. Unlock each wallet once after enabling.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The air-gapped switch: turning on needs a confirm, turning off every wallet's password (a companion's is tried from
 * its parent's first, see [AirGapMode.leave]).
 */
@Composable
private fun AirGapSetting(
    repo: WalletRepository,
    wallets: List<WalletRepository.WalletInfo>,
    airGapped: Boolean,
    onChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var entering by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Air-gapped mode", style = MaterialTheme.typography.bodyMedium)
            Text("Signs PSBTs from a desktop watch-only wallet. Sync, sending, MWEB, prices and Sparrow Link are off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = airGapped, onCheckedChange = { on -> if(on) entering = true else leaving = true })
    }

    if(entering) {
        AlertDialog(
            onDismissRequest = { entering = false },
            title = { Text("Turn on air-gapped mode?") },
            text = {
                Text("This phone stops all network use. You sign transactions by scanning QR codes from " +
                    "desktop Sparrow and showing the signed result back. Private (MWEB) coins cannot be " +
                    "spent in this mode.")
            },
            confirmButton = {
                TextButton(onClick = {
                    AirGapMode.enter(settings, cancelWork = {
                        val workManager = androidx.work.WorkManager.getInstance(context)
                        workManager.cancelUniqueWork(SyncWorker.WORK_NAME)
                        workManager.cancelUniqueWork("${SyncWorker.WORK_NAME}-now")
                    })
                    entering = false
                    onChanged(true)
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { entering = false }) { Text("Cancel") } }
        )
    }

    if(leaving) {
        val passwords = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
        // companions their parent's password did not open, asked for by name
        var ownPassword by remember { mutableStateOf(emptySet<String>()) }
        val fields = remember(wallets, ownPassword) { AirGapMode.passwordFields(wallets, ownPassword) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if(!busy) { leaving = false; passwords.clear(); ownPassword = emptySet(); error = null } },
            title = { Text("Go back online?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Wallets kept offline will be on a device that connects to the internet. " +
                        "Enter each wallet's password to continue.")
                    for(name in fields) {
                        OutlinedTextField(
                            value = passwords[name] ?: "", onValueChange = { passwords[name] = it },
                            label = { Text(name) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true; error = null
                    scope.launch {
                        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            AirGapMode.leave(settings, wallets, passwords.toMap()) { n, p -> repo.unlock(n, p, session = false) }
                        }
                        busy = false
                        when(result) {
                            AirGapMode.LeaveResult.Left -> {
                                leaving = false
                                passwords.clear()
                                ownPassword = emptySet()
                                SyncWorker.schedule(context, repo.syncStore.intervalMinutes)
                                onChanged(false)
                            }
                            is AirGapMode.LeaveResult.WrongPassword -> error = "Wrong password for ${result.wallet}"
                            is AirGapMode.LeaveResult.MissingPassword -> error = "Enter the password for ${result.wallet}"
                            is AirGapMode.LeaveResult.OwnPasswordNeeded -> {
                                ownPassword = ownPassword + result.wallets
                                error = "Its main wallet's password does not open ${result.wallets.joinToString(", ")}. " +
                                    "Enter its own password."
                            }
                        }
                    }
                }) { Text("Go online") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { leaving = false; passwords.clear(); ownPassword = emptySet(); error = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun LinkReceiveScreen(
    repo: WalletRepository,
    pairing: SparrowLink.Pairing,
    onReceived: (SparrowLink.ReceivedPayload) -> Unit,
    onBack: () -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairing) {
        try {
            onReceived(repo.linkReceiveWallet(pairing))
        } catch(t: Throwable) {
            error = t.message ?: "Transfer failed"
        }
    }
    ScreenColumn {
        Text("Importing from desktop", style = MaterialTheme.typography.headlineSmall)
        if(error == null) {
            CircularProgressIndicator()
            Text("Connecting to the desktop and importing the wallet…", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun LinkSendScreen(repo: WalletRepository, pairing: SparrowLink.Pairing, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    // never a Taproot companion: it has no row of its own (see linkSendWallets)
    val wallets = remember { linkSendWallets(repo.listWallets()) }
    var selected by remember { mutableStateOf(wallets.firstOrNull()?.name) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    ScreenColumn {
        Text("Send wallet to desktop", style = MaterialTheme.typography.headlineSmall)
        if(done) {
            Text("Wallet sent ✓ — on the PC use File → Open Wallet. It opens with the same password it has on this phone.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@ScreenColumn
        }
        if(wallets.isEmpty()) {
            Text("No wallets on this phone yet.", style = MaterialTheme.typography.bodyMedium)
            BackButton(onClick = onBack)
            return@ScreenColumn
        }
        Text("Choose the wallet to send, and enter its password (it authorizes the export and stays the wallet's password on the desktop).",
            style = MaterialTheme.typography.bodyMedium)
        for(w in wallets) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == w.name, onClick = { selected = w.name })
                Text(w.name, modifier = Modifier.weight(1f))
                NetworkBadge(w.network)
            }
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Wallet password") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        val name = selected ?: throw IllegalArgumentException("Pick a wallet")
                        val data = try { repo.unlock(name, password, session = false) }
                                   catch(t: Throwable) { throw IllegalArgumentException("Wrong password") }
                        val fileBytes = repo.exportSparrowWallet(name, data, password)
                        repo.linkSendWallet(pairing, name, fileBytes)
                        done = true
                    } catch(t: Throwable) {
                        error = t.message ?: "Transfer failed"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && selected != null && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(busy) "Encrypting and sending…" else "Push to desktop") }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

@Composable
private fun ImportSparrowScreen(
    repo: WalletRepository,
    payload: SparrowLink.ReceivedPayload,
    openFamily: (name: String, password: String) -> Screen.Home,
    onImported: (Screen.Home) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var filePassword by remember { mutableStateOf("") }
    var linkedPassword by remember { mutableStateOf("") }
    var devicePassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val linked = payload.linked

    ScreenColumn {
        Text("Import desktop wallet", style = MaterialTheme.typography.headlineSmall)
        Text(
            if(linked == null)
                "Wallet ${payload.wallet.name.ifBlank { "file" }} loaded (${payload.wallet.file.size} bytes). " +
                "Enter the password it has on the desktop; it's re-encrypted on this phone with a password of your choice."
            else
                "Receiving \"${payload.wallet.name}\" together with its linked private wallet \"${linked.name}\". " +
                "Enter the desktop passwords; both are re-encrypted on this phone with a password of your choice.",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = filePassword, onValueChange = { filePassword = it },
            label = { Text(if(linked == null) "Desktop wallet password" else "Desktop password: ${payload.wallet.name}") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
            modifier = Modifier.fillMaxWidth()
        )
        if(linked != null) {
            OutlinedTextField(
                value = linkedPassword, onValueChange = { linkedPassword = it },
                label = { Text("Desktop password: ${linked.name} (blank if same)") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(
            value = devicePassword, onValueChange = { devicePassword = it },
            label = { Text("Password on this phone (can be the same)") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Wallet name (optional — uses the desktop name)") },
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        if(devicePassword.isEmpty()) throw IllegalArgumentException("Choose a password for this phone")
                        val walletName = repo.importSparrowPair(
                            payload, filePassword, linkedPassword.ifBlank { filePassword }, name, devicePassword
                        )
                        onImported(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            openFamily(walletName, devicePassword)
                        })
                    } catch(t: Throwable) {
                        error = t.message ?: "Import failed"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(busy) "Decrypting… (takes a few seconds)" else if(linked == null) "Import wallet" else "Import both wallets") }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

@Composable
private fun NetworkPicker(selected: Network, onSelect: (Network) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for(net in Network.entries) {
            RadioButton(selected = selected == net, onClick = { onSelect(net) })
            Text(net.displayName, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A vertical radio list whose whole row selects, so the label is a target too. */
@Composable
internal fun <T> RadioList(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        for(option in options) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onSelect(option) }
            ) {
                RadioButton(selected = selected == option, onClick = { onSelect(option) })
                Text(label(option), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CoinPicker(selected: Coin, onSelect: (Coin) -> Unit) =
    RadioList(Coin.entries, selected, { "${it.displayName} (${it.ticker})" }, onSelect)

@Composable
private fun ScriptTypePicker(options: List<ScriptType>, selected: ScriptType, onSelect: (ScriptType) -> Unit) =
    RadioList(options, selected, { WalletSetup.scriptTypeLabel(it) }, onSelect)

@Composable
private fun SaveWalletScreen(
    title: String,
    mnemonic: String,
    mnemonicEditable: Boolean,
    repo: WalletRepository,
    onSaved: (String, VaultData, String) -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var coin by remember { mutableStateOf(Coin.LITECOIN) }
    var network by remember { mutableStateOf(Network.MAINNET) }
    var scriptType by remember { mutableStateOf(ScriptType.P2WPKH) }
    var phrase by remember { mutableStateOf(mnemonic) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ScreenColumn {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Wallet name") },
            modifier = Modifier.fillMaxWidth()
        )
        CoinPicker(coin) { coin = it; scriptType = WalletSetup.scriptTypes(it, network).first() }
        NetworkPicker(network) {
            network = it
            if(scriptType !in WalletSetup.scriptTypes(coin, it)) scriptType = WalletSetup.scriptTypes(coin, it).first()
        }
        if(WalletSetup.scriptTypes(coin, network).size > 1) {
            ScriptTypePicker(WalletSetup.scriptTypes(coin, network), scriptType) { scriptType = it }
        }
        if(mnemonicEditable) {
            OutlinedTextField(
                value = phrase, onValueChange = { phrase = it },
                label = { Text("Recovery phrase (12–24 words)") }, keyboardOptions = SeedKeyboard,
                modifier = Modifier.fillMaxWidth()
            )
            phrase.trim().takeIf { it.isNotEmpty() }?.let { p ->
                val kind = repo.classifySeed(p, coin, network, scriptType)?.first
                Text(
                    kind?.let { "Detected: $it seed" }
                        ?: if(!WalletSetup.electrumSeedAllowed(coin) && ElectrumSeed.isValid(p)) "Electrum seeds are Litecoin only"
                        else "Not a recognized seed yet",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(phrase, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Text("Write these words down and keep them safe. Anyone with them controls your funds.",
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Wallet password (encrypts on this device)") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                error = if(password.isEmpty()) "Choose a password"
                        else repo.saveWallet(name, phrase, "", password, network, scriptType = scriptType.name, coin = coin)
                            ?: run { onSaved(name.trim(), repo.unlock(name, password), password); null }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save wallet") }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun UnlockScreen(name: String, repo: WalletRepository, unlockFamily: (String) -> Screen.Home,
                         onUnlocked: (Screen.Home) -> Unit, onDeleted: () -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ScreenColumn {
        Text("Unlock \"$name\"", style = MaterialTheme.typography.headlineSmall)
        remember(name) { repo.listWallets().let { all -> unlockHint(all.firstOrNull { it.name == walletFileName(name) }, all) } }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                unlocking = true
                error = null
                val typed = password
                scope.launch {
                    val opened = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching { unlockFamily(typed) }
                    }
                    unlocking = false
                    opened.fold({ onUnlocked(it) }, { error = if(repo.walletVaultExists(name)) "Wrong password" else WalletGoneException().message })
                }
            },
            enabled = !unlocking,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(unlocking) "Unlocking…" else "Unlock") }
        BackButton(onClick = onBack)
        if(!confirmDelete) {
            TextButton(onClick = { confirmDelete = true }, enabled = !unlocking) { Text("Delete this wallet from device…") }
        } else {
            Text("This removes the wallet from this phone. Without the seed words it CANNOT be recovered.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val family = remember(name) { repo.deleteFamily(name) }
            val choices = remember(name) { repo.deleteChoices(name) }
            var chosen by remember(name) { mutableStateOf<DeleteChoice?>(null) }
            var deleteError by remember(name) { mutableStateOf<String?>(null) }
            // never while an unlock runs: it would register the wallet and its sync credentials again as it finishes
            fun delete(choice: DeleteChoice) {
                if(unlocking) return
                if(repo.deleteWallets(choice)) onDeleted() else deleteError = DELETE_FAILED
            }
            if(choices.isEmpty()) {
                deleteError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { delete(DeleteChoice.only(name)) }, enabled = !unlocking, modifier = Modifier.fillMaxWidth()) {
                    Text("Yes, delete \"$name\"")
                }
            } else {
                Text(LINKED_SEED_WORDS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                for((member, layer) in family) {
                    Text("$member (${layer.label})", style = MaterialTheme.typography.bodyMedium)
                }
                for(choice in choices) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == choice, onClick = { chosen = choice }, enabled = !unlocking)
                        Text(choice.label, modifier = Modifier.weight(1f))
                    }
                }
                deleteError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { chosen?.let { delete(it) } }, enabled = chosen != null && !unlocking, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete")
                }
            }
            TextButton(onClick = { confirmDelete = false }) { Text("Keep it") }
        }
    }
}

/**
 * The transaction detail screen: everything the clean activity rows leave out —
 * confirmations, block, txid with tap-to-copy, and the fiat value then and now.
 */
@Composable
private fun TxDetailScreen(tx: TxInfo, coin: Coin, network: Network, onSaveLabel: ((String) -> Unit)? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var usdThen by remember(tx.txid) { mutableStateOf<Double?>(null) }
    var usdNow by remember { mutableStateOf<Double?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(tx.txid) {
        if(NetworkPolicy.blocked) return@LaunchedEffect
        usdNow = PriceService.currentUsd(coin)
        if(tx.timestamp != null && tx.timestamp > 0) usdThen = PriceService.usdOn(context, coin, tx.timestamp)
    }
    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Transaction", style = MaterialTheme.typography.headlineSmall)
            StatusPill(
                if(tx.height > 0) PillState.OK else PillState.SCAN,
                if(tx.height > 0) "Confirmed" else "Pending"
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            TypeChip(tx.kind)
            Text(
                (if(tx.delta >= 0) "+" else "") + formatAmount(coin, tx.delta),
                fontFamily = MonoFont, style = MaterialTheme.typography.headlineSmall,
                color = if(tx.delta >= 0) kindColor(TxKind.RECEIVED) else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                if(usdThen != null) "${formatUsd(tx.delta, usdThen)} then → ${formatUsd(tx.delta, usdNow)} today"
                else "≈ ${formatUsd(tx.delta, usdNow)} today",
                fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if(onSaveLabel == null) {
                tx.label?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        onSaveLabel?.let { save ->
            var labelText by remember(tx.txid) { mutableStateOf(tx.label ?: "") }
            var saved by remember(tx.txid) { mutableStateOf(false) }
            val changed = labelText.trim() != (tx.label ?: "")
            OutlinedTextField(
                value = labelText,
                onValueChange = { labelText = it; saved = false },
                label = { Text("Label") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = { if(changed) { save(labelText); saved = true } }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { save(labelText); saved = true },
                enabled = changed,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save label") }
            if(saved) {
                Text(
                    "✓ Label saved",
                    style = MaterialTheme.typography.bodyMedium,
                    color = kindColor(TxKind.RECEIVED),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun kv(k: String, v: String) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                    }
                }
                kv("Status", when {
                    tx.height <= 0 -> "unconfirmed"
                    tx.confirmations != null -> "%,d confirmations".format(tx.confirmations)
                    else -> "confirmed"
                })
                if(tx.height > 0) kv("Block", "%,d".format(tx.height))
                kv("Date", formatDate(tx.timestamp?.takeIf { it > 0 }))
                kv(if(tx.mweb && tx.txid.length != 64) "Output id" else "Txid",
                    tx.txid.take(10) + "…" + tx.txid.takeLast(8))
                usdThen?.let { kv("Value then", "${formatUsdPrice(it)} / ${coin.ticker}") }
            }
        }
        Button(
            onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(tx.txid))
                copied = true
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(copied) "Copied ✓" else if(tx.mweb && tx.txid.length != 64) "Copy output id" else "Copy txid") }
        // the browser would tie this txid to the phone's IP: not from an air-gapped signer
        val explorerUrl = if(tx.mweb) null else Explorers.txUrl(coin, network, tx.txid)
        if(explorerUrl != null && !NetworkPolicy.blocked) {
            OutlinedButton(
                onClick = {
                    context.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(explorerUrl)
                    ))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("View on explorer") }
        }
        BackButton(onClick = onBack)
    }
}

/**
 * Syncs [other], a family member a Home also syncs ([WalletFamily.alsoSync]), with the account key [account] gives, then
 * starts its inscription check, as the Public layer's Home does after its own sync. Its result or failure is not shown;
 * its balance reaches the wallet list through its session unlock.
 */
internal suspend fun syncFamilyMember(repo: WalletRepository, other: VaultData,
                                      account: suspend () -> HDKey = {
                                          kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { repo.accountKey(other) }
                                      }) {
    runCatching {
        val otherAccount = account()
        repo.syncWallet(other, otherAccount)
        otherAccount
    }.onSuccess { repo.startInscriptionCheck(other, it) }
}

@Composable
private fun HomeScreen(
    repo: WalletRepository,
    name: String,
    data: VaultData,
    linkedData: VaultData?,
    onSend: () -> Unit,
    onReceive: (Int) -> Unit,
    onServer: () -> Unit,
    onDetails: () -> Unit,
    onSwitch: () -> Unit,
    layers: List<Layer> = listOf(Layer.PUBLIC),
    layer: Layer = Layer.PUBLIC,
    onLayer: (Layer) -> Unit = {},
    alsoSync: List<VaultData> = emptyList(),
    onTx: (TxInfo) -> Unit = {},
    onSyncLabels: (SparrowLink.Pairing) -> Unit = {},
    onSignPsbt: (() -> Unit)? = null,
    onExport: () -> Unit = {},
    onCollectibles: () -> Unit = {},
    onLitVm: () -> Unit = {},
    onResumePsbt: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val network = remember(data) { repo.network(data) }
    val account = remember(data) { repo.accountKey(data) }
    // open with the last-known snapshots so navigation never blanks the screen
    var snapshot by remember(data) { mutableStateOf(repo.cachedSnapshot(data)) }
    var privateSnap by remember(data) { mutableStateOf(linkedData?.let { repo.cachedMwebSnapshot(it) }) }
    var usdNow by remember { mutableStateOf<Double?>(null) }
    var syncing by remember { mutableStateOf(false) }
    // the mode only changes on the wallet list, so reading it once per entry is enough
    val gapped = NetworkPolicy.blocked
    var status by remember(data) {
        mutableStateOf(
            if(!gapped && repo.cachedSnapshot(data) != null && !repo.snapshotFresh(data)) "Last known — refreshing…" else null
        )
    }
    LaunchedEffect(Unit) { if(!gapped) usdNow = PriceService.currentUsd(repo.coin(data)) }
    // the one-time notice before any public indexer is asked; until it is answered checks stay Built-in. Dismissing it
    // saves nothing, so it shows again next time
    val noticeHost = remember(data) {
        if(repo.inscriptionNoticeDue(repo.coin(data), network)) InscriptionNotice.host(repo.coin(data), network) else null
    }
    var showNotice by androidx.compose.runtime.saveable.rememberSaveable(name) { mutableStateOf(noticeHost != null) }
    if(showNotice && noticeHost != null) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text(InscriptionNotice.TITLE) },
            text = { Text(InscriptionNotice.body(noticeHost)) },
            confirmButton = {
                TextButton(onClick = {
                    repo.saveInscriptionDefaultChoice(InscriptionNotice.choice(usePublic = true))
                    showNotice = false
                }) { Text(InscriptionNotice.USE_PUBLIC) }
            },
            dismissButton = {
                TextButton(onClick = {
                    repo.saveInscriptionDefaultChoice(InscriptionNotice.choice(usePublic = false))
                    showNotice = false
                }) { Text(InscriptionNotice.USE_BUILT_IN) }
            }
        )
    }
    // the stored inscription checks are read off the main thread, so inscriptionChecks(data) can serve them from memory
    LaunchedEffect(data) { repo.loadInscriptionChecks(data) }
    // the inscription check's line, shown after the sync status while a check for this wallet runs, whoever started it
    val checkProgress = remember(data) { repo.inscriptionProgress(data) }?.collectAsState()
    val checking = checkProgress?.value?.let { (done, total) -> "Checking inscriptions: $done of $total coins" }

    // After a public sync, checks the wallet's coins for inscriptions. The run belongs to the app, not this screen:
    // going to Send or Receive does not stop it. One check per wallet at a time; it saves as it goes.
    fun checkInscriptions() {
        if(data.scriptType == "MWEB") return
        repo.startInscriptionCheck(data, account)
    }

    // The family's other wallets this Home also syncs (the Taproot wallet beside a public wallet), each after this
    // wallet's own sync and check, on its own: its result or failure changes nothing shown here, and its balance reaches
    // the wallet list's total through its session unlock. Its inscription check takes the same path as this wallet's.
    val alsoAccounts = remember(alsoSync) { java.util.concurrent.ConcurrentHashMap<VaultData, HDKey>() }
    // their balances as last synced, for the family total the wallet list's card shows; read again after each sync
    var alsoBalances by remember(alsoSync) { mutableStateOf(alsoSync.map { repo.cachedSnapshot(it)?.balance }) }
    suspend fun syncAlong(other: VaultData) = syncFamilyMember(repo, other) {
        alsoAccounts[other] ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            repo.accountKey(other)
        }.also { alsoAccounts[other] = it }
    }

    // One wallet in two layers: refresh syncs the public side and the linked private
    // side together, and the status line reports both.
    fun refresh(checkAfter: Boolean = true) {
        syncing = true
        scope.launch {
            try {
                val privateJob = if(linkedData != null && linkedData.scriptType == "MWEB")
                    async { runCatching { repo.mwebSync(linkedData) } } else null
                val publicResult = runCatching {
                    // a direct state write (Compose state is thread-safe) keeps it ordered before the final status
                    repo.syncWallet(data, account) { n -> status = "Syncing… $n addresses checked" }
                }
                publicResult.onSuccess { snapshot = it }
                val privateResult = privateJob?.await()
                privateResult?.onSuccess { privateSnap = it }

                status = pairStatus(
                    publicResult.fold({ "" }, { "sync failed — ${it.message}" }),
                    privateResult?.fold({ mwebStateText(it) }, { "unavailable — ${it.message}" })
                )
                if(checkAfter && publicResult.isSuccess) checkInscriptions()
                if(checkAfter) for(other in alsoSync) scope.launch {
                    syncAlong(other)
                    alsoBalances = alsoSync.map { repo.cachedSnapshot(it)?.balance }
                }
            } finally {
                syncing = false
            }
        }
    }

    LaunchedEffect(account) {
        if(gapped) return@LaunchedEffect
        // refresh only when opened stale, at the configured interval, or on demand —
        // switching between screens/layers just shows the cached snapshot
        val maxAge = repo.syncStore.intervalMinutes.takeIf { it > 0 }?.let { it * 60_000L } ?: 60_000L
        val privateFresh = linkedData == null || linkedData.scriptType != "MWEB" ||
            (repo.cachedMwebSnapshot(linkedData)?.initialScanComplete == true &&
                repo.mwebSnapshotFresh(linkedData, maxAge))
        if(!repo.snapshotFresh(data, maxAge) || !privateFresh || alsoSync.any { !repo.snapshotFresh(it, maxAge) }) refresh()
        if(linkedData != null && linkedData.scriptType == "MWEB") {
            // keep the combined balance filling in while the private coin scan runs
            var polls = 0
            while(polls++ < 120) {
                if(privateSnap?.initialScanComplete == true) break
                kotlinx.coroutines.delay(6_000)
                if(!syncing) refresh(checkAfter = false) // the first sync's check covers the public coins
            }
        }
    }

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    linkedLabel(layers, layer)?.let { "${network.displayName.uppercase()} · $it" }
                        ?: network.displayName.uppercase(),
                    fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LayerSwitch(layers, layer, onLayer)
        run {
            val also = alsoBalances.filterNotNull().takeIf { it.isNotEmpty() }
            val shown = publicHomeTotal(
                snapshot?.balance?.total, snapshot?.balance?.unconfirmed,
                privateSnap?.let { it.confirmed + it.unconfirmed }, privateSnap?.unconfirmed,
                also?.sumOf { it.total }, also?.sumOf { it.unconfirmed }
            )
            BalanceHeader(
                coin = repo.coin(data),
                eyebrow = shown.eyebrow,
                total = shown.total,
                usdNow = usdNow,
                publicPart = shown.publicPart,
                privatePart = shown.privatePart,
                taprootPart = shown.taprootPart,
                pending = shown.pending
            )
        }
        StatusSlot(statusWithCheck(status, checking), when {
            status == null -> if(checking != null) PillState.SCAN else PillState.OK
            status!!.contains("failed", true) || status!!.contains("reconnect", true) -> PillState.IDLE
            status!!.contains("scanning", true) || status!!.contains("syncing", true) || syncing || checking != null -> PillState.SCAN
            else -> PillState.OK
        })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, enabled = !gapped, modifier = Modifier.weight(1f)) { Text("Send") }
            Button(onClick = { onReceive(snapshot?.firstUnusedReceiveIndex ?: 0) }, modifier = Modifier.weight(1f)) {
                Text("Receive")
            }
            OutlinedButton(onClick = { refresh() }, enabled = !syncing && !gapped, modifier = Modifier.weight(1f)) {
                Text(if(syncing) "…" else "Refresh")
            }
        }
        onResumePsbt?.let {
            OutlinedButton(onClick = it, enabled = !gapped, modifier = Modifier.fillMaxWidth()) {
                Text("Continue external signing…")
            }
        }
        if(gapped) {
            Text("Off in air-gapped mode", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        snapshot?.let { snap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("ACTIVITY", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp))
                    if(snap.history.isEmpty()) {
                        Text("No transactions yet.", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                    for(tx in snap.history.take(25)) {
                        val info = TxInfo(
                            kind = kindFor(tx.delta, data.txLabels[tx.txid]),
                            label = data.txLabels[tx.txid],
                            timestamp = tx.timestamp,
                            height = tx.height,
                            confirmations = tx.confirmations,
                            txid = tx.txid,
                            delta = tx.delta,
                            mweb = false
                        )
                        ActivityRow(info, usdNow) { onTx(info) }
                    }
                }
            }
        }
        // under the Activity card: the wallet's inscriptions and runes, from the stored checks
        // beside it, LitVM on a Litecoin testnet wallet's Public layer: judged from the wallet list, read off the main thread
        val litVmOffered by androidx.compose.runtime.produceState(false, name, data, layer) {
            // the wallet list is read only for a Litecoin testnet wallet's Public layer
            if(!LitVmModel.mayOffer(repo.coin(data), repo.network(data), data.scriptType, data.mnemonic.isNotBlank(), layer)) return@produceState
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val wallets = repo.listWallets()
                    LitVmModel.available(wallets.firstOrNull { it.name == walletFileName(name) }, data.mnemonic.isNotBlank(), wallets, layer)
                }.getOrDefault(false)
            }
        }
        val collectiblesOffered = remember(data) { CollectiblesModel.available(repo.coin(data), data.scriptType) }
        if(collectiblesOffered || litVmOffered) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if(collectiblesOffered) {
                    OutlinedButton(onClick = onCollectibles, modifier = Modifier.weight(1f)) { Text("Collectibles") }
                }
                if(litVmOffered) {
                    OutlinedButton(onClick = onLitVm, modifier = Modifier.weight(1f)) { Text(LitVmModel.BUTTON) }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("Details") }
            OutlinedButton(onClick = onServer, enabled = !gapped, modifier = Modifier.weight(1f)) { Text("Server") }
            OutlinedButton(onClick = onSwitch, modifier = Modifier.weight(1f)) { Text("Wallets") }
        }
        if(!gapped) LabelSyncButton(onError = { status = it }, onPairing = onSyncLabels)
        val externalOk = remember(data) { externalSigningSupported(repo.coin(data), repo.network(data), data.scriptType) }
        onSignPsbt?.takeIf { externalOk }?.let {
            OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                Text("Sign a transaction (PSBT)…")
            }
        }
        if(gapped && externalOk) {
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text("Export to desktop (air-gapped)")
            }
        }
    }
}

/** Scan-to-sync entry point: desktop Tools → Link with Mobile → Sync labels shows the QR. */
@Composable
private fun LabelSyncButton(onError: (String) -> Unit, onPairing: (SparrowLink.Pairing) -> Unit) {
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            try {
                val pairing = SparrowLink.parseQr(text)
                if(pairing.mode != "labels") {
                    throw SparrowLink.LinkException("That QR is a wallet transfer — scan it from the Wallets screen")
                }
                onPairing(pairing)
            } catch(e: Exception) {
                onError(e.message ?: "Not a Sparrow Link QR code")
            }
        }
    }
    OutlinedButton(
        onClick = {
            scanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan the QR from desktop: Tools → Link with Mobile → Sync Labels with Phone")
                setBeepEnabled(false)
                setOrientationLocked(true)
            })
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Sync labels with desktop") }
}

@Composable
private fun MwebHomeScreen(
    repo: WalletRepository,
    name: String,
    data: VaultData,
    linkedData: VaultData?,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onDetails: () -> Unit,
    onSwitch: () -> Unit,
    layers: List<Layer> = listOf(Layer.PUBLIC),
    layer: Layer = Layer.PUBLIC,
    onLayer: (Layer) -> Unit = {},
    onTx: (TxInfo) -> Unit = {},
    onSyncLabels: (SparrowLink.Pairing) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val network = remember(data) { repo.network(data) }
    // open with the last-known snapshots so navigation never blanks the screen
    var snapshot by remember(data) { mutableStateOf(repo.cachedMwebSnapshot(data)) }
    var publicBalance by remember(data) { // confirmed, pending
        mutableStateOf(linkedData?.takeIf { it.scriptType != "MWEB" }?.let { repo.cachedSnapshot(it) }
            ?.let { it.balance.confirmed to it.balance.unconfirmed })
    }
    var usdNow by remember { mutableStateOf<Double?>(null) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember(data) {
        mutableStateOf(
            if(repo.cachedMwebSnapshot(data) != null && !repo.mwebSnapshotFresh(data)) "Last known — refreshing…" else null
        )
    }
    LaunchedEffect(Unit) { usdNow = PriceService.currentUsd(Coin.LITECOIN) }
    // the linked public wallet's account key, derived on its first sync and kept for its inscription check
    var linkedAccount by remember(linkedData) { mutableStateOf<HDKey?>(null) }

    fun refresh(checkAfter: Boolean = true) {
        syncing = true
        scope.launch {
            try {
                // one wallet: refresh both layers together, report both the same way
                val publicJob = if(linkedData != null && linkedData.scriptType != "MWEB")
                    async {
                        runCatching {
                            val account = linkedAccount ?: repo.accountKey(linkedData).also { linkedAccount = it }
                            repo.syncWallet(linkedData, account)
                        }
                    } else null
                val privateState = try {
                    val snap = repo.mwebSync(data)
                    snapshot = snap
                    mwebStateText(snap)
                } catch(t: Throwable) {
                    "scanner error — ${t.message}"
                }
                val publicResult = publicJob?.await()
                publicResult?.onSuccess { publicBalance = it.balance.confirmed to it.balance.unconfirmed }
                status = pairStatus(
                    publicResult?.fold({ "" }, { "sync failed — ${it.message}" }),
                    privateState
                )
                // as Home does after a public sync: the linked public wallet's inscription check, in the app's scope
                if(checkAfter && publicResult?.isSuccess == true && linkedData != null) {
                    linkedAccount?.let { repo.startInscriptionCheck(linkedData, it) }
                }
            } finally {
                syncing = false
            }
        }
    }

    LaunchedEffect(data) {
        // refresh only when opened stale, at the configured interval, or on demand
        val maxAge = repo.syncStore.intervalMinutes.takeIf { it > 0 }?.let { it * 60_000L } ?: 60_000L
        if(!(repo.mwebSnapshotFresh(data, maxAge) &&
                repo.cachedMwebSnapshot(data)?.initialScanComplete == true)) refresh()
        // the first coin scan streams results in — poll until it reports complete
        var polls = 0
        while(polls++ < 120) {
            if(snapshot?.initialScanComplete == true) break
            kotlinx.coroutines.delay(6_000)
            if(!syncing) refresh(checkAfter = false) // the first sync's check covers the public coins
        }
    }

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(name, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${network.displayName.uppercase()} · PRIVATE LAYER · MWEB" + (linkedLabel(layers, layer)?.let { " · $it" } ?: ""),
                    fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LayerSwitch(layers, layer, onLayer)
        run {
            val privateTotal = snapshot?.let { it.confirmed + it.unconfirmed }
            val publicTotal = publicBalance?.let { it.first + it.second }
            BalanceHeader(
                eyebrow = if(publicTotal != null) "Total balance" else "Private balance · MWEB",
                total = privateTotal?.let { it + (publicTotal ?: 0) },
                usdNow = usdNow,
                publicPart = if(privateTotal != null) publicTotal else null,
                privatePart = if(publicTotal != null) privateTotal else null,
                pending = snapshot?.let { it.unconfirmed + (publicBalance?.second ?: 0) },
                fiatNote = "amounts confidential on-chain"
            )
        }
        StatusSlot(status, when {
            status == null -> PillState.OK
            status!!.contains("reconnect", true) || status!!.contains("scanner:", true) -> PillState.IDLE
            status!!.contains("scanning", true) || status!!.contains("syncing", true) || syncing -> PillState.SCAN
            else -> PillState.OK
        })
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSend, modifier = Modifier.weight(1f)) { Text("Send") }
            Button(onClick = onReceive, modifier = Modifier.weight(1f)) { Text("Receive") }
            OutlinedButton(onClick = { refresh() }, enabled = !syncing, modifier = Modifier.weight(1f)) {
                Text(if(syncing) "…" else "Refresh")
            }
        }

        run {
            // Desktop's records end at transfer time; MWEB prunes spent outputs so the
            // network can't backfill. Live coins the phone has seen since then are its
            // own witnessed receives — merge them in (change coins excluded).
            val changeAddress = remember(data) { repo.mwebChangeAddress(data) }
            val importedMax = remember(data) {
                data.mwebHistory.mapNotNull { e -> e.height.takeIf { it > 0 } }.maxOrNull() ?: 0
            }
            val liveReceives = snapshot?.utxos.orEmpty()
                .filter { it.address != changeAddress && (it.height > importedMax || it.height <= 0) }

            val rows = buildList {
                for(e in data.mwebHistory) {
                    val label = (data.txLabels[e.txid] ?: e.label).ifEmpty { null }
                    add(TxInfo(
                        kind = kindFor(e.value, label), label = label,
                        timestamp = e.time.takeIf { it > 0 }, height = e.height,
                        confirmations = null, txid = e.txid, delta = e.value, mweb = true
                    ))
                }
                for(u in liveReceives) add(TxInfo(
                    kind = TxKind.RECEIVED, label = data.txLabels[u.outputId],
                    timestamp = u.blockTime.takeIf { it > 0 }, height = u.height,
                    confirmations = null, txid = u.outputId, delta = u.value, mweb = true
                ))
            }.sortedWith(compareByDescending<TxInfo> { it.height <= 0 }.thenByDescending { it.height })

            if(rows.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("ACTIVITY", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp))
                        for(r in rows) {
                            ActivityRow(r, usdNow) { onTx(r) }
                        }
                        Text("Desktop records up to the last transfer, plus receives this phone has " +
                            "seen since. Private spends made on desktop after the transfer appear " +
                            "after the next pair transfer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
            }
        }

        snapshot?.let { snap ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Coins", style = MaterialTheme.typography.titleSmall)
                    if(snap.utxos.isEmpty()) {
                        Text(
                            if(snap.initialScanComplete)
                                "Coin scan complete — no unspent private coins right now."
                            else
                                "No coins found yet. The scanner checks every private output on the " +
                                "chain against your view key — coins appear here as they're found. " +
                                "Keep the app open.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    for(utxo in snap.utxos.sortedByDescending { it.height }) {
                        Column {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatAmount(Coin.LITECOIN, utxo.value), style = MaterialTheme.typography.bodyMedium,
                                    color = if(utxo.spent) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.primary)
                                Text(
                                    when {
                                        utxo.spent -> "spent"
                                        utxo.height <= 0 -> "unconfirmed"
                                        else -> "height ${utxo.height}"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(formatDate(utxo.blockTime.takeIf { it > 0 }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f)) { Text("Details") }
            OutlinedButton(onClick = onSwitch, modifier = Modifier.weight(1f)) { Text("Wallets") }
        }
        LabelSyncButton(onError = { status = it }, onPairing = onSyncLabels)
    }
}

/**
 * External signing: shows the unsigned PSBT (QR / copy / share) for a desktop or
 * hardware signer, then takes the signed PSBT back (scan / paste), finalizes it,
 * and broadcasts. A txid is shown only when it is final (Native Segwit), and the PSBT as
 * animated frames, since previous transactions can make it too big for one QR. Back keeps
 * the PSBT ([WalletRepository.pendingPsbts]) for the wallet's Home to reopen; broadcasting
 * or Discard drops it.
 */
@Composable
private fun PsbtSignScreen(
    repo: WalletRepository,
    home: Screen.Home,
    unsigned: WalletRepository.UnsignedSendPreview,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val network = remember(home.data) { repo.network(home.data) }
    var signedText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentTxid by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { signedText = it.trim(); error = null }
    }

    ScreenColumn {
        Column {
            Text("External signing", style = MaterialTheme.typography.headlineSmall)
            Text("${network.displayName.uppercase()} · UNSIGNED TRANSACTION",
                fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        sentTxid?.let { txid ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ Sent", style = MaterialTheme.typography.titleMedium, color = kindColor(TxKind.RECEIVED))
                    Text("Signed transaction broadcast to the ${repo.coin(home.data).displayName} network.", style = MaterialTheme.typography.bodySmall)
                    Text(shortAddress(txid), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@ScreenColumn
        }

        ReviewCard(listOf(
            "To" to shortAddress(unsigned.toAddress),
            "Amount" to formatAmount(repo.coin(home.data), unsigned.amount),
            "Fee" to "${formatAmount(repo.coin(home.data), unsigned.fee)} (${unsigned.feeRatePerVb} ${feeUnit(repo.coin(home.data))})"
        ) + listOfNotNull(unsigned.txid?.let { "Txid" to shortAddress(it) }))

        SectionLabel("Give this to the signer")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                AnimatedUrQr(remember(unsigned) { AirgapUr.psbtEncoder(java.util.Base64.getDecoder().decode(unsigned.psbtBase64)) })
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(unsigned.psbtBase64))
                    copied = true
                },
                modifier = Modifier.weight(1f)
            ) { Text(if(copied) "Copied ✓" else "Copy PSBT") }
            OutlinedButton(
                onClick = {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, unsigned.psbtBase64)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share PSBT"))
                },
                modifier = Modifier.weight(1f)
            ) { Text("Share…") }
        }
        Text("On desktop Sparrow: File → Open Transaction → From Text, paste, sign, then copy " +
            "the signed result back here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionLabel("Signed result")
        OutlinedTextField(
            value = signedText,
            onValueChange = { signedText = it; error = null },
            label = { Text("Signed PSBT (base64)") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scanner.launch(paymentScanOptions(repo.coin(home.data))) }, enabled = !busy,
                modifier = Modifier.weight(1f)) { Text("Scan QR") }
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        signedText = it; error = null
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Paste") }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        sentTxid = repo.finalizeAndBroadcast(repo.coin(home.data), network, signedText, unsigned.mwebBlob)
                        if(sentTxid != null) {
                            repo.pendingPsbts.drop(pendingPsbtKey(home))
                            repo.invalidateSnapshots(home.data, home.linkedData)
                        }
                    } catch(t: Throwable) {
                        error = t.message ?: "Could not finalize the PSBT"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && signedText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if(busy) "Broadcasting…" else "Finalize and broadcast") }
        OutlinedButton(
            onClick = { repo.pendingPsbts.drop(pendingPsbtKey(home)); onBack() },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text("Discard this PSBT") }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

/**
 * The phone as the external signer: takes a PSBT built elsewhere (desktop Sparrow, a
 * watch-only coordinator), signs the inputs this wallet owns, and hands the signed
 * PSBT back by QR / copy / share. Keys never leave the device.
 */
@Composable
private fun SignPsbtScreen(repo: WalletRepository, home: Screen.Home, onBack: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val context = LocalContext.current
    val walletData = signingWallet(home)
    var inputText by remember { mutableStateOf("") }
    var signed by remember { mutableStateOf<WalletRepository.SignedPsbt?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var showText by remember { mutableStateOf(false) }
    // a fresh collector per scan, so parts of an abandoned scan never block the next one
    var collector by remember { mutableStateOf(AirgapUr.PsbtCollector()) }
    // the review is what the user approves; a PSBT that fails it can't be signed. It unlocks
    // the wallet, so it runs once typing pauses and off the main thread, and counts only
    // while the field still holds the text it reviewed.
    val reviewed by produceState<ReviewedPsbt<com.sparrowwallet.mobile.crypto.PsbtReview>?>(null, walletData) {
        psbtReviews(snapshotFlow { inputText }) { repo.reviewPsbt(walletData, it) }.collect { value = it }
    }
    val current = reviewed?.takeIf { it.isFor(inputText) }
    val review = current?.result?.map { it.second }

    ScreenColumn {
        Column {
            Text("Sign a transaction", style = MaterialTheme.typography.headlineSmall)
            Text("PSBT · THIS PHONE IS THE SIGNER",
                fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionLabel("Unsigned PSBT")
        if(scanning) {
            UrScanner(
                onFrame = collector::receive,
                onDone = { bytes -> inputText = Psbt.toBase64(bytes); error = null; signed = null; scanning = false }
            )
            OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel scan") }
        } else {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it; error = null; signed = null },
                label = { Text("PSBT (base64)") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { collector = AirgapUr.PsbtCollector(); scanning = true; signed = null },
                    modifier = Modifier.weight(1f)) { Text("Scan QR") }
                OutlinedButton(
                    onClick = {
                        clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            inputText = it; error = null; signed = null
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Paste") }
            }
        }
        review?.onSuccess { if(signed == null) PsbtReviewCard(it, repo.coin(walletData)) }
        (error ?: review?.exceptionOrNull()?.let { it.message ?: "Not a valid PSBT" } ?: review?.getOrNull()?.refusal)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if(signed == null) {
            Button(
                onClick = {
                    try {
                        signed = repo.signPsbt(walletData, current!!.result.getOrThrow().first)
                        copied = false
                        showText = false
                    } catch(t: Throwable) {
                        error = t.message ?: "Could not sign the PSBT"
                    }
                },
                enabled = review?.getOrNull()?.refusal == null && review?.isSuccess == true && !scanning,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Sign with this wallet") }
        }

        signed?.let { result ->
            Text("✓ Signed", style = MaterialTheme.typography.titleMedium, color = kindColor(TxKind.RECEIVED))
            SectionLabel("Show this to desktop Sparrow")
            AnimatedUrQr(remember(result) { AirgapUr.psbtEncoder(result.bytes) })
            if(showText) {
                Text(result.base64, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showText = !showText }, modifier = Modifier.weight(1f)) {
                    Text(if(showText) "Hide text" else "Show as text")
                }
                OutlinedButton(
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(result.base64))
                        copied = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if(copied) "Copied ✓" else "Copy") }
                OutlinedButton(
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, result.base64)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share signed PSBT"))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Share…") }
            }
        }
        BackButton(onClick = onBack)
    }
}

/** Runs one label-sync session against the desktop and reports the outcome. */
@Composable
private fun LabelSyncScreen(
    repo: WalletRepository,
    home: Screen.Home,
    pairing: SparrowLink.Pairing,
    onDone: (Map<String, VaultData>) -> Unit,
    onBack: () -> Unit
) {
    var result by remember { mutableStateOf<WalletRepository.LabelSyncOutcome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pairing) {
        try {
            val wallets = labelSyncWallets(home)
            result = repo.linkSyncLabels(pairing, wallets)
        } catch(t: Throwable) {
            error = t.message ?: "Label sync failed"
        }
    }
    ScreenColumn {
        Text("Label sync", style = MaterialTheme.typography.headlineSmall)
        when {
            error != null -> {
                Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                BackButton(onClick = onBack)
            }
            result == null -> {
                CircularProgressIndicator()
                Text("Connecting to the desktop and exchanging labels…", style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Text(result!!.summary, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { onDone(result!!.updated) }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
    }
}

@Composable
internal fun QrImage(text: String, contentDescription: String = "Address QR code") {
    val bitmap = remember(text) {
        val size = 512
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
        )
        val pixels = IntArray(size * size) { i ->
            if(matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.RGB_565)
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = contentDescription,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ReceiveScreen(repo: WalletRepository, home: Screen.Home, startIndex: Int, onBack: () -> Unit) {
    val data = home.data
    val network = remember(data) { repo.network(data) }

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Receive", style = MaterialTheme.typography.headlineSmall)
            NetworkBadge(network)
        }
        if(data.scriptType == "MWEB") {
            val address = remember(data) { repo.mwebInfo(data).firstAddress }
            QrImage(address)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Private (MWEB) address", style = MaterialTheme.typography.titleSmall)
                    Text(address, style = MaterialTheme.typography.bodyMedium)
                    Text("MWEB addresses are reusable without a privacy loss — payments to this address " +
                        "aren't linkable on-chain.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            val account = remember(data) { repo.accountKey(data) }
            var offset by remember { mutableStateOf(0) }
            val index = startIndex + offset
            val address = remember(account, index, data) { repo.receiveAddress(data, account, index) }
            // every address shown stays watched by the next sync, however far Next address goes
            LaunchedEffect(data, index) { repo.noteReceiveShown(data, index) }
            QrImage(PaymentUri.build(repo.coin(data), address))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Address #$index", style = MaterialTheme.typography.titleSmall)
                    Text(address, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { offset++ }) { Text("Next address") }
                        if(offset > 0) {
                            TextButton(onClick = { offset = 0 }) { Text("First unused") }
                        }
                    }
                    Text("Rotates to a fresh address once one is used. Every address shown here stays watched.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

/**
 * One status vocabulary for both Homes: "Synced" when every present layer is fine,
 * otherwise only the layers with something to report. null layer = not present,
 * "" = fine, anything else = shown.
 */
private fun pairStatus(publicState: String?, privateState: String?): String =
    listOfNotNull(
        publicState?.takeIf { it.isNotEmpty() }?.let { "Public: $it" },
        privateState?.takeIf { it.isNotEmpty() }?.let { "Private: $it" }
    ).joinToString(" • ").ifEmpty { "Synced" }

/** The status line with a running inscription check after it; a bare "Synced" gives way to the check. */
private fun statusWithCheck(status: String?, checking: String?): String? = when {
    checking == null -> status
    status == null || status == "Synced" -> checking
    else -> "$status • $checking"
}

/** The private layer's noteworthy state, or "" when fully caught up. */
private fun mwebStateText(s: MwebdService.MwebSnapshot): String = when {
    s.scanError != null -> "coin stream reconnecting"
    !s.status.synced -> "scanner syncing (block ${s.status.mwebUtxosHeight})"
    !s.initialScanComplete -> "scanning coins (${s.utxos.size} found, ${s.scanUptimeSeconds}s)"
    else -> ""
}

/** Converts a USD amount string to litoshis at [rate] (USD per LTC), or null if invalid. */
private fun usdToLitoshis(text: String, rate: Double): Long? =
    try {
        text.trim().toBigDecimal()
            .divide(rate.toBigDecimal(), 8, java.math.RoundingMode.HALF_UP)
            .movePointRight(8).longValueExact().takeIf { it > 0 }
    } catch(e: Exception) {
        null
    }

private fun paymentScanOptions(coin: Coin) = ScanOptions().apply {
    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    setPrompt("Scan a ${coin.displayName} address or payment QR")
    setBeepEnabled(false)
    setOrientationLocked(true)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Amount entry: currency toggles with a tap on the unit inside the field, Max sweeps
 * the balance, and the equivalence line keeps its slot so nothing jumps.
 */
@Composable
private fun AmountSection(
    amountText: String,
    onAmountChange: (String) -> Unit,
    amountIsUsd: Boolean,
    onUnitToggle: () -> Unit,
    sendMax: Boolean,
    onMaxToggle: () -> Unit,
    usdRate: Double?,
    amountLitoshis: Long?,
    maxHint: String,
    coin: Coin
) {
    SectionLabel("Amount")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = if(sendMax) "" else amountText,
            onValueChange = onAmountChange,
            label = { Text(if(sendMax) maxHint else "Amount") },
            enabled = !sendMax,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            trailingIcon = {
                if(usdRate != null && !sendMax) {
                    TextButton(onClick = onUnitToggle) {
                        Text(if(amountIsUsd) "USD ⇅" else "${coin.ticker} ⇅", fontFamily = MonoFont)
                    }
                } else {
                    Text(if(amountIsUsd) "USD" else coin.ticker, fontFamily = MonoFont,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 14.dp))
                }
            },
            modifier = Modifier.weight(1f)
        )
        if(sendMax) {
            Button(onClick = onMaxToggle) { Text("Max") }
        } else {
            OutlinedButton(onClick = onMaxToggle) { Text("Max") }
        }
    }
    Text(
        when {
            sendMax -> "sending entire balance"
            amountLitoshis != null && amountIsUsd -> "≈ ${formatAmount(coin, amountLitoshis)}"
            amountLitoshis != null -> "≈ ${formatUsd(amountLitoshis, usdRate)}"
            else -> " "
        },
        fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Review as an aligned table, mono throughout — same anatomy as the detail screen. */
@Composable
private fun ReviewCard(rows: List<Pair<String, String>>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Review")
            for((k, v) in rows) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun shortAddress(address: String): String =
    if(address.length <= 22) address else address.take(12) + "…" + address.takeLast(8)

/**
 * Live public-side fee estimate: largest-first coin selection over the wallet's real
 * utxos with the script type's size formula (P2WPKH ~11 + 68/input + 31/output vB; the
 * per-input size follows [inputType], P2TR 58). Returns null
 * when the amount can't be covered. Review still shows the exact built fee.
 */
private fun estimatePublicFee(utxosDesc: List<Long>, amount: Long, sendMax: Boolean, ratePerVb: Long,
                              coin: Coin = Coin.LITECOIN, inputType: ScriptType = ScriptType.P2WPKH): Long? {
    val inputVbytes = when(inputType) { ScriptType.P2PKH -> 148L; ScriptType.P2SH_P2WPKH -> 91L; ScriptType.P2WPKH -> 68L; ScriptType.P2TR -> 58L }
    val outputVbytes = when(inputType) { ScriptType.P2WPKH -> 31L; ScriptType.P2TR -> 43L; else -> 34L }
    fun vsize(inputs: Int, outputs: Int): Long = coin.feeSize(11L + inputVbytes * inputs + outputVbytes * outputs)
    if(utxosDesc.isEmpty()) return null
    if(sendMax) return vsize(utxosDesc.size, 1) * ratePerVb
    var selected = 0
    var total = 0L
    for(value in utxosDesc) {
        selected++
        total += value
        val fee = vsize(selected, 2) * ratePerVb
        if(total >= amount + fee) return fee
    }
    return null
}

@Composable
private fun SendScreen(
    repo: WalletRepository,
    home: Screen.Home,
    onSignExternally: (WalletRepository.UnsignedSendPreview) -> Unit = {},
    onBack: () -> Unit
) {
    // one wallet: both layers fund this screen; the paying layer is chosen automatically
    val (publicData, privateData) = sendWallets(home)
    val scope = rememberCoroutineScope()
    val network = remember(home.data) { repo.network(home.data) }
    val sendCoin = remember(publicData) { publicData?.let { repo.coin(it) } ?: Coin.LITECOIN }
    val copy = remember(sendCoin) { sendCopy(sendCoin) }
    val publicAccount = remember(publicData) { publicData?.let { repo.accountKey(it) } }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    var address by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var sendMax by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Any?>(null) }
    var sentTxid by remember { mutableStateOf<String?>(null) }
    var usdRate by remember { mutableStateOf<Double?>(null) }
    var amountIsUsd by remember { mutableStateOf(false) }
    var feeRate by remember { mutableStateOf<Long?>(null) }
    var scannedCoins by remember { mutableStateOf<SendCoins?>(null) }
    var maturing by remember { mutableStateOf(0L) }
    // coins that may carry an inscription or tokens are held back unless the user includes them
    var includeHeld by remember { mutableStateOf(false) }
    // a sweep (Send max, a peg-in's too) leaves coins not yet checked for inscriptions out unless the user includes them
    var includeUnchecked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if(NetworkPolicy.blocked) return@LaunchedEffect
        usdRate = PriceService.currentUsd(sendCoin)
        feeRate = runCatching { repo.currentFeeRate(sendCoin, network) }.getOrNull()
        val coins = publicData?.let { d -> runCatching { repo.sendCoins(d, publicAccount!!) }.getOrNull() }
        scannedCoins = coins ?: SendCoins(emptyList(), 0L)
        maturing = coins?.maturing ?: 0L
    }
    val heldCoins = scannedCoins?.held.orEmpty()
    val uncheckedCoins = scannedCoins?.unchecked.orEmpty()
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text ->
            try {
                val payment = PaymentUri.parse(text, sendCoin)
                address = payment.address
                payment.amount?.let { amountText = it; sendMax = false; amountIsUsd = false }
                preview = null
                error = null
            } catch(e: Exception) {
                error = e.message ?: "Couldn't read that QR code"
            }
        }
    }

    val isMwebDest = sendCoin == Coin.LITECOIN && repo.isMwebAddress(address, network)
    val addressError = address.trim().takeIf { it.isNotEmpty() }
        ?.let { if(isMwebDest) null else repo.validateAddress(it, walletParams(sendCoin, network)) }
    val sharedWarning = if(isMwebDest || addressError != null || address.isBlank()) null
                        else sendWarning(sendCoin, network, address.trim())
    var sharedConfirmed by remember(address) { mutableStateOf(false) }
    val amountLitoshis = if(amountIsUsd) usdRate?.let { usdToLitoshis(amountText, it) }
                         else parseAmount(amountText)

    // each box counts only while it is shown, so a coin that turns held or unchecked later is never released unseen
    // (the held box is not shown for a peg-in, which never takes held coins)
    val heldIncluded = includeHeld && !isMwebDest && heldCoins.isNotEmpty()
    val uncheckedIncluded = includeUnchecked && sendMax && uncheckedCoins.isNotEmpty()
    // the coins this send can use, in the order it takes them: Send max the Clean ones plus whatever the user includes,
    // a payment Clean first, then Unchecked, then held when included; a peg-in the same, never held ones
    val utxos: List<Long>? = scannedCoins?.let {
        when {
            isMwebDest -> it.pegInUsable(sendMax, uncheckedIncluded)
            sendMax -> it.sweepable(heldIncluded, uncheckedIncluded)
            else -> it.usable(heldIncluded)
        }
    }
    val publicAvail = utxos?.sum() ?: publicData?.let { repo.cachedSnapshot(it)?.balance?.total }
    // spendable = 6+ confirmations, matching the builder's coin rule
    val privateAvail = privateData?.let { repo.cachedMwebSnapshot(it) }?.let { repo.mwebSpendableBalance(it) }

    // fee estimate per possible funding layer (Review shows the exact built fee)
    val peginFee = if(isMwebDest && feeRate != null)
        MwebdService.estimateMwebFee(listOf(MwebdService.OutSpec(0, ByteArray(66))),
            feeRate!! * 1000, includeChange = false) +
            2300 * feeRate!! // the ~2.2KB MWEB blob pays relay fee too; exact figure in Review
    else 0L
    val publicFeeEst: Long? = run {
        val rate = feeRate ?: return@run null
        val coins = utxos ?: return@run null
        if(publicData == null) return@run null
        estimatePublicFee(coins, (amountLitoshis ?: 0L) + peginFee, sendMax, rate, sendCoin, repo.scriptType(publicData))
            ?.plus(peginFee)
    }
    val privateFeeEst: Long? = run {
        val rate = feeRate ?: return@run null
        if(privateData == null) return@run null
        val destScript = try {
            if(isMwebDest || address.isBlank()) ByteArray(66)
            else com.sparrowwallet.mobile.crypto.Addresses.toScriptPubKey(address.trim(), network)
        } catch(e: Exception) { ByteArray(66) }
        MwebdService.estimateMwebFee(listOf(MwebdService.OutSpec(0, destScript)),
            rate * 1000, includeChange = true)
    }

    // pick the paying layer: user override wins; Auto = public when it can cover, else private
    var fundingOverride by remember { mutableStateOf<Boolean?>(null) } // null = Auto
    val amountEntered = (amountLitoshis ?: 0L) > 0 || sendMax
    val autoFunding: Boolean? = when {
        !amountEntered -> when {
            (publicAvail ?: 0L) > 0L -> false
            (privateAvail ?: 0L) > 0L -> true
            else -> if(publicData != null) false else true
        }
        publicFeeEst != null -> false
        privateData != null && sendMax && (privateAvail ?: 0L) > 0L -> true
        privateData != null && privateFeeEst != null &&
            (privateAvail ?: 0L) >= (amountLitoshis ?: 0L) + privateFeeEst -> true
        else -> null // insufficient in both layers
    }
    val fundingPrivate: Boolean? = when(fundingOverride) {
        null -> autoFunding
        false -> if(publicData != null) false else autoFunding
        true -> if(privateData != null) true else autoFunding
    }
    val estimatedFee = when(fundingPrivate) {
        false -> publicFeeEst
        true -> privateFeeEst
        null -> null
    }
    val fundingCovers = when(fundingPrivate) {
        false -> publicFeeEst != null
        true -> if(sendMax) (privateAvail ?: 0L) > 0L
                else privateFeeEst != null && (privateAvail ?: 0L) >= (amountLitoshis ?: 0L) + privateFeeEst
        null -> false
    }
    val amountValid = when(fundingPrivate) {
        false -> sendMax || amountLitoshis != null
        true -> sendMax || amountLitoshis != null
        null -> false
    }

    fun buildPreview() {
        busy = true; error = null
        scope.launch {
            try {
                preview = if(fundingPrivate == true) {
                    repo.prepareMwebSend(privateData!!, address.trim(), amountLitoshis ?: 0L, sendMax)
                } else {
                    repo.prepareSend(publicData!!, publicAccount!!, address.trim(), amountLitoshis ?: 0L, sendMax,
                        heldIncluded, uncheckedIncluded)
                }
            } catch(t: Throwable) {
                error = t.message ?: "Could not build the transaction"
            } finally {
                busy = false
            }
        }
    }

    fun confirmSend() {
        busy = true; error = null
        scope.launch {
            try {
                sentTxid = when(val p = preview) {
                    is WalletRepository.SendPreview -> repo.broadcast(sendCoin, network, p.txHex)
                    is WalletRepository.MwebSendPreview -> repo.broadcastMweb(privateData!!, p.rawTx)
                    else -> null
                }
                if(sentTxid != null) {
                    // the wallet just changed — make the Homes refresh on return
                    repo.invalidateSnapshots(publicData, privateData)
                }
            } catch(t: Throwable) {
                publicData?.let { repo.forgetScan(it) } // a spent coin in the scan: the next Preview scans again
                error = t.message ?: "Broadcast failed"
            } finally {
                busy = false
            }
        }
    }

    ScreenColumn {
        Column {
            Text("Send", style = MaterialTheme.typography.headlineSmall)
            Text("${network.displayName.uppercase()} · ${copy.subtitle}",
                fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        sentTxid?.let { txid ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✓ Sent", style = MaterialTheme.typography.titleMedium, color = kindColor(TxKind.RECEIVED))
                    Text("Transaction broadcast to the ${sendCoin.displayName} network.", style = MaterialTheme.typography.bodySmall)
                    Text(shortAddress(txid), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            return@ScreenColumn
        }

        SectionLabel("To")
        OutlinedTextField(
            value = address,
            onValueChange = { address = it; preview = null },
            label = { Text(copy.addressLabel) },
            isError = addressError != null,
            modifier = Modifier.fillMaxWidth()
        )
        addressError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { scanner.launch(paymentScanOptions(sendCoin)) }, enabled = !busy,
                modifier = Modifier.weight(1f)) { Text("Scan QR") }
            OutlinedButton(
                onClick = {
                    clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        address = it; preview = null; error = null
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Paste") }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if(publicData != null && publicAccount != null) {
                OutlinedButton(
                    onClick = {
                        address = repo.receiveAddress(publicData, publicAccount,
                            repo.cachedSnapshot(publicData)?.firstUnusedReceiveIndex ?: 0)
                        sendMax = false; preview = null
                    },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text(copy.toSelf) }
            }
            if(privateData != null) {
                OutlinedButton(
                    onClick = {
                        address = repo.mwebInfo(privateData).firstAddress
                        sendMax = false; preview = null
                    },
                    enabled = !busy, modifier = Modifier.weight(1f)
                ) { Text("→ My Private") }
            }
        }
        if(sharedWarning != null) {
            Text(sharedWarning, style = MaterialTheme.typography.bodySmall, color = LtcPalette.amber)
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { sharedConfirmed = !sharedConfirmed; preview = null }) {
                Checkbox(checked = sharedConfirmed, onCheckedChange = { sharedConfirmed = it; preview = null })
                Text("The recipient expects ${sendCoin.displayName}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if(address.isNotBlank() && addressError == null) {
            when {
                fundingPrivate == true && isMwebDest ->
                    "Private MWEB transfer — fully confidential."
                fundingPrivate == true && !isMwebDest ->
                    "Paid from Private to a public address — pegs out of MWEB (extra confirmations to mature)."
                fundingPrivate == false && isMwebDest ->
                    "Paid from Public to a private address — pegs into MWEB."
                else -> null
            }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Paying from")
            if(fundingPrivate == null) {
                Text(if(utxos == null) "checking coins…" else copy.shortfall, fontFamily = MonoFont,
                    style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.error)
            } else if(copy.showLayers) {
                LayerTag(privateLayer = fundingPrivate)
            }
        }
        if(publicData != null && privateData != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                @Composable
                fun choice(label: String, selected: Boolean, onClick: () -> Unit) {
                    if(selected) Button(onClick = onClick, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                    else OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text(label) }
                }
                choice("Auto", fundingOverride == null) { fundingOverride = null; preview = null }
                choice("Public", fundingOverride == false) { fundingOverride = false; preview = null }
                choice("Private", fundingOverride == true) { fundingOverride = true; preview = null }
            }
        }
        publicAvail?.let { avail ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(copy.available)
                Text("${formatAmountShort(sendCoin, avail)} · ${formatUsd(avail, usdRate)}",
                    fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if(maturing > 0) {
            Text("${formatAmountShort(sendCoin, maturing)} more is waiting for confirmations before it can be sent " +
                "(${repo.minConfirmations}, or longer for mined coins).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val publicPays = fundingPrivate != true
        // a peg-in never takes held coins: its held line says so, with no box
        if(heldCoins.isNotEmpty() && publicPays) {
            Text(heldCopy(heldCoins.size, formatAmountShort(sendCoin, heldCoins.sum()), scannedCoins?.heldInscribed == true, heldIncluded,
                    pegIn = isMwebDest),
                style = MaterialTheme.typography.bodySmall, color = LtcPalette.amber)
            if(!isMwebDest) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { includeHeld = !includeHeld; preview = null }) {
                    Checkbox(checked = includeHeld, onCheckedChange = { includeHeld = it; preview = null })
                    Text("Include held coins", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // a sweep (a peg-in's too) leaves Unchecked coins out unless the user includes them
        if(uncheckedCoins.isNotEmpty() && publicPays && sendMax && !uncheckedIncluded) {
            Text(uncheckedLeftOutCopy(uncheckedCoins.size, formatAmountShort(sendCoin, uncheckedCoins.sum())),
                style = MaterialTheme.typography.bodySmall, color = LtcPalette.amber)
        }
        if(publicPays && sendMax && uncheckedCoins.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { includeUnchecked = !includeUnchecked; preview = null }) {
                Checkbox(checked = includeUnchecked, onCheckedChange = { includeUnchecked = it; preview = null })
                Text("Include unchecked coins", style = MaterialTheme.typography.bodySmall)
            }
        }
        privateAvail?.let { avail ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Private")
                Text("${formatAmountShort(Coin.LITECOIN, avail)} · ${formatUsd(avail, usdRate)}",
                    fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Est. fee")
            Text(
                when {
                    estimatedFee != null && fundingCovers ->
                        "≈ ${formatAmountShort(sendCoin, estimatedFee)} · ${formatUsd(estimatedFee, usdRate)}"
                    !amountEntered -> "…"
                    utxos == null && fundingPrivate != true -> "checking coins…"
                    fundingPrivate == false -> copy.cannotCover
                    fundingPrivate == true -> "private coins can't cover this"
                    else -> copy.shortfall
                },
                fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AmountSection(
            amountText = amountText,
            onAmountChange = { amountText = it; preview = null },
            amountIsUsd = amountIsUsd,
            onUnitToggle = { amountIsUsd = !amountIsUsd; amountText = ""; preview = null },
            sendMax = sendMax,
            onMaxToggle = { sendMax = !sendMax; preview = null },
            usdRate = usdRate,
            amountLitoshis = amountLitoshis,
            maxHint = if(fundingPrivate == true) "Entire private balance" else copy.maxHint,
            coin = sendCoin
        )

        (preview as? WalletRepository.SendPreview)?.let { p ->
            ReviewCard(listOf(
                "From" to copy.from,
                "To" to shortAddress(p.toAddress),
                "Amount" to "${formatAmount(sendCoin, p.amount)} · ${formatUsd(p.amount, usdRate)}",
                "Fee" to "${formatAmount(sendCoin, p.fee)} · ${formatUsd(p.fee, usdRate)}",
                "Total" to "${formatAmount(sendCoin, p.amount + p.fee)} · ${formatUsd(p.amount + p.fee, usdRate)}"
            ))
            if(p.held > 0) {
                Text(heldWarning(p.held), style = MaterialTheme.typography.bodySmall, color = LtcPalette.amber)
            }
            if(p.unchecked > 0) {
                Text(uncheckedWarning(p.unchecked), style = MaterialTheme.typography.bodySmall, color = LtcPalette.amber)
            }
        }
        (preview as? WalletRepository.MwebSendPreview)?.let { p ->
            ReviewCard(listOf(
                "From" to "Private layer",
                "To" to shortAddress(p.toAddress),
                "Amount" to "${formatAmount(Coin.LITECOIN, p.amount)} · ${formatUsd(p.amount, usdRate)}",
                "MWEB fee" to "${formatAmount(Coin.LITECOIN, p.fee)} · ${formatUsd(p.fee, usdRate)}",
                "Type" to if(p.pegOut) "Move to Public (peg-out)" else "Private transfer"
            ))
        }

        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        if(preview == null) {
            Button(
                onClick = { buildPreview() },
                enabled = !busy && address.trim().isNotEmpty() && addressError == null
                        && amountValid && fundingPrivate != null && fundingCovers
                        && (sharedWarning == null || sharedConfirmed),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if(busy) "Building…" else "Preview") }
        } else {
            // the old Preview position becomes Edit, so a stray second tap can't broadcast
            OutlinedButton(
                onClick = { preview = null },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("← Edit") }
            Button(
                onClick = { confirmSend() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if(busy) "Broadcasting…" else "Confirm and send") }
            // external signer path: any public-funded send, peg-ins included
            if(preview is WalletRepository.SendPreview && publicData != null
                && externalSigningSupported(sendCoin, network, publicData.scriptType)) {
                OutlinedButton(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                val unsigned = repo.prepareSendPsbt(
                                    publicData!!, publicAccount!!, address.trim(),
                                    amountLitoshis ?: 0L, sendMax, heldIncluded, uncheckedIncluded)
                                // the PSBT selects its coins again with the checks as they are now: unless it spends
                                // exactly the reviewed coins for the reviewed amount, review again rather than hand
                                // the signer coins the user did not see
                                val reviewed = preview as? WalletRepository.SendPreview
                                if(reviewed == null || !psbtMatchesPreview(reviewed.inputs, reviewed.amount, unsigned.inputs, unsigned.amount)) {
                                    preview = null
                                    error = "Coin checks changed since Preview. Preview again."
                                } else {
                                    onSignExternally(unsigned)
                                }
                            } catch(t: Throwable) {
                                error = t.message ?: "Could not build the PSBT"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sign externally instead (PSBT)…") }
            }
        }
        BackButton(enabled = !busy, onClick = onBack)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ServerScreen(repo: WalletRepository, data: VaultData, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val network = remember(data) { repo.network(data) }
    val coin = remember(data) { repo.coin(data) }
    val state = remember { serverScreenState(repo.serverSettings(coin, network)) }
    val saved = (state as? ServerScreenState.Configured)?.settings
    var kind by remember { mutableStateOf(serverKindFor(coin, saved)) }
    val esplora = kind == BackendKind.ESPLORA
    val savedElectrum = saved?.takeIf { it.esploraUrl == null }
    var host by remember { mutableStateOf(savedElectrum?.host ?: "") }
    var port by remember { mutableStateOf(savedElectrum?.port?.toString() ?: "50002") }
    var esploraUrl by remember { mutableStateOf(saved?.esploraUrl ?: "") }
    var useTls by remember { mutableStateOf(savedElectrum?.useTls ?: true) }
    var selfSigned by remember { mutableStateOf(savedElectrum?.allowSelfSigned ?: false) }
    var useProxy by remember { mutableStateOf(saved?.useProxy ?: false) }
    var proxyHost by remember { mutableStateOf(saved?.proxyHost ?: "127.0.0.1") }
    var proxyPort by remember { mutableStateOf(saved?.proxyPort?.toString() ?: "9050") }
    val publicIndexer = remember { InscriptionSetting.Public.url(coin, network) }
    var savedChecks by remember { mutableStateOf(repo.inscriptionSetting(coin, network)) }
    // the proxy the saved setting was last checked through: an unchanged Your own is not asked again
    var savedProxy by remember { mutableStateOf(saved?.takeIf { it.useProxy }?.let { com.sparrowwallet.mobile.electrum.SocksProxy(it.proxyHost, it.proxyPort) }) }
    // Public / Your own / Built-in; Public only where the coin has a public indexer
    var checks by remember { mutableStateOf(InscriptionChoice.of(savedChecks)) }
    var checksUrl by remember { mutableStateOf((savedChecks as? InscriptionSetting.YourOwn)?.customUrl ?: "") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun current(): WalletRepository.ServerSettings? =
        ServerFields(kind, host, port, useTls, selfSigned, esploraUrl, useProxy, proxyHost, proxyPort).settings()

    ScreenColumn {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(if(esplora) "Esplora server" else "Electrum server", style = MaterialTheme.typography.headlineSmall)
            NetworkBadge(network)
        }
        Text("The node this wallet reads the ${coin.displayName} network through. Settings are stored per coin and network.",
            style = MaterialTheme.typography.bodySmall)
        if(state == ServerScreenState.NeedsOwnServer) {
            Text("${coin.displayName} ${network.displayName} has no public server. Enter your own node's server to use this wallet.",
                style = MaterialTheme.typography.bodyMedium, color = LtcPalette.amber)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for((k, label) in listOf(BackendKind.ELECTRUM to "Electrum server", BackendKind.ESPLORA to "Esplora API")) {
                if(kind == k) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label) }
                else OutlinedButton(onClick = { kind = k; status = null }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
        if(esplora) {
            OutlinedTextField(value = esploraUrl, onValueChange = { esploraUrl = it },
                label = { Text("Esplora API URL (https://…/api)") }, modifier = Modifier.fillMaxWidth())
        } else {
            OutlinedTextField(value = host, onValueChange = { host = it },
                label = { Text("Host (.onion needs the Tor proxy below)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            LabeledSwitch("Use TLS", useTls) { useTls = it }
            LabeledSwitch("Accept self-signed certificate", selfSigned) { selfSigned = it }
        }
        LabeledSwitch("Route through Tor / SOCKS5 proxy (Orbot)", useProxy) { useProxy = it }
        if(useProxy) {
            OutlinedTextField(value = proxyHost, onValueChange = { proxyHost = it },
                label = { Text("Proxy host (Orbot: 127.0.0.1)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = proxyPort, onValueChange = { proxyPort = it },
                label = { Text("Proxy port (Orbot: 9050)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("Install the Orbot app and start it before syncing over Tor.",
                style = MaterialTheme.typography.bodySmall)
        }
        SectionLabel("Inscription checks")
        Text("Public or Your own asks an indexer which coins carry an inscription or runes, so none is spent by accident. " +
            "The indexer sees the coins it is asked about. Built-in checks on-chain only: slower, and it cannot see runes.",
            style = MaterialTheme.typography.bodySmall)
        RadioList(inscriptionChoices(publicIndexer), checks, { inscriptionChoiceLabel(it, publicIndexer) }) { checks = it }
        if(checks == InscriptionChoice.YOUR_OWN) {
            OutlinedTextField(value = checksUrl, onValueChange = { checksUrl = it },
                label = { Text("Indexer URL (ord or Nintondo API)") }, modifier = Modifier.fillMaxWidth())
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val s = current() ?: run { status = "Fix the server fields first"; return@OutlinedButton }
                    busy = true; status = "Connecting…"
                    scope.launch {
                        status = try {
                            "Connected: " + connectionSummary(repo.testConnection(s, coin, network))
                        } catch(t: Throwable) {
                            "Connection failed: ${t.message}"
                        }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Test") }
            Button(
                onClick = {
                    val s = current() ?: run { status = "Fix the server fields first"; return@Button }
                    val checksSetting = when(checks) {
                        InscriptionChoice.PUBLIC -> InscriptionSetting.Public
                        InscriptionChoice.YOUR_OWN -> when(val u = checkYourOwnUrl(checksUrl)) {
                            is YourOwnUrl.Ok -> InscriptionSetting.YourOwn(u.url)
                            is YourOwnUrl.Refused -> { status = u.reason; return@Button }
                        }
                        InscriptionChoice.BUILT_IN -> InscriptionSetting.BuiltIn
                    }
                    // the inscription setting is written only when it changed; a new or changed Your own (or proxy) is
                    // opened first (its chain and kind checked) through the proxy being saved, and nothing is saved when
                    // it is refused
                    val proxy = s.takeIf { it.useProxy }?.let { com.sparrowwallet.mobile.electrum.SocksProxy(it.proxyHost, it.proxyPort) }
                    val shown = savedChecks
                    val shownProxy = savedProxy
                    busy = true; status = "Saving…"
                    scope.launch {
                        try {
                            val refused = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                saveInscriptionChoice(shown, shownProxy, checksSetting, proxy, NetworkPolicy.blocked, { url ->
                                    com.sparrowwallet.mobile.electrum.indexer.Indexers.open(url, coin, network, proxy)
                                }) { changed ->
                                    repo.saveServerSettings(coin, network, s)
                                    changed?.let { repo.saveInscriptionSetting(coin, network, it) }
                                }
                            }
                            if(refused != null) {
                                status = refused
                            } else {
                                savedChecks = repo.inscriptionSetting(coin, network)
                                savedProxy = proxy
                                status = "Saved for ${coin.displayName} ${network.displayName}"
                            }
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy, modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }
        BackButton(onClick = onBack)
    }
}

@Composable
private fun DetailsScreen(repo: WalletRepository, name: String, data: VaultData, onExport: () -> Unit, onBack: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf<VaultData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val network = remember(data) { repo.network(data) }

    ScreenColumn {
        Text("Wallet details", style = MaterialTheme.typography.headlineSmall)
        val shown = revealed
        if(shown == null) {
            Text("Re-enter the wallet password to view the seed and keys.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = PasswordKeyboard,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    error = try { revealed = repo.unlock(name, password, session = false); null }
                            catch(t: Throwable) { "Wrong password" }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Reveal") }
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recovery phrase", style = MaterialTheme.typography.titleSmall)
                    Text(shown.mnemonic, style = MaterialTheme.typography.bodyLarge)
                    if(shown.passphrase.isNotEmpty()) {
                        Text("Passphrase: ${shown.passphrase}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("${shown.seedType} • ${shown.derivationPath} • ${network.displayName}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Account xpub (watch-only import)", style = MaterialTheme.typography.titleSmall)
                    Text(repo.accountXpub(shown), style = MaterialTheme.typography.bodySmall)
                }
            }
            if(repo.coin(shown).mweb) {
            val mweb = remember(shown) { repo.mwebInfo(shown) }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MWEB (private)", style = MaterialTheme.typography.titleSmall)
                    Text("First address:", style = MaterialTheme.typography.bodySmall)
                    Text(mweb.firstAddress, style = MaterialTheme.typography.bodySmall)
                    Text("View key — scan secret:", style = MaterialTheme.typography.bodySmall)
                    Text(mweb.scanSecretHex, style = MaterialTheme.typography.bodySmall)
                    Text("View key — spend pubkey:", style = MaterialTheme.typography.bodySmall)
                    Text(mweb.spendPubKeyHex, style = MaterialTheme.typography.bodySmall)
                    Text("Same two lines Electrum-LTC shows under Wallet → Information, and what desktop " +
                        "Sparrow-Scrypt derives, usable for MWEB watch-only tools. MWEB balance and sending " +
                        "aren't on mobile yet (needs the mwebd scanner); funds sent here are visible on desktop.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            }
        }
        if(externalSigningSupported(repo.coin(data), repo.network(data), data.scriptType)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                Text("Export to desktop (air-gapped)")
            }
        }
        BackButton(onClick = onBack)
    }
}
