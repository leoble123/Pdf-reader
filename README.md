# Lumen

A fast, offline file reader for Android with a liquid-glass UI.

Lumen opens PDFs and most other common files. Everything is drawn on the device. The app has **no `INTERNET` permission**, so it can't send your files anywhere.

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

## How it stays smooth

* **PDF zoom is a GPU layer transform.** Pinching doesn't re-measure, recompose or re-render anything. After the gesture ends, only the visible part of each page is re-rendered at full resolution (`PdfDoc.renderRegion`). Deep zoom stays sharp without allocating very large bitmaps.
* **PDF rendering runs on one dedicated thread fed by a priority queue.** Visible pages go before prefetches. Among equal priorities the most recent request goes first, so a fast fling renders the page you stop on. Requests for pages that have already scrolled away are dropped. Each page shows a quick low-res preview first, then fades to full quality. The pages next to the current one are prefetched.
* **Spreadsheet columns are virtualized in the draw phase.** Scrolling sideways only redraws; it never recomposes.
* **Everything heavy runs off the main thread**: parsing, decoding, searching and thumbnails.

## Build

```bash
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest    # parser tests + JVM-rendered screenshots (app/build/screenshots)
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
  ui/        theme, glass components, home screen
```
