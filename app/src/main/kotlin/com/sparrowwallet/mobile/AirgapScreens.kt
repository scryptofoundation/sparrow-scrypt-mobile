package com.sparrowwallet.mobile

import com.sparrowwallet.mobile.crypto.Coin
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.mobile.crypto.PsbtReview
import com.sparrowwallet.mobile.storage.VaultData
import kotlinx.coroutines.delay

/**
 * Where a debug build's adb hook (DebugUrReceiver) delivers QR frames: set by [UrScanner]
 * while it is on screen, so tests can scan without a camera. Unused in release builds.
 */
@Volatile var debugFrameSink: ((String) -> Unit)? = null

/** Shows a UR as a QR, cycling through its fountain-coded parts when it needs more than one. */
@Composable
fun AnimatedUrQr(encoder: UREncoder, framesPerSecond: Int = 6) {
    var fps by remember(encoder) { mutableIntStateOf(framesPerSecond) }
    // upper case puts the QR in alphanumeric mode: denser codes, easier to read
    var frame by remember(encoder) { mutableStateOf(encoder.nextPart().uppercase()) }
    if(!encoder.isSinglePart) {
        LaunchedEffect(encoder, fps) {
            while(true) {
                delay(1000L / fps)
                frame = encoder.nextPart().uppercase()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) { QrImage(frame, "Animated QR code") }
        }
        if(!encoder.isSinglePart) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { fps = (fps - 2).coerceAtLeast(2) }, enabled = fps > 2,
                    modifier = Modifier.weight(1f)) { Text("Slower") }
                Text("$fps / s", fontFamily = MonoFont, style = MaterialTheme.typography.labelMedium)
                OutlinedButton(onClick = { fps = (fps + 2).coerceAtMost(12) }, enabled = fps < 12,
                    modifier = Modifier.weight(1f)) { Text("Faster") }
            }
        }
    }
}

/**
 * Continuous camera scanner for animated QRs: every frame goes to [onFrame] until it
 * reports [AirgapUr.Progress.Done], then [onDone] gets the PSBT once.
 */
@Composable
fun UrScanner(onFrame: (String) -> AirgapUr.Progress, onDone: (ByteArray) -> Unit) {
    val context = LocalContext.current
    var percent by remember { mutableStateOf<Int?>(null) }
    var finished by remember { mutableStateOf(false) }
    var permitted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    LaunchedEffect(Unit) { if(!permitted) askCamera.launch(Manifest.permission.CAMERA) }

    val handle: (String) -> Unit = handle@{ text ->
        if(finished) return@handle
        when(val progress = onFrame(text)) {
            is AirgapUr.Progress.Done -> { finished = true; onDone(progress.psbt) }
            is AirgapUr.Progress.Partial -> percent = progress.percent
            AirgapUr.Progress.Ignored -> {}
        }
    }
    DisposableEffect(Unit) {
        debugFrameSink = handle
        onDispose { debugFrameSink = null }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if(permitted) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            val barcodeView = remember {
                DecoratedBarcodeView(context).apply {
                    barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    setStatusText("")
                    decodeContinuous(BarcodeCallback { result -> result.text?.let(handle) })
                }
            }
            DisposableEffect(lifecycle, barcodeView) {
                val observer = LifecycleEventObserver { _, event ->
                    when(event) {
                        Lifecycle.Event.ON_RESUME -> barcodeView.resume()
                        Lifecycle.Event.ON_PAUSE -> barcodeView.pause()
                        else -> {}
                    }
                }
                lifecycle.addObserver(observer) // replays ON_RESUME when already resumed
                onDispose {
                    lifecycle.removeObserver(observer)
                    barcodeView.pause()
                }
            }
            AndroidView(factory = { barcodeView }, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        } else {
            Text("Camera access is needed to scan. Paste the PSBT instead, or allow the camera in settings.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        percent?.let {
            LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
            Text("$it% received", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the PSBT does, shown before signing: every output, how many inputs are ours, the fee. */
@Composable
fun PsbtReviewCard(review: PsbtReview) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for(out in review.outputs) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(out.address ?: "Script ${out.scriptHex}", fontFamily = MonoFont,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if(out.change) {
                        Text("change", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatAmount(Coin.LITECOIN, out.value), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
                }
            }
            val signedHere = review.inputs.count { it.ours }
            Text("${review.inputs.size} inputs, $signedHere signed by this phone",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                review.fee?.let { fee ->
                    "Fee ${formatAmount(Coin.LITECOIN, fee)}" + (review.feeRateLitPerVb?.let { " (%.2f lit/vB)".format(it) } ?: "")
                } ?: "Fee unknown",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/**
 * The account as an animated `ur:crypto-account`, for desktop Sparrow to scan into a
 * watch-only wallet that builds PSBTs for this phone to sign.
 */
@Composable
fun ExportToDesktopScreen(repo: WalletRepository, data: VaultData, onBack: () -> Unit) {
    val encoder = remember(data) {
        AirgapUr.encoder(AirgapUr.accountExport(repo.masterKey(data), repo.accountKey(data), repo.accountPath(data)))
    }
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text("Export to desktop", style = MaterialTheme.typography.headlineSmall)
            Text("AIR-GAPPED · WATCH-ONLY", fontFamily = MonoFont, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("On the PC, with the Litecoin coin selected: New Wallet, Native Segwit, Airgapped Hardware " +
            "Wallet, Sparrow Mobile, Scan. Point the webcam at this code.",
            style = MaterialTheme.typography.bodyMedium)
        AnimatedUrQr(encoder)
        BackButton(onClick = onBack)
    }
}
