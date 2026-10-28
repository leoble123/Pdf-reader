# Lumen

A fast, offline file reader, editor and scanner for Android, built with Material You.

Lumen opens PDFs and most other common files, lets you edit them, and includes a document scanner, PDF page tools, a photo editor and a video editor. Everything is drawn on the device. The app has **no `INTERNET` permission**, so it can't send your files anywhere.

The UI is Material 3 with dynamic colour (it takes its palette from your wallpaper on Android 12+), with light, dark and pure-black themes.

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
| Video & audio | mp4 mkv webm mov mp3 flac m4a ogg wav… | ExoPlayer with Material controls, scrubbing, ±10 s, playback speed, album art |
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
| PDF | Markup mode: pen, highlighter, eraser, lasso and text notes, with undo/redo. See *Pen & stylus* below. *Save* writes the marks into the PDF as real Ink and FreeText annotations through an incremental update. The original bytes stay untouched, so bookmarks and forms survive, and any PDF app can show, edit or remove the marks. Pressure strokes keep their varying width. Encrypted files fall back to a flattened copy |

## Notebooks

Draw on blank paper: tap **New notebook** on the home screen and choose blank, lined, grid or dot paper. Notebooks open straight into drawing, with every pen feature below. Add pages from the toolbar and export the whole notebook as a PDF whenever you like. Notebooks live on the home shelf, which shows each one's first page. Long-press one to rename or delete it. The paper is a tiny vector PDF (every page shares one drawing), and the ink is stored beside it, so adding pages never touches what you've drawn.

## Pen & stylus

Applies to PDF markup and the photo editor's Draw tool.

- **Stylus draws, fingers navigate.** The first time a stylus touches the screen, fingers switch to scrolling and zooming. The hand button on the toolbar switches finger drawing back on, for phones without a pen. Touches from your palm are ignored while the pen is down. A pen that lands while you're scrolling takes over straight away.
- **Two-finger tap undoes, three-finger tap redoes.** You get a haptic tick and a small *Undo* / *Redo* pill.
- **Pressure** changes the line's thickness. Every sample the digitizer reports is used, and events are delivered unbuffered for lower latency.
- **Hold to straighten:** pause at the end of a stroke and it becomes a straight line, which you can keep dragging; angles near 0°/45°/90° snap exactly. Closed loops become a clean rectangle or ellipse.
- **Eraser:** removes whole strokes or only the part you rub over. The stylus's eraser end or side button erases with any tool.
- **Lasso** (PDF): circle marks to select them, then drag, duplicate, recolour or delete them.
- **Extras:** a hover ring shows where the pen will land and how big it is. The size slider has a true-to-scale preview, there's a custom colour strip that remembers recent colours, and you can pinch-zoom the photo canvas while drawing.
- **Palm rejection:** while the pen hovers or was just lifted, finger touches are treated as your hand resting and ignored. A deliberate two-finger pinch (both fingers landing together) still zooms. Palm-sized contacts never count, and a touch the system cancels as a palm leaves no ink. Multi-finger undo taps must land together, so a resting palm can't trigger them.
- **Feel:** a one-euro filter removes jitter on slow strokes without adding lag on fast ones, and the stroke ends exactly where the pen lifted. Android's motion prediction draws a short tail a frame ahead of the pen to hide latency.
- **Erasing** sweeps the whole path between samples, so fast swipes don't skip strokes. The eraser size is adjustable and shown to scale, and a bounding-box check keeps it quick on busy pages.
- **Speed:** each page has separate layers for highlights, finished ink and the stroke in progress. A new pen sample redraws only the live layer, and that layer extends its path in place. Ink is saved to disk on a background thread.

## PDF tools

Lumen includes its own PDF engine (`pdf/engine`). It reads classic and compressed cross-references, object streams and hybrid files, and repairs damaged cross-reference tables. The page organiser uses it to merge PDFs, add images as pages, reorder, rotate, delete and extract pages. Pages are copied object-for-object, so text stays selectable and nothing gets re-rendered. Outputs pass qpdf's syntax check and pypdf in strict mode.

## Scanner

Photograph a page with the camera app or import photos. Lumen finds the page edges with Otsu thresholding and region growing, flattens the perspective, and cleans the page up:
- *Enhance* removes shadows while keeping colour
- *B&W* uses adaptive thresholding for crisp text
- *Grayscale* is also available

Pages save as a compact PDF that embeds the JPEGs directly. No camera permission and no ML model are needed.

## More

- **Folders:** add any folder through the system picker to browse it with sorting and filtering. Swipe between the photos in a folder.
- **Compress video / extract audio:** re-encode to 480p, 720p or 1080p, or save the soundtrack as M4A.
- **Read aloud:** reads documents with the phone's offline text-to-speech, scrolling along as it goes.
- **Settings:** theme, dynamic colour, pure black, smooth motion, finger drawing, keep screen on. *Smooth motion* asks for the screen's highest refresh rate (90/120 Hz); many phones otherwise hold apps at 60 Hz.

## Photo editor

Crop (free, original, 1:1, 4:5, 9:16, 16:9, 3:2), rotate, flip, straighten. Exposure, contrast, highlights, shadows, saturation, warmth, tint, fade and vignette. Ten filters with adjustable strength. Brush drawing and text layers you can drag. Unlimited undo/redo, and hold the compare button to see the original. The live preview is one AGSL GPU shader on a 1600 px proxy (Android 13+). Export re-runs the same math on the CPU at up to 4096 px and saves to *Pictures/Lumen*. The original file is never touched.

## Video editor

A timeline in the style of mobile video apps: it scrolls under a fixed playhead, shows a filmstrip of each clip, and pinches to zoom. Mix videos and photos. Trim with handles, split at the playhead, duplicate, reorder, delete. Per-clip speed (0.25×–4×), volume or mute, and captions. Filters and colour adjustments for the whole edit, aspect ratio (9:16, 1:1, 4:5, 16:9), and a background music track that loops. Live preview uses Media3 `CompositionPlayer`. Export uses Media3 `Transformer` with the phone's hardware encoder (H.264, 480p–1440p) and saves to *Movies/Lumen*. Projects autosave and reopen where you left off.

## How it stays smooth

* **Material without blur.** Bars and panels are plain tonal surfaces, so drawing them costs nothing per frame. A bundled baseline profile AOT-compiles the app and Compose at install, so the first launch is as fast as later ones.

* **PDF zoom is a GPU layer transform.** Pinching doesn't re-measure, recompose or re-render anything. After the gesture ends, only the visible part of each page is re-rendered at full resolution (`PdfDoc.renderRegion`). Deep zoom stays sharp without allocating very large bitmaps.
* **PDF rendering runs on one dedicated thread fed by a priority queue.** Visible pages go before prefetches. Among equal priorities the most recent request goes first, so a fast fling renders the page you stop on. Requests for pages that have already scrolled away are dropped. Each page shows a quick low-res preview first, then fades to full quality. The pages next to the current one are prefetched.
* **Spreadsheet columns are virtualized in the draw phase.** Scrolling sideways only redraws; it never recomposes.
* **Everything heavy runs off the main thread**: parsing, decoding, searching and thumbnails.

## Build

```bash
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # parser/editor round-trip tests + JVM-rendered screenshots (app/build/screenshots)
```

Requires JDK 21 and Android SDK 37. Minimum Android version: 8.0 (API 26). Dynamic colour needs Android 12+; older versions use a built-in indigo palette.

A prebuilt APK is in [`release/`](release/). It's signed with the debug key so it installs directly. Use your own signing config before publishing.

## Project layout

```
app/src/main/java/app/lumen/reader/
  core/      file detection, recents, thumbnails
  pdf/       PdfDoc (render engine), PdfViewer (zoom/pan list), PdfScreen
  pdf/engine PDF object reader/writer, page assembler, incremental annotator
  pdf/tools  page organiser
  scan/      edge detection, perspective warp, scan filters, scanner UI
  files/     folder browser
  rich/      one document model + renderer for Markdown/DOCX/PPTX/ODT/EPUB
  table/     CSV/XLSX parsing and the canvas grid
  text/      text loading, encoding detection, syntax highlighting
  image/ media/ archive/ viewer/   remaining viewers and shared chrome
  edit/      shared colour engine (Look), editor widgets, photo/ and video/ editors
  ui/        theme, Material components, home, settings
```
