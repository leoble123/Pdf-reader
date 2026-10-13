# Lumen

A fast, offline file reader and editor for Android with a liquid-glass UI.

Lumen opens PDFs and most other common files, lets you edit them, and includes a photo editor and a video editor. Everything is drawn on the device. The app has **no `INTERNET` permission**, so it can't send your files anywhere.

The floating bars, buttons and panels are real refracting glass, built with [Kyant's Backdrop / Liquid Glass](https://github.com/Kyant0/AndroidLiquidGlass) library (`io.github.kyant0:backdrop`).

## Supported formats

| Kind | Formats | Viewer |
|---|---|---|
| PDF | `.pdf` | Custom renderer: GPU pinch-zoom up to 8×, sharp tile re-render, fling, double-tap zoom, page scrubber, go-to-page, Light/Sepia/Night page colors, resume where you left off. On Android 15+: full-text search with highlights, working internal and external links, password-protected files |
| Word / OpenDocument | `.docx` `.odt` | Reader view with headings, bold/italic, numbered and bulleted lists, tables, images, links |
| Slides | `.pptx` | One card per slide: titles, bullets, tables, images |
| eBooks | `.epub` | Chapters, table of contents, images, Paper/Night themes, pinch to resize text |
| Markdown | `.md` | Rendered (GFM tables, task lists, code blocks), with a toggle to show the source |
| Spreadsheets | `.xlsx` `.csv` `.tsv` | Canvas grid that stays smooth on large sheets; sheet tabs, dates, right-aligned numbers |
| Code & text | 80+ extensions | Syntax highlighting, line numbers, find, word-wrap toggle, pinch to resize text, encoding detection |
| Images | jpg png webp gif heic avif bmp… | Zoom up to 12×; very large photos load the visible area at full resolution; animated GIF/WebP |
| Video & audio | mp4 mkv webm mov mp3 flac m4a ogg wav… | ExoPlayer with glass controls, scrubbing, ±10 s, playback speed, album art |
| Archives | `.zip` `.jar` `.apk` `.cbz`… | Browse folders and open any file inside |
| Web / vector | `.html` `.svg` | Sandboxed WebView: no JavaScript, no network, no file access |
| Anything else | | File details, a hex preview, and "open as text / archive / image" |

## Editing

Edits are saved back to the original file when the app that provided it allows writing. Otherwise Lumen asks where to save a copy.

| What | How |
|---|---|
| Text, code, Markdown | Full editor with live syntax highlighting, undo/redo, find & replace, line/column readout. Keeps the file's encoding, BOM and line endings |
| CSV / Excel | Tap a cell to edit it, add rows. `.xlsx` saves are surgical: only the edited `<c>` cells are rewritten, so styles, other sheets, charts and formulas elsewhere survive |
| Word (`.docx`) | Tap a paragraph to rewrite, delete, or add one below. Only touched paragraphs change; headings and list styles carry over. Paragraphs holding images or objects are read-only |
| PDF | Markup mode: pen, highlighter, eraser and text notes in 8 colours, with undo/redo. Two fingers still zoom and pan while drawing. Marks are kept per document and reappear when you reopen it. *Export* writes an annotated copy. Android has no API for adding annotations to an existing PDF, so pages in the copy are rendered at 2× and the marks are drawn on top as vectors |

## Photo editor

Crop (free, original, 1:1, 4:5, 9:16, 16:9, 3:2), rotate, flip, straighten. Exposure, contrast, highlights, shadows, saturation, warmth, tint, fade and vignette. Ten filters with adjustable strength. Brush drawing and text layers you can drag. Unlimited undo/redo, and hold the compare button to see the original. The live preview is one AGSL GPU shader on a 1600 px proxy (Android 13+). Export re-runs the same math on the CPU at up to 4096 px and saves to *Pictures/Lumen*. The original file is never touched.

## Video editor

A timeline in the style of mobile video apps: it scrolls under a fixed playhead, shows a filmstrip of each clip, and pinches to zoom. Mix videos and photos. Trim with handles, split at the playhead, duplicate, reorder, delete. Per-clip speed (0.25×–4×), volume or mute, and captions. Filters and colour adjustments for the whole edit, aspect ratio (9:16, 1:1, 4:5, 16:9), and a background music track that loops. Live preview uses Media3 `CompositionPlayer`. Export uses Media3 `Transformer` with the phone's hardware encoder (H.264, 480p–1440p) and saves to *Movies/Lumen*. Projects autosave and reopen where you left off.

## How it stays smooth

* **PDF zoom is a GPU layer transform.** Pinching doesn't re-measure, recompose or re-render anything. After the gesture ends, only the visible part of each page is re-rendered at full resolution (`PdfDoc.renderRegion`). Deep zoom stays sharp without allocating very large bitmaps.
* **PDF rendering runs on one dedicated thread fed by a priority queue.** Visible pages go before prefetches. Among equal priorities the most recent request goes first, so a fast fling renders the page you stop on. Requests for pages that have already scrolled away are dropped. Each page shows a quick low-res preview first, then fades to full quality. The pages next to the current one are prefetched.
* **Spreadsheet columns are virtualized in the draw phase.** Scrolling sideways only redraws; it never recomposes.
* **Everything heavy runs off the main thread**: parsing, decoding, searching and thumbnails.

## Build

```bash
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # parser/editor round-trip tests + JVM-rendered screenshots (app/build/screenshots)
```

Requires JDK 21 and Android SDK 37. Minimum Android version: 8.0 (API 26). Real glass blur needs Android 12+ and refraction needs Android 13+. Older versions show a frosted fallback.

A prebuilt APK is in [`release/`](release/). It's signed with the debug key so it installs directly. Use your own signing config before publishing.

## Project layout

```
app/src/main/java/app/lumen/reader/
  core/      file detection, recents, thumbnails
  pdf/       PdfDoc (render engine), PdfViewer (zoom/pan list), PdfScreen
  rich/      one document model + renderer for Markdown/DOCX/PPTX/ODT/EPUB
  table/     CSV/XLSX parsing and the canvas grid
  text/      text loading, encoding detection, syntax highlighting
  image/ media/ archive/ viewer/   remaining viewers and shared chrome
  edit/      shared colour engine (Look), editor widgets, photo/ and video/ editors
  ui/        theme, glass components, home screen
```
