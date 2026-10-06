# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project: MultiViewPDF

A PDF reader for use with AR glasses (VITURE Beast + Pixel, Android desktop mode or mirrored touch). Key features:
- one-page and two-page modes; in two-page mode either pane can be **locked** while the other turns pages (patent figure + text)
- independent per-pane **rotation** (view-only)
- whole-document **crop** (view-only, manual or auto), remembered per document
- **sticky notes and highlights saved as standard PDF annotations** (Text / Highlight), written with incremental saves so Okular, Xodo and Acrobat see them

- **Package:** `com.pgratz.multiviewpdf`
- **Min SDK:** 31; compile/target 35; ABIs arm64-v8a + x86_64 (emulator)
- **Language/UI:** Kotlin, Jetpack Compose + Material3 (dark theme)
- **PDF engine:** MuPDF `com.artifex.mupdf:fitz` from `https://maven.ghostscript.com` (AGPL)

## Status (as of 2026-10-06)

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
   - git repo on GitHub: `pgratz1/MultiViewPDF` (private), branch `main`

## User / environment notes
- The user (pgratz) builds personal apps in `~/Dropbox/Documents/Android_Dev/`.
- Target hardware: a Pixel 10 on Android 17 driving VITURE Beast glasses in desktop mode. Sometimes the phone is mirrored and used with touch.
- Design everything for **both touch and mouse/keyboard**, with a dark UI (black is see-through on the glasses).
- This folder syncs via Dropbox between the desktop and the laptop. `app/build`, `.gradle` and `.kotlin` (~300 MB) sync too.
  - If Gradle acts strangely after switching machines, run `./gradlew clean`.
  - Better still, exclude those folders from Dropbox sync (Dropbox → Preferences → Selective sync, or `attr -s com.dropbox.ignored -V 1 app/build`).
  - Don't build on both machines at the same time.
- `local.properties` (`sdk.dir=/home/pgratz/Android/Sdk`) also syncs. If the laptop's SDK lives elsewhere, Android Studio rewrites it. Remember it's shared.

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

On the laptop, the system JDK 25 works with no `JAVA_HOME`. On the desktop, set `export JAVA_HOME=~/android-studio/jbr` first. If Gradle fails with a JDK/class-version error on either machine, point `JAVA_HOME` at a JDK 17 or 21 (`sudo apt install openjdk-21-jdk` → `/usr/lib/jvm/java-21-openjdk-amd64`). Dropbox can drop the exec bit on `gradlew`. If `./gradlew` says permission denied, run `chmod +x gradlew` (or use `bash gradlew`).

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
