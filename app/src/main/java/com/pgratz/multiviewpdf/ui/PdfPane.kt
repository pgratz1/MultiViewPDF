package com.pgratz.multiviewpdf.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.multiviewpdf.UiState
import com.pgratz.multiviewpdf.ViewerViewModel
import com.pgratz.multiviewpdf.model.AnnotType
import com.pgratz.multiviewpdf.model.FitMode
import com.pgratz.multiviewpdf.model.HighlightColors
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageTransform
import com.pgratz.multiviewpdf.model.Selection
import com.pgratz.multiviewpdf.model.Tool
import com.pgratz.multiviewpdf.model.ViewMode
import com.pgratz.multiviewpdf.model.hitTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** "invert(1) hue-rotate(180deg)": dark pages that keep highlight hues recognisable. */
private val InvertFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.430f, -0.144f, 0f, 255f,
            -0.426f, -0.430f, -0.144f, 0f, 255f,
            -0.426f, -1.430f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
)

private const val MAX_BASE_PIXELS = 16_000_000f

/** Width of the outer page-edge tap zones, as a fraction of the page's on-screen width. */
private const val EDGE_ZONE = 0.15f

/** A rendered bitmap of a page region, with what it was rendered for. */
private class Rendered(
    val image: ImageBitmap,
    val page: Int,
    val visible: PRect,
    val rotation: Int,
    val scale: Float,
    val x0: Int,
    val y0: Int,
) {
    fun matches(page: Int, t: PageTransform) =
        this.page == page && visible == t.visible && rotation == t.rotation
}

/** Durations of the view glide (zoom / scroll jumps) and the page-turn slide. */
private const val GLIDE_MS = 160
private const val TURN_MS = 180

/** How far a turning page slides, as a fraction of the pane size. */
private const val TURN_SLIDE = 0.08f

/** Frame-by-frame drawing parameters from [PaneAnimator.frame]. */
private class AnimFrame(
    /** Glide layer for the current page: drawn at shift + scale·x. */
    val scale: Float,
    val shift: Offset,
    /** Page-turn slide of the incoming page, and its alpha; null alpha = don't draw it yet. */
    val inSlide: Offset,
    val inAlpha: Float?,
    val outgoing: Outgoing?,
    val outSlide: Offset,
    val outAlpha: Float,
)

/** The previous page's bitmap and where it was on screen when the page turned. */
private class Outgoing(val image: Rendered?, val k: Float, val offset: Offset, val size: Size)

/**
 * Short transitions, applied while drawing. Discrete view jumps (double-tap, keys, wheel,
 * edge taps, fit) glide from what was on screen to the new view; page turns slide the
 * old page out and the new one in from the direction of travel, once the new page has
 * rendered. Drags and pinches follow the finger and are never animated.
 */
private class PaneAnimator(private val scope: CoroutineScope) {
    private val glide = Animatable(1f)
    private var glideJob: Job? = null
    private var glidePending = false
    private var fromScale = 1f
    private var fromShift = Offset.Zero

    private val turn = Animatable(1f)
    private var turnJob: Job? = null
    private var turnPending = false
    private var turning = false
    private var waiting = false
    private var turnDir = 0
    private var turnVertical = false
    private var outgoing: Outgoing? = null

    // What the last frame was drawn for.
    private var page = -1
    private var k = 0f
    private var o = Offset.Zero
    private var size = Size.Zero
    private var shape: Any? = null

    // Always read the Animatable so the drawing that called us is redrawn as it changes.
    private fun glideP(): Float = glide.value.let { if (glidePending) 0f else it }
    private fun layerScale() = fromScale + (1f - fromScale) * glideP()
    private fun layerShift() = fromShift * (1f - glideP())

    private fun snapGlide() {
        glideJob?.cancel()
        glidePending = false
        fromScale = 1f
        fromShift = Offset.Zero
    }

    private fun startTurn() {
        if (!waiting) return
        waiting = false
        turnPending = true
        turnJob?.cancel()
        turnJob = scope.launch {
            turn.snapTo(0f)
            turnPending = false
            turn.animateTo(1f, tween(TURN_MS, easing = FastOutSlowInEasing))
            outgoing = null
            turning = false
        }
    }

    /**
     * Called at the start of each draw with the view about to be drawn. [shapeKey] covers
     * things a glide can't express (rotation, crop): when it changes the view snaps.
     */
    fun frame(
        page: Int, t: PageTransform, o: Offset, view: Size, base: Rendered?,
        gesture: Boolean, vertical: Boolean,
    ): AnimFrame {
        val k = t.scale
        val size = Size(t.contentWidth, t.contentHeight)
        val shapeKey = t.visible to t.rotation
        if (page != this.page) {
            if (this.page >= 0) {
                // Freeze the old page where it is on screen; it slides out once the new one is ready.
                val r = layerScale()
                outgoing = Outgoing(
                    base?.takeIf { it.page == this.page }, this.k * r, layerShift() + this.o * r, this.size * r,
                )
                turnDir = if (page > this.page) 1 else -1
                turnVertical = vertical
                turning = true
                waiting = true
                turnJob?.cancel()
                turnJob = scope.launch {
                    delay(400) // don't hold the old page forever if rendering is slow
                    startTurn()
                }
            }
            snapGlide()
        } else if (turning || gesture || shapeKey != shape || this.k <= 0f) {
            snapGlide()
        } else if (k != this.k || o != this.o) {
            // Start from exactly what's on screen now (mid-glide included), easing to the new view.
            val r = layerScale()
            val visK = this.k * r
            val visO = layerShift() + this.o * r
            val s = visK / k
            val sh = visO - o * s
            if (abs(s - 1f) < 0.002f && sh.getDistance() < 1f) snapGlide() else {
                fromScale = s
                fromShift = sh
                glidePending = true
                glideJob?.cancel()
                glideJob = scope.launch {
                    glide.snapTo(0f)
                    glidePending = false
                    glide.animateTo(1f, tween(GLIDE_MS, easing = FastOutSlowInEasing))
                }
            }
        }
        this.page = page
        this.k = k
        this.o = o
        this.size = size
        this.shape = shapeKey

        if (waiting && base?.matches(page, t) == true) startTurn()
        val tp = turn.value.let { if (turnPending || waiting) 0f else it }
        val unit = if (turnVertical) Offset(0f, view.height * TURN_SLIDE) else Offset(view.width * TURN_SLIDE, 0f)
        val out = outgoing.takeIf { turning }
        return AnimFrame(
            scale = layerScale(),
            shift = layerShift(),
            inSlide = if (out != null) unit * (turnDir * (1f - tp)) else Offset.Zero,
            inAlpha = if (waiting) null else if (out != null) tp else 1f,
            outgoing = out,
            outSlide = unit * (-turnDir * tp),
            outAlpha = 1f - tp,
        )
    }
}

private enum class DragMode { PAN, SELECT, HANDLE_A, HANDLE_B, PINCH }

/**
 * Per-pane viewport: where the page content sits in the pane and conversions between
 * pane pixels and page space. [transform] and the view size are refreshed every
 * composition so gesture code always sees current geometry.
 */
@Stable
private class PaneController(val index: Int, val vm: ViewerViewModel, scope: CoroutineScope) {
    val anim = PaneAnimator(scope)
    var pan by mutableStateOf(Offset.Zero)
    var transform: PageTransform? = null
    var viewW = 0f
    var viewH = 0f
    var wheelAccum = 0f
    var enterFromBottom = false
    var enterFromRight = false

    /** A drag or pinch is in progress (the view follows the finger, no animation). */
    var gestureActive = false

    /** The next page turn came from vertical scrolling, so it slides vertically. */
    var turnVertical = false

    /** When and where the last plain tap ended, for double-tap detection. */
    var lastTapUp = 0L
    var lastTapPos = Offset.Zero

    /** Where a page narrower than the pane sits: 0 = left edge, 0.5 = centred, 1 = right edge. */
    var alignX = 0.5f

    val offset: Offset
        get() {
            val t = transform ?: return Offset.Zero
            return Offset(
                clampAxis(pan.x, t.contentWidth, viewW, alignX),
                clampAxis(pan.y, t.contentHeight, viewH, 0.5f),
            )
        }

    private fun clampAxis(o: Float, content: Float, view: Float, align: Float) =
        if (content <= view) (view - content) * align else o.coerceIn(view - content, 0f)

    fun panBy(d: Offset) {
        pan = offset + d
        pan = offset // store the clamped value so over-scrolling doesn't accumulate
    }

    fun toPage(screen: Offset): PPoint? {
        val t = transform ?: return null
        val o = offset
        return t.contentToPage(PPoint(screen.x - o.x, screen.y - o.y))
    }

    fun toScreen(p: PPoint): Offset {
        val t = transform ?: return Offset.Zero
        val c = t.pageToContent(p)
        val o = offset
        return Offset(c.x + o.x, c.y + o.y)
    }

    /** Page-space tolerance equivalent to [px] screen pixels. */
    fun slop(px: Float): Float = transform?.let { px / it.scale } ?: 0f

    fun zoomAround(focus: Offset, factor: Float) {
        val cur = vm.state.value.layout.panes[index].zoom
        vm.setZoom(index, cur * factor)
        val ratio = vm.state.value.layout.panes[index].zoom / cur
        val o = offset
        // Keep the point under the fingers/cursor fixed; clamped against the new size on draw.
        pan = Offset(focus.x - (focus.x - o.x) * ratio, focus.y - (focus.y - o.y) * ratio)
    }

    /**
     * Wheel or arrow-key scrolling: scrolls within a zoomed page, then turns the page once
     * the edge is reached ([notches] of push past the edge).
     */
    fun scrollOrTurn(dy: Float, notches: Float) {
        val t = transform ?: return
        val o = offset
        val ch = t.contentHeight
        val atTop = ch <= viewH || o.y >= -0.5f
        val atBottom = ch <= viewH || o.y <= viewH - ch + 0.5f
        if ((dy > 0 && !atBottom) || (dy < 0 && !atTop)) {
            wheelAccum = 0f
            panBy(Offset(0f, -dy))
            return
        }
        wheelAccum += dy
        if (abs(wheelAccum) >= notches * viewH * 0.04f) {
            val dir = if (wheelAccum > 0) 1 else -1
            wheelAccum = 0f
            enterFromBottom = dir < 0
            turnVertical = true
            vm.turnPane(index, dir)
        }
    }

    /**
     * -1 / +1 if [x] is in the left / right outer tap zone of the page, else 0. In two-page
     * mode only the screen's outer edges count: left of the left page, right of the right.
     */
    fun edgeZone(x: Float, dual: Boolean): Int {
        val t = transform ?: return 0
        val o = offset
        val left = max(o.x, 0f)
        val right = min(o.x + t.contentWidth, viewW)
        val band = (right - left) * EDGE_ZONE
        return when {
            (!dual || index == 0) && x < left + band -> -1
            (!dual || index == 1) && x > right - band -> 1
            else -> 0
        }
    }

    /** Edge tap: scroll a zoomed page sideways by most of a screen, then turn the page at its edge. */
    fun stepOrTurn(dir: Int) {
        val t = transform ?: return
        val o = offset
        val cw = t.contentWidth
        val canScroll = cw > viewW + 1 && (if (dir > 0) o.x > viewW - cw + 0.5f else o.x < -0.5f)
        if (canScroll) {
            panBy(Offset(-dir * viewW * 0.85f, 0f))
            return
        }
        enterFromRight = dir < 0
        vm.turnPane(index, dir)
    }

    /**
     * Double-tap zoom: makes the page-space column [col] fill the pane across (its height,
     * if the pane is rotated sideways), keeping [focus]'s position along the column.
     */
    fun zoomToColumn(col: PRect, focus: Offset) {
        val t = transform ?: return
        val fp = toPage(focus) ?: return
        val pad = col.width * 0.03f
        val a = toScreen(PPoint(col.x0 - pad, fp.y))
        val b = toScreen(PPoint(col.x1 + pad, fp.y))
        val sideways = t.rotation % 180 != 0
        val span = if (sideways) abs(b.y - a.y) else abs(b.x - a.x)
        val factor = if (span > 0f) (if (sideways) viewH else viewW) / span else 0f
        if (factor < 1.1f) {
            zoomAround(focus, 2f) // the column already fills the pane
            return
        }
        val cur = vm.state.value.layout.panes[index].zoom
        vm.setZoom(index, cur * factor)
        val ratio = vm.state.value.layout.panes[index].zoom / cur
        val o = offset
        val mid = (a + b) / 2f
        // Clamped against the new content size on draw, like zoomAround.
        pan = if (sideways) {
            Offset(focus.x - (focus.x - o.x) * ratio, viewH / 2 - (mid.y - o.y) * ratio)
        } else {
            Offset(viewW / 2 - (mid.x - o.x) * ratio, focus.y - (focus.y - o.y) * ratio)
        }
    }

    fun handleCenter(sel: Selection, which: DragMode, stemPx: Float): Offset {
        val p = toScreen(if (which == DragMode.HANDLE_A) sel.a else sel.b)
        return if (sel.rectMode) p else Offset(p.x, p.y + stemPx)
    }
}

@Composable
fun PdfPane(
    index: Int,
    ui: UiState,
    vm: ViewerViewModel,
    modifier: Modifier = Modifier,
) {
    val layout = ui.layout
    val pane = layout.panes[index]
    val page = pane.page
    val inDoc = page in 0 until ui.pageCount
    val density = LocalDensity.current
    val invert = ui.prefs.invert
    val isActive = layout.mode == ViewMode.DUAL && layout.active == index && !ui.fullscreen

    LaunchedEffect(page) {
        vm.ensurePage(page)
        vm.ensurePage(page + 1)
        vm.ensurePage(page - 1)
    }

    val bounds = if (inDoc) ui.bounds[page] else null
    val visible = bounds?.let { if (layout.cropEnabled) layout.crop.applyTo(it) else it }
    val version = ui.versions[page] ?: 0
    val scope = rememberCoroutineScope()
    val ctl = remember(index, vm) { PaneController(index, vm, scope) }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(if (invert) Color.Black else Color(0xFF1A1A1A))
            .then(if (isActive) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val fitScale = visible?.let {
            val (uw, uh) = PageTransform.unscaledSize(it, pane.rotation)
            if (pane.fit == FitMode.PAGE) min(viewW / uw, viewH / uh) else viewW / uw
        }
        val transform = if (visible != null && fitScale != null) {
            PageTransform(visible, pane.rotation, fitScale * pane.zoom)
        } else null
        ctl.transform = transform
        ctl.viewW = viewW
        ctl.viewH = viewH
        // Two-page mode: justify both pages toward the centre of the screen, like a book spread.
        ctl.alignX = if (layout.mode == ViewMode.DUAL) (if (index == 0) 1f else 0f) else 0.5f

        LaunchedEffect(page, pane.rotation, pane.fit) {
            ctl.pan = Offset(if (ctl.enterFromRight) -1e7f else 0f, if (ctl.enterFromBottom) -1e7f else 0f)
            ctl.enterFromBottom = false
            ctl.enterFromRight = false
            ctl.wheelAccum = 0f
        }

        LaunchedEffect(Unit) {
            vm.paneEvents.collect { ev ->
                if (ev.pane == index && ev is com.pgratz.multiviewpdf.PaneEvent.Scroll) {
                    ctl.scrollOrTurn(ev.fraction * ctl.viewH, notches = 0f)
                }
            }
        }

        // --- Rendering: a base bitmap at fit scale, plus a sharp patch of the visible
        // region once zoomed past it.
        var base by remember { mutableStateOf<Rendered?>(null) }
        var patch by remember { mutableStateOf<Rendered?>(null) }

        LaunchedEffect(page, version, visible, pane.rotation, fitScale, inDoc) {
            if (!inDoc || visible == null || fitScale == null || fitScale <= 0f) return@LaunchedEffect
            val (uw, uh) = PageTransform.unscaledSize(visible, pane.rotation)
            val cap = sqrt(MAX_BASE_PIXELS / (uw * uh * fitScale * fitScale)).coerceAtMost(1f)
            val t = PageTransform(visible, pane.rotation, fitScale * cap)
            val bmp = vm.render(page, t, 0, 0, ceil(t.contentWidth).toInt(), ceil(t.contentHeight).toInt())
            if (bmp != null) base = Rendered(bmp.asImageBitmap(), page, visible, t.rotation, t.scale, 0, 0)
        }

        val offset = ctl.offset
        val needPatch = transform != null && base != null && transform.scale > (base?.scale ?: 0f) * 1.15f
        LaunchedEffect(page, version, transform?.scale, transform?.visible, transform?.rotation, offset, needPatch) {
            if (!needPatch || transform == null) {
                patch = null
                return@LaunchedEffect
            }
            delay(150) // wait for pinch/pan to settle
            val x0 = max(0f, -offset.x).toInt()
            val y0 = max(0f, -offset.y).toInt()
            val x1 = min(transform.contentWidth, viewW - offset.x).toInt()
            val y1 = min(transform.contentHeight, viewH - offset.y).toInt()
            if (x1 <= x0 || y1 <= y0) return@LaunchedEffect
            val bmp = vm.render(page, transform, x0, y0, x1 - x0, y1 - y0) ?: return@LaunchedEffect
            patch = Rendered(bmp.asImageBitmap(), page, transform.visible, transform.rotation, transform.scale, x0, y0)
        }

        val selection = ui.selection?.takeIf { it.pane == index && it.page == page }
        val stemPx = with(density) { 22.dp.toPx() }
        val handleR = with(density) { 9.dp.toPx() }
        val handleHit = with(density) { 30.dp.toPx() }
        val tapSlop = with(density) { 14.dp.toPx() }
        val doubleTapSlop = with(density) { 40.dp.toPx() }
        val selColor = Color(0x553D8BFF)
        var hover by remember { mutableStateOf<Offset?>(null) }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(index) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        vm.setActive(index)
                        val st = vm.state.value
                        val pageNow = st.layout.panes[index].page
                        val longPress = { at: Offset ->
                            vm.onLongPress(index, pageNow, at, ctl.toPage(at) ?: PPoint(0f, 0f), ctl.slop(tapSlop))
                        }
                        if (currentEvent.buttons.isSecondaryPressed) {
                            longPress(down.position)
                            down.consume()
                            do {
                                val ev = awaitPointerEvent()
                            } while (ev.changes.any { it.pressed })
                            return@awaitEachGesture
                        }

                        val sel = st.selection?.takeIf { it.pane == index && it.confirm }
                        val handle = sel?.let { s ->
                            listOf(DragMode.HANDLE_A, DragMode.HANDLE_B).minByOrNull {
                                (ctl.handleCenter(s, it, stemPx) - down.position).getDistance()
                            }?.takeIf { (ctl.handleCenter(s, it, stemPx) - down.position).getDistance() < handleHit }
                        }

                        // Phase 1: tap, long-press, drag or pinch?
                        var outcome = "long"
                        if (handle != null) outcome = "drag" else {
                            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.count { it.pressed } > 1) {
                                        outcome = "pinch"; break
                                    }
                                    val c = ev.changes.firstOrNull { it.id == down.id }
                                    if (c == null || !c.pressed) {
                                        outcome = "tap"; break
                                    }
                                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        outcome = "drag"; break
                                    }
                                }
                            }
                        }

                        when (outcome) {
                            "tap" -> {
                                val p = ctl.toPage(down.position)
                                val used = p != null && vm.onTap(index, pageNow, down.position, p, ctl.slop(tapSlop))
                                val isDouble = down.uptimeMillis - ctl.lastTapUp < viewConfiguration.doubleTapTimeoutMillis &&
                                    (down.position - ctl.lastTapPos).getDistance() < doubleTapSlop
                                val edge = ctl.edgeZone(down.position.x, st.layout.mode == ViewMode.DUAL)
                                ctl.lastTapUp = 0L
                                when {
                                    used -> {}
                                    isDouble -> {
                                        // Double tap: zoom to the text column there; again to zoom back out.
                                        val zoom = st.layout.panes[index].zoom
                                        val visibleNow = ctl.transform?.visible
                                        if (zoom > 1.05f) ctl.zoomAround(down.position, 1f / zoom)
                                        else if (p != null && visibleNow != null) scope.launch {
                                            val col = vm.columnAt(pageNow, visibleNow, p)
                                            if (col != null) ctl.zoomToColumn(col, down.position)
                                            else ctl.zoomAround(down.position, 2f)
                                        }
                                    }
                                    // Tapping the outer page edges scrolls sideways / turns pages, like an e-reader.
                                    edge != 0 -> ctl.stepOrTurn(edge)
                                    else -> {
                                        ctl.lastTapUp = currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis
                                        ctl.lastTapPos = down.position
                                    }
                                }
                                return@awaitEachGesture
                            }
                            "long" -> {
                                longPress(down.position)
                                do {
                                    val ev = awaitPointerEvent()
                                    ev.changes.forEach { it.consume() }
                                } while (ev.changes.any { it.pressed })
                                return@awaitEachGesture
                            }
                        }

                        var mode = when {
                            handle != null -> handle
                            outcome == "pinch" -> DragMode.PINCH
                            st.tool == Tool.HIGHLIGHT -> DragMode.SELECT
                            else -> DragMode.PAN
                        }
                        if (mode == DragMode.SELECT) {
                            ctl.toPage(down.position)?.let { vm.beginHighlightDrag(index, pageNow, it) }
                        }
                        val grab = if (sel != null && handle != null) {
                            ctl.toScreen(if (handle == DragMode.HANDLE_A) sel.a else sel.b) - down.position
                        } else Offset.Zero
                        val startOffset = ctl.offset
                        var total = Offset.Zero

                        ctl.gestureActive = true
                        try { while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (mode == DragMode.SELECT) vm.cancelSelection()
                                mode = DragMode.PINCH
                                ctl.panBy(ev.calculatePan())
                                val z = ev.calculateZoom()
                                if (z != 1f) ctl.zoomAround(ev.calculateCentroid(), z)
                            } else {
                                val c = pressed.first()
                                val d = c.positionChange()
                                when (mode) {
                                    DragMode.PAN, DragMode.PINCH -> {
                                        ctl.panBy(d)
                                        total += d
                                    }
                                    DragMode.SELECT -> ctl.toPage(c.position)?.let { vm.moveSelection(null, it) }
                                    DragMode.HANDLE_A -> ctl.toPage(c.position + grab)?.let { vm.moveSelection(it, null) }
                                    DragMode.HANDLE_B -> ctl.toPage(c.position + grab)?.let { vm.moveSelection(null, it) }
                                }
                            }
                            ev.changes.forEach { it.consume() }
                        } } finally {
                            ctl.gestureActive = false
                        }

                        when (mode) {
                            DragMode.SELECT -> vm.endHighlightDrag()
                            DragMode.PAN -> {
                                // Swipe to turn pages when the page can't scroll further that way.
                                val t = ctl.transform ?: return@awaitEachGesture
                                val cw = t.contentWidth
                                val ch = t.contentHeight
                                if (abs(total.x) > ctl.viewW * 0.15f && abs(total.x) > 2 * abs(total.y)) {
                                    val dir = if (total.x < 0) 1 else -1
                                    val atEdge = cw <= ctl.viewW + 1 ||
                                        (dir > 0 && startOffset.x <= ctl.viewW - cw + 1) ||
                                        (dir < 0 && startOffset.x >= -1)
                                    if (atEdge) vm.turnPane(index, dir)
                                } else if (ch <= ctl.viewH + 1 && abs(total.y) > ctl.viewH * 0.15f && abs(total.y) > 2 * abs(total.x)) {
                                    ctl.turnVertical = true
                                    vm.turnPane(index, if (total.y < 0) 1 else -1)
                                }
                            }
                            else -> {}
                        }
                    }
                }
                .pointerInput(index) {
                    // Mouse wheel and hover (desktop mode).
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: continue
                            when (ev.type) {
                                PointerEventType.Scroll -> {
                                    val d = c.scrollDelta
                                    if (ev.keyboardModifiers.isCtrlPressed) {
                                        if (d.y != 0f) ctl.zoomAround(c.position, if (d.y < 0) 1.1f else 1 / 1.1f)
                                    } else {
                                        val step = 64.dp.toPx()
                                        if (d.y != 0f) ctl.scrollOrTurn(d.y * step, notches = 2f)
                                        if (d.x != 0f) ctl.panBy(Offset(-d.x * step, 0f))
                                    }
                                    c.consume()
                                }
                                PointerEventType.Move ->
                                    hover = if (c.type == PointerType.Mouse && !c.pressed) c.position else null
                                PointerEventType.Exit -> hover = null
                            }
                        }
                    }
                }
        ) {
            val t = transform ?: return@Canvas
            val o = ctl.offset
            val f = ctl.anim.frame(page, t, o, size, base, ctl.gestureActive, ctl.turnVertical)
            ctl.turnVertical = false
            val paper = if (invert) Color.Black else Color.White
            val filter = if (invert) InvertFilter else null
            f.outgoing?.let { out ->
                translate(f.outSlide.x, f.outSlide.y) {
                    drawRect(paper, topLeft = out.offset, size = out.size, alpha = f.outAlpha)
                    out.image?.let { drawRendered(it, out.offset, out.k, filter, f.outAlpha) }
                }
            }
            val inAlpha = f.inAlpha ?: return@Canvas
            withTransform({
                translate(f.inSlide.x + f.shift.x, f.inSlide.y + f.shift.y)
                scale(f.scale, f.scale, pivot = Offset.Zero)
            }) {
                drawRect(paper, topLeft = o, size = Size(t.contentWidth, t.contentHeight), alpha = inAlpha)
                for (r in listOfNotNull(base, patch)) {
                    if (r.matches(page, t)) drawRendered(r, o, t.scale, filter, inAlpha)
                }
                if (selection != null) {
                    for (q in selection.quads) {
                        val path = Path().apply {
                            val ul = ctl.toScreen(q.ul); moveTo(ul.x, ul.y)
                            val ur = ctl.toScreen(q.ur); lineTo(ur.x, ur.y)
                            val lr = ctl.toScreen(q.lr); lineTo(lr.x, lr.y)
                            val ll = ctl.toScreen(q.ll); lineTo(ll.x, ll.y)
                            close()
                        }
                        drawPath(path, selColor)
                    }
                    if (selection.rectMode) {
                        val a = ctl.toScreen(selection.a)
                        val b = ctl.toScreen(selection.b)
                        drawRect(
                            Color(0xFF3D8BFF),
                            topLeft = Offset(min(a.x, b.x), min(a.y, b.y)),
                            size = Size(abs(a.x - b.x), abs(a.y - b.y)),
                            style = Stroke(width = 2f),
                        )
                    }
                    if (selection.confirm) {
                        for (h in listOf(DragMode.HANDLE_A, DragMode.HANDLE_B)) {
                            val anchor = ctl.toScreen(if (h == DragMode.HANDLE_A) selection.a else selection.b)
                            val center = ctl.handleCenter(selection, h, stemPx)
                            drawLine(Color(0xFF3D8BFF), anchor, center, strokeWidth = 4f)
                            drawCircle(Color(0xFF3D8BFF), handleR, center)
                            drawCircle(Color.White, handleR * 0.45f, center)
                        }
                    }
                }
            }
        }

        if (!inDoc) {
            Text(
                if (page >= ui.pageCount && ui.pageCount > 0) "End of document" else "",
                color = Color.Gray,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Hover preview of a note's / highlight comment's text.
        val hoverPos = hover
        if (hoverPos != null && transform != null && ui.menu == null) {
            val p = ctl.toPage(hoverPos)
            val annot = p?.let { ui.annots[page]?.hitTest(it, ctl.slop(tapSlop)) }
            if (annot != null && annot.contents.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFF8C4),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .offset { IntOffset(hoverPos.x.roundToInt() + 16, hoverPos.y.roundToInt() + 16) }
                        .widthIn(max = 320.dp),
                ) {
                    Text(annot.contents, color = Color.Black, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                }
            }
        }

        if (selection != null && selection.confirm && transform != null) {
            val a = ctl.toScreen(selection.a)
            val b = ctl.toScreen(selection.b)
            val barY = (max(a.y, b.y) + stemPx * 2.2f).coerceAtMost(viewH - with(density) { 110.dp.toPx() })
            val barX = (min(a.x, b.x)).coerceIn(0f, max(0f, viewW - with(density) { 300.dp.toPx() }))
            SelectionBar(
                modifier = Modifier.offset { IntOffset(barX.roundToInt(), barY.roundToInt()) },
                onCommit = { vm.commitSelection(it) },
                onCancel = vm::cancelSelection,
                current = ui.prefs.highlightColor,
            )
        }

        if (!ui.fullscreen) PaneFooter(index, ui, vm, Modifier.align(Alignment.BottomCenter))

        val menu = ui.menu
        if (menu != null && menu.pane == index) {
            Box(Modifier.offset { IntOffset(menu.at.x.roundToInt(), menu.at.y.roundToInt()) }) {
                PaneMenu(index, ui, vm)
            }
        }
    }
}

/** Draws [r] for a page whose content origin is at [origin] and whose current scale is [scale]. */
private fun DrawScope.drawRendered(r: Rendered, origin: Offset, scale: Float, filter: ColorFilter?, alpha: Float) {
    val k = scale / r.scale
    drawImage(
        r.image,
        dstOffset = IntOffset((origin.x + r.x0 * k).roundToInt(), (origin.y + r.y0 * k).roundToInt()),
        dstSize = IntSize((r.image.width * k).roundToInt(), (r.image.height * k).roundToInt()),
        alpha = alpha,
        colorFilter = filter,
        filterQuality = FilterQuality.Medium,
    )
}

@Composable
private fun SelectionBar(modifier: Modifier, current: Int, onCommit: (Int?) -> Unit, onCancel: () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Tip("Highlight (Enter)") {
                IconButton(onClick = { onCommit(null) }) { Icon(Icons.Filled.Check, "Highlight") }
            }
            ColorDots(current = current, onPick = { onCommit(it) })
            Tip("Cancel (Esc)") {
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
            }
        }
    }
}

@Composable
fun ColorDots(current: Int?, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (c in HighlightColors.palette) {
            Box(
                Modifier
                    .padding(4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .then(
                        if (c == current) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        else Modifier
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}

@Composable
private fun PaneFooter(index: Int, ui: UiState, vm: ViewerViewModel, modifier: Modifier) {
    val layout = ui.layout
    val pane = layout.panes[index]
    val dual = layout.mode == ViewMode.DUAL
    Surface(
        modifier = modifier.padding(bottom = 6.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC202020),
        contentColor = Color.White,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(Icons.Filled.ChevronLeft, "Previous page (←)") { vm.turnPane(index, -1) }
            Tip("Go to page… (G)") {
                Text(
                    if (pane.page < ui.pageCount) "${pane.page + 1} / ${ui.pageCount}" else "– / ${ui.pageCount}",
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .widthIn(min = 64.dp)
                        .clickable { vm.openDialog(com.pgratz.multiviewpdf.Dialog.GoToPage(index)) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            SmallIcon(Icons.Filled.ChevronRight, "Next page (→)") { vm.turnPane(index, +1) }
            if (dual) {
                SmallIcon(
                    if (pane.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    if (pane.locked) "Unlock pane (L)" else "Lock pane (L)",
                    tint = if (pane.locked) Color(0xFFFFB74D) else Color.White,
                ) { vm.toggleLock(index) }
            }
            SmallIcon(Icons.Filled.RotateRight, "Rotate pane (R)") { vm.rotate(index, 90) }
            SmallIcon(
                if (pane.fit == FitMode.PAGE) Icons.Filled.ZoomOutMap else Icons.Filled.FitScreen,
                if (pane.fit == FitMode.PAGE) "Fit width (F)" else "Fit page (F)",
            ) { vm.toggleFit(index) }
        }
    }
}

@Composable
private fun SmallIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Tip(label) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PaneMenu(index: Int, ui: UiState, vm: ViewerViewModel) {
    val menu = ui.menu ?: return
    val pane = ui.layout.panes[index]
    val dual = ui.layout.mode == ViewMode.DUAL
    val validPage = menu.page in 0 until ui.pageCount
    DropdownMenu(expanded = true, onDismissRequest = vm::dismissMenu) {
        val annot = menu.annot
        if (annot != null) {
            val label = when {
                annot.type == AnnotType.NOTE -> "Edit note"
                annot.contents.isBlank() -> "Add comment"
                else -> "Edit comment"
            }
            DropdownMenuItem(text = { Text(label) }, onClick = { vm.editAnnotText(menu.page, annot) })
            if (annot.type != AnnotType.OTHER) {
                Box(Modifier.padding(horizontal = 8.dp)) {
                    ColorDots(current = annot.color) { vm.recolorAnnot(menu.page, annot.index, it) }
                }
            }
            DropdownMenuItem(text = { Text("Delete") }, onClick = { vm.deleteAnnot(menu.page, annot.index) })
            HorizontalDivider()
        } else if (validPage) {
            DropdownMenuItem(text = { Text("Add note here") }, onClick = { vm.newNote(menu.page, menu.point) })
            DropdownMenuItem(
                text = { Text("Start highlight here") },
                onClick = { vm.startHighlightAt(index, menu.page, menu.point) },
            )
            HorizontalDivider()
        }
        if (dual) {
            DropdownMenuItem(
                text = { Text(if (pane.locked) "Unlock this pane" else "Lock this pane") },
                onClick = { vm.dismissMenu(); vm.toggleLock(index) },
            )
        }
        DropdownMenuItem(text = { Text("Rotate pane ↻") }, onClick = { vm.dismissMenu(); vm.rotate(index, 90) })
        DropdownMenuItem(text = { Text("Rotate pane ↺") }, onClick = { vm.dismissMenu(); vm.rotate(index, -90) })
        if (validPage) {
            DropdownMenuItem(
                text = { Text("Pin this page in the other pane") },
                onClick = { vm.dismissMenu(); vm.sendToOther(index) },
            )
        }
        DropdownMenuItem(
            text = { Text(if (dual) "Single page" else "Two pages") },
            onClick = { vm.dismissMenu(); vm.toggleMode() },
        )
        DropdownMenuItem(
            text = { Text("Go to page…") },
            onClick = { vm.openDialog(com.pgratz.multiviewpdf.Dialog.GoToPage(index)) },
        )
        if (ui.fullscreen) {
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Exit fullscreen") }, onClick = { vm.setFullscreen(false) })
        }
    }
}
