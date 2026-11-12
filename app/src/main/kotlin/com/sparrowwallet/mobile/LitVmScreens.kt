package com.sparrowwallet.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sparrowwallet.mobile.crypto.NetworkPolicy
import com.sparrowwallet.mobile.crypto.evm.EvmAddress
import com.sparrowwallet.mobile.crypto.evm.NftPicturePlan
import com.sparrowwallet.mobile.crypto.evm.SvgSubset
import com.sparrowwallet.mobile.electrum.litvm.LitVmNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The LitVM screen and an NFT's detail. Thin by design: what shows comes from LitVmModel; these composables only draw
 * it and pass taps on. Contract text is only ever plain Text, each value on its own, left to right, with line limits.
 */

/**
 * One visit of a wallet's LitVM screen and its NFT details: the address (only the 20 bytes), the last read, the grid's
 * place, and the reads asked for ([requested]: the open and each Refresh) and finished ([done]). The read belongs to the
 * visit, not to a screen: opening a detail does not stop it, coming back does not start one, and [close] (leaving the
 * visit) cancels it, which closes its client.
 */
internal class LitVmVisit {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val grid = LazyGridState()
    var address by mutableStateOf<EvmAddress?>(null)
    var addressFailed by mutableStateOf(false)
    var read by mutableStateOf<LitVmRead?>(null)
    var reading by mutableStateOf(false)
    var requested by mutableIntStateOf(1)
    var done by mutableIntStateOf(0)
    var names by mutableStateOf<Map<EvmAddress, String>>(emptyMap())

    /** Starts [work] as this visit's read; [reading] until it ends. */
    fun start(work: suspend () -> Unit) {
        reading = true
        scope.launch {
            try {
                work()
            } finally {
                reading = false
            }
        }
    }

    fun close() = scope.cancel()
}

/** Drawn pictures, by chain, contract, token id, source and size: at most a screenful of tiles and 8 MiB. */
private val litVmBitmaps = SizedLru<Bitmap>(LitVmModel.MAX_TILES, 8L * 1024 * 1024) { it.allocationByteCount.toLong() }

/**
 * The wallet's LitVM account on LiteForge: its address, its zkLTC balance and the NFTs it holds in the built-in
 * collections. Reads only during its visit: when it first opens and on Refresh; leaving the visit cancels the read.
 */
@Composable
internal fun LitVmScreen(repo: WalletRepository, home: Screen.Home, visit: LitVmVisit, onTile: (String) -> Unit, onBack: () -> Unit) {
    val data = home.data
    val gapped = NetworkPolicy.blocked
    val density = LocalDensity.current.density.toDouble()
    val tilePixels = remember(density) { NftPicturePlan.pixels(NftPicturePlan.TILE_SIZE, density) }
    val detailPixels = remember(density) { NftPicturePlan.pixels(NftPicturePlan.DETAIL_SIZE, density) }
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // the address is derived from the seed when the screen opens, off the main thread; only the address is kept
    LaunchedEffect(data) {
        if(visit.address != null) return@LaunchedEffect
        val derived = withContext(Dispatchers.Default) { runCatching { repo.litVmAddress(data) }.getOrNull() }
        if(derived == null) visit.addressFailed = true else visit.address = derived
    }
    val address = visit.address
    // the read: on the first open and on Refresh, never air-gapped, never while one runs; it runs in the visit, so a
    // detail opened meanwhile does not stop it, and leaving the visit cancels it (and its client is closed)
    LaunchedEffect(address, visit.requested) {
        val from = address ?: return@LaunchedEffect
        val ticket = visit.requested
        if(!LitVmModel.shouldRead(gapped, ticket, visit.done, visit.reading)) return@LaunchedEffect
        visit.start {
            val read = try {
                repo.readLitVm(data, from, tilePixels, detailPixels)
            } catch(e: CancellationException) {
                throw e
            } catch(e: Exception) {
                LitVmModel.unreachable(from)
            }
            if(read != null) {
                visit.read = read
                visit.names = visit.names + LitVmModel.names(read)
            }
            visit.done = ticket
        }
    }
    val view = remember(address, visit.read, visit.reading, gapped, visit.names, visit.addressFailed) {
        LitVmModel.build(address, visit.read, gapped, visit.reading, visit.names, visit.addressFailed)
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = visit.grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        full("title") { Text(LitVmModel.TITLE, style = MaterialTheme.typography.headlineSmall) }
        full("no-value") { Text(LitVmModel.NO_VALUE, style = MaterialTheme.typography.bodySmall, color = muted) }
        full("account") {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Address", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall, color = muted)
                    when {
                        view.address != null -> {
                            Text(view.address, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                // the address only
                                clipboard.setText(AnnotatedString(view.address))
                                copied = true
                            }) { Text(if(copied) "Copied" else LitVmModel.COPY_ADDRESS) }
                        }
                        visit.addressFailed -> {}
                        else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    view.balance?.let {
                        Text("Balance", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall, color = muted)
                        Text(it, fontFamily = MonoFont, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        full("refresh") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { visit.requested++ }, enabled = view.canRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text(if(visit.reading) "..." else "Refresh")
                }
                view.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                view.statusDetail?.let { PlainText(it, 3, MaterialTheme.typography.bodySmall, color = muted) }
            }
        }
        full("section") { Text(LitVmModel.SECTION, style = MaterialTheme.typography.titleSmall) }
        if(view.empty) {
            full("empty") { Text(LitVmModel.EMPTY, style = MaterialTheme.typography.bodyMedium) }
        }
        items(view.tiles, key = { "tile/${it.key}" }, contentType = { "tile" }) { tile ->
            LitVmTileCard(tile, onClick = { onTile(tile.key) })
        }
        view.notes.forEachIndexed { i, note ->
            full("note/$i") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    note.name?.let { PlainText(it, 1, MaterialTheme.typography.bodySmall, Modifier.weight(1f, fill = false)) }
                    Text(note.text, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
        full("footnote") { Text(LitVmModel.FOOTNOTE, style = MaterialTheme.typography.bodySmall, color = muted) }
        full("back") { BackButton(onClick = onBack) }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) =
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** Contract text: plain, on its own, left to right, cut with an ellipsis after [maxLines]. Never a link or selectable. */
@Composable
private fun PlainText(text: String, maxLines: Int, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    Text(text, modifier = modifier, style = style.copy(textDirection = TextDirection.Ltr), color = color, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

@Composable
private fun LitVmTileCard(tile: LitVmTile, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LitVmPictureBox(tile, detail = false, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            PlainText(tile.label, 1, MaterialTheme.typography.labelMedium)
            PlainText(tile.collection, 1, MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tile.marker, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * A tile's or the detail's picture: an SVG drawn from its plan, a raster through the still decoder, otherwise the
 * model's placeholder. Drawing and decoding run one at a time off the main thread; a tile that leaves the screen first
 * never draws. Any failure shows "No picture".
 */
@Composable
private fun LitVmPictureBox(tile: LitVmTile, detail: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val placeholder = LitVmModel.placeholder(tile.picture)
        if(placeholder != null) Placeholder(placeholder) else LoadedPicture(tile, detail)
    }
}

/** A picture that is drawn or decoded (see [LitVmPictureBox]), cached by its key; "No picture" when that fails. */
@Composable
private fun LoadedPicture(tile: LitVmTile, detail: Boolean) {
    run {
        val picture = tile.picture
        val pixels = when(picture) {
            is LitVmPicture.Drawn -> if(detail) picture.detail.size else picture.tile.size
            else -> if(detail) InscriptionPreview.DETAIL_TARGET.toDouble() else InscriptionPreview.THUMB_TARGET.toDouble()
        }
        val source = when(picture) {
            is LitVmPicture.Drawn -> picture.source
            is LitVmPicture.Raster -> picture.source
            else -> 0L
        }
        val key = LitVmModel.pictureKey(LitVmNetwork.LITEFORGE_TESTNET, tile.contract, tile.tokenId, source, pixels)
        var failed by remember(key) { mutableStateOf(false) }
        // a new key starts empty: never the last key's bitmap
        val bitmap by produceState<Bitmap?>(null, key) {
            litVmBitmaps.get(key)?.let {
                value = it
                return@produceState
            }
            val made = previewDecodes.run {
                when(picture) {
                    is LitVmPicture.Drawn -> drawPlan(if(detail) picture.detail else picture.tile)
                    is LitVmPicture.Raster -> decodeStill(InscriptionPreview.Shown.Image(picture.bytes, picture.width, picture.height), pixels.toInt())
                    else -> null
                }
            }
            if(made == null) {
                failed = true
            } else {
                litVmBitmaps.put(key, made)
                value = made
            }
        }
        val shown = bitmap
        when {
            shown != null -> Image(shown.asImageBitmap(), contentDescription = tile.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            failed -> Placeholder(LitVmPlaceholder(LitVmModel.NO_PICTURE, null))
            else -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun Placeholder(placeholder: LitVmPlaceholder) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
        Text(placeholder.text, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // already cut from the front to fit two lines, so the end of the name stays in view
        placeholder.host?.let { PlainText(it, 2, MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * Draws a plan onto a new transparent bitmap of its size, as [NftPicturePlan]'s drawing contract says. Not a suspend
 * function: call it off the main thread. Null on any failure, out of memory included, never a partial bitmap.
 */
internal fun drawPlan(plan: NftPicturePlan.Plan): Bitmap? {
    var bitmap: Bitmap? = null
    return try {
        val side = plan.size.toInt()
        require(side in 1..NftPicturePlan.MAX_PIXELS.toInt()) { "Picture size out of range" }
        val made = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap = made
        val canvas = Canvas(made)
        val clip = plan.clip
        canvas.clipRect(RectF(clip.x.toFloat(), clip.y.toFloat(), (clip.x + clip.width).toFloat(), (clip.y + clip.height).toFloat()))
        val paint = Paint()
        val matrix = android.graphics.Matrix()
        for(item in plan.items) {
            val values = LitVmDrawing.matrixValues(item.affine) ?: continue
            canvas.save()
            try {
                // the canvas matrix is the identity here, so concatenating sets it
                matrix.setValues(values)
                canvas.concat(matrix)
                when(item) {
                    is NftPicturePlan.PathItem -> drawPath(canvas, paint, item)
                    is NftPicturePlan.TextItem -> drawText(canvas, paint, item)
                }
            } finally {
                canvas.restore()
            }
        }
        made
    } catch(t: Throwable) {
        bitmap?.recycle()
        null
    }
}

private fun freshPaint(paint: Paint, style: Paint.Style, argb: Int, opacity: Double) {
    paint.reset()
    paint.isAntiAlias = true
    paint.style = style
    paint.color = LitVmDrawing.color(argb, opacity)
}

private fun path(cmds: List<NftPicturePlan.Cmd>): Path {
    val path = Path()
    path.fillType = Path.FillType.WINDING
    for(cmd in cmds) {
        when(cmd.op) {
            NftPicturePlan.Op.MOVE -> path.moveTo(cmd[0].toFloat(), cmd[1].toFloat())
            NftPicturePlan.Op.LINE -> path.lineTo(cmd[0].toFloat(), cmd[1].toFloat())
            NftPicturePlan.Op.CUBIC -> path.cubicTo(cmd[0].toFloat(), cmd[1].toFloat(), cmd[2].toFloat(), cmd[3].toFloat(), cmd[4].toFloat(),
                cmd[5].toFloat())
            NftPicturePlan.Op.CLOSE -> path.close()
        }
    }
    return path
}

private fun drawPath(canvas: Canvas, paint: Paint, item: NftPicturePlan.PathItem) {
    val fillPath = item.fillPath?.let { path(it) }
    val fill = item.fill
    if(fillPath != null && fill != null) {
        freshPaint(paint, Paint.Style.FILL, fill, item.opacity)
        canvas.drawPath(fillPath, paint)
    }
    val strokeCmds = item.strokePath
    val stroke = item.stroke
    if(strokeCmds != null && stroke != null && LitVmDrawing.strokeShown(item.strokeWidth)) {
        val strokePath = if(strokeCmds === item.fillPath && fillPath != null) fillPath else path(strokeCmds)
        freshPaint(paint, Paint.Style.STROKE, stroke, item.opacity)
        paint.strokeWidth = item.strokeWidth.toFloat()
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeMiter = 4f
        canvas.drawPath(strokePath, paint)
    }
}

private fun drawText(canvas: Canvas, paint: Paint, item: NftPicturePlan.TextItem) {
    canvas.translate(item.x.toFloat(), item.y.toFloat())
    val scale = (item.size / item.fontPixels).toFloat()
    canvas.scale(scale, scale)
    val typeface = Typeface.create(if(item.monospace) Typeface.MONOSPACE else Typeface.SANS_SERIF, if(item.bold) Typeface.BOLD else Typeface.NORMAL)
    val align = when(item.anchor) {
        SvgSubset.Anchor.START -> Paint.Align.LEFT
        SvgSubset.Anchor.MIDDLE -> Paint.Align.CENTER
        SvgSubset.Anchor.END -> Paint.Align.RIGHT
    }
    fun text(style: Paint.Style, argb: Int) {
        freshPaint(paint, style, argb, item.opacity)
        paint.typeface = typeface
        paint.textAlign = align
        paint.textSize = item.fontPixels.toFloat()
    }
    item.fill?.let {
        text(Paint.Style.FILL, it)
        canvas.drawText(item.content, 0f, 0f, paint)
    }
    val stroke = item.stroke
    val width = LitVmDrawing.textStrokeWidth(item.strokeWidth, item.size, item.fontPixels)
    if(stroke != null && LitVmDrawing.strokeShown(width.toDouble())) {
        text(Paint.Style.STROKE, stroke)
        paint.strokeWidth = width
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeMiter = 4f
        canvas.drawText(item.content, 0f, 0f, paint)
    }
}

/**
 * One NFT: its picture, the app's rows (token id, contract, "Built in", soulbound), then what the collection says, in a
 * box of its own. Read from the visit by key: this screen makes no request.
 */
@Composable
internal fun LitVmNftScreen(tileKey: String, visit: LitVmVisit, onBack: () -> Unit) {
    val gapped = NetworkPolicy.blocked
    val view = remember(visit.address, visit.read, gapped, visit.names) { LitVmModel.build(visit.address, visit.read, gapped, names = visit.names) }
    val tile = view.tiles.firstOrNull { it.key == tileKey }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    ScreenColumn {
        Text("Collectible", style = MaterialTheme.typography.headlineSmall)
        if(tile == null) {
            Text("This collectible is no longer listed.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LitVmPictureBox(tile, detail = true, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
            val detail = tile.detail
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for(row in detail.appRows) {
                        if(row.label != null) {
                            Column {
                                Text(row.label, fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall, color = muted)
                                Text(row.value, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            Text(row.value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Card(modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(LitVmModel.FROM_COLLECTION, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, color = muted)
                    PlainText(detail.title, 2, MaterialTheme.typography.titleMedium)
                    PlainText(detail.collection, 2, MaterialTheme.typography.bodyMedium)
                    if(detail.description.isNotBlank()) {
                        PlainText(detail.description, 20, MaterialTheme.typography.bodySmall)
                    }
                    for(attribute in detail.attributes) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            PlainText(attribute.name, 2, MaterialTheme.typography.bodySmall, Modifier.weight(0.4f), color = muted)
                            PlainText(attribute.value, 2, MaterialTheme.typography.bodySmall, Modifier.weight(0.6f))
                        }
                    }
                }
            }
        }
        BackButton(onClick = onBack)
    }
}
