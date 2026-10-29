// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.ui

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.pyrsos.BuildConfig
import app.pyrsos.R
import app.pyrsos.core.QrFrames
import zxingcpp.BarcodeReader
import java.util.concurrent.Executors

/**
 * The camera, inside the app, reading codes until one is whole. A code that the other phone shows
 * as several frames in turn ([QrFrames]) is collected frame by frame, with the count on screen.
 * Needs the camera permission already granted.
 *
 * Frames are analysed at about 1280×960 with continuous autofocus: enough for the smaller codes
 * the other screen shows in turn, and light enough for slow phones to keep up with them.
 */
@Composable
fun Scanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val deliver by rememberUpdatedState(onCode)
    val collector = remember { QrFrames.Collector() }
    var have by remember { mutableIntStateOf(0) }
    var of by remember { mutableIntStateOf(0) }
    val previewView = remember { PreviewView(context) }
    val haptic = LocalHapticFeedback.current

    DisposableEffect(lifecycleOwner) {
        var done = false
        val analysisThread = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val reader = BarcodeReader(
            BarcodeReader.Options().apply {
                formats = setOf(BarcodeReader.Format.QR_CODE)
                tryHarder = true
                tryRotate = true
            },
        )
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    // Never above: on slow phones a 12-megapixel frame takes seconds to read, and the
                    // codes on the other screen change before it is done.
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisThread) { image ->
            val size = "${image.width}x${image.height}"
            val started = SystemClock.elapsedRealtime()
            val text = image.use { reader.read(it) }.firstNotNullOfOrNull { it.text }
            if (BuildConfig.DEBUG) {
                // Frame header only (PF, index, count, id): never the code itself.
                Log.d(TAG, "scan $size in ${SystemClock.elapsedRealtime() - started} ms: ${text?.take(12) ?: "-"}")
            }
            if (text != null) {
                main.execute {
                    if (done) return@execute
                    val before = collector.have
                    val whole = collector.add(text)
                    if (whole == null && collector.have > before) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    have = collector.have
                    of = collector.of
                    if (whole != null) {
                        done = true
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        deliver(whole)
                    }
                }
            }
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        providerFuture.addListener({
            provider = providerFuture.get().also {
                it.unbindAll()
                it.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, main)

        onDispose {
            done = true
            provider?.unbindAll()
            analysis.clearAnalyzer()
            analysisThread.shutdown()
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AndroidView({ previewView }, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(stringResource(R.string.add_scan_prompt), style = MaterialTheme.typography.bodyLarge)
        if (of > 1) {
            Text(stringResource(R.string.add_scan_progress, have, of), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private const val TAG = "Pyrsos"
