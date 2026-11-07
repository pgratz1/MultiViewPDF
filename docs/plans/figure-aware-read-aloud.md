# Plan: figure-aware read-aloud (skip figure contents, describe figures at first reference)

## Context

Read-aloud (shipped 2026-11-06) walks a page's text in MuPDF block order. On two-column research papers such as the sample in the repo root, `2027_ISCA__ORAP_Paper (1).pdf` (pdfTeX, ACM format, 13 pages, 17 figures, 2 tables), it follows the columns correctly but also reads, in place: figure captions and sub-captions ("(a) PRAC-based mitigation…"), chart tick labels and table cells where they have a text layer, the running header on every page ("Optimizing Hardware Prefetchers… / ISCA '27, June…"), the ACM copyright block, and 4-digit submission line numbers (the current numeric filter only drops 1–3 digits).

The user wants figures and tables handled the way a human reader would: skip their contents in place, and when the body text first refers to "Figure 3" or "Table 1", say what that figure shows, right after the referencing sentence. (Changed 2026-11-07: the note now comes *before* the referencing sentence, the way a reader glances at the figure first.) The description is the caption by default, or a Claude-generated description of the rendered figure when an API key is configured.

Decisions made with the user:
- **Claude model:** `claude-opus-5-5`, low effort. Each figure is described once per document and cached on disk (about one cent per figure; the sample costs ~$0.20 once).
- **API key:** pasted into an in-app settings dialog, stored in the app's private prefs JSON, masked in the UI, never logged.
- **Tables** are handled exactly like figures. **Running headers, footers and page numbers** are skipped. **Footnotes with content are read**; copyright/boilerplate and bibliography small print are skipped.
- **Cross-page references:** describe the figure anyway, don't move either pane.
- Decided by me, flagged: the 7-pt **References** list at the end is skipped (bibliography entries match `^\[\d+\]`); say "end of document" when the last body text is done. Figures are described at their **first** mention per reading session (`stopReading` resets); later mentions read as plain text.

Facts measured on the sample (page 612×792 pt, body 9.0 pt, columns x≈53–296 and 318–560), verified with `mutool`:
- Captions are their own block, first line `^(Figure|Fig\.|Table) \d+:`; 2–7 lines at body size. **Figure captions sit below the figure; this paper's table captions also sit below the tables** (page 7: Table 1 cells 6.7 pt at y 87–226, caption at y 231). Direction must be inferred from evidence, not from kind.
- Sub-captions "(a) …" are 8 pt (0.89× body), just above the caption. Running header 7 pt, 2 lines, y 62–69. Page number at y 715 (90% of the height). Copyright block 7 pt, 3 lines, bottom-left of page 1. Section headings 10.9 pt, 1–2 lines. Body blocks ≥3 lines, 9–11 words/line.
- Most figure bodies here have no text layer (outlined vectors); page 3's right column has only the header and then the Figure 3 caption at y 230. Bar charts in other papers do carry tick labels.
- MuPDF 1.28.5 Java API: `Page.toStructuredText(options)`, `StructuredText.walk()` with `onImageBlock`, `onVector`, `onChar(…, float size, …)`. The 1.28.5 native library does **not** list the `collect-vectors` option (it lists `preserve-images`, `segment`, `table-hunt`, `dehyphenate`, …), so vector bounding boxes cannot be relied on. Regions come from text geometry; image blocks are a refinement.
- Anthropic Java SDK current release is 3.6.0 (OkHttp + Jackson + kotlin-reflect, Java 8+, ships R8 keep rules). Kotlin uses this SDK per the claude-api skill. Image input = `ImageBlockParam` with `Base64ImageSource` (`MediaType.IMAGE_PNG`). Opus 5.5: never send `.thinking(...)`; cost control via `OutputConfig.effort(LOW)`; check `stopReason() == REFUSAL`.

All code paths below are relative to `app/src/main/java/com/pgratz/multiviewpdf/`.

## Files

New:
- `model/Layout.kt` — block classification, caption parsing, region detection → `PageLayout`. Pure Kotlin.
- `model/Figures.kt` — `FigureKey`, `Figure`, `FigureRefs` (reference parser), `ReadItem` (`Body` / `FigureNote`), `ReadingPlans.build`. Pure Kotlin.
- `tts/FigureDescriber.kt` — mode logic (NONE / CAPTION / CLAUDE), memory + disk cache, prefetch, caption fallback.
- `tts/ClaudeClient.kt` — `interface ClaudeClient` + `SdkClaudeClient` (Anthropic Java SDK). `HttpClaudeClient` (raw `HttpURLConnection` + kotlinx.serialization) only if the SDK fails on Android.
- Tests `app/src/test/java/com/pgratz/multiviewpdf/LayoutTest.kt`, `FigureRefsTest.kt`, `ReadingPlanTest.kt`.

Modified:
- `model/Sentences.kt` — `NUMERIC_LINE` → `^\d{1,4}$`; no API change (`split(page, blockFilter)` is reused with `layout::isReadable`), so existing tests stay green.
- `model/PageText.kt` — `TextBlock.bounds`, `PRect.overlapsX`, `PRect.center`.
- `model/Prefs.kt` — `figureMode: FigureMode = CAPTION`, `claudeApiKey = ""`, `claudeModel = "claude-opus-5-5"`.
- `pdf/PdfSession.kt` — `pageImages(i): List<PRect>` (walker `onImageBlock`, best-effort, empty on failure), `renderRegion(i, region, maxPixels): Bitmap`.
- `pdf/PrefsStore.kt` — `loadFigureDescriptions(fp)` / `saveFigureDescriptions(fp, …)` under `prefs/figures/`.
- `ViewerViewModel.kt` — read-aloud section: `sentenceCache` → `planCache` of `ReadingPlan`, eligibility skipping, `FigureCatalog`, prefetch, `Dialog.ReadAloudSettings`, `setReadAloudPrefs`, `testClaude`, `clearFigureDescriptions`.
- `ui/Dialogs.kt` (`ReadAloudSettingsDialog`), `ui/ViewerScreen.kt` (overflow item + `when` branch), `AndroidManifest.xml` (`INTERNET`; exclude `prefs/app.json` from backup), `gradle/libs.versions.toml` + `app/build.gradle.kts` (`com.anthropic:anthropic-java:3.6.0`), `app/src/androidTest/.../PdfSessionTest.kt`, `CLAUDE.md`.

## Data types

```kotlin
// model/Layout.kt
enum class BlockClass { BODY, HEADING, CAPTION, SUB_CAPTION, FOOTNOTE, SMALL_PRINT, HEADER_FOOTER, NUMERIC, IN_FIGURE, EMPTY }
data class ClassifiedBlock(val block: TextBlock, val bounds: PRect, val fontSize: Float, val cls: BlockClass)
enum class FigureKind { FIGURE, TABLE }
data class Caption(val kind: FigureKind, val number: Int, val text: String, val subCaptions: List<String>,
                   val bounds: PRect, val region: PRect) { val extent get() = region.union(bounds) }
data class PageLayout(val page: Int, val pageBounds: PRect, val bodySize: Float,
                      val blocks: List<ClassifiedBlock>, val captions: List<Caption>) { fun isReadable(b: TextBlock): Boolean }
object Layout { fun analyze(page: Int, text: PageText, pageBounds: PRect, images: List<PRect> = emptyList()): PageLayout }

// model/Figures.kt
data class FigureKey(val kind: FigureKind, val number: Int) { val spoken get() = "${kind.label} $number" }
data class Figure(val key: FigureKey, val page: Int, val caption: Caption)
object FigureRefs { fun find(sentence: String): List<FigureKey> }   // ordered, de-duplicated
sealed interface ReadItem { val quads: List<PQuad> }
data class Body(val sentence: Sentence) : ReadItem
data class FigureNote(val key: FigureKey, val figure: Figure?, val page: Int, override val quads: List<PQuad>) : ReadItem
data class ReadingPlan(val page: Int, val layout: PageLayout, val items: List<ReadItem>) { val bodySentences; fun bodyIndexToItem(i: Int): Int }
object ReadingPlans { fun build(layout: PageLayout, sentences: List<Sentence>, resolve: (FigureKey) -> Figure?): ReadingPlan }
```
A `FigureNote` carries no text: the ViewModel resolves the spoken text at speak time (a Claude description may arrive later), so item indices stay stable for the `gen:page:index` utterance ids.

## Layout algorithm (`Layout.analyze`)

Per block: `bounds` = union of line bounds, `fontSize` = median char-quad height, line and word counts.
1. **Body size** = char-weighted median font size over blocks with ≥3 lines (fallback: all blocks).
2. **Classify, first match wins:** EMPTY → NUMERIC (every line `^[\d.,%−–-]+$`: gutter numbers, page numbers, bare tick labels) → CAPTION (first line `^(Figure|Fig\.|FIGURE|FIG\.|Table|TABLE)\s*(\d+)\s*[:.|–—-]`) → HEADER_FOOTER (≤2 lines and entirely within the top 9% or below 90% of the page height, unless fontSize ≥ 1.3×body) → HEADING (fontSize ≥ 1.1×body, ≤2 lines; kept) → small print (fontSize < 0.85×body), split into **FOOTNOTE** (kept) when it is prose (≥5 words/line average) that does not match boilerplate `ACM ISBN|doi\.org|©|Permission to make|licensed under|Copyright|^\[\d+\]` and sits in the bottom third of a column, else **SMALL_PRINT** (skipped) → SUB_CAPTION (first line `^\([a-z]\)\s`, ≤4 lines) → BODY. Unknown stays readable.
3. **Caption merge:** absorb the next block in y order with ≥80% x-overlap, top within 1.2 line pitches of the caption bottom, same size ±0.5, not CAPTION/HEADING/NUMERIC. (Caption→body gaps on the sample are 33–60 pt vs. an 11-pt pitch.)
4. **Boundaries** = bounds of BODY, HEADING and other CAPTION blocks plus the header/footer bands. Column = the caption's x-span widened to the body-column edges that overlap it; a full-width caption spans both columns.
5. **Region candidates** per caption: *above* (from the lowest boundary that overlaps the column and ends above the caption, or the header band, down to the caption top) and *below* (caption bottom to the first boundary below, or the footer band). Score: +2 per SMALL_PRINT/NUMERIC/SUB_CAPTION block whose centre lies inside, +2 per image rect overlapping ≥50%, +1 if height ≥ 40 pt, reject below 15 pt. Higher score wins; ties → above for FIGURE, below for TABLE. On the sample both tables pick *above* on evidence.
6. **Containment:** every non-BODY/HEADING block whose centre lies inside a region → IN_FIGURE (sub-captions, tick labels, table cells); sub-caption texts are collected into `Caption.subCaptions`. A BODY-class block inside a region stays readable in v1 (logged).
7. **Readable** = BODY + HEADING + FOOTNOTE + unknowns, in MuPDF order (column order already correct). Footnotes are read where they occur (end of the column).

Edge cases covered: two captions in one column (the upper caption's bottom bounds the lower one's region), full-width figure above a column, caption-only pages (region reaches the header band), figures with no text layer, table captions below, captions split across blocks, "Figure 2b" letter suffixes, a false "Table 128" (validated against the catalog). Optional refinement if regions look loose on the emulator: render the region at ~1 px/pt and trim blank rows/columns (reuse the ink threshold logic from `AutoCrop`).

## References and the reading plan

`FigureRefs.find`: `(?<![A-Za-z])(Figures?|Figs?\.|FIGS?\.|Tables?|TABLES?)\s*(\d{1,3})[a-z]?` followed by optional ranges/lists (`–`, `,`, `and`, `to`, `through`); expand ranges (≤10 steps), drop letters, de-duplicate in order. "Fig- ure 8" is already "Figure 8" after the splitter's de-hyphenation. Captions never reach the parser (not readable), and `build` additionally ignores sentences matching the caption regex.

`ReadingPlans.build`: each sentence → `Body`; after a sentence, append a `FigureNote` for each referenced key not yet noted on this page, in number order; `quads = listOf(PQuad.of(figure.caption.extent))` when the figure is on this page, else empty (cross-page: no view change). Unresolved keys get `figure = null` and are re-resolved at speak time; still unknown → skipped silently.

**FigureCatalog** (ViewModel-owned): `layouts: Map<Int, PageLayout>`, `figures: Map<FigureKey, Figure>`, scanned-page set. `resolve(key)` scans outward from the current page (p, p+1, p−1, p+2, …) via `layout(p)` = `session.pageText(p)` + `bounds(p)` + `pageImages(p)` (MuPDF thread) + `Layout.analyze` (Default dispatcher) until found or 40 pages tried; a background job started at first play scans the whole document with `yield()` between pages, cancelled on close.

## ViewModel changes (read-aloud section)

- `sentences(page)` → `plan(page)`: layout, `SentenceSplitter.split(text, layout::isReadable)`, `ReadingPlans.build(layout, sentences, catalog::resolve)`, then `prefetchDescriptions(plan)`. `planCache` LRU 6. `hasProse` uses `plan.bodySentences`; `indexAt` runs on `bodySentences` and maps through `bodyIndexToItem`.
- **Eligibility:** `spokenFigures: MutableSet<FigureKey>` per session. `Body` always eligible; `FigureNote` eligible when `prefs.figureMode != NONE`, key not yet spoken, and resolved. `nextEligible(list, from)` / `prevEligible` return null at the ends.
  - `speakAt`: `i = nextEligible(list, index) ?: return advancePage(...)`; state uses `list[i].quads`; speak `i`, queue `j = nextEligible(list, i + 1)`.
  - `Started(i)`: mark a `FigureNote`'s key spoken; queue the item after the one already queued; if nothing follows, prefetch `plan(page + 1)`.
  - `Done(i)`: if `nextEligible(list, i + 1) == null` → `advancePage`.
  - `skipSentence`: step with `nextEligible`/`prevEligible`; null → existing cross-page logic.
  - Generation handling is untouched; a description arriving only mutates the describer cache.
- `textFor(item)`: `Body` → `SentenceSplitter.spoken(text)`; `FigureNote` → `"${key.spoken}. " + describer.textFor(...)` (CAPTION: caption + sub-captions; CLAUDE: cached description, else caption now and start a request if none is in flight). Truncate to `reader.maxLength`.
- `TtsState` gains `isFigure: Boolean` so the bar can show "Figure 3" instead of "12/40".
- `stopReading()` clears `spokenFigures`; `open()`/`closeSession()` reset catalog, `planCache`, describer memory cache and the index job. `pauseReading()` keeps them.
- `prefetchDescriptions(plan)`: CLAUDE mode with a key → for each distinct note key on the page not cached or in flight, `describer.request(...)`, concurrency 2 (`Semaphore`). Region render on the MuPDF dispatcher, PNG + base64 on Default, network on IO.
- Toasts: one "Claude unavailable, reading captions instead" per session; "API key rejected" on 401.

## FigureDescriber / ClaudeClient

- `enum class FigureMode { NONE, CAPTION, CLAUDE }`. Disk cache `prefs/figures/<fingerprint>.json`: `{ "figure-3": { text, model, at } }`.
- `interface ClaudeClient { suspend fun describe(req): ClaudeResult; suspend fun ping(): ClaudeResult }` with `Ok(text) | Refused | Failed(reason, retryable)`.
- `SdkClaudeClient` (anthropic-java 3.6.0): `AnthropicOkHttpClient.builder().apiKey(key).maxRetries(1).timeout(Duration.ofSeconds(60)).build()` built off the main thread and rebuilt when key/model change. Request: `MessageCreateParams.builder().model(prefs.claudeModel).maxTokens(600L).outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build()).system(SYSTEM).addUserMessageOfContentBlockParams(listOf(ContentBlockParam.ofImage(ImageBlockParam.builder().source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_PNG).data(b64).build()).build()), ContentBlockParam.ofText(TextBlockParam.builder().text(user).build())))`. No `.thinking(...)`. Confirm exact names with `jar tf` / the compiler. Response: `REFUSAL` → `Refused`; concatenate text blocks; if `MAX_TOKENS`, cut at the last sentence end; strip markdown characters. Errors most specific first: `UnauthorizedException` → `RateLimitException` → `AnthropicServiceException` → `AnthropicIoException` → `Exception`; all fall back to the caption; log class and status only. The claude-api skill suggests the server-side `fallbacks` beta for refusals on Opus 5.5; here a refusal already falls back to the caption locally, so leave it out (comment says so).
- Render: `PdfSession.renderRegion(i, caption.extent, maxPixels = 1_200_000)` with `PageTransform(extent, 0, scale)`; include the caption in the image. `Bitmap.compress(PNG, 100)`, `java.util.Base64` (no newlines). Expect ~1,600 input tokens per figure.
- System prompt: "You describe figures and tables from research papers for someone who is hearing the paper read aloud and cannot see the page. Answer with two to four plain sentences of spoken prose: no markdown, lists, headings or preamble. Begin with what kind of figure it is and its main takeaway, then the key comparison or trend. Mention specific numbers only when they carry the point. Use the caption and the sentence that referenced the figure for context, and do not repeat the caption word for word. Do not describe colours or styling unless they are needed to convey the result. If the image is unreadable, say what the caption implies instead." User text after the image: "Figure 3 of the paper. Caption: … Sub-captions: … The text referred to it in this sentence: … Describe it for the listener."
- If the SDK will not run on Android (dex failure, Jackson version check, kotlin-reflect clash): `HttpClaudeClient`, POST `https://api.anthropic.com/v1/messages`, headers `x-api-key`, `anthropic-version: 2023-06-01`, JSON body with `output_config: {effort: "low"}` and the image+text content, same result mapping. Record which client is in use in CLAUDE.md.

## Settings UI

Overflow menu "Read-aloud settings…" → `Dialog.ReadAloudSettings` → `ReadAloudSettingsDialog` (pattern: `AuthorDialog`): radios Skip figures / Read captions (default) / Describe with Claude; API key `OutlinedTextField` with `PasswordVisualTransformation` and a show toggle; model radios `claude-opus-5-5` (default) / `claude-haiku-5-5`; "Test" button → `vm.testClaude()` → toast; "Forget cached descriptions for this document"; Save / Cancel. Persisted via `updatePrefs`.

## Tests

- `LayoutTest`: fixture `blk(x0, y0, size, lines)` builds `TextLine`s with `size`-tall, `0.5·size`-wide char quads at the measured coordinates. Page 3: header → HEADER_FOOTER, 3- and 4-digit gutter → NUMERIC, "(a)/(b)" → IN_FIGURE, Figure 2 region ≈ x 53–296 y 69–218, Figure 3 region ≈ x 318–560 y 69–230, body and "2.4 Hardware Prefetching" readable in order. Page 1: copyright → SMALL_PRINT, title and "Abstract" readable, Figure 1 region bounded by the 12-pt submission line. Page 5: Figure 8 region stops at Figure 7's caption. Page 7: Table 1 caption below → region chosen above, 6.7-pt cells IN_FIGURE. Footnote fixture: 7-pt prose "1 This holds for…" at a column bottom → FOOTNOTE (read); "[12] Author…" → SMALL_PRINT. Caption split into two blocks merges; a body paragraph 30 pt below does not.
- `FigureRefsTest`: "Figures 3 and 4", "Figs. 2–4", "Figure 2b", "Table 1", "FIG. 3", caption text excluded by `build`, "Section 3" no match, "Table 128" rejected by the catalog.
- `ReadingPlanTest`: note after the referencing sentence; multiple refs ordered; second mention on the page gets no note; empty quads for a figure on another page; unresolved key → `figure = null`.
- `SentenceSplitterTest`: `lineNumberGutterIsDropped` gains a 4-digit line.
- Instrumented: generated PDF with a paragraph, a small "Figure 1: …" line and a drawn image → `pageImages(0)` returns without throwing, `renderRegion` returns ≤1.2 MP at the requested aspect.

## Verification (emulator `emulator-5554`, then the Pixel)

1. `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`; `./gradlew testDebugUnitTest`; `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`.
2. Push the sample paper to the emulator (`run-as` copy as in CLAUDE.md), open it, go to page 3, press `P`: reading starts at "PRAC, while PRAC-based…" with no header and no "(a)/(b)" text; at "As shown in Figure 3" the caption is spoken once with Figure 3's region highlighted; the second mention reads as plain text; page 7's Table 1 is not read cell by cell and "Table 1" in the text triggers its caption; `,` / `.` step over notes; page 1's copyright block is silent while "Abstract" is read.
3. Settings: enter the API key (the user supplies it at test time), pick Opus 5.5, Test → "Claude OK"; play from page 3: logcat shows one request per figure (`claude figure-3 p3 …`), the description is spoken at the first reference, the cache file appears under `files/prefs/figures/`; a second play shows cache hits only. With a wrong key: "API key rejected", captions read instead. Airplane mode: caption fallback, one toast.
4. Screenshots via `adb exec-out screencap -p` for the highlight and the settings dialog; install on the Pixel (`adb -s 59180DLCR001R9 install -r`) and let the user listen.

## Risks

- Font-size classes use quad heights; relative thresholds hold within a document but a paper with 8-pt captions or 10-pt body shifts ratios (captions are regex-detected, so only the small-print cut moves). Tune 0.85 if needed.
- Loose regions may include whitespace; the ink-trim refinement is ready.
- Charts with body-size labels outside the computed region would still be read; the numeric rule catches bare numbers.
- Anthropic SDK on Android: APK grows ~6–8 MB; Jackson/kotlin-reflect compatibility is the main unknown, with the raw-HTTP client as the fallback.
- The API key sits in plaintext in app-private storage; `allowBackup` exclusion keeps it out of Google backups.
- IEEE-style "TABLE I" / "Fig. 3." captions are outside the regex; follow-up.

## Ordered steps (each builds and tests)

1. `Layout.kt` + `PageText.kt` helpers + `LayoutTest` (pages 1/3/5/7 + footnote fixtures). Unit tests green.
2. `Figures.kt` (`FigureRefs`, `ReadItem`, `ReadingPlans.build`) + tests; `NUMERIC_LINE` 1–4 digits; existing splitter tests still green.
3. `PdfSession.pageImages/renderRegion`, `FigureCatalog`, ViewModel conversion to `ReadingPlan` with eligibility skipping, modes NONE/CAPTION only; `FigureMode` pref default CAPTION; instrumented test. Install and run the page-3 / page-7 / page-1 checks.
4. Settings dialog + prefs + `INTERNET` permission + overflow item (Test wired to a stub).
5. `FigureDescriber` with disk cache and the render → PNG → base64 pipeline against a logging stub client; one render per referenced figure in logcat; cache file written.
6. `SdkClaudeClient` on 3.6.0 (compile-fix loop on type names); Test button; emulator run with the user's key; fallbacks on bad key / airplane mode; cache hits on replay. Switch to `HttpClaudeClient` only if the SDK fails on Android.
7. Optional ink-trim refinement; CLAUDE.md status and design notes; commit and push.
