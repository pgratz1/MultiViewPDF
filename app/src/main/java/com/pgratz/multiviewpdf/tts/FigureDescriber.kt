package com.pgratz.multiviewpdf.tts

import android.graphics.Bitmap
import android.util.Log
import com.pgratz.multiviewpdf.model.Figure
import com.pgratz.multiviewpdf.model.FigureDescription
import com.pgratz.multiviewpdf.model.FigureDescriptions
import com.pgratz.multiviewpdf.model.FigureMode
import com.pgratz.multiviewpdf.model.Layout
import com.pgratz.multiviewpdf.model.SentenceSplitter
import com.pgratz.multiviewpdf.pdf.PrefsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * What to say for a figure note: nothing, the caption, or a Claude description of the
 * rendered figure. Descriptions are generated once per document and cached on disk;
 * while one is in flight (or when it fails) the caption is spoken instead.
 * Main-thread confined except for the work it dispatches itself.
 */
class FigureDescriber(
    private val store: PrefsStore,
    private val scope: CoroutineScope,
    private val render: suspend (Figure) -> Bitmap?,
    private val client: () -> ClaudeClient?,
    private val onFailure: (String) -> Unit,
) {
    private var fingerprint: String? = null
    private var cache = FigureDescriptions()
    private val inFlight = HashSet<String>()
    private val permits = Semaphore(2)
    private var warned = false

    suspend fun load(fp: String) {
        if (fingerprint == fp) return
        fingerprint = fp
        cache = store.loadFigureDescriptions(fp)
        inFlight.clear()
        warned = false
    }

    fun reset() {
        fingerprint = null
        cache = FigureDescriptions()
        inFlight.clear()
        warned = false
    }

    fun cached(fig: Figure): String? = cache.entries[fig.key.id]?.text

    /** The caption without its "Figure 3:" prefix, plus sub-captions, ready to speak. */
    fun captionText(fig: Figure): String {
        val body = fig.caption.text.replace(CAPTION_PREFIX, "").trim()
        val subs = fig.caption.subCaptions.joinToString(" ")
        return SentenceSplitter.spoken(listOf(body, subs).filter { it.isNotBlank() }.joinToString(" "))
    }

    /** Never suspends: returns what can be said right now and starts a request if needed. */
    fun textFor(fig: Figure, mode: FigureMode, sentence: String?): String = when (mode) {
        FigureMode.NONE -> ""
        FigureMode.CAPTION -> captionText(fig)
        FigureMode.CLAUDE -> cached(fig) ?: run {
            request(fig, sentence)
            captionText(fig)
        }
    }

    /** Generates and caches a description for [fig] unless one exists or is in flight. */
    fun request(fig: Figure, sentence: String?) {
        val fp = fingerprint ?: return
        val c = client() ?: return
        val id = fig.key.id
        if (cache.entries.containsKey(id) || !inFlight.add(id)) return
        scope.launch {
            val started = System.currentTimeMillis()
            try {
                permits.withPermit {
                    val bmp = render(fig) ?: return@withPermit
                    val png = withContext(Dispatchers.Default) {
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    }
                    bmp.recycle()
                    val req = DescribeRequest(fig.key.spoken, fig.caption.text, fig.caption.subCaptions, sentence, png)
                    when (val r = c.describe(req)) {
                        is ClaudeResult.Ok -> {
                            if (fingerprint != fp || r.text.isBlank()) return@withPermit
                            cache = cache.copy(entries = cache.entries + (id to FigureDescription(r.text, c.model, System.currentTimeMillis())))
                            store.saveFigureDescriptions(fp, cache)
                            Log.i(TAG, "claude $id p${fig.page + 1} model=${c.model} png=${png.size}B ${System.currentTimeMillis() - started}ms")
                        }
                        ClaudeResult.Refused -> Log.w(TAG, "claude $id refused; caption used")
                        is ClaudeResult.Failed -> {
                            Log.w(TAG, "claude $id failed: ${r.reason}")
                            if (!warned) {
                                warned = true
                                onFailure(r.reason)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "claude $id error", e)
            } finally {
                inFlight.remove(id)
            }
        }
    }

    /** Forgets the cached descriptions of the open document. */
    suspend fun clear() {
        fingerprint?.let { store.deleteFigureDescriptions(it) }
        cache = FigureDescriptions()
    }

    companion object {
        private const val TAG = "MultiViewPDF"
        private val CAPTION_PREFIX = Regex("""^\s*(Figure|Fig\.|FIGURE|FIG\.|Table|TABLE)\s*\d{1,3}[a-z]?\s*[:.|–—\-]\s*""")
        init { Layout.CAPTION } // keep the regexes side by side in mind
    }
}
