# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: MultiViewPDF

A PDF reader for use with AR glasses (VITURE Beast + Pixel, Android desktop mode or mirrored touch). Key features:
- one-page and two-page modes; in two-page mode either pane can be **locked** while the other turns pages (patent figure + text)
- independent per-pane **rotation** (view-only)
- whole-document **crop** (view-only, manual or auto), remembered per document
- **sticky notes and highlights saved as standard PDF annotations** (Text / Highlight), written with incremental saves so Okular, Xodo and Acrobat see them
- **clickable links**: tap/click a link → chip naming the target ("Open uspto.gov" / "Open Page 7"); tap the chip to open the browser or jump the pane. Mouse hover shows the same label, outlines the link and switches to a hand cursor.
- **read aloud** with the system `TextToSpeech` engine: toolbar button / `P` reads the active pane's page from the top and turns pages as it goes; keeps reading with the screen off (`tts/ReadAloudService`, a foreground media service with notification / lock-screen controls); voice selectable in Read-aloud settings; "Read aloud from here" in the long-press menu starts at the tapped sentence. The spoken sentence is highlighted in amber; a floating bar has prev/next sentence, pause, stop and speed (persisted in `AppPrefs.speechRate`).
- **figure-aware reading** (research papers): running headers/footers, captions, sub-captions, chart labels, table cells, copyright small print and bibliographies are not read in place. When the body text first mentions "Figure 3" / "Table 1" a *figure note* is spoken just before that sentence: the caption (default), or a **Claude description** of the rendered figure (`claude-opus-5-5`, low effort, cached per document on disk) when an API key is set under ⋮ → "Read-aloud settings…". Content footnotes are still read.

- **Package:** `com.pgratz.multiviewpdf`
- **Min SDK:** 31; compile/target 35; ABIs arm64-v8a + x86_64 (emulator)
- **Language/UI:** Kotlin, Jetpack Compose + Material3 (dark theme)
- **PDF engine:** MuPDF `com.artifex.mupdf:fitz` from `https://maven.ghostscript.com` (AGPL)

## Status (as of 2026-11-06, evening)

**Start with `HANDOFF.md`** for what is done, what is unverified and the open ideas. Feature designs live in `docs/plans/` (`figure-aware-read-aloud.md` is the plan this section implements).

**2026-11-07, home desktop:** the Anthropic key can live in a git-ignored `.env` at the repo root (`ANTHROPIC_API_KEY=…`, template in `.env.example`). `app/build.gradle.kts` reads it (falling back to the environment variable) into `BuildConfig.ANTHROPIC_API_KEY`; `PrefsStore.loadApp` uses it whenever no key is saved and, on a first launch, selects `FigureMode.CLAUDE`. A key typed in settings wins. Blank `.env` ⇒ empty constant ⇒ old behaviour. Dropbox syncs `.env` between machines.

Same day: figure notes now come *before* the referencing sentence (`ReadingPlans.build`; `referencingSentence` looks forward; `withLeadingNotes` makes "read from here" include them). Background reading: `MainActivity.onStop` no longer pauses; the ViewModel implements `ReadAloudController` and starts `tts/ReadAloudService` whenever `tts` becomes non-null; the service holds a platform `MediaSession` (no androidx.media), mirrors `ViewerViewModel.playback` into a `MediaStyle` notification (channel `read_aloud`, prev / play-pause / next / stop as PendingIntents to itself plus `PlaybackState` actions for API 33+ controls) and stops itself when `playback` is null. Manifest: `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)`, `foregroundServiceType="mediaPlayback"`. Voices: `SpeechReader.voices()/setVoice()`, `AppPrefs.voiceName` ("" = engine default), picker + "Listen" preview in `ReadAloudSettingsDialog` (`vm.loadVoices`, `previewVoice`, `cancelReadAloudSettings` undoes a preview). Known gap: media keys after pausing in the background (see HANDOFF).

**Font sizes come from MuPDF, not glyph boxes.** `PdfSession.pageText` walks the structured text once (`StructuredTextWalker.onChar` is the only place the Java API exposes a char's size) and stores a per-line `TextLine.size`; `Layout.Measured.size` prefers it and falls back to quad heights (0 = unknown, as in older callers). Glyph boxes vary by font: on the MICRO paper's page 2 the chart's tick labels measured 9.8 pt from their boxes against a 5.8 pt font, became a 17-line "paragraph", bounded Figure 2's region from above and left Claude a strip of caption. `isParagraph` additionally needs ≥2 words per line. Debug builds log every page's block classes (`layout pN …` in logcat) and write each image sent to Claude to `cache/figures/<id>.png`; pull with `run-as`.

**Auto-crop ignores margin matter** (`AutoCrop.mainExtent`): ink is grouped into runs along each axis (gaps under 2 % merged); when some run is ≥15 % of the page, runs under 5 % wide anywhere outside the main span (submission line numbers, side banners) and runs under 2 % tall within 12 % of the top/bottom (running headers, page numbers) are dropped. Found on the ISCA '27 cache-replacement draft, whose line numbers made Auto a no-op. The toolbar crop icon now acts directly (toggle, or open the editor when no crop is set) because its popup menu did nothing when clicked from the glasses in desktop mode via AR Touchpad; "Edit crop…" moved to the ⋮ menu (also Shift+C). The same click path works over adb on a simulated desktop-mode display (`settings put global overlay_display_devices 1920x1080/240`, `am start --display N`, `input mouse -d N tap x y`, `screencap -d <SurfaceFlinger id from dumpsys SurfaceFlinger --display-id>`), so the open question is popup windows + the uinput pointer on the real glasses; the ⋮ menu and the pane's long-press menu are popups too.

**Figure-aware read-aloud added.** Verified on the emulator with the ISCA paper in the repo root (`2027_ISCA__ORAP_Paper (1).pdf`; it is a **confidential draft, git-ignored along with every `*.pdf`**, as are the text-layer dumps in `app/src/test/resources/layout/` that `RealPagesLayoutTest` reads; without them those tests are skipped. Regenerate the dumps with the mutool one-liner in the git history of this session or ask Claude). Results: page 3 reads "PRAC, while PRAC-based…" first (no header, no "(a)/(b)" sub-captions, no chart labels); the Figure 3 note fires after the first mention in the left column (item 13 of 37) with the figure highlighted, not again in §2.4.1; a bogus API key logs `claude figure-3 failed: API key rejected`, toasts once and falls back to the caption. **Not yet tried with a real key, nor on the Pixel's speaker.** 90 unit tests (incl. 14 golden tests on the three papers' text layers), 4 instrumented.

Design notes (see `model/Layout.kt`, `model/Figures.kt`, `tts/FigureCatalog.kt`, `tts/FigureDescriber.kt`, `tts/ClaudeClient.kt`):
- `Layout.analyze(PageText)` classifies blocks from text geometry only (this MuPDF build has no `collect-vectors`; image boxes come from `PdfSession.pageImages`): body size = char-weighted median; NUMERIC, CAPTION (regex), bibliography (`[n]` lines), HEADER_FOOTER (≤2 lines in the top 9 % / bottom 10 %), HEADING (≥1.1× body), small print (<0.85× body) split into FOOTNOTE (prose in the lower half, no boilerplate) vs SMALL_PRINT, SUB_CAPTION, BODY. A caption's region is the gap above or below it bounded by prose/headings/other captions in its column(s); the side is chosen by what is inside (this paper's *table* captions sit below the tables). Pages with no body text of their own (references) use the document body size from `FigureCatalog`.
- `ReadingPlans.build` turns readable sentences into `Body` items plus a `FigureNote` before the first sentence that mentions each figure (`FigureRefs.find`: "Figures 3 and 4", "Figs. 2–4", "Figure 2b", "Table 1"). Notes carry no text; `ViewerViewModel.textFor` resolves it when spoken, so item indices (utterance ids) stay stable. `spokenFigures` makes each figure a once-per-session event; `nextEligible`/`prevEligible` skip ineligible notes everywhere the queue advances.
- Claude path: `SdkClaudeClient` (Anthropic Java SDK 3.6.0, runs fine on Android; adds ~50 MB to the unshrunken debug APK — enable R8 for release or swap in a raw-HTTP client) sends the rendered `caption.extent` as PNG + caption + referencing sentence, `maxTokens 600`, effort LOW, no thinking parameter. Refusal/any error → caption; descriptions cached in `files/prefs/figures/<fingerprint>.json`; `prefs/app.json` (holds the key) is excluded from backups via `res/xml/*rules.xml`.
- **IEEE proceedings** (checked on `Page_Size_Aware_Cache_Prefetching.pdf`, MICRO 2022, and `R-Max_…pdf`, ISCA 2026, both git-ignored): captions are "Figure 2." alone on a line, "Fig. 1.", "TABLE I" (roman numerals, title on the next line), and "Algorithm 1:" over a pseudo-code listing. Block diagrams carry labels at *any* font size (9–18 pt), so a few-word block is a `LABEL` (readable only outside figure regions, never a region boundary); numbered section headings are recognised on the joined block text with a title-case / no-code-characters check so listing steps like "2 Acc = <empty list>;" don't count; only paragraphs (≥3 lines or ≥25 words), headings, captions and prose fragments bound a region, and listing lines don't bound an algorithm's; captions merge with the next block only when visibly unfinished ("…archi-"); rotated side banners are dropped. `FigureKind.ALGORITHM` notes say "Algorithm 1. <title>".
- Known gaps: charts whose labels are body-sized *and* outside the inferred region; a one- or two-line sentence sandwiched between a paragraph and a figure can be swallowed by the figure region; regions can include whitespace (an ink-trim pass is the planned refinement).
- Text-layer dump for golden fixtures: `mutool draw -F stext -o - paper.pdf N` → per line `x0 y0 x1 y1 size|text`, blocks separated by `#block`, header `#page W H` (the generator script is in this session's history; 15 lines of Python).

Documents: `README.md` is user-facing (features, key table, build); `HANDOFF.md` is the running state between machines; `PLAN.md` is the original design; `docs/plans/` holds later designs. Keep all four current when a feature lands.

### Earlier status (2026-11-06, morning)

**2026-11-06, on the XPS-8960 + emulator:** added links and read-aloud (plan: `~/.claude/plans/ok-great-i-want-peppy-turing.md` on the XPS). Verified on the API 35 emulator with `tools/testpdf/links.tex` (build with `pdflatex`; web + internal links, "FIG. 3", "U.S. Pat. No.", claims) and the scanned USPTO patent US10000000: link chip → Chrome, internal link → page jump, read-from-here lands on the tapped claim, `,`/`.` cross page boundaries both ways, auto-advance onto the next page, manual page turn re-syncs, drawing sheets (OCR'd numerals only) are skipped via `SentenceSplitter.looksLikeProse`. Installed on the Pixel 10 the same day: **the voice works on the phone.** Still to try on the glasses in desktop mode (hover labels, hand cursor, media keys).

Read-aloud design notes (see `ViewerViewModel` "read aloud" section and `model/Sentences.kt`):
- `PdfSession.pageText()` (MuPDF thread) → `SentenceSplitter.split()` (Default dispatcher) → sentences with one quad per line. Abbreviations (`FIG.`, `No.`, `U.S.`, `et al.`, `e.g.` …) and OCR spacing (`FIG . 3`) don't split; claim numbers merge forward; `spoken()` expands `FIG.` → "Figure" etc. for the engine only.
- Utterance ids are `generation:page:index`; every stop/seek bumps the generation so stale engine callbacks (binder thread → `SharedFlow`) are ignored. Two utterances are queued at a time. There is no engine pause: pause = stop + remember index.
- A page is skipped when reading on if it has fewer than 30 words of ≥4 letters (patent figure sheets). "Read from here" never skips.
- Keys: `P` play/pause, `Shift+P` stop, `,`/`.` sentence back/forward, `<`/`>` slower/faster, media keys. `Esc` first dismisses a link chip.
- Known gaps: reading order is MuPDF block order (fine on the USPTO sample, including two columns); the front page reads the bibliographic fields; no audio focus request; no odd/even pairing.

### Earlier status (2026-10-06)

**The plan's milestones 1–5 are all implemented** (see `PLAN.md` for the original design and its feature decisions: crop and rotation are view-only and remembered per document; v1 annotations are notes + highlights only). Development so far ran on the desktop against the `Pixel_10` emulator. **The app has not yet run on the real Pixel or the Viture glasses.** That's the next step, from the laptop, over Wi-Fi adb.

Verified on the emulator:
- `./gradlew testDebugUnitTest`: 26/26 pass.
- `./gradlew connectedDebugAndroidTest`: 2/2 pass. These create a highlight and a note with MuPDF, save incrementally, reopen, check the `/ID` is unchanged, then edit and delete.
- Manual walkthrough on a generated 4-page "patent" PDF:
  - two-pane spread; lock the right pane on a figure and rotate it while the left pane pages through
  - long-press → "Start highlight here", drag a handle, commit in green
  - long-press → "Add note here"; save
  - `mutool` on the desktop shows a standard `Highlight` (with QuadPoints) and a `Text` annotation, appended after the untouched original bytes
  - auto-crop, dark mode, and view state restored after a force-stop
- Bugs fixed during that pass:
  - black-on-black home screen text (root is now a `Surface`)
  - crop editor clipped by the landscape camera cutout (it's now an in-window overlay, not a `Dialog`)
  - per-document state lost after saving a small file (now keyed by `/ID`)
  - first arrow key after a touch swallowed

### Next steps / things to check on the real phone + glasses
1. Install over Wi-Fi (below) and open a real USPTO patent PDF.
   - Check render speed on large scanned pages.
   - Check the rectangle highlight fallback on image-only pages.
2. Desktop mode on the glasses, driven by the sibling `../AR_Touchpad` app (it injects mouse events):
   - right-click menu
   - wheel scroll/page turn, Ctrl+wheel zoom
   - hover note previews
   - keyboard shortcuts with a BT keyboard
   - window resizing; the activity handles config changes itself, so it shouldn't restart
3. Open a PDF from Files/Drive/Dropbox and save. Confirm the change is written back to the original via SAF; read-only providers should show "Save a copy". Then open the file in Okular and Xodo to confirm the annotations appear.
4. Known gaps / ideas not yet done:
   - password-protected PDFs (shows an error)
   - no outline/TOC, search, thumbnails, or continuous scroll
   - no odd/even page pairing option for spreads (Shift+←/→ shifts a spread by one page instead)
   - large debug APK (~79 MB: icons-extended + no shrinking). Enable `isMinifyEnabled` in release; the MuPDF keep rule is already in `proguard-rules.pro`.
   - no app icon beyond a simple vector
   - git repo on GitHub: `pgratz1/MultiViewPDF` (public), branch `main`

## User / environment notes
- The user (pgratz) builds personal apps in `~/Dropbox/Documents/Android_Dev/`.
- Target hardware: a Pixel 10 on Android 17 driving VITURE Beast glasses in desktop mode. Sometimes the phone is mirrored and used with touch.
- Design everything for **both touch and mouse/keyboard**, with a dark UI (black is see-through on the glasses).
- GitHub `main` (`pgratz1/MultiViewPDF`) is the coherence point between machines: pull before working, commit and push when done. `~/Documents` is a symlink to `~/Dropbox/Documents`, so `~/Documents/Android_Dev/MultiViewPDF` is this same folder, not a second copy.
- This folder syncs via Dropbox between the home desktop, the laptop and the XPS-8960 desktop. `app/build`, `.gradle` and `.kotlin` (~300 MB) sync too.
  - If Gradle acts strangely after switching machines, run `./gradlew clean`.
  - Better still, exclude those folders from Dropbox sync (Dropbox → Preferences → Selective sync, or `attr -s com.dropbox.ignored -V 1 app/build`).
  - Don't build on both machines at the same time.
- `local.properties` (`sdk.dir=/home/pgratz/Android/Sdk`) also syncs. If the laptop's SDK lives elsewhere, Android Studio rewrites it. Remember it's shared.
- On the XPS the Pixel connects over **USB** (serial `59180DLCR001R9`); Wi-Fi debugging doesn't work on that network. With both the phone and the emulator attached, use `adb -s <serial>` and `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` (which uninstalls the app afterwards).
- **Debug signing differs per machine.** A build from one machine won't install over another machine's build (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); `adb uninstall com.pgratz.multiviewpdf` first (app prefs are lost, except the Claude key when it comes from `.env`; annotations live in the PDFs). To avoid it, copy one `~/.android/debug.keystore` to every machine.
- XPS emulator: `~/Android/Sdk/emulator/emulator -avd Pixel_10 -memory 4096 -gpu swiftshader_indirect` (API 35 Play image, so Google TTS is present). With the default host GPU it crashed twice in gfxstream ("error null ctx") after ~20 min; the software renderer is slower but stable. Landscape like the glasses: `adb shell settings put system user_rotation 1` (after `accelerometer_rotation 0`). Keys for testing: `adb shell input keyevent KEYCODE_P`; long-press: `input swipe x y x y 900`.

## Laptop setup & deploying to the phone over Wi-Fi

```bash
# Laptop (checked 2026-10-06): no Android Studio; the system OpenJDK 25 builds and tests fine with no JAVA_HOME.
# SDK at ~/Android/Sdk (android-35, build-tools 34/35). Use the SDK's adb, not the older /usr/bin/adb (1.0.41):
export ANDROID_HOME=~/Android/Sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

# Phone: Settings → Developer options → Wireless debugging → "Pair device with pairing code"
adb pair <phone-ip>:<pairing-port> <6-digit-code>    # one time per laptop
adb connect <phone-ip>:<debug-port>                  # the port shown on the Wireless debugging screen (differs from pairing port)
adb devices                                          # should list the phone

./gradlew installDebug
adb shell am start -n com.pgratz.multiviewpdf/.MainActivity
adb logcat --pid=$(adb shell pidof com.pgratz.multiviewpdf)   # app logs; tag "MultiViewPDF"
```
- `adb` is in `$ANDROID_HOME/platform-tools` (`~/Android/Sdk/platform-tools`).
- The Wi-Fi debug port changes whenever wireless debugging restarts. Re-run `adb connect`.
- Screenshots for checking UI: `adb exec-out screencap -p > shot.png`. In desktop mode, add `-d <display-id>` (from `adb shell dumpsys display | grep mDisplayId`) to capture the glasses display.
- Desktop tools for checking a saved PDF: `mutool show file.pdf trailer`, or a `mutool run script.js file.pdf` script that lists `page.getAnnotations()` (`getType()`, `getContents()`, `getQuadPoints()`).

## Build Commands

On the laptop, the system JDK 25 works with no `JAVA_HOME`. On the home desktop, set `export JAVA_HOME=~/android-studio/jbr` first. On the XPS-8960 (checked 2026-11-06, no Android Studio): JDK 21 from `apt` and an SDK installed with cmdline-tools at `~/Android/Sdk` (android-35, build-tools 35.0.0). Its default `java` is 25, which Gradle 8.11 cannot run on, so `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` is required; clean build + 33/33 unit tests pass there. If Gradle fails with a JDK/class-version error on either machine, point `JAVA_HOME` at a JDK 17 or 21 (`sudo apt install openjdk-21-jdk` → `/usr/lib/jvm/java-21-openjdk-amd64`). Dropbox can drop the exec bit on `gradlew`. If `./gradlew` says permission denied, run `chmod +x gradlew` (or use `bash gradlew`).

```bash
./gradlew assembleDebug                 # build
./gradlew testDebugUnitTest             # JVM unit tests (geometry, navigation, auto-crop)
./gradlew connectedDebugAndroidTest     # MuPDF round-trip tests; needs a device/emulator
./gradlew installDebug
```

The `Pixel_10` AVD's default 2 GB RAM gets apps killed by lowmemorykiller. Start it with `emulator -avd Pixel_10 -memory 4096`. Note that `connectedDebugAndroidTest` uninstalls the app afterwards, which wipes its data.

To open a file on the emulator without the system picker:
`adb shell run-as com.pgratz.multiviewpdf cp /data/local/tmp/x.pdf files/x.pdf`, then
`adb shell am start -a android.intent.action.VIEW -t application/pdf -d file:///data/user/0/com.pgratz.multiviewpdf/files/x.pdf -n com.pgratz.multiviewpdf/.MainActivity`.

## Architecture

```
app/src/main/java/com/pgratz/multiviewpdf/
  MainActivity.kt         VIEW intents, SAF open/save-as pickers, hardware keys → vm.onKey, autosave on stop
  ViewerViewModel.kt      Single StateFlow<UiState>; all commands (navigation, tools, annotations, save)
  model/                  Pure Kotlin (unit-tested, no Android/MuPDF types)
    PageTransform.kt      page space ⇄ pane pixels for crop + rotation + scale; also emits the MuPDF matrix
    Navigator.kt          one/two-pane, linked-spread and lock rules
    ViewLayout.kt         per-document persisted view state (panes, mode, crop)
    AutoCrop.kt, Annotations.kt, Selection.kt, Geometry.kt, Prefs.kt
  pdf/
    PdfSession.kt         Owns the MuPDF document; every call runs on one dedicated thread (MuPDF isn't thread-safe)
    DocumentStore.kt      content:// URI → cache working copy; write back after save
    PrefsStore.kt         JSON files: app prefs + one layout file per document
  ui/
    PdfPane.kt            One pane: base bitmap + hi-res zoom patch, gestures, wheel/hover, long-press menu, selection handles
    ViewerScreen.kt       Toolbar + one/two panes + dialogs; CropEditor.kt is an in-window overlay (not a Dialog)
```

## Key Patterns

- **All coordinates go through `PageTransform`.** Rendering, hit-testing, selection handles and crop all use it. Don't hand-roll rotation math elsewhere.
- **Saving:** annotations are edited in MuPDF, then `PdfSession.save()` does `save(workFile, "incremental")` (falling back to a full rewrite if that fails) and reopens. `DocumentStore.writeBack` then copies the file to the original URI. If the URI is read-only, a "Save a copy" dialog is offered.
- **Annotation identity:** each annotation is identified by its index in the page's annotation list. Re-list (`PdfSession.annotations`) after every edit. The ViewModel's `editPage` does this and bumps `versions[page]` so panes re-render.
- **Per-document view state** is keyed by the PDF trailer `/ID[0]` (`PdfSession.permanentId`), falling back to a hash of the first 64 KB. A content hash alone changes for small files once annotations are appended.
- **Scanned pages without a text layer:** highlight falls back to a rectangle quad.
- **Key-down after a touch:** Android consumes the first navigation key-down after a touch to leave touch mode. `MainActivity.dispatchKeyEvent` handles such keys on key-up when their key-down never arrived.
