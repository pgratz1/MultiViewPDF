# MultiViewPDF

A two-pane PDF reader for Android, built for reading with AR glasses. It runs on a phone driving glasses in Android desktop mode, or mirrored and used with touch. It was written for a Pixel 10 with VITURE Beast glasses.

The main use case is reading patents and papers: keep a figure locked in one pane while you page through the text in the other.

> **Status:** early (v0.1), personal project. It works on a Pixel 10 (Android 17) and the emulator, and is still being tested on the glasses. See [`HANDOFF.md`](HANDOFF.md) for the current state of the work.

## Features

- **One- or two-page view.**
  - Two pages can be linked as a book-style spread, or each pane can page on its own.
  - **Lock** either pane to keep a page on screen while the other one turns.
  - In two-page mode, pages sit against the centre of the screen like an open book.
- **Per-pane rotation** (view only; the file isn't changed).
- **Whole-document crop**, set by hand or detected automatically. It is view only and remembered for each document.
- **Sticky notes and highlights saved as standard PDF annotations** (`Text` and `Highlight`).
  - Saves are incremental: the original bytes stay untouched and the changes are appended, so Okular, Xodo and Acrobat see the annotations.
  - Pages without a text layer (scans) get rectangle highlights.
- **Double-tap zoom to a text column.** Columns are found from the page image, so this works on scanned two-column patents too.
- **Edge taps.** Tapping the outer edge of a page scrolls sideways when you're zoomed in, then turns the page.
- **Fullscreen mode.** Hides all controls; exit from the right-click / long-press menu.
- **Dark (inverted) pages** for the glasses, where black is see-through.
- **Clickable links.** Tap or click a link in the PDF to see where it goes; tap the chip to open the web page or jump to the page. With a mouse, hovering shows the target and a hand cursor.
- **Read aloud** with the phone's text-to-speech engine. Reads the active page from the top and turns pages as it goes, or starts from a long-pressed sentence. The sentence being spoken is highlighted; a small bar gives previous/next sentence, pause, stop and speed. Reading continues with the screen off, with controls in the notification shade and on the lock screen. Pick the voice under ⋮ → *Read-aloud settings…* ("Listen" previews it; the engine's network voices sound the most natural). The splitter knows about "FIG. 3", "U.S. Pat. No." and OCR spacing, and skips patent drawing sheets.
- **Figure-aware reading for papers.** Running headers, captions, chart labels, table cells, algorithm listings and bibliographies are not read in place. Just before the sentence that first mentions "Figure 3" or "Table II", the reader says the caption, or, if you add an Anthropic API key under ⋮ → *Read-aloud settings…*, a short Claude description of the rendered figure (generated once per document and cached on the device). Works with ACM and IEEE layouts.
- **Short animations** for zooming and page turns.
- **Remembers your view of each document**: pages, locks, rotation and crop.
- **Opens from anywhere Android can open a PDF**: Files, Drive, Dropbox and so on. Saves write back to the original file, or offer "Save a copy" when the source is read-only.

## Controls

Everything works with touch, with a mouse, and with a keyboard. Hover over any button to see what it does. Press `?` in the app to see this list.

| Input | Action |
| --- | --- |
| `→` / `PgDn` / `Space` | Next page (a spread turns by 2) |
| `←` / `PgUp` / `Shift+Space` | Previous page |
| `Shift+←` / `Shift+→` | Shift a spread by one page |
| `↑` / `↓`, mouse wheel | Scroll the active pane; turns the page at the end |
| `Home` / `End` | First / last page |
| `Tab` | Switch active pane |
| `1` / `2` | Single / two-page mode |
| `L` | Lock / unlock the active pane |
| `K` | Link / unlink panes as a spread |
| `S` | Pin the active page in the other pane |
| `X` | Swap panes |
| `R` / `Shift+R` | Rotate the active pane ↻ / ↺ |
| `F` | Fit width / fit page |
| `+` / `−` / `0`, `Ctrl`+wheel, pinch | Zoom in / out / reset |
| `H` / `N` | Highlight tool / sticky-note tool |
| `Esc` / `Enter` | Cancel / confirm a highlight selection |
| `C` / `Shift+C` | Crop on/off / edit crop |
| `I` | Dark (inverted) pages |
| `G` | Go to page |
| `F11` | Fullscreen on/off (also: pane menu, Back) |
| `P` / `Shift+P` | Read aloud: play / pause, stop (media keys work too) |
| `,` / `.` | Previous / next sentence |
| `<` / `>` | Slower / faster speech |
| Click / tap a link | Show where it goes; tap the chip to open it |
| `Ctrl+S` / `Ctrl+Shift+S` | Save / save a copy |
| `Ctrl+O` / `Ctrl+W` | Open / close |
| Right-click / long-press | Note, highlight and pane menu |
| Double-click / double-tap | Zoom to the text column (again: zoom out) |
| Click / tap outer page edge | Scroll sideways, then turn the page |
| Swipe | Turn the page (when the page can't scroll further) |

## Building

Requirements:

- the Android SDK with platform 35
- JDK 17–25

Android Studio works, but isn't required.

```bash
./gradlew assembleDebug                 # build the debug APK
./gradlew installDebug                  # install on a connected device
./gradlew testDebugUnitTest             # JVM unit tests (geometry, navigation, auto-crop, columns, layout, sentences)
./gradlew connectedDebugAndroidTest     # MuPDF round-trip tests: annotations, links, text layer (needs a device/emulator)
```

Gradle 8.11 needs a JDK no newer than 23 to run on; if your default `java` is newer, point `JAVA_HOME` at a JDK 17 or 21.

The optional Claude figure descriptions use the Anthropic Java SDK, which adds roughly 50 MB to the unshrunken debug APK. Nothing is sent anywhere unless you enter an API key in the app.

To avoid retyping the key after every reinstall, copy `.env.example` to `.env` at the repo root and put your key in it (`ANTHROPIC_API_KEY=sk-ant-...`). Gradle bakes it into `BuildConfig`; a fresh install then starts with that key and "Describe it with Claude" selected. A key typed in the app still takes precedence. `.env` is git-ignored, but the key sits in plain text inside the APK, so don't hand that APK to anyone.

`local.properties` must point at your SDK (`sdk.dir=/path/to/Android/Sdk`). Android Studio creates it for you.

Device requirements:

- minimum SDK 31 (Android 12); targets SDK 35
- ABIs `arm64-v8a` and `x86_64` (for the emulator)

### Installing over Wi-Fi

On the phone, go to **Settings → Developer options → Wireless debugging**. Then:

```bash
adb pair <phone-ip>:<pairing-port> <code>   # once per computer
adb connect <phone-ip>:<port>               # the port shown on the Wireless debugging screen
./gradlew installDebug
```

## Project layout

```
app/src/main/java/com/pgratz/multiviewpdf/
  MainActivity.kt         intents, file pickers, keyboard, fullscreen system bars
  ViewerViewModel.kt      single StateFlow<UiState>; all commands
  model/                  pure Kotlin, unit-tested: PageTransform, Navigator, AutoCrop, ColumnFinder,
                          Layout (page structure), Sentences (TTS splitting), Figures (references, reading plans), …
  pdf/                    MuPDF session (one dedicated thread), SAF document store, prefs
  tts/                    speech engine wrapper, figure catalog, Claude figure descriptions
  ui/                     Compose UI: panes, toolbar, crop editor, dialogs
```

Every page coordinate goes through `model/PageTransform.kt`: rendering, hit-testing, selection handles and crop. See [`CLAUDE.md`](CLAUDE.md) for more detailed developer notes, [`PLAN.md`](PLAN.md) for the original design and [`docs/plans/`](docs/plans/) for later feature designs.

## Built with

- Kotlin, Jetpack Compose and Material 3
- [MuPDF](https://mupdf.com/) (`com.artifex.mupdf:fitz`) for rendering and annotations
- Android's `TextToSpeech` for reading aloud; the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) for optional figure descriptions

## License

[GNU AGPL v3](LICENSE). This is required by the MuPDF dependency, which is AGPL-licensed.
