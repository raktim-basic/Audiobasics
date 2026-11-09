package com.rkd.audiobasics.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import android.os.SystemClock
import kotlinx.coroutines.launch
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

// One shared easing curve drives every popup transition, in both opening and closing directions.
private const val MORPH_DIALOG_ENTER_MS = 300
private const val MORPH_DIALOG_EXIT_MS = 220
// Shared curve for every popup transition.
private val MorphEasing = CubicBezierEasing(0.13f, 0.3f, 0.01f, 1f)

/** A tap is considered "the thing that opened this popup" while held, and for this long after release. */
private const val MORPH_TAP_FRESH_MS = 800L
private val MorphTapRectSize = 40.dp

// The trigger button fades out over the first quarter of the open animation (while the container
// is still covering it) and fades back in over the last quarter of the close, so it is never
// left sitting on screen next to a dialog that grew out of it.
private fun sourceHideAmount(p: Float): Float = (p / 0.25f).coerceIn(0f, 1f)

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
    val highlightBounds: Rect?,
    val wide: Boolean,
    val bare: Boolean,
    val onDismissRequest: () -> Unit,
    val content: @Composable (close: () -> Unit) -> Unit,
    /** False = a tap on the dimmed backdrop does nothing (back press and `close()` still work). */
    val dismissOnOutsideTap: Boolean = true,
    /**
     * While this returns true (a cloud write is running), the popup can't be dismissed by a
     * backdrop tap OR the back button — both are swallowed. Only the popup's own `close()`
     * (called by its content when the work finishes) still closes it. Read live, so it can
     * be backed by Compose state.
     */
    val isLocked: () -> Boolean = { false },
    /**
     * Live bounds of the trigger, read every frame so the popup collapses back into where the
     * button is NOW (not where it was when the popup opened). Null for entries opened with an
     * explicit [anchor] rect (menus) — those keep using that rect.
     */
    internal val liveAnchor: (() -> Rect?)? = null,
    /** True when [anchor] was picked automatically from the tap that opened a dialog. */
    internal val autoSourced: Boolean = false,
    /** The tagged trigger button to fade out while this popup is open (null if there isn't one). */
    internal val sourceAnchor: MorphAnchor? = null
) {
    /** True once dismissal has started; the host plays the shrink animation, then removes the entry. */
    var closing by mutableStateOf(false)

    /** Creation order, so [MorphSources] can tell which tagged buttons were composed inside this popup. */
    internal val seq: Long = MorphSources.nextSeq()

    /** Where the popup was last laid out (root coordinates); written during layout, read on taps. */
    internal var layoutBounds: Rect? = null

    internal fun currentAnchor(): Rect? = liveAnchor?.invoke() ?: anchor
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

    // ---- Where did the user just tap? -------------------------------------------------------
    // Dialogs are opened by `if (showX) SomeDialog(...)` flags, far from the button that set the
    // flag, so they can't be handed an anchor. Instead every pointer-down is recorded here (by
    // [morphTapTracker]) together with the best "source" for it, and the next popup that opens
    // without an explicit anchor grows from that source.
    private var tapSource: (() -> Rect?)? = null
    private var tapInitial: Rect? = null
    private var tapAnchor: MorphAnchor? = null
    private var tapDown = false
    private var tapLastEventMs = 0L
    private var tapConsumed = true

    internal fun onTapDown(position: Offset, tapRectPx: Float) {
        val half = tapRectPx / 2f
        val pointRect = Rect(position.x - half, position.y - half, position.x + half, position.y + half)
        val live = _entries.filter { !it.closing }
        val top = live.lastOrNull { it.layoutBounds?.contains(position) == true }

        var source: (() -> Rect?)? = null
        var initial: Rect? = null
        var sourceAnchor: MorphAnchor? = null
        if (top != null) {
            val menuAnchor = top.anchor
            if (top.placement == MorphPlacement.Anchored && menuAnchor != null) {
                // A tap on a menu item: the dialog it opens grows from the 3-dot button the menu
                // itself grew from, which is exactly where that menu is collapsing back to.
                initial = menuAnchor
                source = { menuAnchor }
                sourceAnchor = MorphSources.findByBounds(menuAnchor)
            } else {
                // A tap inside a dialog/card: use a tagged button inside it if there is one,
                // otherwise the touch point.
                val hit = MorphSources.hit(position, afterSeq = top.seq)
                if (hit != null) {
                    initial = hit.bounds()
                    source = hit.liveOrLast(initial)
                    sourceAnchor = hit
                }
            }
        } else if (live.isEmpty()) {
            val hit = MorphSources.hit(position, afterSeq = -1L)
            if (hit != null) {
                initial = hit.bounds()
                source = hit.liveOrLast(initial)
                sourceAnchor = hit
            }
        } else {
            // Tap on a backdrop outside every open popup: that dismisses, it doesn't open anything.
            tapSource = null
            tapInitial = null
            tapAnchor = null
            tapDown = true
            tapLastEventMs = SystemClock.uptimeMillis()
            tapConsumed = true
            return
        }
        if (initial == null) {
            initial = pointRect
            source = { pointRect }
            sourceAnchor = null
        }
        tapSource = source
        tapInitial = initial
        tapAnchor = sourceAnchor
        tapDown = true
        tapLastEventMs = SystemClock.uptimeMillis()
        tapConsumed = false
    }

    internal fun onTapUp() {
        tapDown = false
        tapLastEventMs = SystemClock.uptimeMillis()
    }

    /** The source for a popup opening right now, or null if nothing was tapped recently. Single use per tap. */
    private fun consumeTapSource(): TapSource? {
        if (tapConsumed) return null
        val fresh = tapDown || SystemClock.uptimeMillis() - tapLastEventMs <= MORPH_TAP_FRESH_MS
        tapConsumed = true
        val initial = tapInitial ?: return null
        val source = tapSource ?: return null
        return if (fresh) TapSource(initial, source, tapAnchor) else null
    }

    internal class TapSource(val initial: Rect, val live: () -> Rect?, val anchor: MorphAnchor?)

    /**
     * Opens a popup on top of whatever's already open. [anchor] is the trigger's bounds (from
     * [MorphAnchor.bounds]); pass null to get a plain scale-in instead. [content] receives a
     * `close` callback that plays this popup's own exit animation. [onDismissRequest] fires once,
     * as soon as this popup starts closing (backdrop tap, back press, or `close()`) — use it to
     * reset whatever boolean state made you call show() in the first place, so the caller and the
     * overlay never end up disagreeing about open/closed. Returns the entry, so callers that open
     * a popup outside of a click handler (see [MorphPopup]) can close that specific one later,
     * regardless of what else has been pushed on top of it since.
     *
     * [highlightBounds] is the region (root coordinates) that should stay in color while this
     * popup is open and the rest of the background goes monochrome (see [MonochromeExcept]).
     * Defaults to [anchor] — for a menu that's usually right, but pass something wider (e.g. a
     * whole list row instead of just its icon) when the trigger is smaller than what should stay
     * highlighted. Pass null explicitly for "nothing behind this popup should be excluded."
     *
     * [wide] relaxes the usual dialog max-width cap for a [MorphPlacement.Center] popup that
     * wants to use most of the screen width (the player card) rather than a compact alert size.
     *
     * [bare] skips MorphSurface's own container fill/shadow entirely, for content (the player)
     * that already draws its own full surface — background, shape, corners — at its own size.
     * Without this, MorphSurface's container paints a second, differently-sized rectangle
     * behind that content, which is easy to mistake for "the card" not animating/rotating.
     * It also skips the reveal clip once fully open (still applied during the brief open/close
     * transition, for the scale-in/out look) — a 3D-rotated child's rendered pixels can bulge
     * slightly past its own flat layout bounds on the near side, and that clip is sized to
     * those flat bounds, so leaving it on all the time clips off that bulge as flat-cut corners.
     */
    fun show(
        anchor: Rect?,
        placement: MorphPlacement = MorphPlacement.Center,
        containerColor: Color = Color.Unspecified,
        highlightBounds: Rect? = anchor,
        wide: Boolean = false,
        bare: Boolean = false,
        onDismissRequest: () -> Unit = {},
        dismissOnOutsideTap: Boolean = true,
        isLocked: () -> Boolean = { false },
        content: @Composable (close: () -> Unit) -> Unit
    ): MorphEntry {
        // Always consume, so one tap never feeds two popups. Only dialogs without an explicit
        // anchor use it; the card player (bare) keeps its own scale-in, and menus keep theirs.
        val tapped = consumeTapSource()
        val auto = if (anchor == null && placement == MorphPlacement.Center && !bare) tapped else null
        val e = MorphEntry(
            auto?.initial ?: anchor, placement, containerColor, highlightBounds, wide, bare,
            onDismissRequest, content, dismissOnOutsideTap, isLocked,
            liveAnchor = auto?.live, autoSourced = auto != null,
            sourceAnchor = if (auto != null) auto.anchor
                else if (anchor != null && !bare) MorphSources.findByBounds(anchor) else null
        )
        _entries.add(e)
        return e
    }

    /** Every open popup's [MorphEntry.highlightBounds], for [MonochromeExcept] to keep in color. */
    internal val highlightBoundsList: List<Rect> get() = _entries.mapNotNull { it.highlightBounds }

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
    internal var seq: Long = 0L

    /** 0 = fully visible, 1 = hidden. Driven by the popup(s) that grew out of this trigger. */
    internal var hidden by mutableStateOf(0f)
        private set

    // More than one popup can claim the same button at once (a menu collapsing into it while the
    // dialog it opened grows out of it). Each reports its own amount; the button follows the
    // largest, so it stays hidden through the hand-over instead of flickering between writers.
    private val hideClaims = HashMap<Any, Float>()

    internal fun setHidden(owner: Any, amount: Float) {
        if (amount <= 0f) hideClaims.remove(owner) else hideClaims[owner] = amount
        hidden = hideClaims.values.maxOrNull() ?: 0f
    }

    internal fun update(c: LayoutCoordinates) {
        coordinates = c
    }

    /** Current bounds of the trigger in root coordinates, or null if it isn't on screen. */
    fun bounds(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInRoot()

    /** Live bounds, falling back to [last] if the trigger has left the screen (e.g. its row was removed). */
    internal fun liveOrLast(last: Rect?): () -> Rect? = { bounds() ?: last }
}

/**
 * Every on-screen [MorphAnchor], so a tap can be matched to the button under the finger. Main
 * thread only. A tagged button is any composable using [rememberMorphAnchor] / [morphSource].
 */
internal object MorphSources {
    private val anchors = LinkedHashSet<MorphAnchor>()
    private var counter = 0L

    fun nextSeq(): Long = ++counter

    fun register(a: MorphAnchor) {
        a.seq = nextSeq()
        anchors.add(a)
    }

    fun unregister(a: MorphAnchor) {
        anchors.remove(a)
    }

    /** The registered button whose bounds equal [r] (to within a pixel), or null. */
    fun findByBounds(r: Rect): MorphAnchor? {
        for (a in anchors) {
            val b = a.bounds() ?: continue
            if (kotlin.math.abs(b.left - r.left) < 1f && kotlin.math.abs(b.top - r.top) < 1f &&
                kotlin.math.abs(b.right - r.right) < 1f && kotlin.math.abs(b.bottom - r.bottom) < 1f
            ) return a
        }
        return null
    }

    /** Smallest registered button containing [p] that was composed after [afterSeq], or null. */
    fun hit(p: Offset, afterSeq: Long): MorphAnchor? {
        var best: MorphAnchor? = null
        var bestArea = Float.MAX_VALUE
        for (a in anchors) {
            if (a.seq <= afterSeq) continue
            val b = a.bounds() ?: continue
            if (!b.contains(p)) continue
            val area = b.width * b.height
            if (area < bestArea) {
                best = a
                bestArea = area
            }
        }
        return best
    }
}

@Composable
fun rememberMorphAnchor(): MorphAnchor {
    val anchor = remember { MorphAnchor() }
    DisposableEffect(anchor) {
        MorphSources.register(anchor)
        onDispose { MorphSources.unregister(anchor) }
    }
    return anchor
}

/**
 * Tag a button as the visual source of whatever dialog its tap opens: the dialog grows out of
 * these exact bounds and collapses back into them. No wiring needed — [MorphOverlayHost] matches
 * the tap to the smallest tagged button under the finger. Untagged triggers still work: the
 * dialog then grows from the touch point.
 */
@Composable
fun Modifier.morphSource(): Modifier {
    val anchor = rememberMorphAnchor()
    return this.morphAnchor(anchor)
}

/** Records every pointer-down/up (without consuming it) for [MorphOverlayState]'s tap source. */
private fun Modifier.morphTapTracker(state: MorphOverlayState): Modifier =
    this.pointerInput(state) {
        val tapRectPx = MorphTapRectSize.toPx()
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (!change.previousPressed && change.pressed) {
                    state.onTapDown(change.position, tapRectPx)
                } else if (change.previousPressed && !change.pressed) {
                    state.onTapUp()
                }
            }
        }
    }

fun Modifier.morphAnchor(anchor: MorphAnchor): Modifier =
    this.onGloballyPositioned { anchor.update(it) }
        // Read inside the layer block, so fading only redraws this button — no recomposition.
        .graphicsLayer { alpha = 1f - anchor.hidden }

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
    highlightBounds: Rect? = anchor,
    wide: Boolean = false,
    bare: Boolean = false,
    dismissOnOutsideTap: Boolean = true,
    isLocked: () -> Boolean = { false },
    overlay: MorphOverlayState = LocalMorphOverlay.current,
    content: @Composable (close: () -> Unit) -> Unit
) {
    // Read through rememberUpdatedState so the entry (created once) always sees the latest lambda.
    val currentIsLocked by rememberUpdatedState(isLocked)
    DisposableEffect(Unit) {
        val e = overlay.show(
            anchor = anchor,
            placement = placement,
            containerColor = containerColor,
            highlightBounds = highlightBounds,
            wide = wide,
            bare = bare,
            onDismissRequest = onDismissRequest,
            dismissOnOutsideTap = dismissOnOutsideTap,
            isLocked = { currentIsLocked() },
            content = content
        )
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
    val entries = state.entries
    for (i in entries.indices) {
        val entry = entries[i]
        key(entry) {
            val above = entries.subList(i + 1, entries.size)
            MonochromeLayer(
                active = above.isNotEmpty(),
                highlights = above.mapNotNull { it.highlightBounds },
                modifier = Modifier.fillMaxSize().morphTapTracker(state)
            ) {
                MorphContainer(entry = entry, state = state)
            }
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
    val imeInsets = WindowInsets.ime
    val containerColor = entry.containerColor.takeOrElse { MaterialTheme.colorScheme.surface }
    val isAnchored = entry.placement == MorphPlacement.Anchored
    // The app's own theme (Settings > Appearance), NOT the system's. Using the system setting
    // meant a Light app theme on a dark-mode phone was treated as dark here: the drop shadow
    // was switched off and the (light) glow was drawn instead, invisible on a light surface.
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // In dark mode, only anchored menus get the explicit outline. Centered dialogs stay clean.
    val isDarkMenu = isDarkTheme && isAnchored
    val scrimAlpha = if (isAnchored) 0f else 0.5f
    val cornerRadius = if (isAnchored) 12.dp else 16.dp
    val elevation = if (isDarkMenu) 0.dp else 8.dp

    val requestClose = remember(entry) { { state.requestClose(entry) } }

    // Centered popup dialogs use dialog motion regardless of source capture. Source capture
    // controls only the origin/trigger treatment, never which easing curve the popup receives.
    val isPopupDialog = entry.placement == MorphPlacement.Center && !entry.bare
    val sourced = entry.autoSourced
    val contentFade = remember { Animatable(0f) }
    val sourceButton = entry.sourceAnchor
    // The animation spec owns easing in both directions: opening follows E(t), while closing
    // follows 1 - E(t). Every visual consumer reads this already-eased progress directly.
    val visualProgress = { progress.value.coerceIn(0f, 1f) }

    // Never leave the trigger hidden if this popup disappears for any reason.
    DisposableEffect(entry) { onDispose { sourceButton?.setHidden(entry, 0f) } }

    LaunchedEffect(entry.closing) {
        if (isPopupDialog) {
            if (sourced) {
                if (entry.closing) {
                    launch { contentFade.animateTo(0f, tween(90, easing = MorphEasing)) }
                } else {
                    launch { contentFade.animateTo(1f, tween(170, delayMillis = 85, easing = MorphEasing)) }
                }
            }

            val target = if (entry.closing) 0f else 1f
            progress.animateTo(
                target,
                tween(
                    durationMillis = if (entry.closing) MORPH_DIALOG_EXIT_MS else MORPH_DIALOG_ENTER_MS,
                    easing = MorphEasing
                )
            ) {
                if (sourced) {
                    sourceButton?.setHidden(entry, sourceHideAmount(value.coerceIn(0f, 1f)))
                }
            }
            if (entry.closing) state.remove(entry)
        } else if (entry.closing) {
            progress.animateTo(0f, tween(MORPH_EXIT_MS, easing = MorphEasing)) {
                sourceButton?.setHidden(entry, sourceHideAmount(value.coerceIn(0f, 1f)))
            }
            state.remove(entry)
        } else {
            progress.animateTo(1f, tween(MORPH_ENTER_MS, easing = MorphEasing)) {
                sourceButton?.setHidden(entry, sourceHideAmount(value.coerceIn(0f, 1f)))
            }
        }
    }

    // Registered after NavDisplay's own back handler, so it wins while a popup is open.
    // While locked (a write is running) the back press is swallowed here, so it neither closes
    // the popup nor falls through to the screen underneath.
    BackHandler(enabled = !entry.closing) { if (!entry.isLocked()) requestClose() }

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop: dims (dialogs only) and closes on outside tap. Also blocks touches to the app below.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    drawRect(Color.Black.copy(alpha = scrimAlpha * visualProgress()))
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        if (entry.dismissOnOutsideTap && !entry.isLocked()) requestClose()
                    })
                }
        )

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                MorphSurface(
                    entry = entry,
                    requestClose = requestClose,
                    geometry = geometry,
                    progress = visualProgress,
                    contentFadeOverride = if (sourced) ({
                        contentFade.value.coerceIn(0f, 1f)
                    }) else null,
                    containerColor = containerColor,
                    cornerRadius = cornerRadius,
                    elevation = elevation,
                    isDarkMenu = isDarkMenu
                )
            }
        ) { measurables, constraints ->
            val screenW = constraints.maxWidth
            val screenH = constraints.maxHeight
            val margin = (
                if (isAnchored) MorphMenuMargin
                else if (entry.wide) 0.dp
                else MorphDialogMargin
            ).roundToPx()

            val areaL = systemBars.getLeft(this, layoutDirection) + margin
            val areaR = max(areaL, screenW - systemBars.getRight(this, layoutDirection) - margin)
            val areaT = systemBars.getTop(this) + margin
            // Bottom of the placement area backs off for whichever is taller — the nav bar or
            // an open keyboard — so a Center-placed dialog (the create-playlist name field,
            // for instance) re-centers itself above the keyboard instead of being covered by
            // it. WindowInsets.ime is read here (not hoisted) so this recomputes on every
            // keyboard show/hide, the same as it already does for rotation via systemBars.
            val bottomInset = max(systemBars.getBottom(this), imeInsets.getBottom(this))
            val areaB = max(areaT, screenH - bottomInset - margin)
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
                    maxWidth = if (entry.wide) areaW else min(areaW, MorphDialogMaxWidth.roundToPx()),
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
            entry.layoutBounds = Rect(x.toFloat(), y.toFloat(), (x + pw).toFloat(), (y + ph).toFloat())

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
    contentFadeOverride: (() -> Float)?,
    containerColor: Color,
    cornerRadius: Dp,
    elevation: Dp,
    isDarkMenu: Boolean
) {
    val density = LocalDensity.current
    val endRadiusPx = with(density) { cornerRadius.toPx() }
    val close = remember(entry) { requestClose }

    Box(
        modifier = Modifier
            // Light mode and centered dialogs keep the standard elevation behavior.
            .graphicsLayer {
                val p = progress()
                val frame = morphFrame(
                    size,
                    geometry.origin,
                    entry.currentAnchor(),
                    endRadiusPx,
                    p
                )
                shadowElevation = if (isDarkMenu || entry.bare) 0f else {
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
                    entry.currentAnchor(),
                    endRadiusPx,
                    p
                )

                if (isDarkMenu && alpha > 0f) {
                    val glowLayers = 5
                    val maxSpread = with(density) { 10.dp.toPx() }
                    val peakAlpha = 0.03f
                    for (i in glowLayers downTo 1) {
                        val t = i / glowLayers.toFloat()
                        val spread = maxSpread * t
                        val layerAlpha = peakAlpha * (1f - t) * alpha
                        if (layerAlpha <= 0f) continue
                        drawRoundRect(
                            color = Color(0xFFE8E8E8),
                            topLeft = frame.rect.topLeft - Offset(spread, spread),
                            size = Size(
                                frame.rect.width + spread * 2f,
                                frame.rect.height + spread * 2f
                            ),
                            cornerRadius = CornerRadius(frame.radius + spread),
                            alpha = layerAlpha
                        )
                    }
                }

                // Bare entries (the player) draw their own full surface — background, shape,
                // shadow, corners — inside entry.content itself, at their own (often narrower)
                // size. Drawing MorphSurface's own container fill on top of/behind that too
                // would paint a second, larger, differently-colored, non-rotating rectangle
                // behind the real card, which is exactly what made the player look like only
                // its contents were flipping instead of the whole card: the real card *was*
                // rotating the whole time, just dwarfed by this static backdrop rectangle
                // sitting behind it. Skip it here; still clip to frame.rect below so the
                // content can't spill past the popup's growing/shrinking bounds mid-animation.
                if (!entry.bare) {
                    drawRoundRect(
                        color = containerColor,
                        topLeft = frame.rect.topLeft,
                        size = frame.rect.size,
                        cornerRadius = CornerRadius(frame.radius),
                        alpha = alpha
                    )
                }

                if (entry.bare && p >= 1f) {
                    this@drawWithContent.drawContent()
                } else {
                    val reveal = Path().apply {
                        addRoundRect(RoundRect(frame.rect, CornerRadius(frame.radius)))
                    }
                    clipPath(reveal) {
                        this@drawWithContent.drawContent()
                    }
                }

                // Dark-mode anchored menus: draw a soft ambient light halo BEHIND the container,
                // instead of a hard outline. This is the dark-mode analogue of the drop shadow
                // light mode already gets — a "negative shadow": soft light placed around a dark
                // surface so the eye can read its boundary, rather than darkness cast around a
                // light one. Approximated with a handful of expanding, increasingly transparent
                // rounded-rect layers (a manual blur), since Compose has no free box-shadow blur.
                // Drawn where it is (before the container fill below) so the container's own fill
                // covers the inner layers, leaving only the soft-edged sliver outside its bounds.
            }
            // Taps on the popup itself must not fall through to the backdrop (which would close it).
            .pointerInput(Unit) {
                detectTapGestures(onTap = { })
            }
    ) {
        Box(
            modifier = Modifier
                    .graphicsLayer { alpha = contentFadeOverride?.invoke() ?: contentAlpha(progress()) }
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
        text.equals("Song info", ignoreCase = true) -> Icons.Default.Info
        text.equals("Delete", ignoreCase = true) || text.startsWith("Remove from", ignoreCase = true) -> Icons.Default.Delete
        else -> null
    }

    val destructive = text.equals("Unlike", ignoreCase = true) ||
        text.equals("Delete", ignoreCase = true) ||
        text.startsWith("Remove from", ignoreCase = true)

    val resolvedIconTint = if (destructive) {
        iconTint.takeOrElse { textColor.takeOrElse { Color.Red } }
    } else {
        // Pure white / pure black (not the dimmer onSurfaceVariant gray, which read as disabled)
        iconTint.takeOrElse {
            if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else Color.Black
        }
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

/* ---------- Monochrome background while a popup is open ---------- */

/**
 * Wraps the app's main content (screens + player bar — NOT the popups themselves, those are
 * drawn separately by [MorphOverlayHost] on top and stay full color). While [overlay] has any
 * popup open, [content] is drawn desaturated, except for the region(s) named by each open
 * entry's `highlightBounds` (see [MorphOverlayState.show]), which stay in color — e.g. the row
 * whose 3-dot menu is open, or the player bar while the player itself is open.
 *
 * Place this at root coordinates (no offset/padding of its own above it) since highlightBounds
 * are recorded in root coordinates by [MorphAnchor.bounds].
 *
 * Implementation note: content is recorded once by Compose and simply replayed a second time —
 * once desaturated into an offscreen layer, once again clipped to each highlight rect — rather
 * than duplicating the composable tree itself, so state and recomposition are unaffected.
 */
@Composable
fun MonochromeExcept(
    overlay: MorphOverlayState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    MonochromeLayer(
        active = overlay.entries.isNotEmpty(),
        highlights = overlay.highlightBoundsList,
        modifier = modifier.morphTapTracker(overlay),
        content = content
    )
}

/**
 * The actual desaturate-except-[highlights] effect, parameterized so it can wrap either the
 * root screen content ([MonochromeExcept], "active" = any popup open at all) or a single popup
 * inside [MorphOverlayHost] ("active" = something else is stacked on top of *that* popup) —
 * every layer in the stack goes monochrome as soon as anything opens above it, the same rule
 * applied uniformly instead of only ever being applied once at the root.
 */
@Composable
private fun MonochromeLayer(
    active: Boolean,
    highlights: List<Rect>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // Animated 0f..1f rather than an instant on/off switch, in both directions: ramps up to
    // monochrome as soon as [active] flips true, and eases back to full color once it flips
    // back false (entries stay in the overlay's list for the whole time a popup is playing its
    // own closing animation, so callers' `active` naturally stays true until that finishes).
    val amount by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "monochromeAmount"
    )
    val paint = remember { Paint() }
    // The highlight rects vanish the moment the last popup entry is removed, but the fade back
    // to color takes 500ms more. Without this, the highlighted item (e.g. the song whose menu
    // was open) briefly went monochrome with everything else, then faded back in — a visible
    // flash. Keep drawing the last known highlights until the fade has fully finished.
    val lastHighlights = remember { arrayOf<List<Rect>>(emptyList()) }
    SideEffect { if (highlights.isNotEmpty()) lastHighlights[0] = highlights }
    Box(
        modifier = modifier.drawWithContent {
            if (amount <= 0f) {
                drawContent()
                return@drawWithContent
            }
            paint.colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - amount) })
            drawIntoCanvas { canvas ->
                val bounds = Rect(Offset.Zero, size)
                canvas.saveLayer(bounds, paint)
                this@drawWithContent.drawContent()
                canvas.restore()

                for (rect in highlights.ifEmpty { lastHighlights[0] }) {
                    val left = rect.left.coerceIn(bounds.left, bounds.right)
                    val top = rect.top.coerceIn(bounds.top, bounds.bottom)
                    val right = rect.right.coerceIn(bounds.left, bounds.right)
                    val bottom = rect.bottom.coerceIn(bounds.top, bounds.bottom)
                    if (right > left && bottom > top) {
                        canvas.save()
                        canvas.clipRect(left, top, right, bottom)
                        this@drawWithContent.drawContent()
                        canvas.restore()
                    }
                }
            }
        }
    ) {
        content()
    }
}
