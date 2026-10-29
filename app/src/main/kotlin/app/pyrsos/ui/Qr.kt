// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.ui

import android.app.Activity
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.pyrsos.core.QrFrames
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay

private const val FRAME_MS = 1_200L

private fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 4),
    )
    val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/**
 * A QR code, black on white whatever the theme (scanners read dark on light), with the screen
 * at full brightness and kept on while it shows: the other person may take a while to aim.
 * The margin is the four-module quiet zone the standard asks for; dense codes need it.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    // Several smaller codes in turn read far more easily than one dense one: see QrFrames.
    val frames = remember(text) { QrFrames.split(text).map { qrBitmap(it).asImageBitmap() } }
    var shown by remember(text) { mutableIntStateOf(0) }
    LaunchedEffect(frames) {
        while (frames.size > 1) {
            delay(FRAME_MS)
            shown = (shown + 1) % frames.size
        }
    }
    val bitmap = frames[shown]
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness
        window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = 1f } }
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        }
    }
    Box(modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None,
        )
    }
}
