package com.sparrowwallet.mobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.track.InscriptionContent
import com.sparrowwallet.mobile.electrum.inscriptions.InscriptionCheck
import com.sparrowwallet.mobile.storage.VaultData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Collectibles screen and an inscription's detail. Thin by design: what shows comes from CollectiblesModel,
 * InscriptionPreview, InscriptionDetail and the checker; these composables only draw it and pass taps on.
 */

/**
 * The shown wallets' stored rows once read, by layer (null inside: never checked), so "not read yet" is told from
 * "never checked", and rows read for another set of wallets are never shown for this one.
 */
private class StoredRows(val checks: Map<Layer, Map<String, InscriptionCheck>?>)

/** One visit of a wallet's Collectibles, the grid and its detail screens: the grid's place and the previews that failed. */
internal class CollectiblesVisit {
    val grid = LazyGridState()
    val failures = PreviewFailures()
}

/** Preview decodes, one at a time across the app (see [OneAtATime]). */
private val previewDecodes = OneAtATime()

/**
 * The Collectibles of [home]'s shown wallet and, from the Public layer, its Taproot wallet too
 * ([CollectiblesModel.shownLayers]). Each tile's and row's actions go to the wallet holding it ([CollectiblesModel.holder]).
 * [addTaproot] runs "Add Taproot wallet" (slow: it is called off the main thread) and [onAdded] shows what it gave.
 */
@Composable
internal fun CollectiblesScreen(repo: WalletRepository, home: Screen.Home, visit: CollectiblesVisit, onTile: (CollectibleTile) -> Unit,
                                onBack: () -> Unit, addTaproot: (String) -> AddTaprootOutcome, onAdded: (Screen.Collectibles) -> Unit) {
    val family = home.family
    val data = home.data
    val coin = remember(data) { repo.coin(data) }
    val network = remember(data) { repo.network(data) }
    val gapped = NetworkPolicy.blocked
    val setting = remember(data) { repo.inscriptionSetting(coin, network) }
    val members = remember(family, home.layer) {
        CollectiblesModel.shownLayers(family, home.layer).mapNotNull { layer -> family.member(layer)?.let { layer to it } }
    }
    // each shown wallet's run and deeper queue, keyed by layer: adding the Taproot wallet adds one
    val runs = members.map { (layer, member) ->
        androidx.compose.runtime.key(layer, member.name) {
            val status = remember(member.data) { repo.inscriptionStatus(member.data) }?.collectAsState()?.value
            val queued = remember(member.data) { repo.deeperQueued(member.data) }?.collectAsState()?.value ?: emptySet()
            status to queued
        }
    }
    // read again whenever a run moves on: each decided coin is saved as the run goes
    val stored by produceState<StoredRows?>(null, members, runs) {
        value = StoredRows(members.associate { (layer, member) -> layer to repo.loadInscriptionChecks(member.data) })
    }
    // "Add Taproot wallet" is judged from the wallet list (read off the main thread) once per add on this screen; the
    // view waits for the first judgement, so the hint never pops in under a shown header
    var added by remember { mutableStateOf(0) }
    val offer by produceState<TaprootOffer?>(null, home, added) {
        val round = added
        val public = family.public
        value = TaprootOffer(round, withContext(Dispatchers.IO) {
            runCatching {
                val wallets = repo.listWallets()
                CollectiblesModel.companionHint(wallets.firstOrNull { it.name == walletFileName(public.name) },
                    public.data.mnemonic.isNotBlank(), public.data.seedType, wallets, home.layer)
            }.getOrDefault(false)
        })
    }
    val view = remember(stored, members, runs, setting, gapped, offer, added) {
        val judged = offer ?: return@remember null
        stored?.takeIf { it.checks.keys == members.map { m -> m.first }.toSet() }?.let { rows ->
            CollectiblesModel.build(
                members.mapIndexed { i, (layer, member) ->
                    WalletChecks(layer, rows.checks[layer], runs[i].first, runs[i].second, member.data.scriptType)
                },
                setting, coin, gapped, offerTaproot = CollectiblesModel.offerNow(judged, added)
            )
        }
    }
    val failures = visit.failures

    // a line after an add whose wallet is not opened with the family this time
    var note by remember { mutableStateOf<String?>(null) }
    val prompt = remember { PasswordPromptState() }
    val scope = rememberCoroutineScope()
    PasswordPrompt(prompt, title = CollectiblesModel.ADD_TAPROOT, confirm = "Add",
        text = "Enter the password for ${family.public.name}",
        onConfirm = { typed ->
            // leaving the screen cancels the wait (the wallet itself is still made); a refusal keeps the dialog open
            scope.launch {
                when(val outcome = withContext(Dispatchers.Default) { addTaproot(typed) }) {
                    is AddTaprootOutcome.Refused -> prompt.refused(outcome.text)
                    is AddTaprootOutcome.Added -> {
                        prompt.done()
                        // the offer judged before this add goes stale at once: the button is gone until judged again
                        added++
                        note = outcome.note
                        onAdded(outcome.next)
                    }
                }
            }
        })

    // one lazy grid holds the whole screen, so nothing scrolls inside anything else
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = visit.grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        full("title") { Text("Collectibles", style = MaterialTheme.typography.headlineSmall) }
        if(view == null) {
            full("loading") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        } else {
            full("header") {
                Text(view.header, fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            note?.let { line -> full("taproot-note") { Text(line, style = MaterialTheme.typography.bodyMedium) } }
            if(view.showHint) {
                full("taproot-hint") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(CollectiblesModel.TAPROOT_HINT, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { prompt.show() }, modifier = Modifier.fillMaxWidth()) {
                            Text(CollectiblesModel.ADD_TAPROOT)
                        }
                    }
                }
            }
            if(view.empty) {
                full("empty") { Text(CollectiblesModel.EMPTY, style = MaterialTheme.typography.bodyMedium) }
            }
            items(view.tiles, key = { "tile/${it.key}" }, contentType = { "tile" }) { tile ->
                CollectibleTileCard(repo, CollectiblesModel.holder(family, home.layer, tile.layer)?.data, tile, gapped, failures,
                    onClick = { onTile(tile) })
            }
            if(!view.runes.isNullOrEmpty() || view.runesNote != null) {
                full("runes") { RunesCard(view) }
            }
            if(view.unchecked.isNotEmpty()) {
                full("unchecked/title") { Text("Coins not yet checked", style = MaterialTheme.typography.titleSmall) }
                items(view.unchecked, key = { "unchecked/${it.key}" }, span = { GridItemSpan(maxLineSpan) },
                    contentType = { "unchecked" }) { row ->
                    UncheckedCard(row, coin, gapped, onCheckDeeper = {
                        // queued on the wallet holding the coin, never on the other's results
                        CollectiblesModel.holder(family, home.layer, row.layer)?.let { repo.requestDeeperCheck(it.data, row.outpoint) }
                    })
                }
            }
        }
        full("back") { BackButton(onClick = onBack) }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/**
 * One tile: its preview, loaded through the wallet holding it ([data], null when none is shown: no load) while it is on
 * screen (leaving the screen cancels the load), its label and its tag.
 */
@Composable
private fun CollectibleTileCard(repo: WalletRepository, data: VaultData?, tile: CollectibleTile, gapped: Boolean,
                                failures: PreviewFailures, onClick: () -> Unit) {
    var content by remember(tile.key) { mutableStateOf<InscriptionContent?>(null) }
    var failed by remember(tile.key) { mutableStateOf(tile.key in failures || data == null) }
    LaunchedEffect(tile.key) {
        if(data == null || !InscriptionPreview.shouldLoad(tile, gapped, failures)) return@LaunchedEffect
        val loaded = repo.inscriptionContent(data, tile.inscriptionId, tile.mintTxid)
        if(loaded == null) {
            failures.add(tile.key)
            failed = true
        } else {
            content = loaded
        }
    }
    val shown = remember(tile, gapped, failed, content) { InscriptionPreview.shown(tile, gapped, failed, content) }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PreviewBox(shown, tile.label, InscriptionPreview.THUMB_TARGET, InscriptionPreview.TILE_TEXT_LINES,
                onDecodeFailed = { failures.add(tile.key); failed = true },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Text(tile.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            tile.tag?.let { WalletTag(it) }
            content?.let {
                Text(InscriptionPreview.typeText(it.contentType), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Draws a decided preview. Text is plain [Text] (no links, no markup, not selectable); an image is decoded off the main
 * thread by [decodeStill] and described by [description] (the tile's label, never the inscription's own text).
 */
@Composable
private fun PreviewBox(shown: InscriptionPreview.Shown, description: String, target: Int, textLines: Int,
                       onDecodeFailed: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when(shown) {
            is InscriptionPreview.Shown.Loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            is InscriptionPreview.Shown.Unavailable -> Text(shown.text, style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(6.dp))
            is InscriptionPreview.Shown.Text -> Text(shown.text, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                maxLines = textLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(6.dp).align(Alignment.TopStart))
            is InscriptionPreview.Shown.Placeholder -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(6.dp)) {
                Text(shown.type, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(shown.size, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                shown.note?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            is InscriptionPreview.Shown.Image -> {
                // keyed on the body (the cached content's own array), so a recomposition does not decode again
                val bitmap by produceState<ImageBitmap?>(null, shown.body, target) {
                    //One decode at a time across the app; a tile that left while waiting never decodes
                    val decoded = previewDecodes.run { decodeStill(shown, target) }
                    if(decoded == null) onDecodeFailed() else value = decoded.asImageBitmap()
                }
                bitmap?.let {
                    Image(it, contentDescription = description, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } ?: CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * A still frame of bytes [PreviewRules] cleared (see [InscriptionPreview.Shown.Image]): a bounds-only pass first, whose
 * bounds must agree with the header parse and the limits ([InscriptionPreview.decodedBoundsOk]), then a sampled decode.
 * BitmapFactory gives the first frame of an animated image only. Null on any refusal or failure, out of memory included.
 * Not a suspend function: call it off the main thread.
 */
private fun decodeStill(image: InscriptionPreview.Shown.Image, target: Int): Bitmap? {
    val body = image.body
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(body, 0, body.size, bounds)
        if(!InscriptionPreview.decodedBoundsOk(image.width, image.height, bounds.outWidth, bounds.outHeight)) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = PreviewRules.sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        BitmapFactory.decodeByteArray(body, 0, body.size, options)
    } catch(t: Throwable) {
        null
    }
}

/** The wallet an item belongs to, as words (never colour alone). */
@Composable
private fun WalletTag(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
}

@Composable
private fun RunesCard(view: CollectiblesView) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Runes", style = MaterialTheme.typography.titleSmall)
            for(rune in view.runes.orEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rune.name + (rune.symbol?.let { " $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f))
                    Text(rune.amount, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                }
            }
            view.runesNote?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun UncheckedCard(row: UncheckedRow, coin: com.sparrowwallet.mobile.crypto.Coin, gapped: Boolean, onCheckDeeper: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            row.tag?.let { WalletTag(it) }
            Text(row.outpoint, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
            row.value?.let { Text(formatAmount(coin, it), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall) }
            Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when(CollectiblesModel.deeperButton(row, gapped)) {
                DeeperButton.NONE -> {}
                DeeperButton.CHECK -> OutlinedButton(onClick = onCheckDeeper, modifier = Modifier.fillMaxWidth()) {
                    Text(CollectiblesModel.CHECK_DEEPER)
                }
                DeeperButton.QUEUED -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(CollectiblesModel.QUEUED)
                }
                DeeperButton.OFF -> OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(CollectiblesModel.CHECK_DEEPER)
                }
            }
        }
    }
}

/**
 * One inscription: its preview, the facts [InscriptionDetail] lists, and Copy for the id and the outpoint. Its content and
 * address come from the wallet holding it ([CollectiblesModel.holder]); with none shown there, neither is read.
 */
@Composable
internal fun InscriptionScreen(repo: WalletRepository, home: Screen.Home, tile: CollectibleTile, visit: CollectiblesVisit,
                                onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val holder = remember(home.family, home.layer, tile.layer) { CollectiblesModel.holder(home.family, home.layer, tile.layer) }
    val data = holder?.data
    val coin = remember(home.data) { repo.coin(home.data) }
    val gapped = NetworkPolicy.blocked
    var content by remember(tile.key) { mutableStateOf<InscriptionContent?>(null) }
    var failed by remember(tile.key) { mutableStateOf(tile.key in visit.failures || data == null) }
    var copied by remember(tile.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(tile.key) {
        if(data == null || !InscriptionPreview.shouldLoad(tile, gapped, visit.failures)) return@LaunchedEffect
        val loaded = repo.inscriptionContent(data, tile.inscriptionId, tile.mintTxid)
        if(loaded == null) {
            visit.failures.add(tile.key)
            failed = true
        } else {
            content = loaded
        }
    }
    // the address is derived from the holding wallet's keys: off the main thread
    val address by produceState<String?>(null, tile.key, data) {
        val position = InscriptionDetail.position(tile) ?: return@produceState
        val from = data ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { repo.addressAt(from, position.first, position.second) }.getOrNull() }
    }
    val shown = remember(tile, gapped, failed, content) { InscriptionPreview.shown(tile, gapped, failed, content) }
    ScreenColumn {
        Text(tile.label, style = MaterialTheme.typography.headlineSmall)
        PreviewBox(shown, tile.label, InscriptionPreview.DETAIL_TARGET, Int.MAX_VALUE,
            onDecodeFailed = { visit.failures.add(tile.key); failed = true },
            modifier = if(shown is InscriptionPreview.Shown.Text) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(1f))
        InscriptionDetail.carries(tile)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for(line in InscriptionDetail.lines(tile, coin, content?.let { InscriptionPreview.contentLine(it) }, address,
                    CollectiblesModel.detailWallet(home.family, home.layer, tile.layer))) {
                    Column {
                        Text(line.label, fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(line.value, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                            if(line.copy) {
                                TextButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(line.value))
                                        copied = line.label
                                    },
                                    modifier = Modifier.semantics { contentDescription = "Copy ${line.label}" }
                                ) { Text(if(copied == line.label) "Copied" else "Copy") }
                            }
                        }
                    }
                }
            }
        }
        BackButton(onClick = onBack)
    }
}
