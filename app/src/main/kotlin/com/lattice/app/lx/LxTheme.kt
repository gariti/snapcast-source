package com.lattice.app.lx

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lattice.app.R

/**
 * The phone's share of the desktop's design system (`lattice-widget-host/
 * qml/design/Theme.qml`, exported to Open Design as `tokens.css`): colour
 * ROLES, an alpha LADDER, ONE unit that every size is a ratio of, the type
 * roles, and two durations. Nothing in the app draws a hex, a dp or a ms that
 * is not one of these.
 *
 * Colour comes from the desktop. [Roles.lattice] is the built-in snapshot of
 * theme `lattice` as of design-system fingerprint 86a72cbc66d5; the desktop
 * does not push its theme over the link yet (a `theme` message is the
 * follow-up), so today this IS the palette. When it does, replace
 * [LxTheme.roles] and nothing else changes.
 */
@Immutable
data class Roles(
    val ground: Color,
    val surface: Color,
    val ink: Color,
    val accent: Color,
    val onAccent: Color,
    val line: Color,
    val warn: Color,
    val muted: Color,
    val ok: Color,
    val caution: Color,
    val wallpaper1: Color,
    val wallpaper2: Color,
    val wallpaper3: Color,
) {
    companion object {
        /** Theme `lattice`, from tokens.css (do not edit by hand: it mirrors the desktop). */
        val lattice = Roles(
            ground = Color(0xFF141515), surface = Color(0xFF1E1F20), ink = Color(0xFFE5E6E6),
            accent = Color(0xFF50D6FF), onAccent = Color(0xFF212425), line = Color(0xFF5F7A45),
            warn = Color(0xFFCC3333), muted = Color(0xFFCACEB4), ok = Color(0xFF2A7F51),
            caution = Color(0xFFAFC95E),
            wallpaper1 = Color(0xFF55AD45), wallpaper2 = Color(0xFF2A7F51), wallpaper3 = Color(0xFF76CBA7),
        )
    }
}

/** The alpha ladder: the only transparencies (DESIGN.md § 2). */
object Alpha {
    const val hairline = 0.08f
    const val chipRest = 0.2f
    const val chipHot = 0.28f
    const val chipCurrent = 0.42f
    const val dim = 0.28f
    const val idleBorder = 0.35f
    const val caption = 0.5f
    const val secondary = 0.55f
    const val activeBorder = 0.6f
    const val paneIdle = 0.78f
    const val paneActive = 0.92f
    const val keycapFill = 0.14f
    const val keycapBorder = 0.35f
    const val keycapLegend = 0.92f
    const val sectionIdle = 0.5f
    const val ruleIdle = 0.15f
    const val ruleActive = 0.7f
    const val pending = 0.55f
    const val statusInfo = 0.45f
    const val statusWarn = 0.85f
    const val placeholder = 0.3f
    const val fieldRuleIdle = 0.25f
    const val fieldBoxIdle = 0.03f
    const val fieldBoxFocused = 0.06f
}

/** The type roles, as multiples of the unit (DESIGN.md § 3). */
@Immutable
data class TypeRole(val size: Float, val weight: FontWeight, val tracking: TextUnit)

object Type {
    val body = TypeRole(1f, FontWeight.Light, 0.sp)
    val caption = TypeRole(0.7f, FontWeight.Light, 1.sp)
    val display = TypeRole(1.8f, FontWeight.Light, 0.sp)
    val heading = TypeRole(1.15f, FontWeight.Medium, 3.sp)
    val label = TypeRole(0.8f, FontWeight.Medium, 2.sp)
    val title = TypeRole(1.5f, FontWeight.Medium, 0.sp)
}

/**
 * Everything the components size themselves by. [unit] is THE phone unit:
 * 15 dp, chosen so body text lands at Material's 14–15 sp and every ratio the
 * desktop uses (row 3.1u, chip 1.8u, pane radius 0.8u) comes out a touch
 * target. Zoom and the user's font scale multiply it, never the ratios.
 */
@Immutable
data class Lx(
    val roles: Roles = Roles.lattice,
    val unit: Dp = 15.dp,
    val face: FontFamily = FontFamily.Monospace,
) {
    val fast = 160
    val instant = 80

    fun u(n: Float): Dp = unit * n
    fun sp(role: TypeRole): TextUnit = (unit.value * role.size).sp

    // radii
    val radiusPane: Dp get() = unit * 0.8f
    val radiusCard: Dp get() = unit * 0.4f
    val radiusChip: Dp get() = unit * 0.3f
    val radiusRow: Dp get() = unit * 0.25f
    val radiusKeycap: Dp get() = unit * 0.3f

    // spacing
    val inset: Dp get() = unit * 0.9f
    val rowPad: Dp get() = unit * 0.5f
    val rowHeight: Dp get() = unit * 3.1f
    val barHeight: Dp get() = unit * 2.4f
    val chipHeight: Dp get() = unit * 2.25f
    val gap: Dp get() = unit * 0.5f
    val gapSmall: Dp get() = unit * 0.3f

    // the two things that are not a ratio: hairlines and the screen gaps
    val hairline: Dp get() = 1.dp
    val screenGap: Dp get() = 12.dp
    val screenGapSmall: Dp get() = 8.dp

    fun ink(alpha: Float) = roles.ink.copy(alpha = alpha)
    fun accent(alpha: Float) = roles.accent.copy(alpha = alpha)
}

val LocalLx = staticCompositionLocalOf { Lx() }

object LxTheme {
    val current: Lx @Composable get() = LocalLx.current
}

/** Chivo Mono (OFL), the desktop's one face, as the variable TTF: one axis, three weights used. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val ChivoMono = FontFamily(
    listOf(300 to FontWeight.Light, 400 to FontWeight.Normal, 500 to FontWeight.Medium).map { (w, fw) ->
        Font(
            resId = R.font.chivo_mono, weight = fw, style = FontStyle.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(w)),
        )
    }
)

/**
 * Provide the roles and the unit, and a Material colour scheme mapped onto the
 * same roles so the few Material pieces still in the tree (BasicTextField's
 * selection colours, the WebView host) read as part of the system.
 */
@Composable
fun LxTheme(roles: Roles = Roles.lattice, content: @Composable () -> Unit) {
    val lx = Lx(roles = roles, face = ChivoMono)
    // The desktop is dark-first; a light system theme does not change the
    // roles (they come from the wallpaper), only the system bars around them.
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    val scheme = darkColorScheme(
        primary = roles.accent, onPrimary = roles.onAccent,
        primaryContainer = roles.accent.copy(alpha = Alpha.chipCurrent), onPrimaryContainer = roles.ink,
        secondary = roles.muted, onSecondary = roles.ground,
        background = roles.ground, onBackground = roles.ink,
        surface = roles.ground, onSurface = roles.ink,
        surfaceVariant = roles.surface, onSurfaceVariant = roles.ink,
        surfaceContainer = roles.surface, surfaceContainerHigh = roles.surface, surfaceContainerHighest = roles.surface,
        outline = roles.ink.copy(alpha = Alpha.secondary), outlineVariant = roles.line.copy(alpha = Alpha.idleBorder),
        error = roles.warn, onError = roles.ground,
        errorContainer = roles.warn.copy(alpha = Alpha.chipCurrent), onErrorContainer = roles.ink,
        scrim = roles.ground,
    )
    CompositionLocalProvider(LocalLx provides lx) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
