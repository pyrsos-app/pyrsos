// SPDX-License-Identifier: GPL-3.0-or-later
package app.pyrsos.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Typography
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pyrsos.R

enum class ThemeMode { AUTO, DAY, NIGHT }

object ThemePrefs {
    private const val FILE = "ui"
    private const val KEY = "theme"

    fun load(context: Context): ThemeMode =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.AUTO

    fun save(context: Context, mode: ThemeMode) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode.name).apply()
}

private val Day = lightColorScheme(
    primary = Color(0xFFB23C00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF444444),
    error = Color(0xFFB00020),
)

/** Pure black: on the OLED screens of most phones, black pixels draw no power. */
private val Night = darkColorScheme(
    primary = Color(0xFFFF8A50),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF5C1D00),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondaryContainer = Color(0xFF1C1C1C),
    onSecondaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF141414),
    onSurfaceVariant = Color(0xFFB8B8B8),
    error = Color(0xFFFF6B6B),
)

@Composable
fun isNight(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.AUTO -> isSystemInDarkTheme()
    ThemeMode.DAY -> false
    ThemeMode.NIGHT -> true
}

/** One variable font file, with Greek and Latin, set at the weights the app uses. */
@OptIn(ExperimentalTextApi::class)
private fun commissioner(weight: Int) =
    Font(R.font.commissioner, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Commissioner (SIL Open Font License; the licence ships in assets/licenses). */
private val Commissioner = FontFamily(commissioner(400), commissioner(500), commissioner(600), commissioner(700))

private val Type = Typography().let { t ->
    fun TextStyle.withFont() = copy(fontFamily = Commissioner)
    t.copy(
        displayLarge = t.displayLarge.withFont(), displayMedium = t.displayMedium.withFont(), displaySmall = t.displaySmall.withFont(),
        headlineLarge = t.headlineLarge.withFont(), headlineMedium = t.headlineMedium.withFont(), headlineSmall = t.headlineSmall.withFont(),
        titleLarge = t.titleLarge.withFont(), titleMedium = t.titleMedium.withFont(), titleSmall = t.titleSmall.withFont(),
        bodyLarge = t.bodyLarge.withFont(), bodyMedium = t.bodyMedium.withFont(), bodySmall = t.bodySmall.withFont(),
        labelLarge = t.labelLarge.withFont(), labelMedium = t.labelMedium.withFont(), labelSmall = t.labelSmall.withFont(),
    )
}

@Composable
fun PyrsosTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isNight(mode)) Night else Day, typography = Type, content = content)
}

/**
 * The mark and the name, in the theme's accent. The name is drawn, not typed, and its Greek form
 * (drawable-el) shows when the app speaks Greek. [height] is the mark's height. [stacked] puts the
 * name under the mark, both centred, instead of beside it.
 */
@Composable
fun Brand(height: Dp = 30.dp, modifier: Modifier = Modifier, stacked: Boolean = false) {
    val tint = ColorFilter.tint(MaterialTheme.colorScheme.primary)
    val mark = painterResource(R.drawable.ic_mark)
    val name = painterResource(R.drawable.ic_wordmark)
    val markImage = @Composable {
        Image(
            mark,
            contentDescription = null,
            modifier = Modifier.height(height).aspectRatio(mark.intrinsicSize.width / mark.intrinsicSize.height),
            colorFilter = tint,
        )
    }
    val nameImage = @Composable {
        Image(
            name,
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.height(height * 0.55f).aspectRatio(name.intrinsicSize.width / name.intrinsicSize.height),
            colorFilter = tint,
        )
    }
    if (stacked) {
        Column(modifier.semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
            markImage()
            Spacer(Modifier.height(height / 4))
            nameImage()
        }
    } else {
        Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            markImage()
            Spacer(Modifier.width(height / 3))
            nameImage()
        }
    }
}
