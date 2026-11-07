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
import com.pgratz.multiviewpdf.model.Body
import com.pgratz.multiviewpdf.model.CropMargins
import com.pgratz.multiviewpdf.model.FigureKey
import com.pgratz.multiviewpdf.model.FigureMode
import com.pgratz.multiviewpdf.model.FigureNote
import com.pgratz.multiviewpdf.model.FitMode
import com.pgratz.multiviewpdf.model.HighlightColors
import com.pgratz.multiviewpdf.model.LinkInfo
import com.pgratz.multiviewpdf.model.Navigator
import com.pgratz.multiviewpdf.model.PPoint
import com.pgratz.multiviewpdf.model.PQuad
import com.pgratz.multiviewpdf.model.PRect
import com.pgratz.multiviewpdf.model.PageTransform
import com.pgratz.multiviewpdf.model.ReadItem
import com.pgratz.multiviewpdf.model.ReadingPlan
import com.pgratz.multiviewpdf.model.ReadingPlans
import com.pgratz.multiviewpdf.model.RecentDoc
import com.pgratz.multiviewpdf.model.Selection
import com.pgratz.multiviewpdf.model.SentenceSplitter
import com.pgratz.multiviewpdf.model.Tool
import com.pgratz.multiviewpdf.model.ViewLayout
import com.pgratz.multiviewpdf.model.ViewMode
import com.pgratz.multiviewpdf.model.hitTest
import com.pgratz.multiviewpdf.pdf.DocumentStore
import com.pgratz.multiviewpdf.pdf.PdfSession
import com.pgratz.multiviewpdf.pdf.PrefsStore
import com.pgratz.multiviewpdf.tts.ClaudeResult
import com.pgratz.multiviewpdf.tts.FigureCatalog
import com.pgratz.multiviewpdf.tts.FigureDescriber
import com.pgratz.multiviewpdf.tts.ReadAloudController
import com.pgratz.multiviewpdf.tts.ReadAloudService
import com.pgratz.multiviewpdf.tts.SdkClaudeClient
import com.pgratz.multiviewpdf.tts.SpeechReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

data class DocInfo(val uri: Uri, val name: String, val fingerprint: String, val pageCount: Int)

/** Long-press / right-click menu. [at] is in pane pixels; [annot] is set when over one. */
data class ContextMenu(val pane: Int, val page: Int, val at: Offset, val point: PPoint, val annot: AnnotInfo?)

/** A tapped link, shown as a chip near [at] (pane pixels) until it is opened or dismissed. */
data class LinkChip(val pane: Int, val page: Int, val at: Offset, val link: LinkInfo)

enum class TtsStatus { STARTING, PLAYING, PAUSED }

/** Read-aloud progress (null in [UiState.tts] = idle). [quads] outline the sentence being spoken. */
data class TtsState(
    val status: TtsStatus,
    val pane: Int,
    val page: Int,
    val index: Int,
    val count: Int,
    val quads: List<PQuad>,
    /** "Figure 3" while a figure note is spoken; null for body text. */
    val label: String? = null,
)

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
    data object ReadAloudSettings : Dialog
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
    val links: Map<Int, List<LinkInfo>> = emptyMap(),
    val linkChip: LinkChip? = null,
    val tts: TtsState? = null,
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
class ViewerViewModel(app: Application) : AndroidViewModel(app), ReadAloudController {
    private val store = DocumentStore(app)
    private val prefsStore = PrefsStore(app)
    private var session: PdfSession? = null
    private var selectionJob: Job? = null
    private val pageLoads = HashSet<Int>()

    private var reader: SpeechReader? = null
    /** Bumped on every stop/seek; utterance ids carry it so stale engine callbacks are ignored. */
    private var ttsGeneration = 0
    private var ttsJob: Job? = null
    private val planCache = object : LinkedHashMap<Int, ReadingPlan>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ReadingPlan>): Boolean =
            size > PLAN_CACHE
    }
    private var catalog: FigureCatalog? = null
    private var figureIndexJob: Job? = null
    /** Figures already described in this reading session (cleared by stop). */
    private val spokenFigures = HashSet<FigureKey>()
    private var claudeClient: SdkClaudeClient? = null
    private val describer = FigureDescriber(
        store = prefsStore,
        scope = viewModelScope,
        render = { fig -> session?.renderRegion(fig.page, fig.caption.extent) },
        client = ::currentClaudeClient,
        onFailure = { reason -> toast("Claude unavailable, reading captions instead ($reason)") },
        debugDir = if (BuildConfig.DEBUG) java.io.File(app.cacheDir, "figures") else null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _paneEvents = MutableSharedFlow<PaneEvent>(extraBufferCapacity = 16)
    val paneEvents: SharedFlow<PaneEvent> = _paneEvents

    init {
        viewModelScope.launch {
            val prefs = prefsStore.loadApp()
            _state.update { it.copy(prefs = prefs) }
        }
        // Reading aloud runs inside a foreground media service, so it survives the screen
        // turning off and gets controls in the notification shade. Started on the first
        // item; the service stops itself when playback becomes null.
        ReadAloudService.controller = this
        viewModelScope.launch {
            _state.map { it.tts != null }.distinctUntilChanged().collect { reading ->
                if (reading) runCatching { ReadAloudService.start(getApplication()) }
                    .onFailure { Log.w(TAG, "read-aloud service failed to start", it) }
            }
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
        stopReading()
        planCache.clear()
        catalog = null
        figureIndexJob?.cancel()
        figureIndexJob = null
        describer.reset()
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
        if (page in st.bounds && page in st.annots && page in st.links) return
        if (!pageLoads.add(page)) return
        viewModelScope.launch {
            try {
                val b = s.bounds(page)
                val a = s.annotations(page)
                val l = runCatching { s.links(page) }.getOrDefault(emptyList())
                _state.update {
                    it.copy(bounds = it.bounds + (page to b), annots = it.annots + (page to a), links = it.links + (page to l))
                }
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
            else s.copy(
                layout = next,
                selection = s.selection?.takeIf { sel -> next.panes[sel.pane].page == sel.page },
                linkChip = s.linkChip?.takeIf { c -> next.panes[c.pane].page == c.page },
            )
        }
        syncReadingToLayout()
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

    fun swapPanes() {
        // The reader follows its page to the other pane.
        _state.update { s -> s.copy(tts = s.tts?.let { it.copy(pane = 1 - it.pane) }) }
        nav { l, _ -> Navigator.swap(l) }
    }

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
        _state.update { it.copy(menu = ContextMenu(pane, page, at, point, annot), selection = null, linkChip = null) }
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
        val annot = st.annots[page]?.hitTest(point, slop)
        if (annot != null) {
            when (annot.type) {
                AnnotType.NOTE -> editAnnotText(page, annot)
                else -> _state.update { it.copy(menu = ContextMenu(pane, page, at, point, annot)) }
            }
            return true
        }
        val link = st.links[page]?.hitTest(point, slop)
        if (link != null) {
            _state.update { it.copy(linkChip = LinkChip(pane, page, at, link)) }
            return true
        }
        if (st.linkChip != null) {
            dismissLinkChip()
            return true
        }
        return false
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

    // ---------------------------------------------------------------- links

    /** Tap on the chip: follow the link. */
    fun openLink(chip: LinkChip) {
        _state.update { it.copy(linkChip = null) }
        val target = chip.link.page
        if (target != null) goTo(chip.pane, target) else linkRequests.tryEmit(chip.link.uri)
    }

    fun dismissLinkChip() {
        if (_state.value.linkChip != null) _state.update { it.copy(linkChip = null) }
    }

    /** External link URIs for the activity to hand to the browser. */
    val linkRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    // ---------------------------------------------------------------- read aloud

    /** For [ReadAloudService]: document title and progress while reading, null when idle. */
    override val playback: StateFlow<ReadAloudService.Playback?> = _state.map { s ->
        s.tts?.let { t ->
            ReadAloudService.Playback(
                title = s.doc?.name?.removeSuffix(".pdf") ?: "MultiViewPDF",
                page = t.page, index = t.index, count = t.count, label = t.label,
                playing = t.status != TtsStatus.PAUSED,
            )
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun ensureReader(): SpeechReader {
        reader?.let { return it }
        val p = _state.value.prefs
        val r = SpeechReader(getApplication<Application>(), p.speechRate, p.voiceName)
        reader = r
        viewModelScope.launch { r.events.collect { onSpeechEvent(it) } }
        return r
    }

    /** Engine voices for the settings picker; filled by [loadVoices] once the engine is up. */
    private val _voices = MutableStateFlow<List<SpeechReader.VoiceInfo>>(emptyList())
    val voices: StateFlow<List<SpeechReader.VoiceInfo>> = _voices.asStateFlow()
    private val _defaultVoice = MutableStateFlow<String?>(null)
    val defaultVoice: StateFlow<String?> = _defaultVoice.asStateFlow()

    fun loadVoices() {
        val r = ensureReader()
        if (r.isReady) publishVoices(r)
        // else: published from onSpeechEvent(Ready)
    }

    private fun publishVoices(r: SpeechReader) {
        _voices.value = r.voices()
        _defaultVoice.value = r.defaultVoiceName()
    }

    /** Settings "Listen": says a sample sentence in [voiceName] (pauses reading first). */
    fun previewVoice(voiceName: String, rate: Float) {
        pauseReading()
        val r = ensureReader()
        r.setVoice(voiceName)
        r.setRate(rate)
        r.speak(PREVIEW_ID, "This is how the reader will sound. Figure 3 shows the geometric mean speedup over the baseline.", flush = true)
    }

    private fun ensureCatalog(s: PdfSession): FigureCatalog = catalog ?: FigureCatalog(s).also { catalog = it }

    private fun currentClaudeClient(): SdkClaudeClient? {
        val p = _state.value.prefs
        val key = p.claudeApiKey.trim()
        if (key.isEmpty()) return null
        val c = claudeClient
        if (c != null && c.apiKey == key && c.model == p.claudeModel) return c
        return SdkClaudeClient(key, p.claudeModel).also { claudeClient = it }
    }

    /**
     * The page's reading plan: readable sentences (no headers, captions or figure labels)
     * with a figure note before each first mention. Built off the MuPDF thread and cached.
     */
    private suspend fun plan(page: Int): ReadingPlan? {
        val s = session ?: return null
        if (page !in 0 until s.pageCount) return ReadingPlan(page, null, emptyList())
        planCache[page]?.let { return it }
        _state.value.doc?.fingerprint?.let { describer.load(it) }
        val cat = ensureCatalog(s)
        val (text, layout) = cat.analysis(page)
        val sentences = withContext(Dispatchers.Default) { SentenceSplitter.split(text, layout::isReadable) }
        for (key in ReadingPlans.referencedKeys(sentences)) cat.resolve(key, near = page)
        val plan = ReadingPlans.build(page, layout, sentences, cat::resolveCached)
        planCache[page] = plan
        prefetchDescriptions(plan)
        return plan
    }

    /** Drawing sheets (OCR'd reference numerals only) are skipped when reading on. */
    private fun hasProse(plan: ReadingPlan?) =
        plan != null && plan.bodySentences.isNotEmpty() && SentenceSplitter.looksLikeProse(plan.bodySentences)

    private fun eligible(item: ReadItem): Boolean = when (item) {
        is Body -> true
        is FigureNote -> _state.value.prefs.figureMode != FigureMode.NONE && item.key !in spokenFigures &&
            (item.figure ?: catalog?.resolveCached(item.key)) != null
    }

    private fun nextEligible(list: List<ReadItem>, from: Int): Int? {
        var i = from.coerceAtLeast(0)
        while (i < list.size) { if (eligible(list[i])) return i; i++ }
        return null
    }

    private fun prevEligible(list: List<ReadItem>, from: Int): Int? {
        var i = from.coerceAtMost(list.lastIndex)
        while (i >= 0) { if (eligible(list[i])) return i; i-- }
        return null
    }

    /** Toolbar button / P: start on the active pane's page, else pause / resume. */
    fun toggleReading() {
        val t = _state.value.tts
        when (t?.status) {
            null -> {
                val l = _state.value.layout
                startReading(l.active, l.panes[l.active].page)
            }
            TtsStatus.PLAYING, TtsStatus.STARTING -> pauseReading()
            TtsStatus.PAUSED -> resumeReading()
        }
    }

    /** Pane menu: start at the sentence under the long-press. */
    fun readFrom(pane: Int, page: Int, at: PPoint) {
        _state.update { it.copy(menu = null) }
        startReading(pane, page, at)
    }

    private fun startReading(pane: Int, page: Int, at: PPoint? = null) {
        if (page !in 0 until _state.value.pageCount) return
        val gen = ++ttsGeneration
        ttsJob?.cancel()
        ensureReader().stop()
        _state.update { it.copy(tts = TtsState(TtsStatus.STARTING, pane, page, 0, 0, emptyList()), menu = null) }
        session?.let { s ->
            if (figureIndexJob == null) figureIndexJob = viewModelScope.launch { runCatching { ensureCatalog(s).scanAll() } }
        }
        ttsJob = viewModelScope.launch {
            // Toolbar play on a drawing sheet: look ahead for the next page of prose.
            // An explicit "read from here" reads whatever is under the finger.
            var p = page
            var pl = plan(p)
            if (at == null) {
                while (pl != null && !hasProse(pl) && p - page < MAX_SKIP && p + 1 < _state.value.pageCount) pl = plan(++p)
            }
            if (gen != ttsGeneration) return@launch
            if (pl == null || pl.bodySentences.isEmpty()) {
                stopReading()
                toast("No readable text on this page")
                return@launch
            }
            if (p != page) {
                toast("No text on page ${page + 1}; reading from page ${p + 1}")
                showReadingPage(pane, p)
            }
            val index = if (at != null && p == page) {
                pl.withLeadingNotes(pl.bodyIndexToItem(SentenceSplitter.indexAt(pl.bodySentences, at, slop = 4f)))
            } else 0
            speakAt(pane, p, index, pl.items, gen, TtsStatus.PLAYING)
        }
    }

    override fun pauseReading() {
        val t = _state.value.tts ?: return
        if (t.status == TtsStatus.PAUSED) return
        ++ttsGeneration
        ttsJob?.cancel()
        reader?.stop()
        _state.update { it.copy(tts = t.copy(status = TtsStatus.PAUSED)) }
    }

    override fun resumeReading() {
        val t = _state.value.tts ?: return
        if (t.status != TtsStatus.PAUSED) return
        restartAt(t.pane, t.page, t.index, TtsStatus.PLAYING)
    }

    override fun stopReading() {
        ++ttsGeneration
        ttsJob?.cancel()
        reader?.stop()
        spokenFigures.clear()
        if (_state.value.tts != null) _state.update { it.copy(tts = null) }
    }

    /** Previous / next item ([dir] = -1 / +1), crossing page boundaries. */
    override fun skipSentence(dir: Int) {
        val t = _state.value.tts ?: return
        val status = if (t.status == TtsStatus.PAUSED) TtsStatus.PAUSED else TtsStatus.PLAYING
        val gen = ++ttsGeneration
        ttsJob?.cancel()
        reader?.stop()
        ttsJob = viewModelScope.launch {
            val pl = plan(t.page) ?: return@launch
            val list = pl.items
            if (dir < 0) {
                val prev = prevEligible(list, t.index - 1)
                if (prev != null) {
                    speakAt(t.pane, t.page, prev, list, gen, status, backward = true)
                    return@launch
                }
                var p = t.page - 1
                while (p >= 0) {
                    val pp = plan(p)
                    if (gen != ttsGeneration) return@launch
                    if (pp != null && pp.bodySentences.isNotEmpty()) {
                        showReadingPage(t.pane, p)
                        speakAt(t.pane, p, pp.items.lastIndex, pp.items, gen, status, backward = true)
                        return@launch
                    }
                    p--
                }
                speakAt(t.pane, t.page, 0, list, gen, status)
            } else {
                val next = nextEligible(list, t.index + 1)
                if (next != null) speakAt(t.pane, t.page, next, list, gen, status)
                else advancePage(t.pane, t.page, gen, status)
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        val r = (rate.coerceIn(0.5f, 2f) * 10).roundToInt() / 10f
        updatePrefs { it.copy(speechRate = r) }
        reader?.setRate(r)
        // The engine applies the rate to later utterances only; restart the sentence to hear it now.
        val t = _state.value.tts
        if (t != null && t.status == TtsStatus.PLAYING) restartAt(t.pane, t.page, t.index, TtsStatus.PLAYING)
    }

    fun changeSpeechRate(delta: Float) = setSpeechRate(_state.value.prefs.speechRate + delta)

    /** Re-reads [page] from item [index] (or only moves the marker there when paused). */
    private fun restartAt(pane: Int, page: Int, index: Int, status: TtsStatus) {
        val gen = ++ttsGeneration
        ttsJob?.cancel()
        reader?.stop()
        ttsJob = viewModelScope.launch {
            val pl = plan(page)
            if (gen != ttsGeneration) return@launch
            if (pl == null || pl.bodySentences.isEmpty()) {
                _state.update { s ->
                    s.copy(tts = s.tts?.copy(status = TtsStatus.PAUSED, pane = pane, page = page, index = 0, count = 0, quads = emptyList(), label = null))
                }
                if (pl != null) toast("No text on this page")
                return@launch
            }
            speakAt(pane, page, index, pl.items, gen, status)
        }
    }

    /**
     * Points the reader at the first eligible item at or after [index] (before, when
     * [backward]) and, unless paused, speaks it and queues the next one.
     */
    private fun speakAt(pane: Int, page: Int, index: Int, list: List<ReadItem>, gen: Int, status: TtsStatus, backward: Boolean = false) {
        val i = (if (backward) prevEligible(list, index) else nextEligible(list, index))
            ?: nextEligible(list, 0)
            ?: run { advancePage(pane, page, gen, status); return }
        val item = list[i]
        val r = ensureReader()
        val shown = when {
            status == TtsStatus.PAUSED -> TtsStatus.PAUSED
            r.isReady -> TtsStatus.PLAYING
            else -> TtsStatus.STARTING
        }
        _state.update { it.copy(tts = TtsState(shown, pane, page, i, list.size, item.quads, (item as? FigureNote)?.key?.spoken)) }
        if (status == TtsStatus.PAUSED) return
        r.speak(utteranceId(gen, page, i), textFor(item, r), flush = true)
        nextEligible(list, i + 1)?.let { j -> r.speak(utteranceId(gen, page, j), textFor(list[j], r), flush = false) }
    }

    private fun textFor(item: ReadItem, r: SpeechReader): String = when (item) {
        is Body -> SentenceSplitter.spoken(item.sentence.text)
        is FigureNote -> {
            val fig = item.figure ?: catalog?.resolveCached(item.key)
            val detail = if (fig != null) describer.textFor(fig, _state.value.prefs.figureMode, null) else ""
            "${item.key.spoken}. $detail"
        }
    }.take(r.maxLength)

    private fun prefetchDescriptions(plan: ReadingPlan) {
        val prefs = _state.value.prefs
        if (prefs.figureMode != FigureMode.CLAUDE || prefs.claudeApiKey.isBlank()) return
        plan.items.forEachIndexed { idx, item ->
            if (item is FigureNote) item.figure?.let { describer.request(it, plan.referencingSentence(idx)) }
        }
    }

    private fun utteranceId(gen: Int, page: Int, index: Int) = "$gen:$page:$index"

    private fun parseId(id: String): Triple<Int, Int, Int>? {
        val parts = id.split(':')
        if (parts.size != 3) return null
        val gen = parts[0].toIntOrNull() ?: return null
        val page = parts[1].toIntOrNull() ?: return null
        val index = parts[2].toIntOrNull() ?: return null
        return Triple(gen, page, index)
    }

    /** Moves [pane] to [page] and the reader with it in one update, so [syncReadingToLayout] sees no mismatch. */
    private fun showReadingPage(pane: Int, page: Int) {
        _state.update { s ->
            val next = Navigator.goTo(s.layout, pane, page, s.pageCount).copy(active = pane)
            s.copy(
                layout = next,
                tts = s.tts?.copy(pane = pane, page = page),
                selection = s.selection?.takeIf { sel -> next.panes[sel.pane].page == sel.page },
                linkChip = s.linkChip?.takeIf { c -> next.panes[c.pane].page == c.page },
            )
        }
    }

    /** After the last item of [fromPage]: continue on the next page that has prose. */
    private fun advancePage(pane: Int, fromPage: Int, gen: Int, status: TtsStatus) {
        ttsJob = viewModelScope.launch {
            val n = _state.value.pageCount
            var p = fromPage + 1
            var pl: ReadingPlan? = null
            var skipped = 0
            while (p < n) {
                pl = plan(p)
                if (gen != ttsGeneration) return@launch
                if (pl == null || hasProse(pl) || skipped >= MAX_SKIP) break
                skipped++
                p++
            }
            if (pl == null || pl.bodySentences.isEmpty() || p >= n) {
                stopReading()
                toast(if (p >= n) "End of document" else "No more text found")
                return@launch
            }
            if (skipped > 0) toast("Skipped $skipped page(s) without text")
            val l = _state.value.layout
            val spread = l.mode == ViewMode.DUAL && Navigator.isSpread(l)
            val visibleIn = if (spread) l.panes.indexOfFirst { it.page == p } else -1
            val target = when {
                visibleIn >= 0 -> visibleIn // the other half of the spread: just move the marker
                spread -> 0 // turn the spread and read its left page first
                else -> pane
            }
            if (visibleIn >= 0) {
                _state.update { s -> s.copy(layout = s.layout.copy(active = target), tts = s.tts?.copy(pane = target, page = p)) }
            } else {
                showReadingPage(target, p)
            }
            speakAt(target, p, 0, pl.items, gen, status)
        }
    }

    /** A manual page turn in the reading pane restarts reading at the top of the new page. */
    private fun syncReadingToLayout() {
        val st = _state.value
        val t = st.tts ?: return
        val shown = st.layout.panes[t.pane].page
        if (shown != t.page) {
            restartAt(t.pane, shown, 0, if (t.status == TtsStatus.PAUSED) TtsStatus.PAUSED else TtsStatus.PLAYING)
        }
    }

    private fun onSpeechEvent(e: SpeechReader.Event) {
        when (e) {
            SpeechReader.Event.Ready -> reader?.let { publishVoices(it) }
            SpeechReader.Event.InitFailed -> {
                _state.update { it.copy(tts = null) }
                toast("No text-to-speech engine available (Settings → Accessibility → Text-to-speech output)")
            }
            is SpeechReader.Event.Started -> {
                val (gen, page, i) = parseId(e.id) ?: return
                if (gen != ttsGeneration) return
                val list = planCache[page]?.items ?: return
                if (i !in list.indices) return
                val item = list[i]
                if (item is FigureNote) spokenFigures += item.key
                _state.update { st ->
                    val t = st.tts ?: return@update st
                    st.copy(tts = t.copy(status = TtsStatus.PLAYING, page = page, index = i, count = list.size, quads = item.quads, label = (item as? FigureNote)?.key?.spoken))
                }
                // Keep one item queued behind the current one so there is no gap.
                val r = ensureReader()
                val j = nextEligible(list, i + 1)
                if (j == null) viewModelScope.launch { runCatching { plan(page + 1) } }
                else nextEligible(list, j + 1)?.let { k -> r.speak(utteranceId(gen, page, k), textFor(list[k], r), flush = false) }
            }
            is SpeechReader.Event.Done -> {
                val (gen, page, i) = parseId(e.id) ?: return
                if (gen != ttsGeneration) return
                val list = planCache[page]?.items ?: return
                val t = _state.value.tts ?: return
                if (nextEligible(list, i + 1) == null) advancePage(t.pane, page, gen, TtsStatus.PLAYING)
            }
            is SpeechReader.Event.Stopped -> {}
            is SpeechReader.Event.Error -> {
                if (e.id == PREVIEW_ID) { toast("That voice failed to speak (error ${e.code})"); return }
                val gen = e.id?.let { parseId(it)?.first }
                if (gen != null && gen != ttsGeneration) return
                stopReading()
                toast("Speech failed (error ${e.code})")
            }
        }
    }

    // ---------------------------------------------------------------- read-aloud settings

    fun setReadAloudPrefs(mode: FigureMode, apiKey: String, model: String, voiceName: String) {
        updatePrefs { it.copy(figureMode = mode, claudeApiKey = apiKey.trim(), claudeModel = model, voiceName = voiceName) }
        reader?.let { it.setVoice(voiceName); it.setRate(_state.value.prefs.speechRate) }
        claudeClient = null
        // Plans built under the old mode may lack prefetches; they are cheap to rebuild.
        planCache.clear()
        dismissDialog()
    }

    /** Settings "Cancel": undo any preview voice/rate and close. */
    fun cancelReadAloudSettings() {
        val p = _state.value.prefs
        reader?.let { it.setVoice(p.voiceName); it.setRate(p.speechRate) }
        dismissDialog()
    }

    /** Settings "Test": one tiny request with the key and model as typed. */
    fun testClaude(apiKey: String, model: String) {
        val key = apiKey.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            toast("Testing $model…")
            when (val r = SdkClaudeClient(key, model).ping()) {
                is ClaudeResult.Ok -> toast("Claude OK ($model)")
                ClaudeResult.Refused -> toast("Claude answered with a refusal; the key works")
                is ClaudeResult.Failed -> toast("Claude test failed: ${r.reason}")
            }
        }
    }

    fun clearFigureDescriptions() {
        viewModelScope.launch {
            describer.clear()
            toast("Forgot this document's figure descriptions")
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
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                toggleReading(); return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { stopReading(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { skipSentence(+1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { skipSentence(-1); return true }
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
            KeyEvent.KEYCODE_P -> if (shift) stopReading() else toggleReading()
            KeyEvent.KEYCODE_COMMA -> if (shift) changeSpeechRate(-0.1f) else skipSentence(-1)
            KeyEvent.KEYCODE_PERIOD -> if (shift) changeSpeechRate(+0.1f) else skipSentence(+1)
            KeyEvent.KEYCODE_ESCAPE -> when {
                st.linkChip != null -> dismissLinkChip()
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
        if (ReadAloudService.controller === this) ReadAloudService.controller = null
        reader?.shutdown()
        reader = null
        val s = session ?: return
        session = null
        // viewModelScope is already cancelled here; close on the MuPDF thread directly.
        CoroutineScope(PdfSession.dispatcher).launch { runCatching { s.close() } }
    }

    companion object {
        private const val TAG = "MultiViewPDF"
        private const val PREVIEW_ID = "preview"
        private const val PLAN_CACHE = 6
        /** Pages of drawings to pass over before giving up on finding prose (patents have many sheets). */
        private const val MAX_SKIP = 12
    }
}
