package com.pgratz.multiviewpdf

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pgratz.multiviewpdf.ui.HomeScreen
import com.pgratz.multiviewpdf.ui.ViewerScreen
import com.pgratz.multiviewpdf.ui.theme.MultiViewPdfTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ViewerViewModel by viewModels()

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) vm.open(uri, persist = true)
        }

    private val saveAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) vm.saveAs(uri)
        }

    private fun pickPdf() = openLauncher.launch(arrayOf("application/pdf"))

    private fun pickSaveAs() {
        val name = vm.state.value.doc?.name?.removeSuffix(".pdf") ?: "document"
        saveAsLauncher.launch("$name (annotated).pdf")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.openRequests.collect { pickPdf() } }
                launch { vm.saveAsRequests.collect { pickSaveAs() } }
            }
        }

        setContent {
            MultiViewPdfTheme {
                val ui by vm.state.collectAsStateWithLifecycle()
                val immersive = ui.fullscreen && ui.doc != null
                LaunchedEffect(immersive) { setSystemBarsHidden(immersive) }
                // Surface (not Box) so text gets the theme's light-on-dark content color.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.safeDrawingPadding()) {
                    if (ui.doc == null) HomeScreen(ui, vm, ::pickPdf)
                    else ViewerScreen(ui, vm, ::pickPdf, ::pickSaveAs)
                    }
                }
            }
        }
    }

    /** Fullscreen: hide status and navigation bars; a swipe from the edge shows them briefly. */
    private fun setSystemBarsHidden(hidden: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_EDIT) return
        val persistable = intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        vm.open(uri, persist = persistable)
    }

    /** Keys ViewRootImpl uses to leave touch mode (its hidden isNavigationKey list). */
    private val touchModeExitKeys = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER,
    )

    /** downTime of the last key-down we saw, to detect key-downs Android swallowed. */
    private var lastDownTime = -1L

    /**
     * Keyboard shortcuts go to the ViewModel before Compose focus handling sees them.
     * After a touch, Android consumes the next arrow/PgUp/PgDn key-down to leave touch
     * mode, so a key-up whose key-down never arrived is treated as the press.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                lastDownTime = event.downTime
                vm.onKey(event.keyCode, event.isShiftPressed, event.isCtrlPressed)
            }
            KeyEvent.ACTION_UP -> event.downTime != lastDownTime && event.keyCode in touchModeExitKeys &&
                vm.onKey(event.keyCode, event.isShiftPressed, event.isCtrlPressed)
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        vm.autosave()
    }
}
