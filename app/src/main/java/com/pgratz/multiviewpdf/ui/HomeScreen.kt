package com.pgratz.multiviewpdf.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pgratz.multiviewpdf.UiState
import com.pgratz.multiviewpdf.ViewerViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(ui: UiState, vm: ViewerViewModel, onOpen: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            vm.messageShown()
            snackbar.showSnackbar(it)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("MultiViewPDF", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Two-pane PDF reader with sticky notes and highlights",
                color = Color.Gray,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
            if (ui.loading) {
                CircularProgressIndicator()
            } else {
                Button(onClick = onOpen) {
                    Icon(Icons.Filled.FolderOpen, null)
                    Text("  Open PDF…")
                }
            }
            if (ui.prefs.recent.isNotEmpty()) {
                Text(
                    "Recent",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
                )
                LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    items(ui.prefs.recent, key = { it.uri }) { r ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.open(Uri.parse(r.uri), persist = false) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                        .format(Date(r.lastOpened)),
                                    color = Color.Gray,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            IconButton(onClick = { vm.forgetRecent(r.uri) }) {
                                Icon(Icons.Filled.Close, "Remove from recent")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}
