package com.pgratz.multiviewpdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pgratz.multiviewpdf.UiState
import com.pgratz.multiviewpdf.ViewerViewModel
import com.pgratz.multiviewpdf.model.CropMargins
import com.pgratz.multiviewpdf.model.PageTransform
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Whole-document crop editor: drag the edges of the box on a sample page, or let "Auto"
 * find the content area across the document. View-only; the PDF isn't modified.
 */
@Composable
fun CropEditor(ui: UiState, vm: ViewerViewModel) {
    var margins by remember { mutableStateOf(ui.layout.crop) }
    var page by remember { mutableIntStateOf(ui.layout.panes[ui.layout.active].page.coerceIn(0, ui.pageCount - 1)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(page) { vm.ensurePage(page) }

    // An in-window overlay rather than a Dialog: dialog windows on a landscape phone with
    // a camera cutout get offset and run off-screen; the main window already has insets.
    BackHandler(onBack = vm::dismissDialog)
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } } // don't let touches reach the panes
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF121212)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Crop all pages", color = Color.White, modifier = Modifier.weight(1f))
                IconButton(onClick = { page = (page - 1).coerceAtLeast(0) }) {
                    Icon(Icons.Filled.ChevronLeft, "Previous sample page", tint = Color.White)
                }
                Text("p. ${page + 1}", color = Color.White)
                IconButton(onClick = { page = (page + 1).coerceAtMost(ui.pageCount - 1) }) {
                    Icon(Icons.Filled.ChevronRight, "Next sample page", tint = Color.White)
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val bounds = ui.bounds[page]
                if (bounds == null) {
                    CircularProgressIndicator()
                    return@BoxWithConstraints
                }
                val w = constraints.maxWidth.toFloat()
                val h = constraints.maxHeight.toFloat()
                val scale = min(w / bounds.width, h / bounds.height) * 0.96f
                val t = remember(bounds, scale) { PageTransform(bounds, 0, scale) }
                val imgW = t.contentWidth
                val imgH = t.contentHeight
                val origin = Offset((w - imgW) / 2, (h - imgH) / 2)
                var image by remember { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(page, scale) {
                    image = vm.render(page, t, 0, 0, imgW.toInt(), imgH.toInt())?.asImageBitmap()
                }
                val grab = with(LocalDensity.current) { 36.dp.toPx() }
                val current by androidx.compose.runtime.rememberUpdatedState(margins)

                Canvas(
                    Modifier.fillMaxSize().pointerInput(imgW, imgH) {
                        var edges = BooleanArray(4) // left, top, right, bottom
                        detectDragGestures(
                            onDragStart = { p ->
                                val m = current
                                val l = origin.x + m.left * imgW
                                val r = origin.x + (1 - m.right) * imgW
                                val tp = origin.y + m.top * imgH
                                val b = origin.y + (1 - m.bottom) * imgH
                                val inY = p.y in (tp - grab)..(b + grab)
                                val inX = p.x in (l - grab)..(r + grab)
                                edges = booleanArrayOf(
                                    inY && abs(p.x - l) < grab,
                                    inX && abs(p.y - tp) < grab,
                                    inY && abs(p.x - r) < grab,
                                    inX && abs(p.y - b) < grab,
                                )
                            },
                        ) { change, d ->
                            change.consume()
                            val m = current
                            margins = m.copy(
                                left = if (edges[0]) m.left + d.x / imgW else m.left,
                                top = if (edges[1]) m.top + d.y / imgH else m.top,
                                right = if (edges[2]) m.right - d.x / imgW else m.right,
                                bottom = if (edges[3]) m.bottom - d.y / imgH else m.bottom,
                            ).clamped()
                        }
                    }
                ) {
                    drawRect(Color.White, origin, Size(imgW, imgH))
                    image?.let {
                        drawImage(
                            it,
                            dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                            dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
                        )
                    }
                    val l = origin.x + margins.left * imgW
                    val r = origin.x + (1 - margins.right) * imgW
                    val tp = origin.y + margins.top * imgH
                    val b = origin.y + (1 - margins.bottom) * imgH
                    val dim = Color(0x99000000)
                    drawRect(dim, origin, Size(imgW, tp - origin.y))
                    drawRect(dim, Offset(origin.x, b), Size(imgW, origin.y + imgH - b))
                    drawRect(dim, Offset(origin.x, tp), Size(l - origin.x, b - tp))
                    drawRect(dim, Offset(r, tp), Size(origin.x + imgW - r, b - tp))
                    val accent = Color(0xFFFFB74D)
                    drawRect(accent, Offset(l, tp), Size(r - l, b - tp), style = Stroke(3f))
                    val hs = 10.dp.toPx()
                    for (c in listOf(
                        Offset((l + r) / 2, tp), Offset((l + r) / 2, b), Offset(l, (tp + b) / 2), Offset(r, (tp + b) / 2),
                        Offset(l, tp), Offset(r, tp), Offset(l, b), Offset(r, b),
                    )) drawCircle(accent, hs / 2, c)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        vm.autoCrop()?.let { margins = it }
                        busy = false
                    }
                }) { Text("Auto") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { margins = CropMargins.NONE }) { Text("Reset") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::dismissDialog) { Text("Cancel") }
                Button(onClick = { vm.setCrop(margins) }) { Text("Apply") }
            }
        }
    }
}
