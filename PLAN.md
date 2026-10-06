# MultiViewPDF: plan for an AR-glasses-friendly Android PDF viewer

## Context
You want a PDF viewer to use with your AR glasses (Viture XR Pro + Pixel, the same setup as the sibling `AR_Touchpad` project). It needs:
- **Sticky notes and highlights** saved as standard PDF annotations, so Okular, Xodo, Acrobat and other readers can see them.
- **A two-page mode.** It can show two pages side by side, or lock either pane on one page (for example a patent figure) while the other pane moves through the text.
- **Independent rotation** for each pane, so a sideways figure can be rotated without rotating the text.
- **Whole-document cropping**, like Xodo or Okular's trim.

Your answers on the open questions:
- Touch and mouse/keyboard (desktop mode) should both get full support.
- Crop changes only the view. The app remembers it for each document and does not modify the PDF.
- Rotation is per pane and view-only.
- v1 annotations are sticky notes and highlights only.

`MultiViewPDF/` is empty. This will be a new standalone Gradle project with `gradlew` at its root, laid out like `AR_Touchpad`.

## Key technical decision: PDF engine = MuPDF (`com.artifex.mupdf:fitz`)
- Android's built-in `PdfRenderer` can only render pages. Its annotation editing is new and incomplete. PDFium wrappers for Android don't expose the annotation API, so using PDFium would mean writing our own JNI layer.
- MuPDF's Java API covers everything we need in one library:
  - fast rendering (`AndroidDrawDevice`)
  - a structured-text layer for selecting text (`StructuredText.highlight(a, b)` returns quads)
  - creating and editing annotations (`PDFPage.createAnnotation(TYPE_HIGHLIGHT / TYPE_TEXT)`, `setQuadPoints`, `setContents`, `setColor`)
  - standards-compliant saving (`PDFDocument.save(..., "incremental")`)
- Okular itself can use MuPDF-compatible annotation formats, so files will round-trip cleanly.
- **License caveat:** MuPDF is AGPL. That's fine for personal use or an open-source app. A closed-source app sold or distributed to others would need Artifex's commercial license.
- Get it from the Maven repo `https://maven.ghostscript.com`. Use the latest 1.2x release (confirm the exact version at scaffold time).

## Project setup
- Package `com.pgratz.multiviewpdf`, minSdk 31, compileSdk/targetSdk 35. ABIs: arm64-v8a, plus x86_64 so the emulator works.
- Kotlin + Jetpack Compose + Material3, with a single-activity MVVM structure. Copy the version catalog from `PaulsCalc/gradle/libs.versions.toml` (AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2025.02.00).
- Add these dependencies:
  - MuPDF fitz
  - `kotlinx-serialization-json` and DataStore, to save each document's view state
  - `lifecycle-viewmodel-compose`
- Manifest:
  - `resizeableActivity=true`, and handle window-size config changes, so the app works in desktop-mode windows on the glasses
  - an intent filter for `application/pdf` (VIEW), so the app appears in "Open with"
- Add a `CLAUDE.md` with build commands and an architecture summary, matching the sibling projects.

## Architecture (files under `app/src/main/java/com/pgratz/multiviewpdf/`)
```
MainActivity.kt              Handles the VIEW intent and the SAF picker; hosts Compose; routes hardware key events to the ViewModel
pdf/
  PdfSession.kt              Owns the MuPDF Document. All calls run on one dedicated single-thread dispatcher (MuPDF isn't thread-safe per doc)
  PageRenderer.kt            Renders a page region to a Bitmap at a given scale/rotation/crop; LRU bitmap cache; low-res base + hi-res visible tile when zoomed
  AnnotationRepo.kt          List/create/update/delete highlights and notes; maps MuPDF annots to app models
  DocumentStore.kt           Opens a content:// URI → working copy in cache dir; save = incremental save to the working copy, then stream back to the URI ("wt")
  AutoCrop.kt                Renders pages at low resolution, finds the non-white content bbox, takes the union across sampled pages
model/
  PaneState.kt               pageIndex, rotation (0/90/180/270), locked, zoom, pan
  ViewerState.kt             mode (Single | Dual), panes[2], activePane, linked, tool (Pan|Highlight|Note), cropMargins, invert
  PageTransform.kt           Pure math: page coords ⇄ screen coords given crop + rotation + zoom/pan (unit-tested)
  DocPrefs.kt                @Serializable per-document state (crop, pane pages/rotations/locks, mode), keyed by a content fingerprint (size + hash of first 64KB), so it survives URI changes
ViewerViewModel.kt           StateFlow<ViewerState>; navigation, lock, rotate, crop, and annotation intents; debounced autosave of DocPrefs
ui/
  ViewerScreen.kt            Top bar/toolbar, single or dual layout, status (page X / Y per pane, lock icons)
  PdfPane.kt                 One pane: draws the bitmap, overlays annotations/selection, handles gestures + mouse (pointer events)
  NoteDialog.kt              Create/edit/delete sticky-note text
  CropScreen.kt              Overlay to drag crop margins on a representative page + "Auto" button
  theme/…
```

## Feature design

### One-page and two-page modes
- **Single mode** shows one pane filling the window. It has the same zoom, rotation and crop controls as dual mode.
- **Dual mode** shows two panes side by side.
- Switch modes with a toolbar toggle, `1`/`2`, or the context menu. Switching keeps the active pane's page. Going back to dual mode restores the second pane's previous page and lock state.
- The last mode used is saved for each document.

### Two-page mode and locking
- The **Linked** setting is on by default with neither pane locked. The right pane always shows the left pane's page + 1, and next/prev moves both panes by 2, like turning a book's pages. A setting chooses odd or even pages on the left.
- **Lock a pane** (lock icon, `L`, or the long-press/right-click menu) to freeze that pane on its current page. Next/prev and swipes then move only the unlocked pane, by 1 page.
- Each pane also has its own **page jump** (tap its page number) and **independent scroll**: swiping or scrolling inside a pane moves only that pane, unless the panes are linked and neither is locked.
- **Utility commands:**
  - "Send this page to other pane": lets you see a figure referenced in the text in the other pane.
  - "Swap panes."
- **Active pane** is the last pane you touched or clicked; Tab toggles it. Keyboard navigation and rotation apply to the active pane.
- **Rotation:** each pane has its own rotation (button, `R` / `Shift+R`). The page is rendered already rotated, so layout and fit-to-pane stay correct. `PageTransform` converts touch and click positions back to page coordinates.
- **Zoom:** each pane has fit-width, fit-page and free zoom. Pinch, Ctrl+scroll wheel, and `+`/`-` all work.

### Cropping (view-only, whole document)
- Crop is stored as margins (fractions of the page's width and height), so pages of slightly different sizes all crop sensibly.
- You set it in CropScreen: drag the four edges on a page, choose **Auto** (union of the content boxes from up to ~20 sampled pages, plus a small padding), or **Reset**.
- Both panes, and all rendering and hit-testing, apply it through `PageTransform`. It is saved in `DocPrefs`, and a toolbar toggle turns it on and off quickly.

### Annotations (standard PDF, saved into the file)
- **Highlight tool:**
  - Drag across text. `StructuredText.highlight(start, end)` returns quads, and the app creates a `TYPE_HIGHLIGHT` annotation with those quad points and a color (yellow default; a small palette is available).
  - **Fallback for scanned patents with no text layer** (common for USPTO PDFs): if the selection finds no text, the dragged rectangle becomes the highlight's quad. It's still a standard highlight annotation.
- **Note tool:**
  - Tap or click to place a `TYPE_TEXT` (sticky note) annotation with the "Note" icon. A dialog edits its contents, and the author is set in Settings.
  - Hovering the mouse over a note shows its text. Tapping or clicking a note opens it for editing or deletion.
- **Without switching tools:** with touch, the long-press menu ("Add note here" / "Start highlight here", described under Input) creates annotations while staying in Pan mode. With a mouse, the Highlight/Note tools or shortcuts let you drag or click directly.
- **Editing:** tap/click, long-press, or right-click an existing highlight to change its color, add a comment (the highlight's contents, which Okular/Xodo show as a pop-up), or delete it.
- **Saving:** changes are kept in memory and marked dirty. They save on Ctrl+S, the toolbar Save button, and automatically when the app goes to the background. Saves are incremental, so the original content is preserved and other readers see the annotations.
  - The app requests persistable read+write permission on the SAF URI.
  - If the URI is read-only (for example an email attachment), the app offers "Save as copy".

### Input (touch and mouse/keyboard treated equally)
- **Touch:**
  - swipe to change page
  - pinch to zoom
  - a floating tool switcher (Pan / Highlight / Note)
- **Long-press menu** (touch), opened at the press point. The same menu opens on right-click with a mouse.
  - On empty page area or text:
    - **Add note here:** places a sticky note at that spot and opens the note editor.
    - **Start highlight here:** the word under your finger is selected with start and end drag handles, and there is a floating ✓ / ✗ bar. Drag the handles to extend the selection across text. On a scanned page with no text, it starts a rectangle you can resize. ✓ creates the highlight, using the last color or one picked from the bar.
    - Lock/unlock this pane, rotate this pane, send page to other pane, single/dual mode.
  - On an existing annotation: Edit note / add comment, change color, Delete.
- **Mouse:**
  - scroll wheel scrolls or changes page
  - Ctrl+wheel zooms
  - right-click opens the same context menu
  - hover shows note previews and makes buttons visible
- **Keyboard shortcuts:**

  | Keys | Action |
  |---|---|
  | ←/→, PgUp/PgDn, Space | Navigate |
  | Tab | Switch active pane |
  | `L` | Lock pane |
  | `R` / `Shift+R` | Rotate |
  | `1` / `2` | Single / dual mode |
  | `H` / `N` / `Esc` | Highlight / Note / Pan tool |
  | `C` | Crop on/off |
  | `I` | Invert colors |
  | `G` | Go to page |
  | Ctrl+S | Save |

- **AR-specific: invert/dark rendering mode.** It renders pages as light text on black. On your glasses' displays, black is see-through and easier on the eyes than a full-white page. It's cheap to build, because MuPDF can invert at render time.

## Build order (milestones)
1. **Scaffold and single-page viewer:**
   - Gradle project with MuPDF
   - open a PDF with SAF or a VIEW intent
   - render one page with zoom/pan and page navigation
   - keyboard and mouse wheel support
2. **Dual pane:**
   - `PaneState`/`ViewerState`
   - linked/locked navigation
   - per-pane rotation and zoom
   - active-pane handling and swap/send commands
   - `DocPrefs` persistence and a recent-files list
3. **Crop:** `PageTransform` with crop, the crop editor, auto-trim, and the toggle.
4. **Annotations:**
   - render existing annotations (MuPDF draws them)
   - highlight tool (text + rectangle fallback)
   - sticky notes
   - long-press/right-click menu (add note here, start highlight with drag handles)
   - edit/delete context menus
   - incremental save back to the URI
5. **Polish:** invert mode, hover previews, a settings screen (author name, highlight colors, odd/even pairing), and a `CLAUDE.md`.

## Verification
- **Unit tests** (`./gradlew test`):
  - `PageTransform`: round-trips page↔screen for every rotation, crop and zoom combination
  - `ViewerViewModel` navigation rules: linked advance by 2, locked pane stays fixed, the unlocked pane advances by 1, behavior at document boundaries
  - crop margin math
- **Instrumented test** (`./gradlew connectedAndroidTest`): open a bundled sample PDF, add a highlight and a note, save, reopen with MuPDF, and assert that both annotations exist with the right type, quads and contents.
- **Cross-reader check:** after annotating on the phone, copy the PDF to Linux and open it in **Okular**. Highlights and notes should appear and be editable. Also confirm with `mutool show file.pdf pages` or pdfinfo, and in Xodo on the phone.
- **Touch check:** in both single and dual mode, long-press → "Add note here" creates a note at the finger position. Long-press → "Start highlight here", then drag the handles and press ✓, creates a highlight covering the selected text. Check this in a rotated and cropped pane too, to verify the coordinate mapping.
- **On the glasses:** install with `./gradlew installDebug` and run in desktop mode with `AR_Touchpad`, and also in mirrored/touch mode. Use a real patent PDF to try a locked figure pane with independent rotation, a cropped view, and highlighting on a scanned page.
