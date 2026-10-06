# MultiViewPDF

A two-pane PDF reader for Android, built for reading with AR glasses. It runs on a phone driving glasses in Android desktop mode, or mirrored and used with touch. It was written for a Pixel 10 with VITURE Beast glasses.

The main use case is reading patents and papers: keep a figure locked in one pane while you page through the text in the other.

> **Status:** early (v0.1), personal project. It works on a Pixel 10 (Android 17) and the emulator, and is still being tested on the glasses.

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
./gradlew testDebugUnitTest             # JVM unit tests (geometry, navigation, auto-crop, columns)
./gradlew connectedDebugAndroidTest     # MuPDF annotation round-trip tests (needs a device/emulator)
```

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
  model/                  pure Kotlin, unit-tested: PageTransform, Navigator, AutoCrop, ColumnFinder, …
  pdf/                    MuPDF session (one dedicated thread), SAF document store, prefs
  ui/                     Compose UI: panes, toolbar, crop editor, dialogs
```

Every page coordinate goes through `model/PageTransform.kt`: rendering, hit-testing, selection handles and crop. See [`CLAUDE.md`](CLAUDE.md) for more detailed developer notes and [`PLAN.md`](PLAN.md) for the original design.

## Built with

- Kotlin, Jetpack Compose and Material 3
- [MuPDF](https://mupdf.com/) (`com.artifex.mupdf:fitz`) for rendering and annotations

## License

[GNU AGPL v3](LICENSE). This is required by the MuPDF dependency, which is AGPL-licensed.
