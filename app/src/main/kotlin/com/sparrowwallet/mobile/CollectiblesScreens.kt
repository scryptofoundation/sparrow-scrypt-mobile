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
import com.sparrowwallet.mobile.crypto.HDKey
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

/** The stored rows once read (null inside: never checked), so "not read yet" is told from "never checked". */
private class StoredRows(val checks: Map<String, InscriptionCheck>?)

@Composable
internal fun CollectiblesScreen(repo: WalletRepository, data: VaultData, onTile: (CollectibleTile) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val coin = remember(data) { repo.coin(data) }
    val network = remember(data) { repo.network(data) }
    val gapped = NetworkPolicy.blocked
    val setting = remember(data) { repo.inscriptionSetting(coin, network) }
    val status = remember(data) { repo.inscriptionStatus(data) }?.collectAsState()?.value
    val queued = remember(data) { repo.deeperQueued(data) }?.collectAsState()?.value ?: emptySet()
    // read again whenever the run moves on: each decided coin is saved as the run goes
    val stored by produceState<StoredRows?>(null, data, status, queued) { value = StoredRows(repo.loadInscriptionChecks(data)) }
    val view = remember(stored, status, queued, setting) {
        stored?.let { CollectiblesModel.build(it.checks, status, setting, coin, queued) }
    }
    val failures = remember(data) { PreviewFailures() }
    var account by remember(data) { mutableStateOf<HDKey?>(null) }

    fun checkDeeper(outpoint: String) {
        scope.launch {
            val key = account ?: withContext(Dispatchers.Default) { repo.accountKey(data) }.also { account = it }
            repo.requestDeeperCheck(data, key, outpoint)
        }
    }

    // one lazy grid holds the whole screen, so nothing scrolls inside anything else
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
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
            if(view.empty) {
                full("empty") { Text(CollectiblesModel.EMPTY, style = MaterialTheme.typography.bodyMedium) }
            }
            items(view.tiles, key = { "tile/${it.key}" }, contentType = { "tile" }) { tile ->
                CollectibleTileCard(repo, data, tile, gapped, failures, onClick = { onTile(tile) })
            }
            if(!view.runes.isNullOrEmpty() || view.runesNote != null) {
                full("runes") { RunesCard(view) }
            }
            if(view.unchecked.isNotEmpty()) {
                full("unchecked/title") { Text("Coins not yet checked", style = MaterialTheme.typography.titleSmall) }
                items(view.unchecked, key = { "unchecked/${it.outpoint}" }, span = { GridItemSpan(maxLineSpan) },
                    contentType = { "unchecked" }) { row ->
                    UncheckedCard(row, coin, gapped, onCheckDeeper = { checkDeeper(row.outpoint) })
                }
            }
        }
        full("back") { BackButton(onClick = onBack) }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** One tile: its preview, loaded while it is on screen (leaving the screen cancels the load), and its label. */
@Composable
private fun CollectibleTileCard(repo: WalletRepository, data: VaultData, tile: CollectibleTile, gapped: Boolean,
                                failures: PreviewFailures, onClick: () -> Unit) {
    var content by remember(tile.key) { mutableStateOf<InscriptionContent?>(null) }
    var failed by remember(tile.key) { mutableStateOf(tile.key in failures) }
    LaunchedEffect(tile.key) {
        if(!InscriptionPreview.shouldLoad(tile, gapped, failures)) return@LaunchedEffect
        val loaded = repo.inscriptionContent(data, tile.inscriptionId, tile.mintTxid)
        if(loaded == null) {
            failures.add(tile.key)
            failed = true
        } else {
            content = loaded
        }
    }
    val shown = InscriptionPreview.shown(tile, gapped, failed, content)
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PreviewBox(shown, tile.label, InscriptionPreview.THUMB_TARGET, InscriptionPreview.TILE_TEXT_LINES,
                onDecodeFailed = { failures.add(tile.key); failed = true },
                modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            Text(tile.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            }
            is InscriptionPreview.Shown.Image -> {
                // keyed on the body (the cached content's own array), so a recomposition does not decode again
                val bitmap by produceState<ImageBitmap?>(null, shown.body, target) {
                    val decoded = withContext(Dispatchers.Default) { decodeStill(shown, target) }
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

/** One inscription: its preview, the facts [InscriptionDetail] lists, and Copy for the id and the outpoint. */
@Composable
internal fun InscriptionScreen(repo: WalletRepository, data: VaultData, tile: CollectibleTile, onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val coin = remember(data) { repo.coin(data) }
    val gapped = NetworkPolicy.blocked
    var content by remember(tile.key) { mutableStateOf<InscriptionContent?>(null) }
    var failed by remember(tile.key) { mutableStateOf(false) }
    var copied by remember(tile.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(tile.key) {
        if(!InscriptionPreview.shouldLoad(tile, gapped, PreviewFailures())) return@LaunchedEffect
        val loaded = repo.inscriptionContent(data, tile.inscriptionId, tile.mintTxid)
        if(loaded == null) failed = true else content = loaded
    }
    // the address is derived from the wallet's keys: off the main thread
    val address by produceState<String?>(null, tile.key) {
        val position = InscriptionDetail.position(tile) ?: return@produceState
        value = withContext(Dispatchers.Default) { runCatching { repo.addressAt(data, position.first, position.second) }.getOrNull() }
    }
    val shown = InscriptionPreview.shown(tile, gapped, failed, content)
    ScreenColumn {
        Text(tile.label, style = MaterialTheme.typography.headlineSmall)
        PreviewBox(shown, tile.label, InscriptionPreview.DETAIL_TARGET, Int.MAX_VALUE, onDecodeFailed = { failed = true },
            modifier = if(shown is InscriptionPreview.Shown.Text) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().aspectRatio(1f))
        InscriptionDetail.carries(tile)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for(line in InscriptionDetail.lines(tile, coin, content?.let { InscriptionPreview.contentLine(it) }, address)) {
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
