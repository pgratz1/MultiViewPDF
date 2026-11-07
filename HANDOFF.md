# Handoff — 2026-11-06, from the XPS-8960 to the home machine

Everything below is committed and pushed to `pgratz1/MultiViewPDF` `main` (last commit on this machine: see `git log -1`). The working tree is clean. Dropbox syncs this folder too, so the home machine should see the same files; run `git status` there first and trust GitHub if the two disagree.

## What was done today (three sessions)

1. **Clickable links.** Tap/click a PDF link → chip ("Open uspto.gov" / "Open Page 7") → tap the chip to open the browser or jump the pane. Hover shows the label, outlines the link and uses a hand cursor. Hit-testing prefers exact hits, then the nearest within the tap slop.
2. **Read aloud** (system `TextToSpeech`). Toolbar button / `P`, "Read aloud from here" in the pane menu, floating bar with prev/next sentence, pause, stop, speed. Sentence splitter is patent-aware (`FIG. 3`, `U.S. Pat. No.`, OCR spacing) and skips drawing sheets. Verified on the Pixel's speaker.
3. **Figure-aware reading** (research papers). Headers/footers, captions, sub-captions, chart labels, table cells, algorithm listings, bibliographies and copyright small print are not read in place. A *figure note* precedes the first sentence that mentions "Figure 3" / "Table II" / "Alg. 1": the caption by default, or a **Claude Opus 5.5 description** of the rendered figure when an API key is set (⋮ → "Read-aloud settings…"; cached per document under `files/prefs/figures/`). Works on ACM (ISCA draft) and IEEE (MICRO 2022, ISCA 2026) layouts; see `CLAUDE.md` → Status for the rules and `docs/plans/figure-aware-read-aloud.md` for the design.

4. **2026-11-07, home desktop:** Anthropic key from `.env` (see `CLAUDE.md`); figure notes moved to *before* the referencing sentence; read-aloud keeps going with the screen off via `tts/ReadAloudService` (foreground `mediaPlayback` service + `MediaSession`: notification/lock-screen controls for prev/play-pause/next/stop); voice picker with "Listen" preview in Read-aloud settings (`AppPrefs.voiceName`). Verified on the Pixel: reading advanced with the screen off, the shade shows "paper · Page 2 · 7 / 31". Headset/media keys reach the session while it is playing; after a pause in the background, a key press may not resume it (the notification's Play does) — the media-button session seems to follow the Google TTS process's audio, not ours.

Tests: `./gradlew testDebugUnitTest` → 90 pass (14 of them golden tests on real text-layer dumps; they **skip** if the dumps are missing). `connectedDebugAndroidTest` → 4 pass.

## Not yet done / verify first on the home machine

- **A real Claude description has never been generated.** Only the failure path (bogus key → "API key rejected" → caption) was exercised. Put the key in `.env` at the repo root (`ANTHROPIC_API_KEY=…`, see `.env.example`; Dropbox carries it to the other machines) and every install has it already. Or on the phone: ⋮ → Read-aloud settings → "Describe it with Claude", paste a Console API key (your Max plan includes $100/month of Platform credits; create the key at platform.claude.com), press **Test**, then play from page 3 of the ISCA paper. Expect one `claude figure-N … ms` line per figure in logcat and a JSON file under `files/prefs/figures/`.
- **Glasses / desktop mode** untested for the new features: hover labels and hand cursor on links, media keys, the TTS bar placement in fullscreen.
- The Pixel has today's final build installed (debug, signed by the XPS). **Installing a home-machine build over it will fail** with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; either `adb uninstall com.pgratz.multiviewpdf` first (loses view prefs only; annotations live in the PDFs) or copy `~/.android/debug.keystore` from one machine to the other. Since 2026-11-07 the Claude key survives a reinstall when it is in `.env`.

## Known gaps / ideas, roughly in priority order

1. Debug APK is ~129 MB (was 79) because of the Anthropic Java SDK's dependency tree (Jackson, kotlin-reflect, jsonschema). Options: enable R8 for a release build (keep rules ship with the SDK), or replace `SdkClaudeClient` with a ~100-line `HttpURLConnection` client behind the same `ClaudeClient` interface (shape is sketched in the plan).
2. A one- or two-line sentence sandwiched between a paragraph and a figure can be swallowed by the figure region (`Layout.region`: only paragraphs, headings, captions and prose fragments bound a region). An ink-trim pass (render the region, trim blank rows) would also tighten loose regions.
3. Charts whose labels are body-sized *and* outside the inferred region are still read. Add any such paper to `app/src/test/resources/layout/` with the mutool one-liner in `CLAUDE.md` and extend `RealPagesLayoutTest`.
4. References/bibliography pages are silent by design; a "read references" toggle would be easy (`BlockClass.SMALL_PRINT` from `BIB_ENTRY`).
5. Earlier backlog (unchanged): password PDFs, outline/TOC, search, continuous scroll, odd/even spread pairing, app icon.

## Machine notes

- XPS: JDK 21 via `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (default `java` is 25, Gradle 8.11 rejects it); SDK at `~/Android/Sdk`; emulator `Pixel_10` (API 35 Play image) — start it with `-gpu swiftshader_indirect`, the host GPU path crashed twice; Pixel over USB, serial `59180DLCR001R9`.
- The three sample papers in the repo root and the fixture dumps are **git-ignored** (`*.pdf`, `app/src/test/resources/layout/*.txt`); the ISCA one is a confidential draft. Dropbox carries them to the home machine anyway.
- `CLAUDE.md` has the full developer notes; `README.md` the user-facing feature list and key table.
