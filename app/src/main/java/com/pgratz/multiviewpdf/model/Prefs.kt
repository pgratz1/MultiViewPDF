package com.pgratz.multiviewpdf.model

import kotlinx.serialization.Serializable

@Serializable
data class RecentDoc(val uri: String, val name: String, val lastOpened: Long)

/** App-wide settings (per-document view state lives in [ViewLayout]). */
@Serializable
data class AppPrefs(
    val author: String = "",
    val highlightColor: Int = HighlightColors.palette[0],
    /** Light-on-dark page rendering; black is see-through on the glasses. */
    val invert: Boolean = false,
    val recent: List<RecentDoc> = emptyList(),
    /** Read-aloud speed; 1.0 is the engine's normal rate. */
    val speechRate: Float = 1f,
    /** What read-aloud says when the text first mentions a figure or table. */
    val figureMode: FigureMode = FigureMode.CAPTION,
    /** Anthropic API key for [FigureMode.CLAUDE]; app-private storage, never logged. */
    val claudeApiKey: String = "",
    val claudeModel: String = CLAUDE_DEFAULT_MODEL,
) {
    fun withRecent(doc: RecentDoc): AppPrefs =
        copy(recent = (listOf(doc) + recent.filter { it.uri != doc.uri }).take(MAX_RECENT))

    companion object {
        const val MAX_RECENT = 20
        const val CLAUDE_DEFAULT_MODEL = "claude-opus-5-5"
        val CLAUDE_MODELS = listOf("claude-opus-5-5" to "Claude Opus 5.5", "claude-haiku-5-5" to "Claude Haiku 5.5")
    }
}

@Serializable
enum class FigureMode { NONE, CAPTION, CLAUDE }

/** Per-document cache of generated figure descriptions, keyed by [FigureKey.id]. */
@Serializable
data class FigureDescriptions(val entries: Map<String, FigureDescription> = emptyMap())

@Serializable
data class FigureDescription(val text: String, val model: String, val at: Long)
