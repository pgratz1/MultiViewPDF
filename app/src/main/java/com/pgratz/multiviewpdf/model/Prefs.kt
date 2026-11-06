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
) {
    fun withRecent(doc: RecentDoc): AppPrefs =
        copy(recent = (listOf(doc) + recent.filter { it.uri != doc.uri }).take(MAX_RECENT))

    companion object {
        const val MAX_RECENT = 20
    }
}
