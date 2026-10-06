package com.pgratz.multiviewpdf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgratz.multiviewpdf.NoteEdit
import com.pgratz.multiviewpdf.UiState
import com.pgratz.multiviewpdf.ViewerViewModel

@Composable
fun NoteDialog(edit: NoteEdit, vm: ViewerViewModel) {
    var text by remember(edit) { mutableStateOf(edit.text) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val isNew = edit.annotIndex == null
    AlertDialog(
        onDismissRequest = vm::dismissNote,
        title = { Text(if (edit.isComment) "Highlight comment" else if (isNew) "New note" else "Note") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 4,
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(onClick = { vm.saveNote(text) }, enabled = !isNew || text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew && edit.annotIndex != null) {
                    TextButton(onClick = { vm.deleteAnnot(edit.page, edit.annotIndex) }) { Text("Delete") }
                }
                TextButton(onClick = vm::dismissNote) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun GoToPageDialog(pane: Int, ui: UiState, vm: ViewerViewModel) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val target = text.toIntOrNull()
    val valid = target != null && target in 1..ui.pageCount
    val go = {
        if (valid) {
            vm.dismissDialog()
            vm.goTo(pane, target!! - 1)
        }
    }
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text(if (ui.layout.mode == com.pgratz.multiviewpdf.model.ViewMode.DUAL) "Go to page (${if (pane == 0) "left" else "right"} pane)" else "Go to page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(6) },
                label = { Text("1 – ${ui.pageCount}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = go, enabled = valid) { Text("Go") } },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
fun AuthorDialog(current: String, vm: ViewerViewModel) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text("Annotation author") },
        text = {
            Column {
                Text("Stored in each note and highlight you create (shown by Okular, Acrobat, Xodo).")
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { vm.setAuthor(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmCloseDialog(vm: ViewerViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text("Save annotations?") },
        text = { Text("This document has notes or highlights that haven't been saved to the file.") },
        confirmButton = { TextButton(onClick = { vm.dismissDialog(); vm.saveAndClose() }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.dismissDialog(); vm.closeDocument() }) { Text("Discard") }
                TextButton(onClick = vm::dismissDialog) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun SaveFailedDialog(reason: String, vm: ViewerViewModel, onSaveAs: () -> Unit) {
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text("Couldn't save to the original file") },
        text = {
            Text(
                "The app that provided this PDF may not allow changes ($reason). " +
                    "Your annotations are still open here — save a copy to keep them."
            )
        },
        confirmButton = { TextButton(onClick = { vm.dismissDialog(); onSaveAs() }) { Text("Save a copy…") } },
        dismissButton = { TextButton(onClick = vm::dismissDialog) { Text("Not now") } },
    )
}

private val shortcuts = listOf(
    "→ / PgDn / Space" to "Next page (spread turns by 2)",
    "← / PgUp / Shift+Space" to "Previous page",
    "Shift+← / →" to "Shift a spread by one page",
    "↑ / ↓" to "Scroll active pane",
    "Home / End" to "First / last page",
    "Tab" to "Switch active pane",
    "1 / 2" to "Single / two-page mode",
    "L" to "Lock / unlock active pane",
    "K" to "Link / unlink panes as a spread",
    "S" to "Pin active page in the other pane",
    "X" to "Swap panes",
    "R / Shift+R" to "Rotate active pane ↻ / ↺",
    "F" to "Fit width / fit page",
    "+ / − / 0" to "Zoom in / out / reset (also Ctrl+wheel)",
    "H" to "Highlight tool",
    "N" to "Sticky-note tool",
    "Esc" to "Cancel / back to pan tool",
    "Enter" to "Confirm highlight selection",
    "C / Shift+C" to "Crop on/off / edit crop",
    "I" to "Dark (inverted) pages",
    "G" to "Go to page",
    "F11" to "Fullscreen on / off",
    "Ctrl+S / Ctrl+Shift+S" to "Save / save a copy",
    "Ctrl+O / Ctrl+W" to "Open / close",
    "Right-click / long-press" to "Note, highlight and pane menu",
    "Double-click / double-tap" to "Zoom to the text column (again: zoom out)",
    "Click / tap outer page edge" to "Scroll sideways, then turn the page",
)

@Composable
fun ShortcutsDialog(vm: ViewerViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissDialog,
        title = { Text("Keyboard & mouse") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((k, v) in shortcuts) {
                    Row(Modifier.padding(vertical = 2.dp)) {
                        Text(k, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(190.dp))
                        Text(v, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = vm::dismissDialog) { Text("Close") } },
    )
}
