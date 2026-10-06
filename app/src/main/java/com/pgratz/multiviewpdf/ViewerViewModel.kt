package com.pgratz.multiviewpdf

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pgratz.multiviewpdf.model.AnnotInfo
import com.pgratz.multiviewpdf.model.AnnotType
import com.pgratz.multiviewpdf.model.AppPrefs
import com.pgratz.multiviewpdf.model.CropMargins
import com.pgratz.multiviewpdf.model.FitMode
import com.pgratz.multiviewpdf.model.HighlightColors
import com.pgratz.multiviewpdf.model.Navigator
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageTransform
import com.pgratz.multiviewpdf.model.RecentDoc
import com.pgratz.multiviewpdf.model.Selection
import com.pgratz.multiviewpdf.model.Tool
import com.pgratz.multiviewpdf.model.ViewLayout
import com.pgratz.multiviewpdf.model.ViewMode
import com.pgratz.multiviewpdf.model.hitTest
import com.pgratz.multiviewpdf.pdf.DocumentStore
import com.pgratz.multiviewpdf.pdf.PdfSession
import com.pgratz.multiviewpdf.pdf.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

data class DocInfo(val uri: Uri, val name: String, val fingerprint: String, val pageCount: Int)

/** Long-press / right-click menu. [at] is in pane pixels; [annot] is set when over one. */
data class ContextMenu(val pane: Int, val page: Int, val at: Offset, val point: PPoint, val annot: AnnotInfo?)

/** Note/comment editor. [annotIndex] null = new sticky note at [at]. */
data class NoteEdit(
    val page: Int,
    val annotIndex: Int?,
    val at: PPoint?,
    val text: String,
    val isComment: Boolean,
)

sealed interface Dialog {
    data class GoToPage(val pane: Int) : Dialog
    data object Author : Dialog
    data object ConfirmClose : Dialog
    data class SaveFailed(val reason: String) : Dialog
    data object CropEditor : Dialog
    data object Shortcuts : Dialog
}

sealed interface PaneEvent {
    val pane: Int

    /** Scroll by a fraction of the pane height (keyboard ↑/↓). */
    data class Scroll(override val pane: Int, val fraction: Float) : PaneEvent
}

data class UiState(
    val doc: DocInfo? = null,
    val loading: Boolean = false,
    val layout: ViewLayout = ViewLayout(),
    val tool: Tool = Tool.PAN,
    /** Changes not yet written back to the original file. */
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val selection: Selection? = null,
    val menu: ContextMenu? = null,
    val noteEdit: NoteEdit? = null,
    val dialog: Dialog? = null,
    val bounds: Map<Int, PRect> = emptyMap(),
    val annots: Map<Int, List<AnnotInfo>> = emptyMap(),
    /** Bumped when a page's annotations change so panes re-render it. */
    val versions: Map<Int, Int> = emptyMap(),
    val prefs: AppPrefs = AppPrefs(),
    val message: String? = null,
    /** Toolbar, pane footers and system bars hidden; exit from the pane menu (or Back / F11). */
    val fullscreen: Boolean = false,
) {
    val pageCount: Int get() = doc?.pageCount ?: 0
}

@OptIn(FlowPreview::class)
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DocumentStore(app)
    private val prefsStore = PrefsStore(app)
    private var session: PdfSession? = null
    private var selectionJob: Job? = null
    private val pageLoads = HashSet<Int>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _paneEvents = MutableSharedFlow<PaneEvent>(extraBufferCapacity = 16)
    val paneEvents: SharedFlow<PaneEvent> = _paneEvents

    init {
        viewModelScope.launch {
            val prefs = prefsStore.loadApp()
            _state.update { it.copy(prefs = prefs) }
        }
        // Remember each document's view (pages, locks, rotations, crop) as it changes.
        viewModelScope.launch {
            _state.map { s -> s.doc?.let { it.fingerprint to s.layout } }
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(500)
                .collect { (fp, layout) -> prefsStore.saveLayout(fp, layout) }
        }
    }

    // ---------------------------------------------------------------- documents

    fun open(uri: Uri, persist: Boolean) {
        viewModelScope.launch {
            if (_state.value.dirty) saveNow()
            closeSession()
            _state.update { UiState(prefs = it.prefs, loading = true) }
            try {
                if (persist) store.persistPermission(uri)
                val imported = store.import(uri)
                val s = PdfSession.open(imported.file)
                session = s
                val n = s.pageCount
                // Prefer the PDF's own permanent ID; the content hash changes for small
                // files once annotations are appended to them.
                val fingerprint = s.permanentId()?.let { "id-$it" } ?: imported.fingerprint
                val layout = prefsStore.loadLayout(fingerprint)?.sanitized(n)
                    ?: ViewLayout(mode = if (n > 1) ViewMode.DUAL else ViewMode.SINGLE)
                val prefs = _state.value.prefs.withRecent(
                    RecentDoc(uri.toString(), imported.name, System.currentTimeMillis())
                )
                _state.update {
                    it.copy(
                        doc = DocInfo(uri, imported.name, fingerprint, n),
                        loading = false,
                        layout = layout,
                        prefs = prefs,
                    )
                }
                prefsStore.saveApp(prefs)
            } catch (e: Exception) {
                Log.e(TAG, "open failed", e)
                _state.update { it.copy(loading = false, message = "Couldn't open: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun forgetRecent(uri: String) = updatePrefs { it.copy(recent = it.recent.filter { r -> r.uri != uri }) }

    /** Close button / back: asks first if there are unsaved annotations. */
    fun requestClose() {
        if (_state.value.dirty) _state.update { it.copy(dialog = Dialog.ConfirmClose) }
        else closeDocument()
    }

    fun closeDocument() {
        viewModelScope.launch {
            closeSession()
            _state.update { UiState(prefs = it.prefs) }
        }
    }

    fun saveAndClose() {
        viewModelScope.launch {
            if (saveNow()) {
                closeSession()
                _state.update { UiState(prefs = it.prefs) }
            }
        }
    }

    private suspend fun closeSession() {
        val s = session ?: return
        session = null
        pageLoads.clear()
        withContext(NonCancellable) { runCatching { s.close() } }
    }

    fun save() {
        viewModelScope.launch { saveNow() }
    }

    /** Called when the app goes to the background. */
    fun autosave() {
        if (_state.value.dirty && !_state.value.saving) save()
    }

    private suspend fun saveNow(): Boolean = withContext(NonCancellable) {
        val s = session ?: return@withContext false
        val doc = _state.value.doc ?: return@withContext false
        _state.update { it.copy(saving = true) }
        try {
            s.save()
            store.writeBack(s.workFile, doc.uri)
            _state.update { it.copy(dirty = false, saving = false, message = "Saved") }
            true
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            _state.update {
                it.copy(saving = false, dialog = Dialog.SaveFailed(e.message ?: e.javaClass.simpleName))
            }
            false
        }
    }

    /** Writes the annotated document to a new file and continues editing that one. */
    fun saveAs(uri: Uri) {
        viewModelScope.launch {
            val s = session ?: return@launch
            try {
                s.save()
                store.persistPermission(uri)
                store.writeBack(s.workFile, uri)
                _state.update {
                    val d = it.doc ?: return@update it
                    it.copy(doc = d.copy(uri = uri), dirty = false, dialog = null, message = "Saved copy")
                }
                val name = _state.value.doc?.name ?: "document.pdf"
                updatePrefs { p -> p.withRecent(RecentDoc(uri.toString(), name, System.currentTimeMillis())) }
            } catch (e: Exception) {
                _state.update { it.copy(dialog = Dialog.SaveFailed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    // ---------------------------------------------------------------- pages

    /** Loads page size and annotations the first time a pane shows [page]. */
    fun ensurePage(page: Int) {
        val s = session ?: return
        if (page !in 0 until s.pageCount) return
        val st = _state.value
        if (page in st.bounds && page in st.annots) return
        if (!pageLoads.add(page)) return
        viewModelScope.launch {
            try {
                val b = s.bounds(page)
                val a = s.annotations(page)
                _state.update { it.copy(bounds = it.bounds + (page to b), annots = it.annots + (page to a)) }
            } catch (e: Exception) {
                Log.e(TAG, "load page $page failed", e)
            } finally {
                pageLoads.remove(page)
            }
        }
    }

    suspend fun render(page: Int, t: PageTransform, x0: Int, y0: Int, w: Int, h: Int): Bitmap? {
        val s = session ?: return null
        return try {
            s.render(page, t, x0, y0, w, h)
        } catch (e: Exception) {
            Log.e(TAG, "render $page failed", e)
            null
        }
    }

    /** Page-space column around [at] for double-tap zoom, or null if none was found. */
    suspend fun columnAt(page: Int, visible: PRect, at: PPoint): PRect? {
        val s = session ?: return null
        return try {
            s.columnAt(page, visible, at)
        } catch (e: Exception) {
            Log.e(TAG, "columnAt $page failed", e)
            null
        }
    }

    // ---------------------------------------------------------------- navigation & layout

    private fun nav(f: (ViewLayout, Int) -> ViewLayout) {
        _state.update { s ->
            val next = f(s.layout, s.pageCount)
            if (next == s.layout) s
            else s.copy(layout = next, selection = s.selection?.takeIf { sel -> next.panes[sel.pane].page == sel.page })
        }
    }

    fun turn(dir: Int, fine: Boolean = false) {
        val before = _state.value.layout
        nav { l, n -> Navigator.turn(l, dir, n, fine) }
        if (before.mode == ViewMode.DUAL && before.panes.all { it.locked }) toast("Both panes are locked")
    }

    fun turnPane(pane: Int, dir: Int) {
        val l = _state.value.layout
        if (l.mode == ViewMode.DUAL && l.panes[pane].locked) {
            toast("Pane is locked — unlock to turn its pages")
            return
        }
        nav { lay, n -> Navigator.turnPane(lay, pane, dir, n) }
    }

    fun goTo(pane: Int, page: Int) = nav { l, n -> Navigator.goTo(l, pane, page, n) }

    fun setActive(pane: Int) {
        if (_state.value.layout.active != pane) _state.update { it.copy(layout = it.layout.copy(active = pane)) }
    }

    fun toggleActive() {
        val l = _state.value.layout
        _state.update { it.copy(layout = l.copy(active = 1 - l.active)) }
    }

    fun toggleLock(pane: Int) = nav { l, n -> Navigator.toggleLock(l, pane, n) }

    fun rotate(pane: Int, delta: Int) = nav { l, _ -> Navigator.rotate(l, pane, delta) }

    fun setMode(mode: ViewMode) = nav { l, n -> Navigator.setMode(l, mode, n) }

    fun toggleMode() = setMode(if (_state.value.layout.mode == ViewMode.DUAL) ViewMode.SINGLE else ViewMode.DUAL)

    fun toggleLinked() = nav { l, n -> Navigator.setLinked(l, !l.linked, n) }

    fun swapPanes() = nav { l, _ -> Navigator.swap(l) }

    fun sendToOther(pane: Int) = nav { l, _ -> Navigator.sendToOther(l, pane) }

    fun setZoom(pane: Int, zoom: Float) = nav { l, _ -> Navigator.setZoom(l, pane, zoom) }

    fun zoomBy(pane: Int, factor: Float) = setZoom(pane, _state.value.layout.panes[pane].zoom * factor)

    fun toggleFit(pane: Int) = nav { l, _ ->
        l.withPane(pane) { it.copy(fit = if (it.fit == FitMode.PAGE) FitMode.WIDTH else FitMode.PAGE, zoom = 1f) }
    }

    // ---------------------------------------------------------------- crop & display

    fun setCrop(m: CropMargins) = _state.update {
        val c = m.clamped()
        it.copy(layout = it.layout.copy(crop = c, cropEnabled = !c.isNone), dialog = null)
    }

    fun toggleCrop() {
        val l = _state.value.layout
        if (l.crop.isNone) openDialog(Dialog.CropEditor)
        else _state.update { it.copy(layout = l.copy(cropEnabled = !l.cropEnabled)) }
    }

    suspend fun autoCrop(): CropMargins? = try {
        session?.autoCrop()
    } catch (e: Exception) {
        Log.e(TAG, "autocrop failed", e)
        null
    }

    fun setFullscreen(on: Boolean) = _state.update { it.copy(fullscreen = on, menu = null) }

    fun toggleInvert() = updatePrefs { it.copy(invert = !it.invert) }

    fun setAuthor(name: String) {
        updatePrefs { it.copy(author = name.trim()) }
        dismissDialog()
    }

    fun setHighlightColor(color: Int) = updatePrefs { it.copy(highlightColor = color) }

    private fun updatePrefs(f: (AppPrefs) -> AppPrefs) {
        val p = f(_state.value.prefs)
        _state.update { it.copy(prefs = p) }
        viewModelScope.launch { prefsStore.saveApp(p) }
    }

    // ---------------------------------------------------------------- tools & annotations

    fun setTool(tool: Tool) = _state.update { it.copy(tool = tool, selection = null) }

    fun onLongPress(pane: Int, page: Int, at: Offset, point: PPoint, slop: Float) {
        val annot = _state.value.annots[page]?.hitTest(point, slop)
        _state.update { it.copy(menu = ContextMenu(pane, page, at, point, annot), selection = null) }
    }

    /** Tap on page content. Returns false if nothing used it (pane may then flip pages). */
    fun onTap(pane: Int, page: Int, at: Offset, point: PPoint, slop: Float): Boolean {
        val st = _state.value
        if (st.selection != null) {
            _state.update { it.copy(selection = null) }
            return true
        }
        if (page !in 0 until st.pageCount) return false
        if (st.tool == Tool.NOTE) {
            _state.update { it.copy(tool = Tool.PAN) }
            newNote(page, point)
            return true
        }
        val annot = st.annots[page]?.hitTest(point, slop) ?: return false
        when (annot.type) {
            AnnotType.NOTE -> editAnnotText(page, annot)
            else -> _state.update { it.copy(menu = ContextMenu(pane, page, at, point, annot)) }
        }
        return true
    }

    fun dismissMenu() = _state.update { it.copy(menu = null) }

    fun newNote(page: Int, at: PPoint) =
        _state.update { it.copy(menu = null, noteEdit = NoteEdit(page, null, at, "", isComment = false)) }

    fun editAnnotText(page: Int, annot: AnnotInfo) = _state.update {
        it.copy(
            menu = null,
            noteEdit = NoteEdit(page, annot.index, null, annot.contents, isComment = annot.type != AnnotType.NOTE),
        )
    }

    fun dismissNote() = _state.update { it.copy(noteEdit = null) }

    fun saveNote(text: String) {
        val edit = _state.value.noteEdit ?: return
        val s = session ?: return
        _state.update { it.copy(noteEdit = null) }
        editPage(edit.page) {
            if (edit.annotIndex == null) {
                if (text.isBlank()) return@editPage false
                s.addNote(edit.page, edit.at!!, text, HighlightColors.noteDefault, _state.value.prefs.author)
            } else {
                s.setContents(edit.page, edit.annotIndex, text)
            }
            true
        }
    }

    fun deleteAnnot(page: Int, index: Int) {
        val s = session ?: return
        _state.update { it.copy(menu = null, noteEdit = null) }
        editPage(page) { s.delete(page, index); true }
    }

    fun recolorAnnot(page: Int, index: Int, color: Int) {
        val s = session ?: return
        _state.update { it.copy(menu = null) }
        setHighlightColor(color)
        editPage(page) { s.setColor(page, index, color); true }
    }

    /** Long-press "Start highlight here": select the word under the finger, with handles. */
    fun startHighlightAt(pane: Int, page: Int, p: PPoint) {
        val s = session ?: return
        _state.update { it.copy(menu = null) }
        viewModelScope.launch {
            val word = s.wordAt(page, p)
            val sel = if (word != null) {
                Selection(pane, page, word.first, word.second, rectMode = false,
                    quads = s.highlightQuads(page, word.first, word.second), confirm = true)
            } else {
                // No text here (scanned page): start a box the user can resize.
                val a = PPoint(p.x - 40f, p.y - 8f)
                val b = PPoint(p.x + 40f, p.y + 8f)
                Selection(pane, page, a, b, rectMode = true, quads = listOf(PQuad.of(PRect.spanning(a, b))), confirm = true)
            }
            _state.update { it.copy(selection = sel) }
        }
    }

    /** Highlight tool: drag across text (or draw a box where there is none). */
    fun beginHighlightDrag(pane: Int, page: Int, p: PPoint) {
        if (page !in 0 until _state.value.pageCount) return
        _state.update { it.copy(selection = Selection(pane, page, p, p, false, emptyList(), confirm = false)) }
    }

    fun moveSelection(a: PPoint?, b: PPoint?) {
        val cur = _state.value.selection ?: return
        val sel = cur.copy(a = a ?: cur.a, b = b ?: cur.b)
        _state.update { it.copy(selection = sel) }
        val s = session ?: return
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val updated = if (sel.confirm && sel.rectMode) {
                sel.copy(quads = listOf(PQuad.of(PRect.spanning(sel.a, sel.b))))
            } else {
                val text = s.highlightQuads(sel.page, sel.a, sel.b)
                when {
                    text.isNotEmpty() -> sel.copy(quads = text, rectMode = false)
                    sel.confirm -> sel.copy(quads = emptyList())
                    else -> sel.copy(quads = listOf(PQuad.of(PRect.spanning(sel.a, sel.b))), rectMode = true)
                }
            }
            _state.update { st ->
                val live = st.selection
                if (live != null && live.page == sel.page && live.a == sel.a && live.b == sel.b) st.copy(selection = updated) else st
            }
        }
    }

    fun endHighlightDrag() {
        val sel = _state.value.selection ?: return
        if (sel.confirm) return
        viewModelScope.launch {
            selectionJob?.join()
            commitSelection()
        }
    }

    fun cancelSelection() = _state.update { it.copy(selection = null) }

    fun commitSelection(color: Int? = null) {
        val sel = _state.value.selection ?: return
        val s = session ?: return
        _state.update { it.copy(selection = null) }
        val quads = sel.quads
        if (quads.isEmpty()) return
        if (sel.rectMode) {
            val r = PRect.spanning(sel.a, sel.b)
            if (abs(r.width) < 3f || abs(r.height) < 3f) return
        }
        val c = color ?: _state.value.prefs.highlightColor
        if (color != null) setHighlightColor(color)
        editPage(sel.page) { s.addHighlight(sel.page, quads, c, _state.value.prefs.author); true }
    }

    /** Runs an annotation edit, then refreshes that page's annotation list and rendering. */
    private fun editPage(page: Int, edit: suspend () -> Boolean) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                if (!edit()) return@launch
                val a = s.annotations(page)
                _state.update {
                    it.copy(
                        annots = it.annots + (page to a),
                        versions = it.versions + (page to (it.versions[page] ?: 0) + 1),
                        dirty = true,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "annotation edit failed", e)
                toast("Couldn't change annotation: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- dialogs & messages

    fun openDialog(d: Dialog) = _state.update { it.copy(dialog = d, menu = null) }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun toast(msg: String) = _state.update { it.copy(message = msg) }

    fun messageShown() = _state.update { it.copy(message = null) }

    // ---------------------------------------------------------------- keyboard

    /** Hardware keyboard shortcuts (desktop mode on the glasses). Returns true if handled. */
    fun onKey(keyCode: Int, shift: Boolean, ctrl: Boolean): Boolean {
        val st = _state.value
        if (st.doc == null) {
            if (ctrl && keyCode == KeyEvent.KEYCODE_O) return openRequests.tryEmit(Unit)
            return false
        }
        if (st.dialog != null || st.noteEdit != null) return false
        val active = st.layout.active
        if (ctrl) {
            when (keyCode) {
                KeyEvent.KEYCODE_S -> if (shift) saveAsRequests.tryEmit(Unit) else save()
                KeyEvent.KEYCODE_O -> openRequests.tryEmit(Unit)
                KeyEvent.KEYCODE_W -> requestClose()
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> zoomBy(active, 1.25f)
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> zoomBy(active, 0.8f)
                KeyEvent.KEYCODE_0 -> setZoom(active, 1f)
                else -> return false
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> turn(+1, fine = shift)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> turn(-1, fine = shift)
            KeyEvent.KEYCODE_SPACE -> turn(if (shift) -1 else +1)
            KeyEvent.KEYCODE_DPAD_DOWN -> _paneEvents.tryEmit(PaneEvent.Scroll(active, 0.15f))
            KeyEvent.KEYCODE_DPAD_UP -> _paneEvents.tryEmit(PaneEvent.Scroll(active, -0.15f))
            KeyEvent.KEYCODE_MOVE_HOME -> goTo(active, 0)
            KeyEvent.KEYCODE_MOVE_END -> goTo(active, st.pageCount - 1)
            KeyEvent.KEYCODE_TAB -> toggleActive()
            KeyEvent.KEYCODE_L -> toggleLock(active)
            KeyEvent.KEYCODE_R -> rotate(active, if (shift) -90 else 90)
            KeyEvent.KEYCODE_1 -> setMode(ViewMode.SINGLE)
            KeyEvent.KEYCODE_2 -> setMode(ViewMode.DUAL)
            KeyEvent.KEYCODE_K -> toggleLinked()
            KeyEvent.KEYCODE_X -> swapPanes()
            KeyEvent.KEYCODE_S -> sendToOther(active)
            KeyEvent.KEYCODE_H -> setTool(if (st.tool == Tool.HIGHLIGHT) Tool.PAN else Tool.HIGHLIGHT)
            KeyEvent.KEYCODE_N -> setTool(if (st.tool == Tool.NOTE) Tool.PAN else Tool.NOTE)
            KeyEvent.KEYCODE_ESCAPE -> when {
                st.menu != null -> dismissMenu()
                st.selection != null -> cancelSelection()
                else -> setTool(Tool.PAN)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (st.selection != null) commitSelection() else return false
            KeyEvent.KEYCODE_C -> if (shift) openDialog(Dialog.CropEditor) else toggleCrop()
            KeyEvent.KEYCODE_I -> toggleInvert()
            KeyEvent.KEYCODE_G -> openDialog(Dialog.GoToPage(active))
            KeyEvent.KEYCODE_F -> toggleFit(active)
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> zoomBy(active, 1.25f)
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> zoomBy(active, 0.8f)
            KeyEvent.KEYCODE_0 -> setZoom(active, 1f)
            KeyEvent.KEYCODE_SLASH, KeyEvent.KEYCODE_F1 -> openDialog(Dialog.Shortcuts)
            KeyEvent.KEYCODE_F11 -> setFullscreen(!st.fullscreen)
            else -> return false
        }
        return true
    }

    /** Requests the activity to show a file picker (Ctrl+O, Ctrl+Shift+S). */
    val openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveAsRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCleared() {
        val s = session ?: return
        session = null
        // viewModelScope is already cancelled here; close on the MuPDF thread directly.
        CoroutineScope(PdfSession.dispatcher).launch { runCatching { s.close() } }
    }

    companion object {
        private const val TAG = "MultiViewPDF"
    }
}
