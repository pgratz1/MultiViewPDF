package com.pgratz.multiviewpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.multiviewpdf.Dialog
import com.pgratz.multiviewpdf.TtsState
import com.pgratz.multiviewpdf.TtsStatus
import com.pgratz.multiviewpdf.UiState
import com.pgratz.multiviewpdf.ViewerViewModel
import com.pgratz.multiviewpdf.model.Tool
import com.pgratz.multiviewpdf.model.ViewMode
import kotlin.math.roundToInt

@Composable
fun ViewerScreen(ui: UiState, vm: ViewerViewModel, onOpen: () -> Unit, onSaveAs: () -> Unit) {
    val doc = ui.doc ?: return
    BackHandler {
        when {
            ui.selection != null -> vm.cancelSelection()
            ui.fullscreen -> vm.setFullscreen(false)
            else -> vm.requestClose()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            vm.messageShown()
            snackbar.showSnackbar(it)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!ui.fullscreen) TopBar(ui, vm, doc.name, onOpen, onSaveAs)
            Row(Modifier.fillMaxSize()) {
                if (ui.layout.mode == ViewMode.SINGLE) {
                    PdfPane(ui.layout.active, ui, vm, Modifier.fillMaxSize())
                } else {
                    PdfPane(0, ui, vm, Modifier.weight(1f).fillMaxHeight())
                    Spacer(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF000000)))
                    PdfPane(1, ui, vm, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
        ui.tts?.let {
            TtsBar(
                it, ui.prefs.speechRate, vm,
                Modifier.align(Alignment.TopCenter).padding(top = if (ui.fullscreen) 4.dp else 52.dp),
            )
        }
        if (ui.saving) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        if (ui.dialog == Dialog.CropEditor) CropEditor(ui, vm)
    }

    ui.noteEdit?.let { NoteDialog(it, vm) }
    when (val d = ui.dialog) {
        is Dialog.GoToPage -> GoToPageDialog(d.pane, ui, vm)
        Dialog.Author -> AuthorDialog(ui.prefs.author, vm)
        Dialog.ConfirmClose -> ConfirmCloseDialog(vm)
        is Dialog.SaveFailed -> SaveFailedDialog(d.reason, vm, onSaveAs)
        Dialog.CropEditor -> {} // drawn as an overlay above
        Dialog.Shortcuts -> ShortcutsDialog(vm)
        Dialog.ReadAloudSettings -> ReadAloudSettingsDialog(ui.prefs, vm)
        null -> {}
    }
}

@Composable
private fun TopBar(ui: UiState, vm: ViewerViewModel, name: String, onOpen: () -> Unit, onSaveAs: () -> Unit) {
    val layout = ui.layout
    val dual = layout.mode == ViewMode.DUAL
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIcon(Icons.Filled.Close, "Close document") { vm.requestClose() }
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Tip(if (ui.dirty) "$name (unsaved changes)" else name) {
                    Text(
                        (if (ui.dirty) "• " else "") + name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(
                    if (dual) Icons.Filled.ViewAgenda else Icons.Filled.ViewColumn,
                    if (dual) "Single page (1)" else "Two pages (2)",
                ) { vm.toggleMode() }
                if (dual) {
                    BarIcon(
                        if (layout.linked) Icons.Filled.Link else Icons.Filled.LinkOff,
                        if (layout.linked) "Unlink panes (K)" else "Link panes as a spread (K)",
                    ) { vm.toggleLinked() }
                    BarIcon(Icons.Filled.SwapHoriz, "Swap panes (X)") { vm.swapPanes() }
                }
                Spacer(Modifier.width(8.dp))
                ToolIcon(Icons.Filled.PanTool, "Pan (Esc)", ui.tool == Tool.PAN) { vm.setTool(Tool.PAN) }
                ToolIcon(Icons.Filled.BorderColor, "Highlight (H)", ui.tool == Tool.HIGHLIGHT) {
                    vm.setTool(Tool.HIGHLIGHT)
                }
                ToolIcon(Icons.AutoMirrored.Filled.Comment, "Sticky note (N)", ui.tool == Tool.NOTE) {
                    vm.setTool(Tool.NOTE)
                }
                HighlightColorButton(ui, vm)
                Spacer(Modifier.width(8.dp))
                CropButton(ui, vm)
                ToolIcon(Icons.Filled.InvertColors, "Dark pages (I)", ui.prefs.invert) { vm.toggleInvert() }
                val reading = ui.tts?.status
                BarIcon(
                    when (reading) {
                        null -> Icons.Filled.RecordVoiceOver
                        TtsStatus.PAUSED -> Icons.Filled.PlayArrow
                        else -> Icons.Filled.Pause
                    },
                    when (reading) {
                        null -> "Read aloud (P)"
                        TtsStatus.PAUSED -> "Resume reading (P)"
                        else -> "Pause reading (P)"
                    },
                    tint = if (reading != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
                ) { vm.toggleReading() }
                BarIcon(Icons.Filled.Fullscreen, "Fullscreen (F11)") { vm.setFullscreen(true) }
                BarIcon(
                    Icons.Filled.Save, "Save (Ctrl+S)",
                    tint = if (ui.dirty) MaterialTheme.colorScheme.primary else Color.Gray,
                ) { vm.save() }
                OverflowMenu(vm, onOpen, onSaveAs)
            }
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, tint: Color = Color.Unspecified, onClick: () -> Unit) {
    Tip(label) {
        IconButton(onClick = onClick) {
            if (tint == Color.Unspecified) Icon(icon, label) else Icon(icon, label, tint = tint)
        }
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Tip(label) {
        IconButton(
            onClick = onClick,
            modifier = if (selected) {
                Modifier.padding(2.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small)
            } else Modifier,
        ) {
            Icon(
                icon, label,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun HighlightColorButton(ui: UiState, vm: ViewerViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tip("Highlight color") {
            IconButton(onClick = { open = true }) {
                Box(
                    Modifier.size(20.dp).background(Color(ui.prefs.highlightColor), MaterialTheme.shapes.extraSmall)
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Box(Modifier.padding(horizontal = 8.dp)) {
                ColorDots(current = ui.prefs.highlightColor) {
                    vm.setHighlightColor(it)
                    open = false
                }
            }
        }
    }
}

/**
 * Acts directly, like the C key: toggles the crop, or opens the editor when none is set.
 * No popup menu here on purpose: popup windows have been unreliable with the injected
 * mouse on the glasses in desktop mode; the editor is also in the ⋮ menu and on Shift+C.
 */
@Composable
private fun CropButton(ui: UiState, vm: ViewerViewModel) {
    val l = ui.layout
    val label = when {
        l.crop.isNone -> "Crop: set up (C)"
        l.cropEnabled -> "Crop: show full pages (C)"
        else -> "Crop: show cropped (C)"
    }
    ToolIcon(Icons.Filled.Crop, label, l.cropEnabled, vm::toggleCrop)
}

@Composable
private fun OverflowMenu(vm: ViewerViewModel, onOpen: () -> Unit, onSaveAs: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Tip("More") {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Edit crop… (Shift+C)") },
                onClick = { open = false; vm.openDialog(Dialog.CropEditor) },
            )
            DropdownMenuItem(text = { Text("Go to page… (G)") }, onClick = {
                open = false; vm.openDialog(Dialog.GoToPage(vm.state.value.layout.active))
            })
            DropdownMenuItem(text = { Text("Pin active page in other pane (S)") }, onClick = {
                open = false; vm.sendToOther(vm.state.value.layout.active)
            })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Open another PDF… (Ctrl+O)") }, onClick = { open = false; onOpen() })
            DropdownMenuItem(text = { Text("Save a copy… (Ctrl+Shift+S)") }, onClick = { open = false; onSaveAs() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Annotation author…") }, onClick = {
                open = false; vm.openDialog(Dialog.Author)
            })
            DropdownMenuItem(text = { Text("Read-aloud settings…") }, onClick = {
                open = false; vm.openDialog(Dialog.ReadAloudSettings)
            })
            DropdownMenuItem(text = { Text("Keyboard shortcuts (?)") }, onClick = {
                open = false; vm.openDialog(Dialog.Shortcuts)
            })
        }
    }
}

/** Floating read-aloud controls, shown while a reading session exists (playing or paused). */
@Composable
private fun TtsBar(t: TtsState, rate: Float, vm: ViewerViewModel, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xCC202020),
        contentColor = Color.White,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            SmallIcon(Icons.Filled.SkipPrevious, "Previous sentence (,)") { vm.skipSentence(-1) }
            if (t.status == TtsStatus.STARTING) {
                CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                SmallIcon(
                    if (t.status == TtsStatus.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (t.status == TtsStatus.PLAYING) "Pause (P)" else "Resume (P)",
                ) { vm.toggleReading() }
            }
            SmallIcon(Icons.Filled.SkipNext, "Next sentence (.)") { vm.skipSentence(+1) }
            Text(
                when {
                    t.label != null -> "p. ${t.page + 1} · ${t.label}"
                    t.count > 0 -> "p. ${t.page + 1} · ${t.index + 1}/${t.count}"
                    else -> "p. ${t.page + 1}"
                },
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            SmallIcon(Icons.Filled.Remove, "Slower (<)") { vm.changeSpeechRate(-0.1f) }
            Text("${(rate * 10).roundToInt() / 10.0}×", fontSize = 13.sp)
            SmallIcon(Icons.Filled.Add, "Faster (>)") { vm.changeSpeechRate(+0.1f) }
            SmallIcon(Icons.Filled.Stop, "Stop reading (Shift+P)") { vm.stopReading() }
        }
    }
}
