package com.rkd.audiobasics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Morph overlay — a "container transform" for popups.
 *
 * A menu or dialog grows out of the exact button that opened it (and shrinks back into it
 * on dismiss), instead of appearing from nowhere.
 *
 * WHY THIS ISN'T A Dialog / DropdownMenu: those live in their own Android window, so nothing
 * can animate between them and a button in the app window. Instead, popups that use this
 * are drawn INSIDE the app's own composition by a single MorphOverlayHost (see MainActivity),
 * on top of everything else.
 *
 * HOW IT WORKS: the trigger records its on-screen bounds when tapped; the host then animates
 * a rounded container from those bounds to the popup's final size/position. The popup's
 * content is laid out at its final size the whole time and simply revealed (clipped) by the
 * growing container, so text never gets squashed. Content fades in halfway through.
 *
 * USAGE (at a trigger):
 *     val overlay = LocalMorphOverlay.current
 *     val anchor = rememberMorphAnchor()
 *     IconButton(
 *         onClick = { overlay.show(anchor.bounds(), MorphPlacement.Anchored) { close -> MyMenu(close) } },
 *         modifier = Modifier.morphAnchor(anchor)
 *     ) { ... }
 *
 * LIMITATION: a trigger that lives inside a real Dialog window (e.g. PlayerDialog) can't use
 * the app-level host — the overlay would render underneath that window. Such a window needs
 * its own MorphOverlayHost + LocalMorphOverlay provider inside it.
 */

private const val MORPH_ENTER_MS = 280
private const val MORPH_EXIT_MS = 200
private val MorphEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private val MorphMenuMaxWidth = 280.dp
private val MorphDialogMaxWidth = 560.dp
private val MorphMenuMargin = 8.dp
private val MorphDialogMargin = 24.dp

enum class MorphPlacement {
    /** Small popup positioned next to the trigger (menus). Right edge lines up with the trigger's right edge. */
    Anchored,

    /** Centered popup with a dimmed backdrop (dialogs). */
    Center
}

@Stable
class MorphEntry internal constructor(
    val anchor: Rect?,
    val placement: MorphPlacement,
    val containerColor: Color,
    val onDismissRequest: () -> Unit,
    val content: @Composable (close: () -> Unit) -> Unit
) {
    /** True once dismissal has started; the host plays the shrink animation, then removes the entry. */
    var closing by mutableStateOf(false)
}

/**
 * Holds every currently-open popup for one window, as a stack: [show] pushes a new one on top
 * without disturbing whatever's already open underneath (e.g. Create Playlist opening on top of
 * Add to Playlist), and each is closed independently — closing the top one reveals the one below,
 * unchanged. Back press closes only the topmost, since Compose's BackHandler already resolves to
 * the most-recently-registered callback first, matching stack order.
 */
@Stable
class MorphOverlayState {
    private val _entries = mutableStateListOf<MorphEntry>()
    internal val entries: List<MorphEntry> get() = _entries

    /**
     * Opens a popup on top of whatever's already open. [anchor] is the trigger's bounds (from
     * [MorphAnchor.bounds]); pass null to get a plain scale-in instead. [content] receives a
     * `close` callback that plays this popup's own exit animation. [onDismissRequest] fires once,
     * as soon as this popup starts closing (backdrop tap, back press, or `close()`) — use it to
     * reset whatever boolean state made you call show() in the first place, so the caller and the
     * overlay never end up disagreeing about open/closed. Returns the entry, so callers that open
     * a popup outside of a click handler (see [MorphPopup]) can close that specific one later,
     * regardless of what else has been pushed on top of it since.
     */
    fun show(
        anchor: Rect?,
        placement: MorphPlacement = MorphPlacement.Center,
        containerColor: Color = Color.Unspecified,
        onDismissRequest: () -> Unit = {},
        content: @Composable (close: () -> Unit) -> Unit
    ): MorphEntry {
        val e = MorphEntry(anchor, placement, containerColor, onDismissRequest, content)
        _entries.add(e)
        return e
    }

    /** Closes the topmost popup (with its exit animation), if any. */
    fun close() {
        _entries.lastOrNull()?.let { requestClose(it) }
    }

    /** Starts closing [e] specifically, wherever it sits in the stack. Safe to call more than once. */
    internal fun requestClose(e: MorphEntry) {
        if (!e.closing) {
            e.closing = true
            e.onDismissRequest()
        }
    }

    internal fun remove(e: MorphEntry) {
        _entries.remove(e)
    }
}

val LocalMorphOverlay = staticCompositionLocalOf<MorphOverlayState> {
    error("No MorphOverlayState provided — wrap the app in CompositionLocalProvider(LocalMorphOverlay provides ...)")
}

/** Remembers where a trigger is on screen, so the popup can grow out of it. */
@Stable
class MorphAnchor {
    private var coordinates: LayoutCoordinates? = null

    internal fun update(c: LayoutCoordinates) {
        coordinates = c
    }

    /** Current bounds of the trigger in root coordinates, or null if it isn't on screen. */
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()
}

@Composable
fun rememberMorphAnchor(): MorphAnchor = remember { MorphAnchor() }

fun Modifier.morphAnchor(anchor: MorphAnchor): Modifier =
    this.onGloballyPositioned { anchor.update(it) }

/**
 * For popups driven by an outer `var show... by remember { mutableStateOf(false) }` (the
 * pattern used throughout this app: `if (showX) SomeDialog(..., onDismiss = { showX = false })`).
 *
 * Opens on first composition and — critically — also calls [overlay]'s close() when THIS
 * composable leaves composition for any reason, including the caller setting its `show`
 * flag to false directly from an action handler (e.g. after a successful create/save)
 * rather than through [onDismissRequest]. Without that, the overlay entry would outlive the
 * composable that created it, since MorphOverlayHost renders independently of the call site.
 *
 * Use this instead of calling `overlay.show(...)` directly inside a LaunchedEffect(Unit).
 */
@Composable
fun MorphPopup(
    onDismissRequest: () -> Unit,
    anchor: Rect? = null,
    placement: MorphPlacement = MorphPlacement.Center,
    containerColor: Color = Color.Unspecified,
    overlay: MorphOverlayState = LocalMorphOverlay.current,
    content: @Composable (close: () -> Unit) -> Unit
) {
    DisposableEffect(Unit) {
        val e = overlay.show(anchor, placement, containerColor, onDismissRequest, content)
        onDispose { overlay.requestClose(e) }
    }
}

/**
 * Draws every currently-open popup for this [state], stacked in open order (most recently
 * opened on top). Place exactly once per window, as the last child of a full-screen Box, and
 * provide the same state via LocalMorphOverlay.
 */
@Composable
fun MorphOverlayHost(state: MorphOverlayState) {
    for (entry in state.entries) {
        key(entry) {
            MorphContainer(entry = entry, state = state)
        }
    }
}

private class MorphGeometry {
    /** Top-left of the popup in root coordinates; written during layout, read while drawing. */
    var origin: Offset = Offset.Zero
}

private class MorphFrame(val rect: Rect, val radius: Float)

private data class MorphOutlineShape(val rect: Rect, val radius: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/** Container bounds + corner radius at animation progress [p] (0 = trigger, 1 = fully open), in the popup's local coordinates. */
private fun morphFrame(
    size: Size,
    origin: Offset,
    anchor: Rect?,
    endRadius: Float,
    p: Float
): MorphFrame {
    val t = p.coerceIn(0f, 1f)
    val start = if (anchor != null) {
        anchor.translate(-origin.x, -origin.y)
    } else {
        Rect(size.width * 0.1f, size.height * 0.1f, size.width * 0.9f, size.height * 0.9f)
    }
    val end = Rect(
        left = 0f,
        top = 0f,
        right = size.width,
        bottom = size.height
    )
    val startRadius = if (anchor != null) min(start.width, start.height) / 2f else endRadius
    val rect = Rect(
        left = start.left + (end.left - start.left) * t,
        top = start.top + (end.top - start.top) * t,
        right = start.right + (end.right - start.right) * t,
        bottom = start.bottom + (end.bottom - start.bottom) * t
    )
    val radius = (startRadius + (endRadius - startRadius) * t)
        .coerceIn(0f, min(rect.width, rect.height) / 2f)
    return MorphFrame(rect, radius)
}

// The container's fill/shadow fade in over the first 15% so the trigger icon doesn't blink out.
private fun containerAlpha(p: Float): Float = (p / 0.15f).coerceIn(0f, 1f)

// Content fades in between 30% and 80%, once the container is big enough to hold it.
private fun contentAlpha(p: Float): Float = ((p - 0.3f) / 0.5f).coerceIn(0f, 1f)

@Composable
private fun MorphContainer(entry: MorphEntry, state: MorphOverlayState) {
    val progress = remember { Animatable(0f) }
    val geometry = remember { MorphGeometry() }
    val systemBars = WindowInsets.systemBars
    val containerColor = entry.containerColor.takeOrElse { MaterialTheme.colorScheme.surface }
    val isAnchored = entry.placement == MorphPlacement.Anchored
    val isDarkTheme = isSystemInDarkTheme()
    val isDarkDialog = !isAnchored && isDarkTheme
    val scrimAlpha = if (isAnchored) 0f else 0.5f
    val cornerRadius = if (isAnchored) 12.dp else 16.dp
    val elevation = if (isAnchored || !isDarkDialog) 8.dp else 0.dp

    val requestClose = remember(entry) { { state.requestClose(entry) } }

    LaunchedEffect(entry.closing) {
        if (entry.closing) {
            progress.animateTo(0f, tween(MORPH_EXIT_MS, easing = MorphEasing))
            state.remove(entry)
        } else {
            progress.animateTo(1f, tween(MORPH_ENTER_MS, easing = MorphEasing))
        }
    }

    // Registered after NavDisplay's own back handler, so it wins while a popup is open.
    BackHandler(enabled = !entry.closing) { requestClose() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop: dims (dialogs only) and closes on outside tap. Also blocks touches to the app below.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = scrimAlpha * progress.value))
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { requestClose() })
                }
        )

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                MorphSurface(
                    entry = entry,
                    requestClose = requestClose,
                    geometry = geometry,
                    progress = { progress.value },
                    containerColor = containerColor,
                    cornerRadius = cornerRadius,
                    elevation = elevation,
                    isDarkDialog = isDarkDialog
                )
            }
        ) { measurables, constraints ->
            val screenW = constraints.maxWidth
            val screenH = constraints.maxHeight
            val margin = (if (isAnchored) MorphMenuMargin else MorphDialogMargin).roundToPx()

            val areaL = systemBars.getLeft(this, layoutDirection) + margin
            val areaR = max(areaL, screenW - systemBars.getRight(this, layoutDirection) - margin)
            val areaT = systemBars.getTop(this) + margin
            val areaB = max(areaT, screenH - systemBars.getBottom(this) - margin)
            val areaW = areaR - areaL
            val areaH = areaB - areaT

            val childConstraints = if (isAnchored) {
                Constraints(
                    minWidth = 0,
                    maxWidth = min(areaW, MorphMenuMaxWidth.roundToPx()),
                    minHeight = 0,
                    maxHeight = areaH
                )
            } else {
                Constraints(
                    minWidth = 0,
                    maxWidth = min(areaW, MorphDialogMaxWidth.roundToPx()),
                    minHeight = 0,
                    maxHeight = areaH
                )
            }

            val placeable = measurables[0].measure(childConstraints)
            val pw = placeable.width
            val ph = placeable.height

            val anchor = entry.anchor
            val x: Int
            val y: Int
            if (isAnchored && anchor != null) {
                // Right edge on the trigger's right edge, top on the trigger's top; pushed back on-screen if needed.
                x = (anchor.right.roundToInt() - pw).coerceIn(areaL, max(areaL, areaR - pw))
                y = anchor.top.roundToInt().coerceIn(areaT, max(areaT, areaB - ph))
            } else {
                x = areaL + (areaW - pw) / 2
                y = areaT + (areaH - ph) / 2
            }

            geometry.origin = Offset(x.toFloat(), y.toFloat())

            layout(screenW, screenH) {
                placeable.place(x, y)
            }
        }
    }
}

@Composable
private fun MorphSurface(
    entry: MorphEntry,
    requestClose: () -> Unit,
    geometry: MorphGeometry,
    progress: () -> Float,
    containerColor: Color,
    cornerRadius: Dp,
    elevation: Dp,
    isDarkDialog: Boolean
) {
    val density = LocalDensity.current
    val endRadiusPx = with(density) { cornerRadius.toPx() }
    val close = remember(entry) { requestClose }

    Box(
        modifier = Modifier
            // Light mode keeps the standard elevation shadow. Dark dialogs use only the
            // explicit gray outline below.
            .graphicsLayer {
                val p = progress()
                val frame = morphFrame(
                    size,
                    geometry.origin,
                    entry.anchor,
                    endRadiusPx,
                    p
                )
                shadowElevation = if (isDarkDialog) 0f else {
                    with(density) { elevation.toPx() } * containerAlpha(p)
                }
                shape = MorphOutlineShape(frame.rect, frame.radius)
                clip = false
            }
            .drawWithContent {
                val p = progress()
                val alpha = containerAlpha(p)
                val frame = morphFrame(
                    size,
                    geometry.origin,
                    entry.anchor,
                    endRadiusPx,
                    p
                )

                drawRoundRect(
                    color = containerColor,
                    topLeft = frame.rect.topLeft,
                    size = frame.rect.size,
                    cornerRadius = CornerRadius(frame.radius),
                    alpha = alpha
                )

                val reveal = Path().apply {
                    addRoundRect(RoundRect(frame.rect, CornerRadius(frame.radius)))
                }
                clipPath(reveal) {
                    this@drawWithContent.drawContent()
                }

                // Dark-mode dialogs get a simple, visible gray outline. Draw it LAST so the
                // dialog content cannot cover the outline. Light mode keeps the normal shadow.
                if (isDarkDialog && alpha > 0f) {
                    drawRoundRect(
                        color = Color(0xFF6B6B6B),
                        topLeft = frame.rect.topLeft,
                        size = frame.rect.size,
                        cornerRadius = CornerRadius(frame.radius),
                        style = Stroke(width = with(density) { 2.dp.toPx() }),
                        alpha = alpha
                    )
                }
            }
            // Taps on the popup itself must not fall through to the backdrop (which would close it).
            .pointerInput(Unit) {
                detectTapGestures(onTap = { })
            }
    ) {
        Box(
            modifier = Modifier
                    .graphicsLayer { alpha = contentAlpha(progress()) }
        ) {
            entry.content(close)
        }
        if (entry.closing) {
            // While shrinking away, swallow touches so an item can't be tapped twice.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { }) }
            )
        }
    }
}

/* ---------- Menu building blocks (match the look of the old DropdownMenu) ---------- */

@Composable
fun MorphMenuColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(min = 180.dp)
            .width(IntrinsicSize.Max)
            .padding(vertical = 8.dp),
        content = content
    )
}

@Composable
fun MorphMenuItem(
    text: String,
    onClick: () -> Unit,
    leadingIcon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
    textColor: Color = Color.Unspecified
) {
    // Keep explicit icons when a caller needs a specific icon/state; otherwise infer the common
    // action from its label so every Morph menu has the same visual language.
    val actionIcon = leadingIcon ?: when {
        text.equals("Share", ignoreCase = true) -> Icons.Default.Share
        text.startsWith("Add to playlist", ignoreCase = true) -> Icons.Default.Add
        text.equals("Play next", ignoreCase = true) -> Icons.Default.SkipNext
        text.equals("Add to queue", ignoreCase = true) || text.equals("Queue", ignoreCase = true) -> Icons.Default.QueueMusic
        text.equals("Play all", ignoreCase = true) -> Icons.Default.PlayArrow
        text.equals("Shuffle", ignoreCase = true) -> Icons.Default.Shuffle
        text.equals("Like", ignoreCase = true) -> Icons.Default.FavoriteBorder
        text.equals("Unlike", ignoreCase = true) -> Icons.Default.Favorite
        text.equals("Reorder", ignoreCase = true) || text.equals("Cancel reorder", ignoreCase = true) -> Icons.Default.SwapVert
        text.equals("Rename", ignoreCase = true) -> Icons.Default.Edit
        text.equals("Delete", ignoreCase = true) || text.startsWith("Remove from", ignoreCase = true) -> Icons.Default.Delete
        else -> null
    }

    val destructive = text.equals("Unlike", ignoreCase = true) ||
        text.equals("Delete", ignoreCase = true) ||
        text.startsWith("Remove from", ignoreCase = true)

    val resolvedIconTint = if (destructive) {
        iconTint.takeOrElse { textColor.takeOrElse { Color.Red } }
    } else {
        iconTint.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actionIcon?.let {
            Icon(
                imageVector = it,
                contentDescription = null,
                tint = resolvedIconTint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = textColor.takeOrElse { MaterialTheme.colorScheme.onSurface }
        )
    }
}
