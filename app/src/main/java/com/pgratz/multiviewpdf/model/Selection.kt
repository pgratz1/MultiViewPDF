package com.pgratz.multiviewpdf.model

/**
 * An in-progress highlight on one pane. [a]/[b] are the selection ends in page space;
 * in [rectMode] (pages with no text layer, e.g. scanned patents) they are opposite
 * corners of a box instead.
 */
data class Selection(
    val pane: Int,
    val page: Int,
    val a: PPoint,
    val b: PPoint,
    val rectMode: Boolean,
    val quads: List<PQuad>,
    /** true: show handles and a ✓/✗ bar (long-press flow); false: commit on release (tool drag). */
    val confirm: Boolean,
)
