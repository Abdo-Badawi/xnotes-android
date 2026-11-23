# Phase 3 — High-Priority PDF Rendering Fixes (C1–C4)

**Branch:** `phase-3-pdf-rendering`
**Implementation commit:** `79238d10` — `Fix high-priority PDF rendering scheduling issues`
**Scope:** Only audit items C1–C4. No Bitmap Pool, tiles, adaptive memory budgets, progressive
rendering, page-cache size changes, or final-quality changes.

---

## A. Summary

The four HIGH-priority findings in `docs/phase-3-pdf-rendering-audit.md` are fixed:

1. **C1 — Display renders are now cancellable per request.**
   Every scheduled PDF background render gets a `CancelToken`. It is passed from `CanvasState`
   through the `paintPageBackground` hook → `Editor.paintBackground` → `PdfSource.renderRegion` →
   `PdfiumDocument.render` → `nativeRender` (`IFSDK_PAUSE`). The existing document-lifetime token is
   preserved as the outer token. Cancellation is checked before bitmap allocation, before the PDFium
   call, and again before publication; the native cleanup path is untouched.

2. **C2 — Obsolete queued work is skipped before doing expensive work.**
   Scheduled builds capture the cache generation and (for speculative work) the page's cache-band
   membership. At execution they re-check the generation, the request token and the band **before**
   allocating a surface or calling PDFium. `dropCachesExcept` / `updateCacheBand` now also cancel
   requests whose pages left the band. `pendingInk` / `pendingBg` became identity-checked maps so an
   older task can never remove a newer request's pending mark, and cleanup runs in `finally` and on
   executor rejection.

3. **C3 — Priorities are meaningful and explicitly propagated.**
   `PdfPriority` is reordered so the sharp viewport outranks soft page caches:
   `INTERACTIVE, SHARP, VISIBLE_RENDER, BG_REPAIR, THUMBNAIL, SEARCH, WARM`.
   Visible pages use `VISIBLE_RENDER`, prefetch uses `WARM`, the sharp viewport uses `SHARP`, markup
   rebakes use `BG_REPAIR`, and thumbnails/search keep `THUMBNAIL`/`SEARCH`. Link/text warming moved
   from `INTERACTIVE` to `WARM`; committed taps/captures stay `INTERACTIVE`. Priorities are passed as
   explicit arguments rather than relying on a `ThreadLocal` for the display paths.

4. **C4 — PDF background work and ink-cache work have separate executors.**
   `CanvasView` now owns `inkExecutor` (`xnotes-ink`) and `backgroundExecutor` (`xnotes-pdf`).
   `CanvasState.runAsync` remains the ink path; a nullable `runAsyncBackground` is the PDF path and
   falls back to `runAsync` when not installed (keeping older unit-test harnesses synchronous).
   Cache publication still goes through `postToMain`, and the maps remain main-thread-only.

No rendering-resolution, `maxCachePx`, sharp-viewport-quality, page-transform, `PdfKey`, rotation or
stroke-geometry code was changed.

---

## B. C1 — Display render cancellation

### Previous behaviour

`PdfSource.render` called `pdf.render(index, bmp, fullW, fullH, left, top, priority())` with the
default `token = null` (`PdfSource.kt`, pre-change lines 168–191). `PdfiumDocument.render` already
accepted a token and `nativeRender` already polled it through `IFSDK_PAUSE`, but no display path
supplied one. A page the user left therefore kept the single shared PDFium thread busy until its
render finished.

### Root cause

The per-request token parameter existed but was never threaded through
`CanvasState → Editor → PdfSource`. The hook’s type had no place for a token or a priority:

```kotlin
var paintPageBackground: ((Page, Renderer, Double, Rect) -> Unit)?
```

### Files/classes changed

- `app/src/main/java/com/xnotes/canvas/CanvasState.kt`
  - `paintPageBackground` now takes `cancel: CancelToken?` and `priority: PdfPriority`.
  - `scheduleBg` creates a `BackgroundRequest(CancelToken())` per page and stores it in
    `pendingBg`.
  - `buildBackground(page, res, priority, token)` checks the token before the surface allocation,
    after it, and before returning.
  - `scheduleRebake` creates its own token (`rebakeTokens`), also cancellable.
  - `requestSharpViewport` creates a `SharpRequest` token covering the whole viewport render.
  - `updateCacheBand` / `applyBandKeep` cancel requests for pages outside the new keep band.
  - `invalidateCachesForZoom`, `invalidateAllCaches`, `invalidateBackground`,
    `invalidateAllBackgrounds`, `invalidatePageGeometry`, `invalidatePage`, `invalidatePaper` and
    `relayout` cancel the obsolete in-flight requests they invalidate.
- `app/src/main/java/com/xnotes/ui/Editor.kt`
  - `installPageBackground` forwards `(cancel, priority)`.
  - `paintBackground` takes `cancel`/`priority` and passes them to
    `PdfSource.renderRegion`.
- `app/src/main/java/com/xnotes/platform/PdfSource.kt`
  - `renderRegion`, `renderPage`, `pageSizePoints`, `allPageSizePoints`, `imageRectsFor` and the
    private `render` accept an explicit `priority` and (for the render path) `cancel`.
  - The token is checked before page-size lookup, before page-size use, before bitmap allocation,
    before/after the PDFium render, before/after markups, before the filtered second bitmap, and
    before publication. Every cancelled path recycles whatever bitmap it had allocated.
- `app/src/main/java/com/xnotes/platform/PdfiumDocument.kt`
  - `pageSizes` and `imageRects` accept an optional token so a cancelled request also drops the
    queued size/image read, not just the page raster.

### Token lifecycle

1. `updateCacheBand()` (start of `CanvasView.onDraw`) computes the keep band.
2. `scheduleBg` immediately creates the request’s `CancelToken` and records it in `pendingBg`.
3. The build runs on `backgroundExecutor`. `isBuildCurrent` checks generation/token/band.
4. `PdfSource.renderRegion` checks the token, then calls `PdfiumDocument.render` with it.
5. `withHandle` registers both the document lifetime token and the request token on the queued
   `PdfiumTask`. If the request is cancelled while queued, the task is removed and the caller returns
   null at once. If it is already running, `nativeRender`’s pause callback observes the same Java
   `isCancelled` field and stops between PDFium steps.
6. On cancellation, `FPDF_RenderPage_Close`, `FPDFBitmap_Destroy` and
   `AndroidBitmap_unlockPixels` still run exactly as before. A cancelled render returns `false`;
   `PdfSource` recycles the bitmap and returns null; `buildBackground` recycles any surface it had
   created and refuses to publish.
7. `postToMain` checks token + generation + band again before storing the cache entry, so a result
   cancelled after the native render can never become visible.

### When requests are cancelled

- Page leaves the cache keep band (`updateCacheBand` / `prefetchAndPrune`).
- Zoom change (`invalidateCachesForZoom`), full invalidation, background invalidation, margin/geometry
  change, page invalidation, paper change, layout change.
- A sharp request is superseded by a different view (`requestSharpViewport` sees a different
  gen/row/scroll/zoom and cancels the in-flight request, then starts the new one after cleanup).
- Document close/replace: `PdfiumDocument.close()` still cancels the lifetime token, which aborts
  every request token as the second token on the task.
- Valid current-page builds are not cancelled: the keep band includes the page the draw loop is
  showing; only a generation/zoom invalidation or actual cancellation makes it invalid.

### Native cleanup preserved

No JNI file was modified. `pdf_jni.cpp` still closes the page, destroys the FPDF bitmap wrapper and
unlocks the Android pixels in all paths. The existing tests around queued cancellation still pass.

### Tests

`PdfRenderSchedulingTest`:
- `aVisibleBackgroundBuildCarriesAnUncancelledTokenAndPublishes`
- `aPrefetchWhosePageLeftTheBandIsSkippedAndNeverPublished`
- `aZoomInvalidationStopsAnInFlightBackgroundBeforeItPaints`

`PdfiumThreadTest`:
- `aRunningJobObservesItsTokenBeingCancelled` (a running body sees the token turn true and stops)

All pass.

---

## C. C2 — Obsolete queued work

### Previous behaviour

`CanvasView` used one `ExecutorService`; queued runnables could not be removed.
`scheduleInk` and `scheduleBg` ran their body unconditionally and checked `cacheGen` only inside
`postToMain` after the expensive work. `dropCachesExcept` only pruned maps. A fast fling could queue
a full PDF render + ink raster for every intermediate page and execute them all before discarding
their results.

### New pre-execution validity

`CanvasState` now keeps `bandKeep`, an immutable set of the pages kept by the last frame
(`@Volatile`, written on the main thread). `isBuildCurrent(page, gen, prefetch, token)` requires:

- `gen == cacheGen` (the request has not been invalidated);
- the request token is not cancelled (background/rebake requests);
- for speculative work (`prefetch = true`), the page is still in `bandKeep`.

Visible builds are not band-checked at start (the draw loop schedules them before that frame’s band
is published); they are band-checked at publication, and their background variants additionally carry
a token that `updateCacheBand` cancels when the page leaves the band.

`buildBackground` also checks the token before surface allocation, after allocation and before
returning. `renderInk` is only called after the validity check, so an obsolete ink build allocates
nothing.

### Generation checks

`cacheGen` is `@Volatile`. Every build captures it on the main thread at schedule time and checks it
both before work and before publish. `invalidateCachesForZoom`, all-cache/background/geometry/page
invalidation and `relayout` also cancel the relevant in-flight tokens, so queued PDF work is removed
from the PDFium queue (via `CancelToken` hooks) and running native work stops.

### Pending bookkeeping

- `pendingInk: HashMap<Page, Any>` stores a unique request identity per page.
- `pendingBg: HashMap<Page, BackgroundRequest>` stores the token object per page.
- `rebakeTokens: HashMap<Page, CancelToken>` tracks rebakes.
- Cleanup always runs in `finally` and publishes through `postToMain`.
- Cleanup removes a pending mark only when the identity still matches (`pendingInk[page] === request`,
  `pendingBg[page] === request`, `rebakeTokens[page] === token`), so an older task cannot erase a
  newer request’s state.
- `scheduleInk`/`scheduleBg`/`scheduleRebake` catch synchronous executor rejection, clear their
  pending mark and rethrow; a rejected queue therefore cannot leave a page permanently pending.

### Cache-band wiring

- `CanvasView.onDraw` calls `state.updateCacheBand()` before scheduling visible pages, so a jump to a
  distant page is in-band when its build starts.
- `prefetchAndPrune` applies the band again after scheduling and then prunes the maps.
- `applyBandKeep` cancels `pendingBg`, `rebakeTokens` and the sharp request for pages outside the new
  band. Its sharp cancellation is intentional: a sharp frame covers the visible viewport, so a band
  change means the view moved.

### Races considered

- **Publish vs. cancellation:** publish requires generation, token and band checks. A cancelled
  result is never stored.
- **Older cleanup vs. newer request:** identity comparisons, plus `pending*` prevents a second
  request for the same page from being queued concurrently.
- **Re-enter while pending:** the page waits for the old runnable’s cleanup (a frame or so), then the
  draw loop schedules it again. This is deliberate: it avoids two concurrent requests for one page.
- **Sharp view change:** the old request is cancelled and `sharpAgain` starts the new view after
  cleanup; `pendingSharp` is reset only by the request that owns it.
- **Band update timing:** `updateCacheBand` runs at the start of a frame, before visible scheduling,
  eliminating the “old band rejects the newly visible page” race.
- **Markup rebake:** rebakes do **not** use the generation check, because every markup edit bumps the
  generation for the layer it is rebuilding; they use token + band + the existing sequence number,
  preserving “edits during a paint make one more after it”.

### Tests

`PdfRenderSchedulingTest`:
- `anObsoleteInkBuildIsSkippedBeforeAllocating`
- `anObsoleteBackgroundBuildIsSkippedBeforeAllocating`
- `aRejectedExecutorDoesNotLeaveThePagePending`
- `aRejectedBackgroundExecutorDoesNotLeaveThePagePending`
- `speculativePrefetchStopsWhenItsQueueIsAlreadyDeep`
- plus the C1 skip tests above.

`MarginLayoutTest`, `UndoCacheRepairTest`, `PlaybackFadeCacheTest`, `MarkupBakeTest` and
`CacheBandTest` continue to pass, covering the existing “discard mid-drag, repaint, rebuild once”
behaviour.

---

## D. C3 — Priority propagation

### Previous behaviour

- `PdfPriority` order was `INTERACTIVE, VISIBLE_RENDER, BG_REPAIR, SHARP, THUMBNAIL, SEARCH, WARM`,
  so `SHARP` was **below** the soft page cache.
- `SHARP` and `BG_REPAIR` were unused.
- Visible builds, prefetch, rebakes and the sharp viewport all ran at `VISIBLE_RENDER` because
  `PdfSource.priority()` read a `ThreadLocal` that the scheduler never set.
- Link warming and text prefetch used `INTERACTIVE`, so speculative reads could jump ahead of the
  visible page.

### New mapping

| Workload | Priority | How it is passed |
|---|---|---|
| Document open, link/text tap, screenshot capture | `INTERACTIVE` | existing explicit calls / capture hook |
| Sharp viewport (full-resolution visible pixels) | `SHARP` | `requestSharpViewport` → `renderSharpFrame` → hook → `PdfSource.renderRegion` |
| Current visible page raster | `VISIBLE_RENDER` | default hook priority from the draw loop |
| Markup background rebake | `BG_REPAIR` | `scheduleRebake` → `buildBackground` → hook |
| Thumbnails | `THUMBNAIL` | explicit on `renderThumbnail` / explorer `renderPage` |
| Search | `SEARCH` | existing `PdfSource.searchText` |
| Near-page prefetch, link/text warming | `WARM` | `prefetchAndPrune` background call; `setCurrentLinks(WARM)`; `PageTextCache.prefetchRead(WARM)` |

### Explicit propagation

- `CanvasState.backgroundForOrSchedule(page, priority, prefetch)` takes the intended priority and
  stores it in the schedule; `buildBackground` passes it to the hook with the token.
- `paintPageBackground` signature now carries `(cancel, priority)`.
- `Editor.paintBackground` passes both to `PdfSource.renderRegion`.
- `PdfSource.renderRegion`/`renderPage` accept `priority` and pass it to `PdfiumDocument.render`.
- Thumbnail/explorer call sites pass `PdfPriority.THUMBNAIL` explicitly; their old
  `PdfSource.withPriority` wrappers were removed.
- `PageTextCache` has a separate `prefetchRead` used only by `prefetch`; `request`/`get` use the
  interactive reader.
- `PdfPriority` is reordered so `SHARP` is second, immediately below `INTERACTIVE` and above
  `VISIBLE_RENDER`. The `PdfiumThread` queue is unchanged: one shared thread, FIFO within a priority.

### Starvation and FIFO

FIFO within a priority is untouched (`PdfiumTask.compareTo`). Speculative `WARM` work is also bounded
by `prefetchAndPrune` (see C4/E), so a long scroll cannot build an unbounded prefetch backlog. Higher
priorities drain first; lower-priority work (search, thumbnails, warm) is never dropped permanently,
only deferred.

### Tests

- `PdfiumThreadTest.sharpViewportOutranksVisibleAndVisibleOutranksRepair`
- `PdfiumThreadTest.runsTheMostUrgentJobFirstAndInOrderWithinAPriority` (unchanged, still passes)
- `PdfRenderSchedulingTest.prefetchIsQueuedAtWarmBehindTheVisiblePage`
- `PdfRenderSchedulingTest.theSharpViewportRunsAtSharpPriority`
- `PdfRenderSchedulingTest.aMarkupRebakeRunsAtBackgroundRepairPriority`
- `PageTextCacheTest.prefetchUsesTheLowerPriorityReaderWhileATapUsesTheInteractiveOne`

---

## E. C4 — Executor separation

### Previous architecture

`CanvasView` had one `newSingleThreadExecutor` (`xnotes-cache`). `CanvasState.runAsync` was used for
both `buildBackground` (which calls the PDF layer and blocks on `PdfiumThread`) and `renderInk`
(Skia-only). While a PDF render waited, no ink-cache rebuild could run, including a post-commit full
rebuild.

### New architecture

- `CanvasState.runAsync` — ink builds (`scheduleInk`, `renderInk`).
- `CanvasState.runAsyncBackground` — anything that may block on PDFium (`scheduleBg`,
  `scheduleRebake`, `requestSharpViewport`).
- `runAsyncBackground` is nullable and falls back to `runAsync`; production always installs it.
- `CanvasView` owns two daemon `ThreadPoolExecutor`s with one thread each:
  - `inkExecutor` thread `xnotes-ink`
  - `backgroundExecutor` thread `xnotes-pdf`
- `onAttachedToWindow` creates them if absent/shutdown; `onDetachedFromWindow` calls `shutdown()` on
  both. Queued tasks are allowed to finish, as before; skipped tasks clean their pending marks.
- `backgroundQueueDepth` and `inkQueueDepth` expose `ThreadPoolExecutor.queue.size`. `prefetchAndPrune`
  stops adding speculative work when the corresponding depth is at `PREFETCH_BG_QUEUE_LIMIT = 8` /
  `PREFETCH_INK_QUEUE_LIMIT = 16`. Visible builds are not gated. Together with the per-page pending
  maps this prevents an unbounded speculative backlog.

### Thread ownership / synchronization

- `caches`, `bgCaches`, `pendingInk`, `pendingBg`, `rebakeTokens`, `bandKeep` writes and
  `cacheSnapshot` remain main-thread-only.
- Background workers read only immutable snapshots (`InkItems`, item lists, page identity), the
  `@Volatile` `cacheGen`, the `@Volatile` `bandKeep` set, and their own token.
- All publications still go through `postToMain`.
- PDFium stays one thread; the background executor only serialises requests before they reach it.

### Ink correctness

`appendToCache`, `repairRegion`, `refreshInkRegions`, `refreshAllInk`, `appendToSharpInk`,
`repairSharpInk`, `SharpViewportEditReplayTest` and `WetInkCache` were not changed. Their tests pass.
Splitting the executors only changes *where a scheduled build runs*; it does not change what is
painted or when a repair is replayed.

### Tests

- `PdfRenderSchedulingTest.inkRebuildPublishesWhileABackgroundBuildIsStillBlocked` — runs the ink
  worker, proves the ink cache is published while the PDF task has not started, then runs the PDF
  worker and proves the background publishes separately.
- Existing `SharpViewportEditReplayTest`, `UndoCacheRepairTest`, `PlaybackFadeCacheTest`,
  `WetInkCacheTest`, `SelectionCacheRepairTest`, `CacheBandTest`, and Phase 1/2 suites all pass.

---

## F. Android compatibility

| Item | Before | After |
|---|---|---|
| `minSdk` | 26 | **26 (unchanged)** |
| New Android framework APIs | — | **None** |
| New dependencies | — | **None** |
| New APIs above API 23 used | — | **None** |

Every new concurrency primitive is plain Java available long before API 23:
`ThreadPoolExecutor`, `LinkedBlockingQueue`, `TimeUnit`, `java.util.concurrent` futures/latches already
in the project, `@Volatile`, `HashMap`/`ArrayDeque`. The cancellation token is the project’s existing
Kotlin class. No `ActivityManager`, no memory-class query, no new `android.*` API, no desugaring
change.

Verification performed:
- `:app:compileDebugKotlin` with the project’s real `minSdk = 26` configuration: **BUILD SUCCESSFUL**.
- `:app:assembleDebug`: **BUILD SUCCESSFUL**.
- Android 6.0 runtime was **not** tested: the project still has `minSdk = 26` and the Phase 0 report
  records the pre-existing API-23 blockers (Compose dependency floor, core-library desugaring,
  `PixelCopy` fallback). This change neither raises nor lowers `minSdk`; it introduces no new
  Android-version-specific behaviour.

---

## G. Files changed

| File | Change | Reason |
|---|---|---|
| `app/src/main/java/com/xnotes/canvas/CanvasState.kt` | Token-bearing `paintPageBackground`; per-page `BackgroundRequest`/tokens; `bandKeep`; pre-work generation/token/band checks; identity-checked pending maps; rebake/sharp tokens; band updates; cancellations; `runAsyncBackground`; queue-depth gates; `SHARP`/`BG_REPAIR`/`WARM` propagation | C1–C4 core |
| `app/src/main/java/com/xnotes/canvas/CanvasView.kt` | Split `inkExecutor`/`backgroundExecutor`; queue-depth hooks; `updateCacheBand()` at frame start; lifecycle changes | C4, C1 band timing, C2 pre-execution validity |
| `app/src/main/java/com/xnotes/platform/PdfSource.kt` | Explicit priority/cancel on render paths; token checks and bitmap recycling; token-aware page sizes/image rects; warm link/text priorities | C1, C3 |
| `app/src/main/java/com/xnotes/platform/PdfiumDocument.kt` | Optional request token on `pageSizes`/`imageRects` | C1 |
| `app/src/main/java/com/xnotes/platform/PdfiumThread.kt` | Reordered `PdfPriority` and documented semantics | C3 |
| `app/src/main/java/com/xnotes/platform/PageTextCache.kt` | Separate `prefetchRead` for the speculative reader | C3 |
| `app/src/main/java/com/xnotes/ui/Editor.kt` | Hook signature; `paintBackground` token/priority; explicit thumbnail/capture priorities | C1, C3 |
| `app/src/test/java/com/xnotes/canvas/PdfRenderSchedulingTest.kt` | New focused suite for C1–C4 | Tests |
| `app/src/test/java/com/xnotes/platform/PdfiumThreadTest.kt` | SHARP priority order; running-task cancellation observation | Tests |
| `app/src/test/java/com/xnotes/platform/PageTextCacheTest.kt` | Prefetch vs. tap reader | Tests |
| `app/src/test/java/com/xnotes/canvas/CacheBandTest.kt` | Hook arity | Compilation |
| `app/src/test/java/com/xnotes/canvas/MarkupBakeTest.kt` | Hook arity | Compilation |
| `app/src/test/java/com/xnotes/canvas/SelectionCacheRepairTest.kt` | Hook arity | Compilation |
| `app/src/test/java/com/xnotes/canvas/UndoCacheRepairTest.kt` | Hook arity | Compilation |
| `docs/phase-3-pdf-rendering-audit.md` | Added the audit that drove this work | Source of truth |
| `docs/PHASE_3_HIGH_PRIORITY_FIXES.md` | This report | Documentation |

No files deleted. No production rendering-quality parameters changed.

---

## H. Tests and build results

All commands were run from the repository through the Windows toolchain, because the WSL shell has
no JDK (`PROJECT_STATE.md`).

### 1. Main compile

```
cmd.exe /c "gradlew.bat :app:compileDebugKotlin --offline --console=plain"
```

**PASSED.** `BUILD SUCCESSFUL`. Warnings are pre-existing (deprecated PdfBox `appendRawCommands`,
redundant `when` else, etc.); the first compilation attempt exposed one of our own argument-order
mistakes, which was fixed before this run.

### 2. Full JVM unit suite

```
cmd.exe /c "gradlew.bat :app:testDebugUnitTest --offline --console=plain"
```

**PASSED.** `BUILD SUCCESSFUL`.
Aggregated XML results: **2553 tests, 0 skipped, 0 failures, 0 errors** across 206 suites.

Focused results:

| Suite | Tests | Result |
|---|---|---|
| `PdfRenderSchedulingTest` (new) | 12 | PASSED |
| `PdfiumThreadTest` | 10 | PASSED |
| `PageTextCacheTest` | 7 | PASSED |
| `CacheBandTest` | 5 | PASSED |
| `SharpViewportEditReplayTest` | 3 | PASSED |
| `GeometryEvictionTest` | 5 | PASSED |
| `WetInkCacheTest` | 12 | PASSED |

Phase 1/2 suites (`CrossPageStrokeTest`, stroke-tessellation tests, input tests, etc.) are included
in the 2553 passing tests.

What the new tests actually verify:
- `PdfRenderSchedulingTest` observes the hook’s priority/token and the cache maps after manually
  draining the ink/PDF queues. It proves skip-before-allocation, cancellation propagation to the
  painter, pending cleanup, WARM/SHARP/BG_REPAIR/VISIBLE priorities, depth gating, and ink/PDF
  executor isolation.
- `PdfiumThreadTest` proves the real priority queue orders `INTERACTIVE > SHARP > VISIBLE_RENDER >
  BG_REPAIR > WARM` and that a running body sees its token cancelled.
- `PageTextCacheTest` proves speculative prefetch uses the low-priority reader while a tap request
  uses the interactive reader.

### 3. Debug APK

```
cmd.exe /c "gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain"
```

**PASSED.** `BUILD SUCCESSFUL`. The log shows the pre-existing `sdkmanager` “inconsistent location”
diagnostics for Android 36 system images; they are unrelated to this change and are not build
failures.

### 4. Lint

```
cmd.exe /c "gradlew.bat :app:lintDebug --offline --console=plain"
```

**FAILED — pre-existing baseline.** Lint reports **302 errors / 168 warnings / 63 hints** and aborts
on the first error in an unchanged file:
`AndroidRenderer.kt:96: Call requires API level 29 (current min is 26): Paint#setBlendMode [NewApi]`.
This is not introduced by this change (no JNI/renderer file was touched). The report contains no new
`NewApi`/`WrongConstant` error in the new scheduling code. Lint is therefore **not** a clean gate for
this repository and was not claimed as passed.

### Not run

- Instrumentation tests (`app/src/androidTest` does not exist in this checkout).
- Benchmark module (does not exist).
- Physical-device or API-23 emulator runs (no device/emulator session was used).

---

## I. Regression and risk review

### Phase 1 / Phase 2 preservation

- Input classification files (`InteractionController`, `InputTouchRewriter`, `FrontInk`, provisional
  replay) were not modified. The 2553-test suite includes the Phase 1/2 tests and passes.
- `appendToCache`, `repairRegion`, `refreshInkRegions`, `refreshAllInk`, `appendToSharpInk`,
  `repairSharpInk` and `WetInkCache` were not changed.
- `SharpViewportEditReplayTest` still proves edits during a sharp build are replayed.
- `CrossPageStrokeTest`, stroke tessellator, pinch/fit tests and the geometry-eviction tests pass.

### PDF quality

- `renderScale = 1.0`, `clampedRes`, `maxCachePx`, `pxPerPt = res * dpi / 72`, the sharp viewport
  resolution and `SharpFrame` blitting were not changed.
- No new downscaling, no device-based quality tier, no tile change.
- `FPDF_ANNOT`, `FPDF_REVERSE_BYTE_ORDER`, form drawing, rotation/`PdfKey` handling and the
  filter/image-stamp path are untouched in behaviour. Priority/cancellation only affect *whether*
  a render completes, never its pixel parameters.

### Remaining risks / limitations

1. **Cancellation granularity is page-level.** A render already inside PDFium stops at the next pause
   check; it cannot be pre-empted mid-primitive. This is the same safe mechanism as before.
2. **Scanner/gateway tests use fake queues.** They prove the scheduler contract, not native timing.
   No device measurement of cancellation effectiveness or frame time is claimed.
3. **A page can still wait one frame** after a band change while its cancelled runnable cleans up
   before being rescheduled.
4. **Sharp viewport cancellation on any band change** may restart a sharp render during a slow,
   continuous pan; it is the correct trade-off for avoiding work for a view the user left, but its
   visual/timing effect is unmeasured.
5. **Lint is red for pre-existing reasons** (302 errors), so it cannot serve as a clean CI gate until
   a baseline/cleanup task is approved.
6. **Android 6.0 runtime remains unverified** because the project is still `minSdk = 26`. The new code
   is API-23-safe, but that is a code-level claim, not a device test.
7. **No instrumentation/benchmark tests** were added; the task’s test scope was the JVM scheduler and
   existing regression suites.

---

## J. Before / after behaviour

| Problem | Before | After |
|---|---|---|
| **C1** Obsolete PDF renders | Continued to completion on the shared PDFium thread; only the document lifetime could stop them | Each request has a token; page/zoom/layout invalidation cancels it; native pause stops in-progress work; cancelled results never publish |
| **C2** Queued background builds | Queued runnables always executed; generation checked only after the work | Generation/token/band checked before allocation and before PDFium; obsolete builds skip; pending marks cleaned in `finally` and on rejection |
| **C3** Priority model | `SHARP` below `VISIBLE_RENDER` and unused; all display/rebake/sharp ≈ `VISIBLE_RENDER`; link/text warming `INTERACTIVE` | `INTERACTIVE > SHARP > VISIBLE_RENDER > BG_REPAIR > THUMBNAIL > SEARCH > WARM`; explicit propagation; warming at `WARM`; sharp at `SHARP`; rebake at `BG_REPAIR` |
| **C4** Single cache executor | A PDF render blocked every ink-cache rebuild | Separate `xnotes-ink` and `xnotes-pdf` workers; ink publishes while the PDF worker is blocked; PDFium still single-threaded; speculative work depth-gated |

Externally visible rendering is unchanged at rest: same resolution, same filters, same annotations,
same sharp viewport. The differences are in scheduling, cancellation and which obsolete requests are
allowed to run.

---

## K. Git information

| Item | Value |
|---|---|
| Branch | `phase-3-pdf-rendering` |
| Implementation commit | `79238d10` — `Fix high-priority PDF rendering scheduling issues` |
| Documentation | This report, committed on the same branch after `79238d10` |
| Remote | `mine` = `https://github.com/Abdo-Badawi/xnotes-android.git` |
| Pushed branch | `mine/phase-3-pdf-rendering` (the implementation commit was verified on the remote before this report was added) |
| Pull request | Not created (only a branch push was requested; GitHub offered the create-PR URL) |

The implementation commit contains the production changes, the focused tests, the adjusted hook-arity
tests and the Phase 3 audit report. This report is in the branch-head documentation commit.
`writing&performance_edits.txt` (untracked phase plan) was not staged or committed.
