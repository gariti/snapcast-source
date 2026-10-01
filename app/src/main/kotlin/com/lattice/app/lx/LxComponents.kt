package com.lattice.app.lx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay

/*
 * The Card App components, on the phone. Each one is the desktop's piece of
 * the same name (`lattice-widget-host/qml/design/Lx*.qml`, drawn in Open Design
 * as `lattice-ds-check › commander-card-app.html`) with the ratios kept and the
 * touch sizes raised: a cap a thumb presses is 1.55 units, not 0.62.
 *
 * A card is a BAND above the tile (wordmark · caption · quiet commands), the
 * TILE (LxPane: section label → rule → content, and content only) and a BAND
 * below (? · the hint slot · the commits). Buttons never go in the tile.
 */

// ── text roles ────────────────────────────────────────────────────────────

@Composable
fun LxText(
    text: String,
    role: TypeRole = Type.body,
    color: Color = LxTheme.current.roles.ink,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign? = null,
    uppercase: Boolean = false,
) {
    val lx = LxTheme.current
    Text(
        if (uppercase) text.uppercase() else text,
        modifier,
        color = color,
        fontFamily = lx.face,
        fontSize = lx.sp(role),
        fontWeight = role.weight,
        letterSpacing = role.tracking,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
        lineHeight = lx.sp(role) * 1.3f,
    )
}

/** The widget's name at the head of its band: Medium, tracked, in the ink. */
@Composable
fun LxWordmark(text: String, modifier: Modifier = Modifier) {
    LxText(text, Type.heading, modifier = modifier.semantics { heading() }, maxLines = 1)
}

/** The quiet line beside a wordmark, in the ink at the caption alpha. */
@Composable
fun LxCaption(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    LxText(text, Type.caption, LxTheme.current.ink(Alpha.caption), modifier, maxLines)
}

// ── the stage and its cards ───────────────────────────────────────────────

/**
 * The stage: the desktop's blurred wallpaper as the ground, and cards stacked
 * down it. The phone gets the wallpaper's three colours over the link one day;
 * until then the roles carry them.
 */
@Composable
fun LxStage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val lx = LxTheme.current
    val r = lx.roles
    Column(
        modifier
            .fillMaxSize()
            .background(r.ground)
            .background(
                Brush.radialGradient(
                    listOf(r.wallpaper1.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(0.2f, 0.1f) * 2000f, radius = 1400f,
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(r.wallpaper3.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(0.9f, 0.45f) * 2000f, radius = 1300f,
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(r.wallpaper2.copy(alpha = 0.4f), Color.Transparent),
                    center = Offset(0.5f, 1.05f) * 2000f, radius = 1500f,
                )
            )
            .padding(horizontal = lx.u(0.7f), vertical = lx.u(0.5f)),
        verticalArrangement = Arrangement.spacedBy(lx.u(0.5f)),
        content = content,
    )
}

/**
 * One card: [top] band, the tile, [bottom] band. `active` draws the tile's
 * border in the accent — that border IS the focus. The tile holds content only.
 */
@Composable
fun LxCard(
    modifier: Modifier = Modifier,
    active: Boolean = true,
    top: (@Composable RowScope.() -> Unit)? = null,
    bottom: (@Composable RowScope.() -> Unit)? = null,
    between: (@Composable ColumnScope.() -> Unit)? = null,
    tilePadding: Dp? = null,
    tile: @Composable ColumnScope.() -> Unit,
) {
    val lx = LxTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(lx.screenGapSmall)) {
        if (top != null) LxBand(content = top)
        LxPane(active = active, padding = tilePadding ?: lx.inset, modifier = Modifier.weight(1f, fill = false).fillMaxWidth(), content = tile)
        between?.invoke(this)
        if (bottom != null) LxBand(content = bottom)
    }
}

/** A band: one row, bar-height, the things around a tile. */
@Composable
fun LxBand(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val lx = LxTheme.current
    Row(
        modifier.fillMaxWidth().heightIn(min = lx.barHeight).padding(horizontal = lx.u(0.2f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.6f)),
        content = content,
    )
}

/** The tile (LxPane): the surface at its alpha, a line border idle, the accent border active. */
@Composable
fun LxPane(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    padding: Dp = LxTheme.current.inset,
    content: @Composable ColumnScope.() -> Unit,
) {
    val lx = LxTheme.current
    val r = lx.roles
    val border by animateColorAsState(
        if (active) r.accent.copy(alpha = Alpha.activeBorder) else r.line.copy(alpha = Alpha.idleBorder),
        tween(lx.fast), label = "paneBorder",
    )
    val fill by animateColorAsState(
        if (active) r.surface.copy(alpha = Alpha.paneActive) else r.surface.copy(alpha = Alpha.paneIdle),
        tween(lx.fast), label = "paneFill",
    )
    Column(
        modifier
            .clip(RoundedCornerShape(lx.radiusPane))
            .background(fill)
            .border(if (active) 2.dp else lx.hairline, border, RoundedCornerShape(lx.radiusPane))
            .padding(padding),
        content = content,
    )
}

// ── section label + rule ──────────────────────────────────────────────────

/** A section label: Medium, tracked, uppercase, in the accent; lit while `here`; the rule under it. */
@Composable
fun LxSection(label: String, badge: String? = null, here: Boolean = false, modifier: Modifier = Modifier) {
    val lx = LxTheme.current
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(lx.u(0.6f))) {
            LxText(
                label, Type.label,
                lx.accent(if (here) 1f else Alpha.sectionIdle),
                Modifier.semantics { heading() }, maxLines = 1, uppercase = true,
            )
            if (badge != null) LxText(badge, Type.caption, lx.ink(Alpha.badge), maxLines = 1)
        }
        Spacer(Modifier.height(lx.u(0.4f)))
        Box(
            Modifier.fillMaxWidth().height(lx.hairline)
                .background(lx.accent(if (here) Alpha.ruleActive else Alpha.ruleIdle)),
        )
        Spacer(Modifier.height(lx.u(0.5f)))
    }
}

/** The gap a section keeps from the content above it. */
@Composable
fun LxSectionGap() = Spacer(Modifier.height(LxTheme.current.u(0.9f)))

// ── rows ──────────────────────────────────────────────────────────────────

/** A row's state marker: one square, one shape per state, no motion. */
enum class Marker { Attached, Background, Waiting, Idle, Exited }

@Composable
fun LxMarker(state: Marker, color: Color, modifier: Modifier = Modifier) {
    val lx = LxTheme.current
    val caution = lx.roles.caution
    Canvas(modifier.size(lx.u(0.6f))) {
        val w = size.width; val h = size.height
        fun poly(vararg pts: Pair<Float, Float>) = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h) }
            close()
        }
        when (state) {
            Marker.Attached -> drawPath(poly(0.16f to 0.06f, 0.94f to 0.5f, 0.16f to 0.94f), color)
            Marker.Background -> drawCircle(color, radius = w * 0.38f)
            Marker.Waiting -> drawPath(poly(0.5f to 0.04f, 0.96f to 0.5f, 0.5f to 0.96f, 0.04f to 0.5f), caution)
            Marker.Idle -> drawPath(poly(0f to 0.4f, 1f to 0.4f, 1f to 0.6f, 0f to 0.6f), color)
            Marker.Exited -> drawPath(
                poly(
                    0.2f to 0.08f, 0.5f to 0.38f, 0.8f to 0.08f, 0.92f to 0.2f, 0.62f to 0.5f, 0.92f to 0.8f,
                    0.8f to 0.92f, 0.5f to 0.62f, 0.2f to 0.92f, 0.08f to 0.8f, 0.38f to 0.5f, 0.08f to 0.2f,
                ),
                color,
            )
        }
    }
}

/**
 * A list row (LxRow): marker or icon · name + meta · a trailing slot · a chevron.
 * The `current` row is the accent stripe with its text inverted. Tapping it
 * is the row's verb.
 */
@Composable
fun LxRow(
    name: String,
    meta: String? = null,
    marker: Marker? = null,
    icon: ImageVector? = null,
    current: Boolean = false,
    dim: Boolean = false,
    chevron: Boolean = false,
    warn: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    // Last on purpose: a trailing lambda at a call site is the row's VERB.
    onClick: (() -> Unit)? = null,
) {
    val lx = LxTheme.current
    val r = lx.roles
    val fg = when { current -> r.onAccent; warn -> r.warn; else -> r.ink }
    val sec = if (current) 0.85f else Alpha.secondary
    val bg by animateColorAsState(if (current) r.accent else Color.Transparent, tween(lx.fast), label = "rowBg")
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(lx.radiusRow))
            .background(bg)
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                else Modifier
            )
            .heightIn(min = lx.rowHeight)
            .lxFocusRing(onClick != null)
            .padding(horizontal = lx.rowPad, vertical = lx.u(0.3f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.6f)),
    ) {
        if (marker != null) Box(Modifier.width(lx.u(1.1f)), contentAlignment = Alignment.Center) {
            LxMarker(marker, if (marker == Marker.Attached) fg else fg.copy(alpha = sec))
        }
        if (icon != null) Icon(icon, null, Modifier.size(lx.u(1.3f)), tint = fg.copy(alpha = if (current) 1f else 0.7f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(lx.u(0.1f))) {
            LxText(name, Type.body.copy(weight = FontWeight.Normal), fg.copy(alpha = if (dim) Alpha.pending else 1f), maxLines = if (current) 2 else 1)
            if (meta != null) LxText(meta, Type.caption, fg.copy(alpha = if (dim) Alpha.pending * sec else sec), maxLines = 2)
        }
        trailing?.invoke(this)
        if (chevron) LxText("›", Type.title, fg.copy(alpha = sec), maxLines = 1)
    }
}

/** A hairline between rows, in the ink at the divider alpha. */
@Composable
fun LxDivider() {
    val lx = LxTheme.current
    Box(Modifier.fillMaxWidth().padding(horizontal = lx.rowPad).height(lx.hairline).background(lx.ink(Alpha.hairline)))
}

/** A FIGURE in a row: the number, and under it the plain word for what it measures. */
@Composable
fun LxFig(n: String, unit: String, hot: Boolean = false, color: Color = LxTheme.current.roles.ink) {
    val lx = LxTheme.current
    Column(horizontalAlignment = Alignment.End) {
        LxText(n, Type.label.copy(weight = FontWeight.Normal, tracking = 0.sp), if (hot) lx.roles.caution else color, maxLines = 1)
        LxText(unit, Type.caption, color.copy(alpha = Alpha.secondary), maxLines = 1)
    }
}

// ── chips ─────────────────────────────────────────────────────────────────

/** A pill stop that toggles or picks: accent-tinted, `on` at the current alpha. */
@Composable
fun LxChip(
    label: String,
    on: Boolean = false,
    warn: Boolean = false,
    armed: Boolean = false,
    enabled: Boolean = true,
    key: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val lx = LxTheme.current
    val r = lx.roles
    val tone = if (warn) r.warn else r.accent
    val fill by animateColorAsState(
        when { armed -> lx.warnFill; on -> tone.copy(alpha = Alpha.chipCurrent); else -> tone.copy(alpha = Alpha.chipRest) },
        tween(lx.fast), label = "chipFill",
    )
    val border = if (armed) lx.warnFill else tone.copy(alpha = if (on) 0.8f else 0.4f)
    // On, the chip's text is the INK (the fill says "on"); warn as text is mixed toward the ink.
    val fg = when { armed -> r.ink; warn -> lx.warnText; else -> r.ink }
    Row(
        modifier
            .heightIn(min = lx.tap)
            .clip(CircleShape)
            .background(fill)
            .border(lx.hairline, border, CircleShape)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
            .lxFocusRing(true)
            .semantics { contentDescription = if (on) "$label, on" else label }
            .padding(horizontal = lx.u(0.95f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.4f)),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(lx.u(1.1f)), tint = fg)
        if (key != null) LxText(key, Type.caption, lx.ink(Alpha.caption), maxLines = 1, uppercase = true)
        LxText(
            label,
            Type.label.copy(tracking = 0.sp, weight = if (on || armed) FontWeight.Medium else FontWeight.Normal),
            fg.copy(alpha = if (enabled) 1f else Alpha.pending), maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LxChips(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val lx = LxTheme.current
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(lx.u(0.4f)), verticalArrangement = Arrangement.spacedBy(lx.u(0.4f))) { content() }
}

// ── buttons ───────────────────────────────────────────────────────────────

enum class ButtonKind { Quiet, Primary, Danger }

/**
 * A button: quiet commands in the top band, the ONE primary and a danger in
 * the bottom band. A danger button ARMS on its first press (it fills the warn
 * colour) and fires on the second — pass an [LxArmState] for that.
 */
@Composable
fun LxButton(
    label: String?,
    kind: ButtonKind = ButtonKind.Quiet,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    pressed: Boolean = false,
    armed: Boolean = false,
    contentDescription: String? = label,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val lx = LxTheme.current
    val r = lx.roles
    val tone = when (kind) { ButtonKind.Quiet -> r.ink; ButtonKind.Primary -> r.wallpaper1; ButtonKind.Danger -> r.warn }
    val fill = when {
        kind == ButtonKind.Danger && armed -> lx.warnFill
        kind == ButtonKind.Primary -> tone
        pressed -> r.accent.copy(alpha = Alpha.chipHot)
        kind == ButtonKind.Danger -> Color.Transparent
        else -> tone.copy(alpha = Alpha.keycapFill)
    }
    val fillA by animateColorAsState(fill, tween(lx.instant), label = "btnFill")
    val border = when {
        kind == ButtonKind.Danger && armed -> lx.warnFill
        kind == ButtonKind.Primary -> tone
        pressed -> r.accent.copy(alpha = 0.8f)
        else -> tone.copy(alpha = Alpha.keycapBorder)
    }
    val fg = when {
        kind == ButtonKind.Danger && armed -> r.ink
        kind == ButtonKind.Primary -> r.ground
        kind == ButtonKind.Danger -> lx.warnText
        pressed -> r.accent
        else -> r.ink
    }
    val h = lx.tap
    Row(
        modifier
            .heightIn(min = h)
            .then(if (label == null) Modifier.widthIn(min = h) else Modifier)
            .clip(CircleShape)
            .background(fillA)
            .border(lx.hairline, border, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .lxFocusRing(true)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            // Text buttons take half the desktop's side padding; the height is the touch size.
            .padding(horizontal = if (label == null) lx.u(0.6f) else lx.u(0.7f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.4f)),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(lx.u(1.3f)), tint = fg.copy(alpha = if (enabled) 1f else Alpha.pending))
        if (label != null) LxText(
            label,
            Type.label.copy(tracking = 1.sp, weight = if (kind == ButtonKind.Primary || armed) FontWeight.Medium else FontWeight.Normal),
            fg.copy(alpha = if (enabled) 1f else Alpha.pending), maxLines = 1,
        )
    }
}

/**
 * The two-press ERASE, as state: the first press ARMS; a second press within
 * the window FIRES; the window running out disarms. Nothing else is a dialog.
 */
class LxArmState(private val windowMs: Long = 3000) {
    var armed by mutableStateOf(false)
        private set
    private var armedAt = 0L

    /** Returns true when the press FIRED (the caller then does the thing). */
    fun press(): Boolean {
        val now = System.currentTimeMillis()
        return if (armed && now - armedAt <= windowMs) { armed = false; true }
        else { armed = true; armedAt = now; false }
    }
    fun disarm() { armed = false }

    @Composable
    fun Expire() {
        LaunchedEffect(armed, armedAt) {
            if (armed) { delay(windowMs); armed = false }
        }
    }
}

@Composable
fun rememberArm(windowMs: Long = 3000): LxArmState {
    val s = remember { LxArmState(windowMs) }
    s.Expire()
    return s
}

// ── keycaps and the hint slot ─────────────────────────────────────────────

/**
 * ONE key, drawn as the key it is. `k` is its size in units: 0.7 in a hint,
 * 1.55 for a cap a thumb presses. `on` is a latched modifier.
 */
@Composable
fun LxKey(
    legend: String,
    k: Float = 0.7f,
    on: Boolean = false,
    warn: Boolean = false,
    quiet: Boolean = false,
    enabled: Boolean = true,
    wide: Boolean = false,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val lx = LxTheme.current
    val r = lx.roles
    val h = lx.unit * k * 1.8f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            on -> r.accent.copy(alpha = Alpha.chipCurrent)
            quiet -> r.ink.copy(alpha = Alpha.keycapFill * 0.5f)
            else -> r.ink.copy(alpha = Alpha.keycapFill)
        },
        tween(lx.instant), label = "keyFill",
    )
    val border = if (on) r.accent.copy(alpha = 0.8f) else r.ink.copy(alpha = Alpha.keycapBorder)
    // Latched, the legend is the ink (the fill says it); warn as text is mixed toward the ink.
    val fg = when { on -> r.ink; warn -> lx.warnText; else -> r.ink.copy(alpha = Alpha.keycapLegend) }
    // A key travels down on press — instant, and still under reduced motion (it is a state, not a loop).
    val travel = with(LocalDensity.current) { (lx.unit * k * 0.26f * 0.3f).toPx() }
    Box(
        modifier
            .defaultMinSize(minWidth = if (wide) h * 1.6f else h, minHeight = h)
            .graphicsLayer { translationY = if (pressed) travel else 0f }
            .clip(RoundedCornerShape(lx.unit * k * 0.3f))
            .background(fill)
            .border(lx.hairline, border, RoundedCornerShape(lx.unit * k * 0.3f))
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .lxFocusRing(onClick != null)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .padding(horizontal = lx.unit * k * 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(lx.unit * k * 0.75f), tint = fg.copy(alpha = if (enabled) 1f else Alpha.pending))
        else Text(
            legend, color = fg.copy(alpha = if (enabled) 1f else Alpha.pending), fontFamily = lx.face,
            fontSize = (lx.unit.value * k * 0.62f).coerceAtLeast(10f).sp, fontWeight = FontWeight.Normal,
            maxLines = 1, softWrap = false,
        )
    }
}

/** A strip of thumb-sized caps, between the tile and the bottom band. */
@Composable
fun LxKeyStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val lx = LxTheme.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = lx.u(0.2f)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One hint: the cap, then what it does. */
data class Hint(val key: String, val text: String)

/**
 * The hint slot: the surface's own gestures, as caps + words, one line. Lives
 * in the bottom band between `?` and the commits. Overflow is cut, never
 * wrapped: a hint strip is one line or it is not a hint strip.
 */
@Composable
fun RowScope.LxHints(hints: List<Hint>) {
    val lx = LxTheme.current
    Row(
        Modifier.weight(1f).clip(RoundedCornerShape(0.dp)),
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.55f)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        hints.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(lx.u(0.3f))) {
                LxKey(h.key, k = 0.72f)
                LxText(h.text, Type.caption, lx.ink(Alpha.keycapCaption), maxLines = 1)
            }
        }
    }
}

// ── field, status, empty state, pill ──────────────────────────────────────

/**
 * One line of input (LxField): a caption in a gutter, the input, and the
 * accent hairline under it that brightens with focus. Nothing but a key or a
 * tap moves the caret; the keyboard never opens on its own.
 */
@Composable
fun LxField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    val lx = LxTheme.current
    val r = lx.roles
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = lx.radiusRow, topEnd = lx.radiusRow))
            .background(lx.ink(if (focused) Alpha.fieldBoxFocused else Alpha.fieldBoxIdle))
            .heightIn(min = lx.u(3f))
            .padding(horizontal = lx.u(0.5f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.6f)),
    ) {
        LxText(label, Type.caption, if (focused) r.accent else lx.ink(Alpha.caption), Modifier.width(lx.u(4.6f)), maxLines = 1, uppercase = true)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(color = r.ink, fontFamily = lx.face, fontSize = lx.sp(Type.body), letterSpacing = 1.sp),
            cursorBrush = SolidColor(r.accent),
            modifier = Modifier.weight(1f).padding(vertical = lx.u(0.4f))
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) LxText(placeholder, Type.body, lx.ink(Alpha.placeholder), maxLines = 1)
                    inner()
                }
            },
        )
    }
    Box(
        Modifier.fillMaxWidth().height(lx.hairline)
            .background(lx.accent(if (focused) 0.8f else Alpha.fieldRuleIdle)),
    )
}

enum class StatusTone { Info, Ok, Warn, Error }

/** The quiet line: "Linked · main display · 2 fps", "Connecting…", "Could not reach the desktop". */
@Composable
fun LxStatus(text: String, tone: StatusTone = StatusTone.Info, modifier: Modifier = Modifier) {
    val lx = LxTheme.current
    val r = lx.roles
    val color = when (tone) {
        StatusTone.Info -> lx.ink(Alpha.statusInfo)
        StatusTone.Ok -> lx.okText
        StatusTone.Warn -> lx.cautionText
        StatusTone.Error -> lx.warnText
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = lx.u(1.6f)).padding(horizontal = lx.u(0.2f))
            .semantics { contentDescription = text; liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.5f)),
    ) {
        Box(Modifier.size(lx.u(0.45f)).clip(CircleShape).background(color))
        LxText(text, Type.caption, color, maxLines = 2)
    }
}

/** What a pane shows when nothing feeds it: a Light headline, numbered steps, a footnote, the last problem. */
@Composable
fun LxEmptyState(
    headline: String,
    steps: List<String> = emptyList(),
    footnote: String? = null,
    error: String? = null,
    modifier: Modifier = Modifier,
) {
    val lx = LxTheme.current
    Column(modifier.padding(horizontal = lx.u(0.2f), vertical = lx.u(0.6f)), verticalArrangement = Arrangement.spacedBy(lx.u(0.8f))) {
        LxText(headline, Type.title.copy(weight = FontWeight.Light), modifier = Modifier.semantics { heading() })
        if (steps.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(lx.u(0.45f))) {
            steps.forEachIndexed { i, s ->
                Row(horizontalArrangement = Arrangement.spacedBy(lx.u(0.8f))) {
                    LxText("${i + 1}", Type.body.copy(weight = FontWeight.Medium), lx.roles.accent)
                    LxText(s, Type.body)
                }
            }
        }
        if (footnote != null) LxText(footnote, Type.label.copy(tracking = 0.sp, weight = FontWeight.Light), lx.ink(Alpha.caption))
        if (error != null) LxText(error, Type.caption, lx.roles.warn)
    }
}

/** A status pill over a picture: "paused after 30 s · tap to resume". */
@Composable
fun BoxScope.LxPill(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val lx = LxTheme.current
    val r = lx.roles
    Row(
        modifier
            .align(Alignment.Center)
            .clip(CircleShape)
            .background(r.ground.copy(alpha = 0.88f))
            .border(lx.hairline, r.line.copy(alpha = 0.4f), CircleShape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = lx.u(0.9f), vertical = lx.u(0.45f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(lx.u(0.4f)),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(lx.u(1f)), tint = r.ink)
        LxText(text, Type.caption, r.ink, maxLines = 1)
    }
}

/** A badge in a picture's corner: "2.0×", "132 %". */
@Composable
fun BoxScope.LxBadge(text: String, alignment: Alignment = Alignment.BottomEnd) {
    val lx = LxTheme.current
    Box(
        Modifier.align(alignment).padding(lx.u(0.5f))
            .clip(RoundedCornerShape(lx.radiusChip))
            .background(lx.roles.ground.copy(alpha = 0.85f))
            .padding(horizontal = lx.u(0.5f), vertical = lx.u(0.2f)),
    ) { LxText(text, Type.caption, lx.roles.accent, maxLines = 1) }
}

/** The surface a mirror or a terminal draws in: a card-radius box with a hairline edge. */
@Composable
fun LxSurface(modifier: Modifier = Modifier, fill: Color = LxTheme.current.roles.ground.copy(alpha = 0.7f), content: @Composable BoxScope.() -> Unit) {
    val lx = LxTheme.current
    Box(
        modifier
            .clip(RoundedCornerShape(lx.radiusCard))
            .background(fill)
            .border(lx.hairline, lx.roles.line.copy(alpha = 0.3f), RoundedCornerShape(lx.radiusCard)),
        content = content,
    )
}

/** The scrim under a card that rose over another. */
@Composable
fun LxScrim(modifier: Modifier = Modifier, onTap: () -> Unit) {
    val lx = LxTheme.current
    Box(
        modifier.fillMaxSize()
            .background(lx.roles.ground.copy(alpha = 0.55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
    )
}

/** Bool → the animated alpha a show/hide needs: never a hard cut. */
@Composable
fun animateShow(shown: Boolean): Float {
    val lx = LxTheme.current
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(lx.fast), label = "show")
    return a
}

// ── the focus ring, the title door, the dialog, the meter ─────────────────

/**
 * The focus ring: the accent at the active-border alpha, drawn outside the
 * stop. Keyboard / switch-access only — a touch never focuses a button, so
 * this is the phone's :focus-visible.
 */
@Composable
fun Modifier.lxFocusRing(stop: Boolean): Modifier {
    if (!stop) return this
    val lx = LxTheme.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    return this
        .focusable(interactionSource = source)
        .then(if (focused) Modifier.border(2.dp, lx.accent(Alpha.activeBorder), CircleShape) else Modifier)
}

/**
 * The band title as the Window card's door: the wordmark with an unfold glyph,
 * the caption under it, ONE tap target with a spoken name. Shrunk under a
 * sheet it is the way back instead.
 */
@Composable
fun RowScope.LxTitleDoor(
    title: String,
    caption: String,
    unfold: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val lx = LxTheme.current
    Column(
        modifier
            .weight(1f)
            .heightIn(min = lx.tap)
            .clip(RoundedCornerShape(lx.radiusRow))
            .clickable(role = Role.Button, onClick = onClick)
            .lxFocusRing(true)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = lx.u(0.3f), vertical = lx.u(0.2f)),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(lx.u(0.25f))) {
            LxText(title, Type.heading, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
            if (unfold) Icon(Icons.Filled.ExpandMore, null, Modifier.size(lx.u(1.1f)), tint = lx.ink(Alpha.caption))
        }
        LxText(caption, Type.caption, lx.ink(Alpha.caption), maxLines = 2)
    }
}

/** A card's secondary task as a DIALOG over its dimmed tile: a section label, the one thing it needs. */
@Composable
fun LxDialog(title: String, badge: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val lx = LxTheme.current
    val r = lx.roles
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(lx.radiusPane))
            .background(r.surface.copy(alpha = Alpha.paneActive))
            .border(2.dp, r.accent.copy(alpha = Alpha.activeBorder), RoundedCornerShape(lx.radiusPane))
            .padding(lx.inset)
            .semantics { contentDescription = title },
    ) {
        LxSection(title, badge = badge, here = true)
        content()
    }
}

/** A level meter: ink track, accent fill. Fed at 6 Hz or slower; still at rest. */
@Composable
fun LxMeter(level: Float, modifier: Modifier = Modifier) {
    val lx = LxTheme.current
    val v by animateFloatAsState(level.coerceIn(0f, 1f), tween(lx.fast), label = "meter")
    Box(
        modifier.fillMaxWidth().height(lx.u(0.5f)).clip(CircleShape).background(lx.ink(Alpha.hairline))
            .semantics { contentDescription = "input level" },
    ) {
        Box(Modifier.fillMaxWidth(fraction = v.coerceAtLeast(0.001f)).height(lx.u(0.5f)).clip(CircleShape).background(lx.roles.accent))
    }
}
