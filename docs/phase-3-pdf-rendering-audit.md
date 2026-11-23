# Phase 3 — PDF Rendering Performance Audit

**Project:** `/mnt/d/xnotes/v1/xnotes-android`
**Branch audited:** `phase-2-stroke-quality` (working tree clean apart from the untracked phase plan)
**Scope:** Audit only. **No production source file was modified.** This report is the only file added.
**Date context:** Phase 3 pre-implementation audit, after Phase 2.
**Method:** Static inspection of the Kotlin platform/canvas/UI layer, the vendored PDFium JNI surface, the
Gradle/test setup, and the existing reports in `docs/`. No benchmark or device measurement is claimed;
anything runtime-dependent is marked **UNMEASURED**.

---

## 0. Executive summary

The project already has a more complete PDF rendering architecture than the phase plan assumes:

- PDFium is driven through a **process-wide, single-threaded, priority-ordered job queue**
  (`PdfiumThread`), with a `CancelToken` mechanism that reaches the native Cairo/PDFium render loop
  through `IFSDK_PAUSE`.
- Display pages are **not** re-rendered every frame. They are rasterised on a dedicated background
  executor into two layer caches (PDF/template **background** and transparent **ink**), pruned to a
  screen band, and blitted by `CanvasView.onDraw`.
- There is a **resolution cap plus a full-resolution “sharp viewport”** pass, so deep zoom remains
  sharp without whole-page mega-bitmaps.
- `kPageCache = 4` in `pdf_jni.cpp` is still present, but it is a cache of **four parsed `FPDF_PAGE`
  objects per document**, not four rendered page bitmaps. Rendered bitmaps live in `CanvasState`
  (`caches` / `bgCaches`) and are bounded by a page band, not by this constant.

The main gaps are in **scheduling and cancellation**, not in the pixel path:

1. The display render path never passes a `CancelToken`; obsolete page renders cannot be stopped.
2. The cache executor’s queued work cannot be removed, and the generation guard is checked only
   **after** the expensive work finishes.
3. Visible pages, prefetch, sharp viewport and background work largely share one priority
   (`VISIBLE_RENDER`); `SHARP` and `BG_REPAIR` are unused.
4. One background executor thread is shared by PDF bitmap builds and ink-cache rebuilds, so a PDF
   render blocks ink rebuilds.
5. There is no memory budget, no device-adaptive prefetch/cache sizing, and no cache drop on
   `onTrimMemory`.
6. A few user-triggered capture paths call the PDF renderer **synchronously on the main thread**.
7. There are no instrumentation/benchmark tests and no large-PDF rendering tests at all.

No evidence supports changing the final rendering resolution on weaker devices. The resolution
math is display-appropriate and should be preserved.

---

## A. Existing architecture

### A.1 Document opening and lifetime

| Step | Code |
|---|---|
| `PdfSource.open(file)` returns at once; `PdfiumDocument` is created and an `opening` job is submitted | `app/src/main/java/com/xnotes/platform/PdfSource.kt:379`, `PdfiumDocument.kt:35` |
| Open runs on `PdfiumThread.shared` at `INTERACTIVE` | `PdfiumDocument.kt:35`, `PdfiumThread.kt:137` |
| Native open: `open(2)` + `fstat64` + `FPDF_LoadCustomDocument` with a positioned `pread64` `FPDF_FILEACCESS` reader | `app/src/main/cpp/pdf_jni.cpp:74-103`, `nativeOpen` at `:357` |
| Handle holds fd, size, `FPDF_DOCUMENT`, optional form handle, page LRU and outline walk | `pdf_jni.cpp:45-55` |
| Page count read after open; `pageCount` waits for the open job | `PdfiumDocument.kt:42-46`, `pdf_jni.cpp:365-370` |
| Note pages are created up front from PDF page sizes at document DPI (`DEFAULT_DPI = 150`) | `platform/Pdf.kt:54-84`, `core/model/PageSize.kt:41` |
| Pages are keyed by `PdfKey(index, turn)`; a turned page is a distinct cache key | `PdfSource.kt:20-31`, `PdfiumDocument.kt:74-90` |
| Close cancels the document lifetime token, then queues `nativeClose` at `THUMBNAIL` | `PdfiumDocument.kt:160-168` |

A note therefore does **not** need PDFium to know its page count/sizes on reopen: they are stored in
the note manifest. PDFium opening happens lazily after the note model is shown.

### A.2 Display rendering pipeline (the path that matters)

```
CanvasView.onDraw (UI thread)
  ├─ visible page loop
  │    ├─ state.backgroundForOrSchedule(page)   → scheduleBg  → runAsync(cache thread xnotes-cache)
  │    │      └─ state.paintPageBackground(page, renderer, res, region)
  │    │           └─ Editor.paintBackground            (Editor.kt:2175)
  │    │                └─ PdfSource.renderRegion       (PdfSource.kt:147)
  │    │                     ├─ pageSizePoints → nativePageSizes
  │    │                     ├─ std imageRectsFor → nativeImageRects (if filter stamps images)
  │    │                     └─ Bitmap.createBitmap + PdfiumDocument.render
  │    │                          └─ pdfium.call(VISIBLE_RENDER)  → nativeRender (PDFium thread)
  │    └─ state.cacheForOrSchedule(page)        → scheduleInk → runAsync(cache thread)
  │           └─ renderInk (Skia only: strokes, shapes, flow; no PDF)
  └─ state.prefetchAndPrune()                   → cacheBand() → schedules neighbours, drops off-band
       ├─ backgroundForOrSchedule / cacheForOrSchedule for prefetch pages
       └─ dropCachesExcept(keep)

publish path (both builds)
  runAsync → postToMain (UI) → generation check → caches/bgCaches[page] = entry
           → onCacheReady() → CanvasView.requestRender()
```

Evidence: `CanvasView.kt:399-473` (draw loop), `CanvasView.kt:45-50` (executor wiring),
`CanvasState.kt:1255-1292`, `:1426-1459`, `:1841-1849`, `Editor.kt:2143-2210`.

Blitting: background first, then `paintUnderInk` (live flow/math), then ink; `drawRaster` is one
`Canvas.drawBitmap` per layer (`AndroidRenderer.kt:294-302`, `CanvasView.kt:427-437`).

### A.3 Native rendering

`nativeRender` (`pdf_jni.cpp:434-475`):

1. `GetPage(doc, index)` — LRU over at most `kPageCache = 4` parsed `FPDF_PAGE` objects per document
   (`pdf_jni.cpp:111-131`). A hit moves the page to the most-recent end; a miss loads and evicts the
   oldest.
2. `AndroidBitmap_lockPixels`, reject non-`RGBA_8888`.
3. `memset` the bitmap white.
4. `FPDFBitmap_CreateEx` **wraps** the Android pixel buffer (no second native bitmap).
5. `FPDF_RenderPageBitmap_Start(..., -left, -top, full_w, full_h, turn, FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER, pause)`.
6. `while (TOBECONTINUED && !cancelled) FPDF_RenderPage_Continue(...)`.
7. `FPDF_RenderPage_Close(page)`.
8. If the PDF has forms, `FPDF_FFLDraw` paints form fields.
9. `FPDFBitmap_Destroy`, `AndroidBitmap_unlockPixels`; returns whether the render completed.

The full page is passed as the render size (`full_w × full_h`) with a bitmap that may be only a
viewport region; PDFium clips to the destination. This is how `renderRegion` avoids huge bitmaps
while still producing region pixels (`PdfSource.kt:147-166`).

### A.4 Scheduling and priorities

`PdfPriority` (`PdfiumThread.kt:9`):
`INTERACTIVE, VISIBLE_RENDER, BG_REPAIR, SHARP, THUMBNAIL, SEARCH, WARM`.

- One daemon thread per process: `PdfiumThread.shared` (`PdfiumThread.kt:137`), used by **all**
  documents, both split-view panes, thumbnails, text and links.
- `PriorityBlockingQueue<PdfiumTask<*>>`; FIFO (`seq`) within a priority (`PdfiumThread.kt:60-61`).
- `submit` / `call`; `call` runs inline when already on the thread (`PdfiumThread.kt:126-133`).
- Thread priority is `Thread.NORM_PRIORITY` (`PdfiumThread.kt:121`).

### A.5 Cancellation

`CancelToken` (`PdfiumThread.kt:12-35`):

- `cancel()` sets `isCancelled` and runs registered hooks.
- A queued task’s hook removes it from the queue and completes its waiter.
- A running task is **not** interrupted; it finishes.
- `nativeRender` builds an `IFSDK_PAUSE` whose `NeedToPauseNow` reads the Java `isCancelled` fields
  of up to two tokens (`pdf_jni.cpp:315-338`), so a native render can be stopped between PDFium
  internal steps. Cleanup (`FPDF_RenderPage_Close`, unlock, destroy) always runs.

Two tokens are threaded into render: the document **lifetime** token and an optional per-call
`token` (`PdfiumDocument.kt:141-153`). `PdfSource.render` passes only the implicit lifetime token
and **no per-request token** (`PdfSource.kt:168-191`).

### A.6 Cache layers

| Cache | Key | Bounded by | Evidence |
|---|---|---|---|
| `caches` | `Page` | visible band (`cacheBand().keep`), no byte budget | `CanvasState.kt:524`, `:1780-1794`, `:1800-1839` |
| `bgCaches` | `Page` | same band | `CanvasState.kt:525` |
| `hlCaches` | `Stroke` | visible pages’ highlighters | `CanvasState.kt:537`, `:1380-1399` |
| `sharpFrame` | one viewport | exactly one | `CanvasState.kt:602`, `:1928-1982` |
| `PageTextCache` | page int | 4 pages LRU | `platform/PageTextCache.kt:118` |
| PDFium page LRU | page int | 4 per document | `pdf_jni.cpp:31`, `:111-131` |
| `pageThumbs` | `Page` | `LruCache` 24 MB | `Editor.kt:498-503` |
| `imageRects`, `geometries`, `pageSizes` maps | page int | unbounded, per open `PdfSource` | `PdfSource.kt:68-95`, `:236-240` |

`CacheEntry.usableFor` requires a matching footprint and resolution (resolution matching is relaxed
while `zoomingInProgress`) and that the page is not in `staleInk` (`CanvasState.kt:1246-1248`).

### A.7 Resolution, zoom and sharp viewport

- `renderScale` is pinned to `1.0`; the supersampling preference was removed (`Editor.kt:2775-2776`).
- A page cache is built at `clampedRes(page) = min(zoom, maxCachePx / max(footprint.w, footprint.h))`
  (`CanvasState.kt:1229-1236`), with `maxCachePx` defaulting to 2048 and user-settable 1024–4096
  (`Preferences.kt:82`, `:310`, `Editor.kt:2857`).
- `paintBackground` converts content pixels to PDF points with
  `pxPerPt = res * document.dpi / 72` (`Editor.kt:2194`), so the raster is exactly `res` device pixels
  per content pixel.
- Past the cap, `requestSharpViewport` renders **the visible slices of the visible pages only** into
  one viewport-sized base + ink surface at `res = zoom` (`CanvasState.kt:1928-2020`), 90 ms after the
  view settles (`CanvasView.kt:878`, `:511-520`).
- A sharp frame is reused across pan/zoom by re-fitting scale/translation until the settled re-render
  lands (`CanvasState.kt:1903-1916`).

### A.8 Thumbnails, links, text, outline

- Side-panel page thumbnails: `pageThumbnail` on `Dispatchers.Default` → `renderThumbnail` →
  `paintBackground` under `withPriority(THUMBNAIL)` using the live `PdfSource` (`Editor.kt:3702-3717`,
  `:3636-3669`).
- Explorer tiles open a **new `PdfSource` per tile**, render, then close (`Editor.kt:3951-3986`).
- Links: one parsed page held at a time, latest-wins warm pump on its own executor; tap parsing is
  `INTERACTIVE` (`PdfSource.kt:83-136`, `:250-338`).
- Text: `PageTextCache` LRU(4), latest-wins prefetch, `INTERACTIVE` for on-demand reads, `SEARCH` for
  search (`PdfSource.kt:55-65`, `PageTextCache.kt:39-118`).
- Outline is read in ~8 ms slices at `THUMBNAIL` (`PdfiumDocument.kt:110-138`, `pdf_jni.cpp:578-638`).

### A.9 Memory-pressure path

`EditorActivity.onTrimMemory` calls `PdfiumDocument.trimAll()` and `ImageDecoder.trim()` on
`UI_HIDDEN` / `RUNNING_LOW` / `RUNNING_CRITICAL` (`EditorActivity.kt:669-677`). `nativeTrim`
closes every loaded `FPDF_PAGE`, clears the LRU and, unless an outline walk is open, reloads the
document to free fonts/glyph caches (`pdf_jni.cpp:386-404`). It does **not** touch `CanvasState`’s
bitmap caches; no `onLowMemory` handler exists.

---

## B. Existing implementations that already work

1. **Process-safe PDFium threading.** A single shared thread with a priority queue is the correct
   design for a non-thread-safe library; `PdfiumThreadTest` covers priority ordering, FIFO within a
   priority, queued cancellation, inline calls, and error propagation (`test/.../PdfiumThreadTest.kt`).
2. **Native-safe cancellation hook.** `IFSDK_PAUSE` is wired to Java cancellation flags; all native
   cleanup is unconditional (`pdf_jni.cpp:315-338`, `:448-475`).
3. **Per-page build de-duplication.** `pendingInk` / `pendingBg` prevent the same page from being
   built twice concurrently (`CanvasState.kt:565-566`, `:1266-1268`, `:1435-1437`).
4. **Stale-result suppression.** `cacheGen` is captured at schedule time and checked before publish;
   `sharpGen` plus exact `sx/sy/z/row` checks protect the sharp frame; edits during a sharp build are
   replayed (`CanvasState.kt:1266-1292`, `:1962-1984`, `SharpViewportEditReplayTest.kt`).
5. **Two-layer cache.** Ink edits never re-rasterise the PDF background; erase/repair is region-based,
   and a committed stroke is appended in place when the cache exists (`CanvasState.kt:1527-1537`,
   `:1554-1564`).
6. **Band-based prefetch.** `cacheBand` is sized by screenfuls/rows, not by a flapping visible count,
   and `CacheBandTest` asserts one render per page on steady scroll, no re-render on back/forth, no
   eviction during a pinch, and neighbour prefetch in a gap (`CanvasState.kt:1800-1839`,
   `test/.../CacheBandTest.kt`).
7. **Quality-preserving zoom cap + sharp viewport.** Whole-page caches are capped; the final visible
   pixels are rendered at exact zoom in a viewport-sized surface. No device-dependent quality level.
8. **Latest-wins ancillary work.** Link warming and text prefetch both coalesce to the latest target
   (`PdfSource.kt:250-292`, `PageTextCache.kt:39-80`).
9. **Zero-copy native bitmap wrap** and form/annotation/`/Rotate` handling (`pdf_jni.cpp:441-470`).
10. **Measurement surface already present.** `DebugOverlay` reports fps/frame ms, cache resolution,
    visible/ink/bg page counts, cache bytes, Java heap, PSS, native, graphics, PDFium revision, open
    documents, loaded pages, last open/text ms (`DebugOverlay.kt:80-140`,
    `PdfiumDocument.kt:191-199`, `CanvasState.kt:2078-2093`).

---

## C. Confirmed problems

Each item below is supported by code inspection. Performance impact is **not measured**; the
mechanism and its consequences are what is confirmed.

### C1. Display renders are never cancelled (HIGH)

- `PdfSource.render` calls `pdf.render(..., priority())` with the default `token = null`
  (`PdfSource.kt:168-191`).
- `PdfiumDocument.render` accepts a token, and `nativeRender` polls it, but every production display
  caller omits it (`grep` over `app/src/main/java` found only this call site).
- Therefore a page render for a page the user has left runs to completion. Scrolling/page-jumping
  during a large render cannot free the PDFium thread for the new visible page.

### C2. Queued background builds cannot be cancelled or skipped (HIGH)

- `runAsync` is a plain single-thread `ExecutorService` (`CanvasView.kt:369-374`); queued runnables
  are not removable.
- `scheduleBg` and `scheduleInk` run the expensive build unconditionally; `cacheGen` is checked only
  inside `postToMain` after the build (`CanvasState.kt:1435-1447`, `:1266-1292`).
- `dropCachesExcept` prunes the maps but does not inform pending tasks (`CanvasState.kt:1780-1794`).
- Consequence: a fast fling across many pages can enqueue a PDF render (plus an ink raster) for every
  intermediate page; all of them execute, then most results are discarded. (`CacheBandTest` proves
  duplication is avoided on *steady* scroll, not that queued obsolete work is skipped.)

### C3. Priority model is flattened / partly dead (HIGH)

- `SHARP` and `BG_REPAIR` are declared but never used anywhere (repository-wide grep).
- Visible page builds, prefetch builds, markup rebakes and the sharp viewport all run at
  `VISIBLE_RENDER` because `PdfSource.priority()` reads only a `ThreadLocal` that these paths do not
  set (`PdfSource.kt:359-367`; `CanvasState.buildBackground` `:1449-1459`,
  `renderSharpFrame` `:1987-2020`, `scheduleRebake` `:1495-1513`).
- Text/link reads use `INTERACTIVE`, which sorts **above** `VISIBLE_RENDER`, so a link/text request
  can jump ahead of the current visible page raster (`PdfSource.kt:58`, `:64`, `:335`).
- The sharp viewport — the final-quality surface for deep zoom — is enqueued FIFO behind any prefetch
  renders already at the same priority.

### C4. One background thread couples PDF rendering and ink-cache rebuilds (HIGH)

- `CanvasView` uses one `newSingleThreadExecutor` for **all** `state.runAsync` work
  (`CanvasView.kt:369-374`, `:45-49`).
- `buildBackground` calls `PdfSource.renderRegion`, which blocks on `PdfiumThread`; while it waits,
  the cache thread cannot build ink caches for other pages.
- `appendToCache` is the fast path for a committed stroke, but if the target page has no usable cache
  (cold page, zoom change, or eviction) it falls back to `invalidatePage` and a full ink rebuild on
  the same thread, queued behind PDF work (`CanvasState.kt:1527-1537`).
- This is the plausible path by which PDF prefetch delays committed-ink settling; runtime impact is
  unmeasured, but the serialisation is certain.

### C5. Some PDF renders run on the main thread (MEDIUM/HIGH)

- `copyScreenshotAsImage`, `screenshotImageForShare`, `copySelectionAsImage` and
  `selectionImageForShare` are called from Compose click handlers (`CanvasMenus.kt:584`, `:601`,
  menu rows).
- They call `renderRegionBitmap` → `paintPageBackground` → `PdfSource.renderRegion` →
  `pdfium.call(...).await()` synchronously on the calling (main) thread (`Editor.kt:1911-1928`,
  `:2007-2046`).
- The render is prioritised `INTERACTIVE`, so it can also jump the queue, but the UI is blocked for
  the whole rasterisation.

### C6. No input-aware deferral of PDF work (MEDIUM)

- No PDF scheduling path inspects pen/touch state; no `PdfPriority` level represents input/ink.
- `PdfiumThread` and the cache executor run at normal thread priority (`PdfiumThread.kt:121`;
  cache thread is a default `newSingleThreadExecutor`).
- On a low-core device, a long PDF render competes directly with the UI/render/input threads and can
  cause GC and CPU contention. No throttling/deferral exists.

### C7. Memory-pressure handling does not cover the page caches (MEDIUM/HIGH)

- `onTrimMemory` trims PDFium pages/fonts and decoded images only (`EditorActivity.kt:669-677`).
- `CanvasState.caches`/`bgCaches` (the largest resident structures) are untouched; there is no
  `onLowMemory` and no byte-budget eviction.
- Caches are dropped only on document swap (`invalidateAllCaches`, `CanvasState.kt:1657-1667`) or by
  the next frame’s band prune.

### C8. No byte budget / device-adaptive sizing (MEDIUM/HIGH)

- The only limit is the per-bitmap long-edge cap (`maxCachePx`, default 2048). Nothing limits the
  number of cached pages or total cache bytes.
- `cacheSnapshot().bytes` already computes the total, but nothing consumes it for eviction
  (`CanvasState.kt:2086-2093`).
- `DeviceCapabilities` only probes for a stylus; there is no `ActivityManager.isLowRamDevice`,
  `memoryClass`, `availableProcessors`, or runtime heap check anywhere in `app/src/main/java`.

### C9. `kPageCache = 4` is not what it looks like (CONFIRMED, not itself a defect)

- It bounds `Doc::pages` — parsed `FPDF_PAGE` handles per open document, LRU, used by render, links,
  text and image-rect reads (`pdf_jni.cpp:31`, `:45`, `:111-138`).
- Rendered ARGB bitmaps are `CanvasState.CacheEntry` surfaces, bounded by the visible band. Raising
  `kPageCache` to 8/16 would only reduce `FPDF_LoadPage`/page-parse repeats when more than four pages
  are visited between renders; it would not add bitmap memory, and no evidence yet shows it is a
  bottleneck.

### C10. Full-size ink surface is allocated even for pages with no ink (MEDIUM)

- `hasPageBackground` gates the background surface for plain pages (`CanvasState.kt:1407-1409`), but
  `renderInk` always allocates a full-footprint transparent ARGB surface regardless of whether
  `items.under/over` and the flow are empty (`CanvasState.kt:1325-1339`).
- On an unannotated imported PDF every cached page therefore carries a full-size (~up to 2048-long-
  edge) transparent ink bitmap in addition to its PDF background. The exact byte share needs
  measurement, but the allocation is unconditional.

### C11. Filtered PDF renders allocate and copy a second full bitmap (MEDIUM)

- When `PdfPageFilter.pageMatrix != null` (invert/contrast/brightness/sepia/multiply/screen), `render`
  allocates the raw bitmap, renders, then allocates a second same-size bitmap and draws the first
  through a `ColorMatrixColorFilter`, then recycles the raw one (`PdfSource.kt:175-190`).
- This doubles transient peak for that page and adds one full-size Skia blit per build. Image re-
  stamping (`stampImages`) then draws image boxes again (`PdfSource.kt:191-233`).

### C12. Eviction relies on GC; document swap clears without recycling (LOW/MEDIUM)

- Page-cache eviction intentionally does not recycle (`CanvasState.kt:518-523` comment;
  `:1780-1794`), which avoids use-after-recycle but defers native pixel release to
  `Bitmap` finalisation/GC.
- `invalidateAllCaches` clears every map without recycling (`CanvasState.kt:1657-1667`); swapping a
  large PDF note can leave a large amount of bitmap memory awaiting GC while the new note allocates.

### C13. No rendering telemetry, tests or benchmarks (CONFIRMED)

- Only `lastOpenMs` and `lastTextMs` are timed (`PdfiumDocument.kt:57`, `:97`, `:183-184`). No per-render
  duration, queue depth/wait, cancellation count, cache hit/miss, or allocation rate.
- The project has **no `app/src/androidTest`** and no benchmark module; test deps are JUnit + JSON only
  (`app/build.gradle.kts:132-139`). There are no large-PDF, memory-pressure, gesture or rendering
  tests.

---

## D. Potential problems (require testing before being called defects)

| # | Suspicion | Why it is only potential | Evidence |
|---|---|---|---|
| D1 | Revisiting a page while its obsolete build is still pending shows blank paper and waits for the backlog | Mechanism is clear (`pendingInk` blocks rescheduling; pruned cache is null; result arrives only when old job runs), latency is unmeasured | `CanvasState.kt:1255-1264`, `:1266-1268`, `:1780-1794` |
| D2 | Rapid note switching retains closed PDFium documents/resources behind the queue | `close()` queues `nativeClose` at `THUMBNAIL`, behind opens/visible work; old lifetime cancels renders but not the close job | `PdfiumDocument.kt:160-168`, `PdfiumThread.kt:9` |
| D3 | Markup list read on the cache thread while the UI may mutate it | `paintBackground` reads `page.markups` live; `rebakeBackground` comments imply a live read; the model may replace (not mutate) lists, which needs confirmation | `Editor.kt:2176-2203`, `CanvasState.kt:1478-1513` |
| D4 | Sharp-frame coverage: while panning at deep zoom, the entering strip uses the capped soft cache | This is deliberate (`sharpViewportBlit` re-fits until settle), but its visible quality cost should be measured | `CanvasState.kt:1903-1916`, `CanvasView.kt:487-520` |
| D5 | Two split-view panes serialise all PDF work through one thread | By design for PDFium safety; the UX cost on tablets is unmeasured | `PdfiumThread.kt:137` |
| D6 | Region render still walks the whole page content in PDFium | `full_w × full_h` is the whole page; the destination only clips. Inherent to PDFium, but the cost ratio region/full is unmeasured | `pdf_jni.cpp:454-457` |
| D7 | Page-cache band sizing on tall pages / multiple visible pages may exceed a 2 GB device’s practical heap | Band and byte formula are known; actual PSS is not | `CanvasState.kt:1800-1839`, `:2086-2093` |
| D8 | Explorer thumbnails open a fresh `PdfSource` per tile | Confirmed code, but it is Phase 4 (storage) territory and outside the display hot path | `Editor.kt:3951-3986` |

---

## E. Performance bottlenecks (ranked by expected impact, not measured)

1. **Obsolete rendering cannot be stopped** (C1 + C2). Every abandoned page costs CPU on the only
   PDFium thread and blocks the job the user actually needs. This is the largest structural cost on
   fast navigation and the one most aligned with the Phase 3 objectives.
2. **Priority flattening and dead priorities** (C3). Even with cancellation, the visible page and the
   sharp viewport can sit behind prefetch/rebake work, and text/link reads can pre-empt the page
   raster. Fixing priorities is cheap and independent of cancellation.
3. **Shared cache thread** (C4). A single PDF call serialises all ink-cache rebuilds for the note,
   including post-commit `invalidatePage` fallbacks.
4. **Main-thread capture renders** (C5). Rare, but a guaranteed UI stall (up to a full 2× region
   render) on screenshot/share/copy.
5. **Unbounded bitmap memory and no pressure response** (C7 + C8 + C10 + C12). Not a frame-rate
   bottleneck by itself, but the main OOM/GC risk on 2 GB devices and the reason peak memory is hard
   to predict.
6. **No input-aware deferral / normal thread priority** (C6). Low-end CPU contention shows up as
   frame drops and occasional ink-handover delay; entirely unmeasured.
7. **Filtered-render double allocation** (C11). Only for notes using PDF colour filters, but it is a
   per-build 2× transient.
8. **Single shared PDFium thread across panes/thumbnails** (D5). Throughput cap, not a latency bug
   in the single-pane case.

Explicitly **not** identified as a bottleneck by the code: the number of rendered bitmaps implied by
`kPageCache`; whole-page re-rendering on every frame (does not happen); or rasterisation on the UI
thread during normal scrolling (does not happen).

---

## F. Memory management findings

### F.1 What is resident

- Per cached page: background surface + ink surface, both `ARGB_8888`, sized
  `ceil(footprint × clampedRes)`. At `maxCachePx = 2048` and a full-page footprint, each layer is
  roughly 16 MB for a square sunken page and ~11–13 MB for an A4-ish page on a tablet; a page band
  keeps several. `cacheSnapshot().bytes` reports the real total live.
- Plus: sharp base + sharp ink (two viewport-sized surfaces), highlighter ribbons, text (4 pages),
  thumbnails (24 MB LRU), decoded images, PDFium glyph/font caches, GL buffers, and the note model.

### F.2 Budget, eviction, protection

- No byte or device-memory budget. Eviction is **band membership**: `dropCachesExcept(cacheBand.keep)`
  each frame (`CanvasState.kt:1780-1794`, `:1841-1849`).
- The current page is always inside `keep` (the band covers visible rows ±1 and one further row), so
  it is protected from eviction; nearby pages get one screenful/row of retention. Off-band pages are
  dropped. Thumbnails and text have their own small bounded caches.
- There is no hit/miss counter. The existing HUD exposes occupancy and bytes only.

### F.3 Allocation, reuse, release

- Every page build allocates fresh bitmaps (`AndroidRasterSurface.create` →
  `Bitmap.createBitmap`, `AndroidRasterSurface.kt:29-34`). No `BitmapFactory.Options.inBitmap` and no
  page-bitmap pool exists.
- The only reuse is `WetInkCache`, which keeps one surface for the live stroke
  (`CanvasState.kt:1674-1692`), plus `repairRegion`/`appendToCache` mutating an existing page surface.
- Explicit `recycle()` is used for transient render results (PDF region, thumbnails, exports, PDF
  ItemRaster); cached page surfaces are intentionally left to GC.
- Bitmap pixel data lives in native memory on API 26+, so the GC pressure is native-accounting rather
  than Java-heap copies; this lowers the case for a hand-written pool. No measurement currently
  justifies one.

### F.4 Duplication

- Filtered builds duplicate the full page (C11).
- At deep zoom the same visible pixels exist in the capped background cache and in the sharp base
  surface (by design).
- The same PDF page is rasterised once for the canvas background and once for a side-panel thumbnail
  at a different size (different consumers, expected).
- An empty ink layer duplicates a full transparent page per cached page (C10).

### F.5 `kPageCache = 4` revisited

It is a **parsed-page** LRU, per document. It does not control rendered bitmap count or their memory.
Because:
- a page is normally rendered once per resolution/content generation and then served from
  `bgCaches`;
- text search uses `nativePageChars`’s own transient load without disturbing the LRU
  (`pdf_jni.cpp:714-742`), and geometry does the same (`:694-712`);
- the visible band is typically 1–3 pages in a single-pane note;

the practical benefit of 8/16 is likely small. It should be decided from counters (page loads per
render, `FPDF_LoadPage` time) rather than assumed. The report therefore does **not** recommend
changing it as part of Phase 3 stage 1.

---

## G. Visual quality findings

### G.1 How resolution is determined (verified)

- `res = zoom × renderScale`, `renderScale = 1.0`, capped per page by `maxCachePx / longEdge`.
- The PDF is rendered at `pxPerPt = res × dpi / 72`; because note page pixels are `points × dpi / 72`,
  this is exactly `res` device pixels per note pixel.
- The canvas draws in device pixels at `zoom` content-pixels-per-device-pixel, so at `zoom = 1` the
  output is 1 PDF-render pixel per device pixel. **Display density is therefore implicitly honoured**:
  the viewport is already in device pixels, and `zoom = fitWidth` produces a raster matched to the
  physical screen. There is no missing DPI factor.
- Past the cap, the sharp viewport is rendered at `res = zoom` into a viewport-sized surface, giving
  exact 1:1 device pixels for the visible page slices.

### G.2 Quality requirements check

| Requirement | Current status |
|---|---|
| Text clarity | Preserved for cached pages and for the sharp viewport; no quality-reducing device tier. |
| Vector quality | PDFium rasterises; no intermediate downscale in the normal path. |
| Image quality | Preserved; optional “keep images unfiltered” re-stamps originals. |
| Display-appropriate resolution | Yes: resolution derives from viewport device pixels; extra supersampling is not used now. |
| Deep-zoom sharpness | Yes for the visible viewport after the 90 ms settle; soft (capped) cache is shown temporarily during pinch/pan. |
| Permanent low-res on weak devices | None exists; none should be added. |

### G.3 Risks to quality (not current defects)

- `maxCachePx` is user-settable down to 1024. A user selecting that sees a softer page until the
  sharp viewport covers it; the sharp pass still restores visible sharpness, so this is a transitional
  / edge-of-screen trade-off, not permanent degradation.
- During a pinch, stale-resolution surfaces are stretched (`zoomingInProgress` bypasses the resolution
  check, `CanvasState.kt:1246-1248`); sharpness returns at settle.
- While panning at deep zoom, the strip entering the viewport uses the capped soft cache until the
  next settle. This is the main quality/performance trade-off already in the design.

No change to `renderScale`, `maxCachePx` semantics, `pxPerPt`, or the sharp-viewport resolution should
be made to improve performance.

---

## H. Adaptive rendering assessment

The architecture has the right seams but almost no adaptation today:

| Dimension | Current | Adaptive? |
|---|---|---|
| PDFium concurrency | 1 process-wide thread (required for safety) | Not safely increaseable; job ordering is the lever |
| Cache/backgroud executor | 1 thread shared by PDF + ink | Can be split/serialised by job class |
| Prefetch distance | fixed: one screen/row each side + one keep row | Could scale with device/memory |
| Cache budget | none (per-bitmap long-edge cap only) | Could add byte budget + pressure eviction |
| Bitmap pool | none | Could add only if measurement shows benefit |
| Queue priorities | fixed enum, partly unused | Easy to make meaningful |
| Cleanup on pressure | PDFium/images only | Could include canvas caches |
| Thread priority | normal | Could lower prefetch/thumbnail threads |
| Device class input | stylus only | Could add memory class/cores/low-RAM |

No code uses `ActivityManager` (`isLowRamDevice`, `memoryClass`), `Runtime.availableProcessors`, or
runtime heap headroom. There is no “device profile” abstraction; `maxCachePx` comes from a user
preference, not the device.

**Conclusion:** adaptive strategy is absent but not blocked. Add it as a separate stage, driven by
measured profiles, and restrict it to background resource allocation — never to final page
resolution or glyph rasterisation scale.

---

## I. Missing tests

### I.1 JVM unit tests (feasible now)

- A scheduler/queue test for per-page render requests: visible > prefetch, cancellation on band
  exit, obsolete-build skip before work, duplicate suppression, generation discard.
- Cancellation plumbing test with a fake `PdfSource`/renderer: obsolete request result must not
  publish; in-flight request must observe cancel and return null.
- Cache-budget eviction tests (once introduced): current page never evicted; budget respected;
  projection after document swap.
- A test that a page with no ink and no flow requests no full-size ink surface (once introduced).
- Regression test that a filtered page build still produces identical pixels to the reference
  (colour-matrix path) and recycles the raw bitmap.
- Existing tests must keep passing: `PdfiumThreadTest`, `CacheBandTest`, `SharpViewportEditReplayTest`,
  `PageTextCacheTest`, `GeometryEvictionTest`, `WetInkCacheTest`, Phase 1/2 stroke tests.

### I.2 Instrumentation tests (new `app/src/androidTest`)

These need a real Android runtime/Bitmap/PDFium, so they cannot be JVM tests:

- Open a 400-page PDF, scroll rapidly to the end and back; assert first-page and final-quality
  deadlines, no ANR, no OOM, bounded cache.
- Page navigation via TOC/bookmarks and page indicator; measure navigation latency and cancelled
  render count.
- Continuous scrolling and zoom while a synthetic stylus stroke is drawn; assert frame statistics
  and that live-ink handover does not stall.
- Screenshot/selection copy while a background render is running; assert no main-thread block
  beyond a budget (after C5 is fixed).
- `am send-trim-memory` at each level; assert cache bytes drop and the current page remains visible.
- Repeated open/close of large PDFs; assert fd count, native PSS and document count return to
  baseline.
- Deep zoom + pan; screenshot comparison of the sharp viewport against a reference render.

### I.3 Benchmark module

Add a `benchmark` (Macrobenchmark) module, not just unit timing:

- cold open → first visible page, cold open → final-quality page;
- fling through 400 pages: frame timing/jank, queue occupancy samples;
- pinch zoom settle: time to sharp frame;
- handwriting during background render: input-to-ink latency and frame drops;
- memory pressure scenario: peak PSS, GC counts, bitmap allocation rate;
- stability: repeated runs for regressions.

### I.4 Metrics to instrument (before/with Phase 3)

Time to first visible page, time to final-quality page, page navigation latency, per-render duration,
PDFium queue depth and per-priority wait time, cancellation effectiveness (requested vs completed
cancellations), cache hit/miss, bitmap allocation bytes/rate, peak PSS/native/graphics, GC count and
pause, UI frame drops, and sharp-frame settle time. `DebugOverlay` already has most memory readouts;
the missing pieces are timing and counters.

---

## J. Recommended implementation plan

Stages are independent where possible. Priorities: **P0** = do first (highest risk reduction),
**P1** = should follow, **P2** = optional/measure-first, **P3** = defer until evidence.

### Stage 0 — Instrumentation and baseline (P0)

- **Files:** `PdfiumThread.kt`, `PdfiumDocument.kt`, `PdfSource.kt`, `CanvasState.kt`,
  `DebugOverlay.kt`, new `app/src/androidTest`, new benchmark module.
- **Classes/methods:** `PdfiumTask`, `PdfiumThread.submit/call`, `PdfiumDocument.render`,
  `PdfSource.render/renderRegion`, `CanvasState.scheduleInk/scheduleBg/prefetchAndPrune`,
  `DebugOverlay.draw`.
- **Current:** only open/text timings; cache counts/bytes; no queue or render timing.
- **Proposed:** debug-only counters and timings (queue depth/wait by priority, render ms, cancel
  count, build-skip count, cache hit/miss, allocated bytes). No behaviour change.
- **Benefit:** makes every later claim measurable and prevents speculative optimisation.
- **Risks:** hot-path overhead if not gated; keep counters cheap/atomic.
- **Tests:** assertions on counters in scheduler unit tests; baseline benchmark recorded in `docs/`.
- **Dependencies:** none. **Do this before any optimisation.**

### Stage 1 — Per-request cancellation and priority propagation (P0)

- **Files:** `CanvasState.kt`, `Editor.kt` (`paintBackground`), `PdfSource.kt`,
  `PdfiumDocument.kt` (already accepts a token), `PdfiumThread.kt` if cancellation hooks are
  extended.
- **Classes/methods:** `CanvasState.scheduleBg/scheduleInk/requestSharpViewport`,
  `PdfSource.renderRegion/render`, `PdfiumDocument.render`.
- **Current:** display renders pass no token; obsolete work runs to completion.
- **Proposed:** create a per-page/per-view request token in `CanvasState`; cancel it when the page
  leaves the cache band, when content/zoom invalidates the build, or when a newer request for the
  same page supersedes it; pass it through `renderRegion`/`render`; check the token before the
  expensive bitmap allocation; keep the document lifetime token as the outer token. Do not cancel
  ink-cache writes, only PDF raster requests.
- **Benefit:** immediate CPU saving on fling/jump and faster current-page turn-around; uses the
  already-safe native pause mechanism.
- **Risks:** cancelling too aggressively can leave the current page blank; cancellation must be
  band-aware and never cancel the current visible page. Native cleanup path is already safe.
- **Tests:** scheduler/fake-render tests for cancel-on-band-exit and current-page protection;
  `PdfiumThreadTest` extensions for two-token tasks; instrumentation cancellation count.
- **Dependencies:** Stage 0 metrics.

### Stage 2 — Queue and executor scheduling (P0/P1)

- **Files:** `CanvasState.kt`, `CanvasView.kt`, `PdfiumThread.kt`, `Editor.kt`.
- **Classes/methods:** `CanvasState.scheduleBg/scheduleInk/prefetchAndPrune/scheduleRebake`,
  `PdfSource.withPriority/priority`, `CanvasView.onDraw`/`prefetchAndPrune` call sites,
  `PdfPriority`.
- **Current:** one flat `VISIBLE_RENDER` for visible/prefetch/sharp/rebake; `SHARP`/`BG_REPAIR`
  unused; `INTERACTIVE` can pre-empt visible; queued obsolete builds still execute.
- **Proposed:** (a) pass an explicit priority into scheduling calls; use `SHARP` for the sharp
  viewport, `BG_REPAIR` for markup rebake, a prefetch/warm level for neighbours; (b) skip a queued
  build before doing work when its generation/band is stale; (c) avoid `INTERACTIVE` pre-emption of
  the current page unless the request is a user tap/waits-on-UI; (d) split or prioritise the cache
  executor so ink rebuilds are not blocked behind PDF calls (e.g. separate ink executor/queue, or
  dispatch PDF builds to a PDF worker and keep ink on another).
- **Benefit:** guarantees “current visible page highest PDF priority” and removes wasted queued work.
- **Risks:** starving thumbnails/text; ink/PDF ordering changes could reveal cache races; keep a
  bounded fairness mechanism. Do not change PDFium single-thread correctness.
- **Tests:** priority-order unit tests per path; `CacheBandTest` extended to assert obsolete work is
  skipped; instrumentation on fling.
- **Dependencies:** Stage 1 cancellation tokens (or compatible epoch checks).

### Stage 3 — Memory budget and pressure response (P1)

- **Files:** `CanvasState.kt`, `CanvasView.kt`, `EditorActivity.kt`, new small device-profile helper
  (e.g. `platform/DeviceProfile.kt`).
- **Classes/methods:** `CanvasState.cacheSnapshot/dropCachesExcept/cacheBand`, `onTrimMemory`.
- **Current:** band-only eviction, no byte budget, no cache drop on trim.
- **Proposed:** compute a cache budget from `ActivityManager.memoryClass`/`isLowRamDevice` and runtime
  headroom; evict by bytes/LRU while always keeping the visible page and mid-gesture surfaces; on
  `RUNNING_LOW/CRITICAL` and `UI_HIDDEN`, drop off-band caches and clear the sharp frame; scale
  prefetch distance with the profile; keep `maxCachePx` as a quality setting, not a stealth quality
  reducer.
- **Benefit:** predictable peak memory on 2 GB devices; fewer OOM kills; adaptive without touching
  visual quality.
- **Risks:** evicting too eagerly causes re-renders and scroll jank; never evict the current page or
  a layer being built. Budget must account for both layers and highlighter ribbons.
- **Tests:** budget unit tests; instrumentation trim-memory scenarios; 400-page peak-PSS benchmark.
- **Dependencies:** Stage 0 metrics; ideally Stage 2 priority so re-renders are correctly ordered.

### Stage 4 — Allocation and copy reduction (P2, measure first)

- **Files:** `CanvasState.kt` (`renderInk`, `buildBackground`), `PdfSource.kt` (`render`),
  `AndroidRasterSurface.kt`.
- **Current:** full-size ink surface for ink-free pages; second full bitmap for filtered pages;
  fresh allocations per build.
- **Proposed:** represent an empty ink layer without a full surface (or a shared 1×1), rebuild on
  demand when ink appears; evaluate filtering at blit time vs baking (only if image re-stamping rule
  can be preserved); consider a bounded size-class pool only if allocation/GC measurements justify it.
- **Benefit:** lower per-page memory and allocation/GC pressure.
- **Risks:** ink must remain correct under `appendToCache`/`repairRegion`/sharp replay; filtered
  colours and “keep images unfiltered” must remain identical; pooling risks use-after-recycle.
- **Tests:** pixel-equality tests for filtered output; empty-ink page test; existing cache-repair
  tests; instrumentation allocation/PSS.
- **Dependencies:** Stage 0 measurements; avoid simultaneous changes to scheduling.

### Stage 5 — Progressive first paint (P2/P3)

- **Files:** `CanvasState.kt`, `Editor.kt`, `PdfSource.kt`, `PdfiumDocument.kt`.
- **Current:** a never-rendered page shows paper until the full-resolution page build lands.
- **Proposed:** optionally render a small preview (e.g. thumbnail-resolution page) to the cache and
  replace it with the final-quality build when it arrives; token-cancel the preview when the view
  moves; guarantee eventual full quality at the current zoom.
- **Benefit:** faster first visible content on large documents.
- **Risks:** extra render work competes with the final render; preview must never become the
  permanent state; quality must not regress. Only pursue if baseline shows first-page latency is a
  real pain point.
- **Tests:** first-visible vs final-quality timing; visual equivalence at settle; cancellation.
- **Dependencies:** Stages 1–2.

### Stage 6 — Tile-based rendering (P3, only with evidence)

- **Files:** `CanvasState.kt`, `PdfSource.kt`, `Editor.kt`.
- **Current:** region rendering for the sharp viewport; whole-page soft caches.
- **Proposed:** evaluate a tile map only if measurement shows re-rendering cost at deep zoom/pan is
  significant after Stages 1–3. A tile map adds cache eviction, seams, and bookkeeping; the existing
  sharp viewport may already be sufficient.
- **Benefit:** possible cheaper deep-zoom updates.
- **Risks:** seam artefacts, memory overhead, complex invalidation, quality mistakes. **Do not**
  introduce tiles merely because the plan mentions them.
- **Tests:** sharpness comparison, memory budget, pan/zoom latency.
- **Dependencies:** all prior metrics; likely not needed.

### Stage 7 — Move capture renders off the main thread (P1, small)

- **Files:** `Editor.kt` (`copyScreenshotAsImage`, `screenshotImageForShare`,
  `copySelectionAsImage`, `selectionImageForShare`, `renderRegionBitmap`), `CanvasMenus.kt`.
- **Current:** synchronous PDF region render on the main thread.
- **Proposed:** run the capture on a background scope with a progress/message, then post the result;
  keep the existing `INTERACTIVE` priority for the capture.
- **Benefit:** removes a guaranteed UI stall without touching normal scrolling.
- **Risks:** lifecycle (note switch during capture), progress/error handling, sharing file lifecycle.
- **Tests:** instrumentation capture-during-render.
- **Dependencies:** none; can run parallel with Stage 1.

### Stage 8 — Evaluate `kPageCache` (P3, measurement only)

- **Files:** `app/src/main/cpp/pdf_jni.cpp`.
- **Current:** 4 parsed pages per document, LRU.
- **Proposed:** add counters for page loads/reloads and time; decide 8/16 only if reloads are a
  measurable fraction of render time. Do not change it on principle.
- **Risks:** negligible memory increase; possible marginal benefit. Not a priority.

---

## K. Risks and dependencies (Phase 1 / Phase 2)

1. **Ink cache paths are shared.** `appendToCache`, `repairRegion`, `refreshInkRegions`,
   `appendToSharpInk`, `repairSharpInk` and `SharpViewportEditReplayTest` are the Phase 1/2
   handover/quality baseline. Any scheduling change must not reorder or delay these in a way that
   drops a committed stroke, erases it twice, or repaints the PDF background unnecessarily.
2. **Do not alter the page-space transform / `PdfKey` / turn math.** Cross-page strokes, rotation,
   and Phase 2 geometry tests depend on it (`CanvasState.displayToPage`, `applyPageTransform`,
   `PdfKey`).
3. **Do not reduce resolution or supersampling.** Phase 2 verified current ink geometry against the
   existing raster pipeline; changing `res` or the sharp/viewport math risks perceived stroke quality.
4. **Cancellation must be scoped to PDF raster requests.** Cancelling an ink cache build or a
   background rebake can resurrect old pixels or blank a page; only the PDF request token should be
   cancelled by navigation/zoom.
5. **Memory-budget eviction must protect live surfaces** (`staleInk`, `pendingSharp`, sharp frame
   being built, `zoomingInProgress`, current page) or scrolling will flash/blank.
6. **Cache thread split risks concurrency.** `caches`/`bgCaches` are main-thread-only maps; any new
   executor must still publish through `postToMain`, and snapshots must stay immutable.
7. **Priority changes can starve ancillary work.** Keep text/link warmth bounded and observable; a
   starved link parser regresses tap latency.
8. **Shared PDFium thread is a correctness constraint.** Do not add concurrency inside PDFium; adapt
   ordering, cancellation, and resource budgets instead.
9. **Phase 1 F1 is still open** (palm/second pointer can pinch before the provisional pen resolves)
   and Phase 2 is baseline-only pending device evidence. Phase 3 scheduling must not mask or worsen
   input classification timing; no PDF work should run on the input thread.
10. **No device measurements yet.** `PROJECT_STATE.md` records that the target interactive display was
    unavailable. Any adaptive profile must be validated on the emulator profiles below and then on
    hardware; emulator GPU timings are not proof of real display performance.

---

## L. Cross-device / emulator testing strategy

Three test profiles (testing configuration, **not** assumptions about shipped devices):

| Profile | Emulator memory | CPU | Display | Purpose |
|---|---|---|---|---|
| Low-end | 2 GB (`-memory 2048`) | 1–2 cores (`-cores 2`), no snapshot | 1080×1920 @ 320–420 dpi | Pressure/OOM, cache budget, background priority |
| Mid-range | 4 GB (`-memory 4096`) | 4 cores | 1440×2200 @ 320 dpi | Typical tablet portrait/landscape |
| High-end | 8 GB (`-memory 8192`) | 8 cores | 2560×1600 @ 320–420 dpi | Large page caches, sharp viewport |

Methods:

- `adb shell wm size` / `wm density` to sweep resolutions/densities without rebuilding.
- `am send-trim-memory … RUNNING_LOW|RUNNING_CRITICAL` for pressure.
- `dumpsys meminfo`, `dumpsys gfxinfo`, Perfetto for memory/frames (Phase 0 protocol).
- Use `-gpu swiftshader_indirect` for functional raster tests and `-gpu host` for relative
  comparisons only; **never** claim emulator GPU numbers represent a physical panel.
- Physical hardware is required for: real stylus/palm behaviour, true GPU/GL wet-ink interaction,
  display-specific touch sampling, real thermal throttling, and final perceived sharpness.

What can be validated on an emulator: functional correctness, cache/eviction logic, memory
budgeting, cancellation counts, scheduler ordering, no-ANR, frame timing under CPU pressure.
What cannot: actual hardware rendering throughput, GPU composition cost, panel dpi perception,
stylus latency, thermal behaviour.

---

## M. Readiness assessment

**Ready to start Phase 3?** Yes for **Stage 0 (instrumentation + baseline) only**.

Reasons:

- The architecture is understood and the risky areas are localised: scheduling/cancellation in
  `CanvasState`/`PdfiumThread`/`PdfSource`, memory policy in `CanvasState`/`EditorActivity`, and two
  main-thread capture functions in `Editor`.
- The native cancellation mechanism is already correct and safe, so Stage 1 is a plumbing exercise,
  not a new concurrency design.
- No evidence supports changing rendering resolution, `kPageCache`, or adding tiles/pools; those
  must wait for measurement.
- Phase 1 F1 and Phase 2 device review are still outstanding per `PROJECT_STATE.md`; Phase 3 work
  must not touch input classification or stroke geometry.

Recommended gate: approve Stages 0–2 (instrumentation, per-request cancellation, scheduling/priority)
together as the first implementation tranche; decide Stages 3–8 from the baseline numbers. Do not
begin the implementation tranche until this report is reviewed.

---

## Appendix — Evidence index (selected)

| Claim | Evidence |
|---|---|
| Single shared PDFium thread + priority queue | `PdfiumThread.kt:39-137`, `PdfiumThreadTest.kt` |
| Native pause/cancel hook | `pdf_jni.cpp:315-338`, `:448-475` |
| Display render passes no per-request token | `PdfSource.kt:168-191`, `PdfiumDocument.kt:141-153` |
| Queued builds not removable / gen checked at publish | `CanvasState.kt:1266-1292`, `:1435-1447`, `CanvasView.kt:369-374` |
| Flat priorities / dead `SHARP`,`BG_REPAIR` | `PdfiumThread.kt:9`, repository grep; `CanvasState.kt:1449-1459`, `:1987-2020` |
| One executor for PDF + ink | `CanvasView.kt:369-374`, `CanvasState.kt:1325-1339` |
| Main-thread capture renders | `Editor.kt:1911-1928`, `:2007-2046`, `CanvasMenus.kt:584,601` |
| Cache band / no byte budget / no recycle | `CanvasState.kt:1780-1849`, `:2086-2093`, `:518-525` |
| No pressure handling for canvas caches | `EditorActivity.kt:669-677` (only PDFium/images) |
| Empty ink surfaces | `CanvasState.kt:1325-1339` |
| Filtered double allocation | `PdfSource.kt:175-190` |
| `kPageCache` scope | `pdf_jni.cpp:31`, `:45`, `:111-138` |
| Resolution math | `CanvasState.kt:1229-1236`, `Editor.kt:2194`, `CanvasState.kt:1928-2020` |
| Existing caches/tests | `CacheBandTest.kt`, `SharpViewportEditReplayTest.kt`, `PageTextCacheTest.kt` |
| No instrumented tests / benchmark module | `app/build.gradle.kts:132-139`; no `app/src/androidTest` |


---

## N. Post-implementation status — Medium-priority tranche

This table is the current status after the Phase 3 high-priority commits (`79238d10`, `a4eda44c`,
`6b54d40f`, `ad017c93`) and the Medium-priority tranche documented in
`docs/PHASE_3_HIGH_PRIORITY_FIXES.md` section N. Nothing here is a hardware measurement.

| Finding | Status | Notes |
|---|---|---|
| **C1** Display renders never cancelled | **Resolved** | Per-request `CancelToken` reaches the PDFium pause hook; cancelled results do not publish. |
| **C2** Queued background builds cannot be skipped | **Resolved** | Generation/token/band checked before allocation and PDFium; identity-checked pending cleanup. |
| **C3** Flat/dead priorities | **Resolved** | `INTERACTIVE > SHARP > VISIBLE_RENDER > BG_REPAIR > THUMBNAIL > SEARCH > WARM`, propagated explicitly. |
| **C4** One executor for PDF + ink | **Resolved** | Separate `xnotes-ink` and `xnotes-pdf` workers; PDFium remains single-threaded. |
| **C5 / M1** Main-thread capture renders | **Deferred** | Still synchronous on screenshot/selection copy/share. A safe async change needs snapshot/identity guards, an async share contract and instrumentation; not implemented speculatively. |
| **C6 / M2** No input-aware deferral | **Resolved in code; hardware validation outstanding** | Pointer down suspends speculative prefetch and cancels in-flight `WARM`; a visible page promotes its WARM request. No timer. Input-latency effect unmeasured. |
| **C7 / M3** Memory pressure ignores page caches | **Partially resolved** | `trimMemoryCaches()` drops off-screen ink/background/highlighter caches and the sharp frame on trim, keeping visible pages; no surface is recycled. |
| **C8 / M3** No byte budget / adaptive sizing | **Deferred** | Only reliably measurable raster bytes exist; native/RenderThread/GC memory is not precisely accountable and no device baseline justifies a limit. |
| **C9** `kPageCache = 4` | **Not applicable (by design)** | It bounds parsed `FPDF_PAGE` handles, not rendered bitmaps. Unchanged. |
| **C10 / M4** Empty ink surface per page | **Resolved in code; runtime saving unmeasured** | Ink-free pages share one 1×1 sentinel; flow-content predicate preserves pages that actually paint text. |
| **C11** Filtered render double allocation | **Deferred** | Still present (`PdfSource.render` raw + filtered bitmaps). Outside the M1–M4 subset; needs pixel-equality and transient-memory validation. |
| **C12** Eviction relies on GC | **Partially addressed** | Pressure eviction drops off-screen references sooner; intentionally still no cache `recycle()`. |
| **C13** No telemetry/benchmarks/androidTest | **Deferred** | No instrumentation or Macrobenchmark module exists in this checkout. |
| **D1–D8** Potential issues | **Unchanged** | No hardware evidence was produced; sharp/pan quality and split-pane throughput remain unmeasured. |
| **Stage 0 instrumentation** | **Not implemented** | Still the prerequisite for any numeric performance claim. |
| **Stage 4 allocation/copy work** | **Partially implemented (M4 only)** | Empty ink surfaces done; filtered double allocation and pooling not done. |
| **Stage 5 progressive first paint / Stage 6 tiles** | **Not started** | No evidence justifies them. |
| **Stage 7 capture off main thread** | **Deferred** | Same as C5/M1. |
| **Stage 8 `kPageCache`** | **Not started / measurement-only** | Unchanged. |

### What still requires physical-device testing

- Real stylus/finger input latency with background PDF rendering, including the M2 deferral and WARM
  promotion policy.
- `onTrimMemory` behavior and peak PSS/native/graphics after M3 pressure eviction.
- Actual memory saved by M4 on unannotated PDFs (JVM tests prove allocation shape, not PSS).
- Capture/sharing responsiveness until M1 is refactored, and the refactor itself.
- Fling-phase scheduling (deferral ends at pointer-up; visible priority and promotion are the only
  mechanisms during inertia).
