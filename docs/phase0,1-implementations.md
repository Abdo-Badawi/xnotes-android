# Phase 0 & Phase 1 — Full Implementation Report

**Project:** `/mnt/d/xnotes/v1/xnotes-android` (GitHub: `Abdo-Badawi/xnotes-android`, built from Windows)
**Reference plan:** `writing&performance_edits.txt`
**Audit reports:** `docs/phase-0-audit.md`, `docs/phase-1-input-latency.md`
**Date context:** work done across Phase 0 (audit) and Phase 1 (input latency).

---

## 0. Snapshot

| Item | Value |
|---|---|
| Active branch | `phase-1-input-latency` (created from `phase-0-performance-audit`) |
| Phase 0 branch / commit | `phase-0-performance-audit` / `e47791c4` — “Add Phase 0 compatibility and performance audit” (pushed to `mine`) |
| Earlier branding commit | `44b6f34c` — “Rebrand app as Pixora with branded splash and launcher icons” (pushed) |
| Phase 1 status | **Uncommitted working-tree changes** on `phase-1-input-latency`, ready for review |
| Source files changed in Phase 1 | 5 |
| Phase 1 diff | `5 files changed, 393 insertions(+), 42 deletions(-)` |
| Build toolchain | Windows `gradlew.bat`, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, JDK 21, NDK 27.0.12077973 |
| Device | **None attached** (`adb devices` empty) → no runtime measurements |

> Phase 0 made **no source changes**. Phase 1 made source changes but did **not** commit them.

---

# 1. Phase 0 — Project Audit & Baseline

## 1.1 Objective

Verify the plan’s assumptions against the real code, establish the Android compatibility baseline, and list confirmed bottlenecks before any optimization.

## 1.2 What was inspected

- Gradle/AGP/Kotlin/NDK/CMake configuration: `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/libs.versions.toml`.
- Manifest: `app/src/main/AndroidManifest.xml`, merged manifest output.
- Native build: `app/src/main/cpp/CMakeLists.txt`, `pdfium.cmake`, `pdf_jni.cpp`.
- Input: `EditorActivity.kt`, `InputTouchRewriter.kt`, `InputCustomization.kt`, `InputCustomizationPanel.kt`, `InteractionController.kt`.
- Stroke/render: `StrokeEngine.kt`, `WetRibbon.kt`, `StrokeTessellator.kt`, `StrokeSimplify.kt`, `ItemMesher.kt`, `FrontInk.kt`, `GlWetPad.kt`, `GlWetPadInk.kt`, `WetInkCache.kt`, `CanvasState.kt`, `CanvasView.kt`.
- PDF: `DocumentCodec.kt`, `PdfSource.kt`, `PdfiumDocument.kt`, `PdfiumThread.kt`, `Editor.kt`, `FilePreview.kt`.
- Dependencies: resolved AAR manifests in the Windows Gradle cache.

## 1.3 Compatibility findings

### Toolchain (verified)

| Item | Value | Source |
|---|---|---|
| `applicationId` | `com.xnotes` | `app/build.gradle.kts:25` |
| `compileSdk` | 36 | `app/build.gradle.kts:20` |
| `minSdk` | **26** | `app/build.gradle.kts:26` |
| `targetSdk` | 36 | `app/build.gradle.kts:27` |
| AGP | 8.13.2 | `gradle/libs.versions.toml:2` |
| Kotlin | 2.2.21 | `gradle/libs.versions.toml:3` |
| Gradle wrapper | 8.13 | `gradle/wrapper/gradle-wrapper.properties:3` |
| Java source/target | 17 | `app/build.gradle.kts:92-93,108` |
| NDK | 27.0.12077973 | `app/build.gradle.kts:22` |
| CMake | 3.22.1 | `app/build.gradle.kts:43` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` | `app/build.gradle.kts:31` |
| Native link | 16 KB page alignment (`-Wl,-z,max-page-size=16384`) | `app/src/main/cpp/CMakeLists.txt:9` |
| PDFium revision | `8ca5b735df4263f43c830479b78213854a392c22` | `app/src/main/cpp/pdfium.cmake:9` |

Merged manifest confirmed `minSdkVersion="26"`, `targetSdkVersion="36"` via `:app:processDebugMainManifest` (offline, successful).

### Dependency minSdk floor (read from resolved AAR manifests)

| Dependency | Version | minSdk |
|---|---|---|
| Compose UI / foundation / runtime / ui-graphics / ui-tooling-preview | 1.11.2 | **23** |
| Compose material3 | 1.4.0 | 21 |
| androidx.core | 1.16.0 | 21 |
| androidx.activity / activity-compose | 1.10.1 | 21 |
| androidx.lifecycle | 2.9.4 | 21 |
| pdfbox-android | 2.0.27.0 | 14 |
| androidsvg | 1.4 | 8 |
| latex-* / commonmark | 1.5.0-kt2.1.0 / 0.30.1 | jars (app floor) |

**Conclusion:** the dependency floor is **API 23**, not 26. `minSdk = 26` is a project choice, so Android 6 is blocked by app code, not by Compose.

### What blocks API 23 in app code

| API / symbol | Requires | Where | Effect |
|---|---|---|---|
| `java.time.*` (`LocalDate`, `Instant`, `LocalDateTime`, `YearMonth`, `WeekFields`, `DateTimeFormatter`) | 26 | `EditorActivity.kt:1117`; `format/CanvasCodec.kt:140,600,663`; `format/DocumentCodec.kt:306,990,1016`; `format/MarkupsXfdf.kt:13-16,148,254,262`; `ui/Backstage.kt:136-138,1329,1455`; `ui/Editor.kt:6064,6117,8021-8022,8066-8072` | Crashes below 26 unless core-library desugaring is enabled |
| `android.view.PixelCopy` | 24 | `canvas/FrontInk.kt:7,387,390`; `ui/EyedropperOverlay.kt:8,200-211`; `ui/InfiniteEditor.kt:1966-1969` | Class absent on API 23; needs guard + fallback |
| `java.util.function.Function/Supplier` | 24 | `vendor/materialcolor/dynamiccolor/*` | Covered only with desugaring |

Already guarded (verify at runtime on 23):
`requestUnbufferedDispatch` (30) — `CanvasView.kt:864`; `registerFrameCommitCallback` (29) — `CanvasView.kt:848`; `SurfaceControl` (29/31) — `GlWetPad.kt:207,406,593`; splash attributes (31) — themes; `foregroundServiceType` (29) and `POST_NOTIFICATIONS`/`FOREGROUND_SERVICE_MICROPHONE` (33/34) are ignored on older platforms.

**Verdict:** the app is **not Android 6 compatible today**. Lowering `minSdk` to 23 requires:
1. core-library desugaring for `java.time`/`java.util.function`;
2. an API-23 `PixelCopy` fallback (front-buffer handover and eyedropper);
3. a real API-23 build + runtime smoke test.

## 1.4 Confirmed bottlenecks (static evidence)

### Input
- 90 ms classification buffer holds DOWN/MOVE/UP for ambiguous single-finger input (`EditorActivity.kt:120, 281-286, 300-352`).
- While buffering, **no event reaches the controller** (`EditorActivity.kt:281-286`) → ink appears only at UP/timeout.
- `hasMultiPointerSignature` forces the full window even without UP (`EditorActivity.kt:334`).
- Flush runs on the main thread (`EditorActivity.kt:256, 338-352`), so main-thread jank extends the delay.
- Unbuffered dispatch is requested only while the wet pad is live (`CanvasView.kt:863-869`; `FrontInk.kt:249,314,363`).
- Per-event `PointerProperties`/`PointerCoords` allocations (`InputTouchRewriter.kt:75-76` before Phase 1).
- Canvas suppresses its live stroke when the GL pad is live (`InteractionController.kt:4746-4747`).

### Stroke quality
- Decimation gate `(1.0/zoom).coerceAtMost(1.0)` content px (`InteractionController.kt:5095`).
- Commit simplification `SIMPLIFY_EPS = 0.2` (`InteractionController.kt:1639-1640, 5101`; `core/stroke/StrokeSimplify.kt`).
- Tessellator join anchor `backL` and capsule `SWALLOWED` path (`StrokeTessellator.kt:148,159,209-259`).
- Wet pad eligible only for `InkPass.OPAQUE` and `rotationDeg == 0` (`FrontInk.kt:203-226`); requires EGL single buffer (`GlWetPad.kt:524 ready = single`).
- Two rasterizers meet at handover (GL pad live vs Skia committed).
- Empty-damage present draws nothing while the canvas suppresses live ink (`GlWetPadInk.kt:260-300`).

### PDF
- Full embedded STORED PDF copied to a temp file on open (`DocumentCodec.kt:784-790`).
- Autosave avoids re-copying via tail splice (`Editor.kt:5204, 5247-5290`) — good.
- `PdfSource.render` never passes a `CancelToken` (`PdfSource.kt:176`; `PdfiumDocument.kt:143-145`).
- Single PDFium thread (`PdfiumThread.kt`), native page cache = 4 (`pdf_jni.cpp:31,111-129`).
- Single cache executor (`CanvasView.kt:369`); per-frame prefetch/prune (`CanvasView.kt:590`; `CanvasState.kt:1764,1825`).
- Thumbnails re-read whole note/PDF (`Editor.kt:4054,4125`; `FilePreview.kt:131`).

### Memory/threads
- `maxCachePx = 2048` → ~16 MB per background and ink surface (`CanvasState.kt:140`).
- Eviction drops map entries without explicit Bitmap recycling (`CanvasState.kt:1764`).
- Main thread does input, buffer flush, PixelCopy, cache callbacks.
- PDFium thread does all open/render/text/link work.

## 1.5 Baseline measurement status

Blocked: `adb devices` showed **no device**. The report documents the exact Perfetto/`gfxinfo`/`meminfo`/logcat protocol and the 9-scenario matrix for when the interactive display is connected.

## 1.6 Phase 0 deliverables

- Report: `docs/phase-0-audit.md` (285 lines).
- Branch/commit: `phase-0-performance-audit` / `e47791c4`, pushed to `mine`.
- **No source files modified.**

---

# 2. Phase 1 — Fix Input Latency

## 2.1 Problem

`EditorActivity` buffered ambiguous single-finger input for up to 90 ms and dispatched nothing until UP/timeout. On the target interactive display (pen reported as `TOOL_TYPE_FINGER`), every stroke whose initial contact size did not match a recording waited — so ink appeared only on lift, and main-thread jank made it worse.

## 2.2 Design

**Immediate provisional ink + deferred authoritative classification + safe rollback/replay.**

Conditions to provision (`shouldProvisional`):
- `ACTION_DOWN`, pointer is `TOOL_TYPE_FINGER`;
- eyedropper not armed;
- `Editor.canBeginProvisionalInput()`:
  - paged editor (`!canvasOpen`),
  - stroke tool,
  - `fingerDraws` on (auto-enabled on stylus-less panels by `Editor.kt:2758`),
  - controller safe: `IDLE`, no selection, no text/label edit, no PDF text selection, no ruler, no polygon/shape/tape draft, no screenshot, no room button;
- customization enabled and has a 1-pointer signature;
- `match(1, initialSize) == null` (otherwise normal immediate classification already happens).

State flow:

```
DOWN (ambiguous, safe)
  → latch WRITE, dispatch immediately, start 90 ms replay window
      MOVE:
        match WRITE           → confirm, keep ink, dispatch live
        match ERASE/PAN/ALT   → rollback, reset latch, replay DOWN..now with that function
        match multi signature → rollback, replay with multi function (first pointer forced)
        no match              → dispatch live (still WRITE)
      UP / writing-pointer lift:
        match anything else   → rollback + replay (includes UP)
        else                  → confirm, commit the ink
      90 ms timeout:
        confirm (keep ink; default finger behaviour is draw on this path)
CANCEL / feature off / destroy:
        rollback, recycle replay log, cancel latch
```

Rollback semantics:
- provisional strokes are never added to the page, never push an undo command, and **suppress page hand-over** (`addStrokePoint`), so nothing is filed that cannot be undone;
- clearing `liveStroke` calls `frontInk.abandon()`, which gives the front-pad pixels back;
- before replay, a synthetic `ACTION_CANCEL` is sent to the view tree so Compose/AndroidView reset their gesture stream;
- the rewriter latch is cleared, then the replay rebuilds the gesture from the original DOWN.

## 2.3 File-by-file changes

### 2.3.1 `app/src/main/java/com/xnotes/EditorActivity.kt`

**a) Timeout now only ends the replay window**
```kotlin
private val inputBufferTimeout = Runnable {
    if (provisionalActive) confirmProvisional() else flushInputBuffer()
}
```
*Why:* the old timeout dispatched the held events; now the ink is already drawn and the timeout only stops replay capability.

**b) New provisional state + debug flag**
```kotlin
private var provisionalActive = false
private var provisionalPointerId = -1
private var provisionalStartNs = 0L
private val inputDebug: Boolean get() = Log.isLoggable(INPUT_LOG_TAG, Log.DEBUG)
```
*Why:* track the provisional gesture and its timing without per-move logging.

**c) `dispatchTouchEvent` routing**
```kotlin
if (!samsung && !featureOn) {
    val wasProvisional = provisionalActive
    cancelProvisional()
    if (wasProvisional) sendCancelLike(ev)
    flushInputBuffer()
    return eyedropperTouch.dispatch(ev, superTouch)
}
if (featureOn && !samsung) {
    if (provisionalActive) { handleProvisional(ev, active); return true }
    if (inputBufferActive) { bufferInput(ev); return true }
    if (shouldProvisional(requireNotNull(active), ev)) { beginProvisional(requireNotNull(active), ev); return true }
    if (shouldBufferInput(requireNotNull(active), ev)) { beginInputBuffer(requireNotNull(active), ev); return true }
}
```
*Why:* provisional path first; legacy buffer remains the exact fallback for everything else. Feature-off mid-gesture sends the pending cancel.

**d) `shouldProvisional` (narrow gate)**
```kotlin
private fun shouldProvisional(active: Editor, ev: MotionEvent): Boolean {
    if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
    if (ev.getToolType(ev.actionIndex) != MotionEvent.TOOL_TYPE_FINGER) return false
    if (com.xnotes.ui.Eyedropper.target != null) return false
    if (!active.canBeginProvisionalInput()) return false
    val customization = active.preferences.inputCustomization
    if (!customization.enabled) return false
    if (!customization.hasSignatureFor(1)) return false
    val size = InputTouchRewriter.contactSize(ev)
    return customization.match(1, size) == null
}
```
*Why:* only ambiguous handwriting that would draw anyway is provisioned. Selections/text/ruler/drafts and non-stroke tools keep the exact old path.

**e) `beginProvisional`**
```kotlin
private fun beginProvisional(active: Editor, ev: MotionEvent) {
    val size = InputTouchRewriter.contactSize(ev)
    provisionalActive = true
    provisionalPointerId = ev.getPointerId(0)
    provisionalStartNs = System.nanoTime()
    inputBuffer.clear(); inputBufferMaxByCount.clear()
    inputBuffer.add(MotionEvent.obtain(ev))
    inputBufferMaxByCount[1] = size
    inputBufferHandler.postDelayed(inputBufferTimeout, INPUT_CLASSIFY_MS)
    active.beginProvisionalInput()
    if (inputDebug) {
        val lag = android.os.SystemClock.uptimeMillis() - ev.eventTime
        Log.d(INPUT_LOG_TAG, "provisional begin size=$size dispatchLag=${lag}ms")
    }
    dispatchClassified(ev, active, samsung = false, standard = -1, classifySize = size,
        forceFirstPointerFunction = InputFunction.WRITE)
}
```
*Why:* draw now as Write; keep copies only for possible replay. `dispatchLag` is the hardware-to-dispatch metric.

**f) `handleProvisional` (resolution)**
```kotlin
val match = if (lifting && !liftOfStroke) null else customization.match(count, peak)
val multi = match != null && match.signature.pointerCount > 1
val resolved = match?.function
when {
    resolved != null && (resolved != InputFunction.WRITE || multi) -> resolveProvisional(active, resolved)
    resolved == InputFunction.WRITE -> {
        confirmProvisional(active)
        dispatchClassified(ev, active, false, -1, classifySize = peak,
            forceFirstPointerFunction = InputFunction.WRITE)
    }
    ev.actionMasked == MotionEvent.ACTION_UP || liftOfStroke -> {
        confirmProvisional(active)
        dispatchClassified(ev, active, false, -1, classifySize = peak,
            forceFirstPointerFunction = InputFunction.WRITE)
    }
    else -> dispatchClassified(ev, active, false, -1, classifySize = peak,
        forceFirstPointerFunction = InputFunction.WRITE)
}
```
*Why:* a different resolved function rolls back; Write keeps the ink; no match commits on the draw-default path; everything else keeps rendering immediately while still replayable.

**g) `resolveProvisional` (rollback + replay)**
```kotlin
val events = ArrayList(inputBuffer)
val peaks = HashMap(inputBufferMaxByCount)
inputBufferHandler.removeCallbacks(inputBufferTimeout)
inputBuffer.clear(); inputBufferMaxByCount.clear()
provisionalActive = false
sendCancelLike(events.lastOrNull())
active.cancelProvisionalInput()
active.inputCustomization.resetClassification()
try {
    for (ev in events) {
        val standard = standardPenAction(ev.actionMasked)
        val samsung = standard >= 0 && ev.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
        dispatchClassified(ev, active, samsung, standard, peaks[ev.pointerCount],
            forceFirstPointerFunction = function)
    }
} finally { for (ev in events) ev.recycle() }
```
*Why:* the cancel resets the view/Compose stream; rollback removes provisional ink; the replay uses the existing classified path with the resolved function and peak size, so erase covers the whole path and a multi-Pan starts from its original centroid.

**h) `sendCancelLike`**
```kotlin
private fun sendCancelLike(ev: MotionEvent?) {
    if (ev == null) return
    val cancel = MotionEvent.obtain(ev)
    try { cancel.action = MotionEvent.ACTION_CANCEL; superTouch(cancel) }
    finally { cancel.recycle() }
}
```
*Why:* provisional events already reached Compose/AndroidView; a second DOWN without a cancel could corrupt its gesture stream.

**i) `confirmProvisional` / `cancelProvisional`**
```kotlin
private fun confirmProvisional(active: Editor? = editor?.active) {
    if (!provisionalActive) return
    inputBufferHandler.removeCallbacks(inputBufferTimeout)
    for (ev in inputBuffer) ev.recycle()
    inputBuffer.clear(); inputBufferMaxByCount.clear()
    provisionalActive = false
    active?.confirmProvisionalInput()
}

private fun cancelProvisional() {
    if (provisionalActive) {
        inputBufferHandler.removeCallbacks(inputBufferTimeout)
        for (ev in inputBuffer) ev.recycle()
        inputBuffer.clear(); inputBufferMaxByCount.clear()
        provisionalActive = false
        if (inputDebug) Log.d(INPUT_LOG_TAG, "provisional cancelled")
    }
    editor?.active?.cancelProvisionalInput()
    editor?.active?.inputCustomization?.resetClassification()
}
```
*Why:* no MotionEvent leaks; cancel also clears the rewriter latch (the cancel path bypasses the rewriter), so the next gesture on the same pointer id is not inherited.

**j) `dispatchClassified`**
```kotlin
private fun dispatchClassified(..., forceFirstPointerFunction: InputFunction? = null): Boolean {
    ...
    active.inputCustomization.rewrite(..., classifySize = classifySize,
        forceFirstPointerFunction = forceFirstPointerFunction)
    ...
}
```
*Why:* thread the resolved/provisional function through to the rewriter.

**k) Destroy cleanup**
```kotlin
override fun onDestroy() {
    cancelProvisional()
    editor?.retire()
    super.onDestroy()
}
```
*Why:* never leave provisional ink or a replay log behind.

### 2.3.2 `app/src/main/java/com/xnotes/canvas/InputTouchRewriter.kt`

**a) `forceFirstPointerFunction` argument**
```kotlin
fun rewrite(..., classifySize: Float? = null,
            forceFirstPointerFunction: InputFunction? = null): MotionEvent? {
```
**b) Override first pointer during down/replay**
```kotlin
if (i == 0 && force != null) {
    // resolved multi-Pan must not have its first finger relatched as Write
    latch.latch(id, force)
} else {
    customization.match(event.pointerCount, size)?.let { latch.latch(id, it.function) }
}
```
*Why:* the old latch is write-once; a replayed multi-Pan/Han could otherwise lose to a grown one-finger Write match.

**c) No-change fast path**
```kotlin
var changed = false
for (i in 0 until event.pointerCount) {
    if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
    if (desiredToolType(latch.functionFor(event.getPointerId(i))) != event.getToolType(i)) {
        changed = true; break
    }
}
val out = if (changed) build(event) else null
```
*Why:* Pan events and real stylus pointers now allocate nothing.

**d) Scratch arrays + `build(event)`**
```kotlin
private val props = Array(MAX_POINTERS) { MotionEvent.PointerProperties() }
private val coords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
private val batchCoords = Array(MAX_POINTERS) { MotionEvent.PointerCoords() }
...
val p = if (n <= props.size) props else Array(n) { MotionEvent.PointerProperties() }
val c = if (n <= coords.size) coords else Array(n) { MotionEvent.PointerCoords() }
...
val out = MotionEvent.obtain(..., p, c, ...)
...
out.addBatch(event.getHistoricalEventTime(h), b, event.metaState)
```
*Why:* `MotionEvent.obtain`/`addBatch` copy their inputs (verified against SDK 37 sources: no exact-length requirement), so per-event `2 × pointerCount` objects are eliminated; historical samples are still fully replayed in order.

### 2.3.3 `app/src/main/java/com/xnotes/canvas/InteractionController.kt`

**a) State**
```kotlin
private var provisionalStroke = false
private var nextStrokeProvisional = false
```
**b) Safe gate**
```kotlin
fun canBeginProvisionalStroke(): Boolean =
    tool.isStroke && fingerDraws && mode == PointerMode.IDLE &&
        !hasSelection && editingText == null && editingLabel == null &&
        !ruler.visible && pdfText?.selection == null && polygonDraft == null &&
        pendingShape == null && pendingTape == null && screenshotRect == null &&
        roomButton == null
```
*Why:* a press that would select/edit/grab the ruler/start a draft must go through exact classification, not provisional ink.

**c) Rollback primitive**
```kotlin
fun cancelProvisionalStroke() {
    nextStrokeProvisional = false
    if (!provisionalStroke) return
    cancelLongPress(); cancelDwell(); tapCandidate = false
    dwellEligible = false; snappedSelectionPendingMenu = false
    strokeDismissedSelection = false
    crossedSegments.clear(); crossedPieces.clear()
    pendingShape = null; shapePageIndex = null
    liveStroke = null            // gives the front pad back, never commits
    strokePageIndex = null
    snapEngaged = false; snapRunStartEdge = null
    snapCurrentEdge = null; snapPenViewport = null
    drawingPointerId = -1; drawingIsStylus = false
    strokeStartTimeMs = 0L; steadier = null
    provisionalStroke = false; mode = PointerMode.IDLE
    requestRender()
}
```
*Why:* clean discard; no undo entry, no page mutation, pad abandoned.

**d) Down capture + beginDraw**
```kotlin
private fun handleDown(e: MotionEvent) {
    inputMultiPan = false
    val wantProvisional = nextStrokeProvisional
    nextStrokeProvisional = false
    ...
    effectiveTool.isStroke -> beginDraw(..., wantProvisional)
}
private fun beginDraw(..., provisional: Boolean = false) {
    val pageIndex = state.pageIndexAtContent(content) ?: return
    provisionalStroke = provisional
    ...
}
```
*Why:* consume the flag once, so an early branch cannot leave a later stroke provisional.

**e) Page hand-over suppression**
```kotlin
if (onPage != null && onPage != pi && !provisionalStroke) {
    handOverStroke(...)
}
```
*Why:* `handOverStroke` files the segment behind the pen (`fileStroke`); a provisional stroke must not file ink that may be rolled back. Once confirmed, hand-over works normally.

**f) Resets**
`endDraw`, `abortGesture`, `resetGestureState` clear `provisionalStroke`/`nextStrokeProvisional`.

### 2.3.4 `app/src/main/java/com/xnotes/ui/Editor.kt`

```kotlin
fun canBeginProvisionalInput(): Boolean =
    !canvasOpen && tool.isStroke && controller.fingerDraws && controller.canBeginProvisionalStroke()

fun beginProvisionalInput() { if (!canvasOpen) controller.beginProvisionalStroke() }
fun confirmProvisionalInput() { if (!canvasOpen) controller.confirmProvisionalStroke() }
fun cancelProvisionalInput() { if (!canvasOpen) controller.cancelProvisionalStroke() }
```
*Why:* the activity talks to panes; the infinite canvas keeps its own pipeline.

### 2.3.5 `app/src/main/java/com/xnotes/ui/InputCustomizationPanel.kt`

```kotlin
fun rewrite(..., classifySize: Float? = null,
            forceFirstPointerFunction: InputFunction? = null): MotionEvent? =
    rewriter.rewrite(event, read(), onAltColor, onPan, onMultiPan, classifySize, forceFirstPointerFunction)

fun resetClassification() = rewriter.reset()
```
*Why:* pass the provisional/authoritative function through; clear the latch before replay.

### 2.3.6 New documentation

- `docs/phase-1-input-latency.md` — Phase 1 audit/implementation report.
- This file — combined Phase 0 + Phase 1 report.

## 2.4 Why these design choices (safety analysis)

- **Provisional only where the default is draw.** `fingerDraws` is auto-enabled on stylus-less panels (`Editor.kt:2758`). On the target (pen reported as `FINGER`), an unmatched finger would draw anyway, so committing on no-match is exactly the default. On a real stylus device, the pen is `STYLUS` and bypasses the buffer; ambiguous finger input keeps the legacy path.
- **Never commit provisional ink on a gesture resolution.** Erase/Pan/Alt/multi cancel the live stroke and replay through the existing classified path. `cancelProvisionalStroke` clears `liveStroke` (front pad abandoned), never pushes an undo command, and provisional strokes suppress page hand-over.
- **Force the first pointer during replay.** The lattice is write-once; without the override, a grown contact could relatch pointer 0 as Write and the multi-Pan would be swallowed (`handlePointerDown` early-returns for a stylus draw).
- **Synthetic CANCEL before replay.** The provisional DOWN/MOVEs already reached Compose/AndroidView; a second DOWN for the same pointer without a cancel could corrupt gesture tracking.
- **Page hand-over off while provisional.** `handOverStroke` calls `fileStroke`, which would make the trailing segment impossible to roll back.
- **No arbitrary timeout change.** `INPUT_CLASSIFY_MS` remains 90 ms as the classification/replay window; it no longer delays rendering.
- **No new APIs.** `MotionEvent`, `Log.isLoggable`, `SystemClock.uptimeMillis` are all ancient; API 23 status is unchanged.

## 2.5 Verification performed

Commands (Windows toolchain, offline), from `/mnt/d/xnotes/v1/xnotes-android`:

```bat
cmd.exe /c "gradlew.bat :app:processDebugMainManifest --offline --console=plain"
cmd.exe /c "gradlew.bat :app:compileDebugKotlin --offline --console=plain"
cmd.exe /c "gradlew.bat :app:testDebugUnitTest --offline --console=plain"
cmd.exe /c "gradlew.bat :app:assembleDebug --offline --console=plain"
cmd.exe /c "gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline --console=plain"
```

Results:

| Check | Result |
|---|---|
| Manifest merge | BUILD SUCCESSFUL (`minSdk 26`, `targetSdk 36` confirmed) |
| Kotlin compile | BUILD SUCCESSFUL |
| Unit tests | BUILD SUCCESSFUL (all existing) |
| Debug APK | BUILD SUCCESSFUL (native libs linked for all ABIs) |
| Pre-existing warning | `InteractionController.kt:1367 'when' is exhaustive so 'else' is redundant` — untouched |

`git diff --check` flags CR at EOL because the repository is CRLF and `core.whitespace` lacks `cr-at-eol`; no EOL conversion was done.

## 2.6 Latency before/after

No device attached (`adb devices` empty), so **no measured latency**. Structural change:

| | Before | After |
|---|---|---|
| Ambiguous writing DOWN dispatch | withheld until UP or 90 ms | same `dispatchTouchEvent` call |
| First ink bound | classification window (plus main-thread jank) | existing draw/present path only |
| Replay cost | on every ambiguous gesture at flush | only when classification differs |

On-device instrumentation (already in the code):
```bash
adb logcat -s xnotes.input:D
# provisional begin size=... dispatchLag=<N>ms
# provisional resolve -> PAN in <N>ms, 2 ptr, <N> events
# provisional confirm in <N>ms (no settled match)
# provisional cancelled
```
`dispatchLag = SystemClock.uptimeMillis() − event.eventTime`.

## 2.7 Unverified on hardware (required Phase 1 gate)

- Actual input-to-first-ink and `dispatchLag`.
- Single-pointer writing with FINGER and true STYLUS.
- A short stroke that ends inside the 90 ms window.
- A stroke whose size resolves to ERASE mid-window (rollback + erase path).
- Two-finger Pan starting after the first finger moved (override path).
- Palm resting while writing (ignored second pointer / no accidental pinch).
- Unrecorded pinch zoom and two-finger gestures.
- Alt-write colour replay.
- Consecutive fast strokes and the synthetic CANCEL in Compose/AndroidView.

## 2.8 Known limitations / remaining issues

- Provisional requires stroke tool + `fingerDraws` + safe idle controller state. Other cases keep the exact old buffer.
- Infinite canvas input is untouched.
- Ambiguous input that resolves late to Pan/Erase can show a brief ink flash before rollback; no ghost ink and no model/undo change.
- No-match short strokes use panel pressure instead of the finger default `1.0` (width-only difference).
- Page crossing is suppressed while provisional; off-page ink in the window stays clipped to the start page.
- No JVM unit test covers the provisional rewriter/controller path (real `MotionEvent`s required; existing tests use Android stubs).
- Android 6 compatibility is unchanged from Phase 0 (`minSdk 26`; desugaring + PixelCopy fallback still required).

---

# 3. Complete change inventory

| File | Phase | Change | Status |
|---|---|---|---|
| `docs/phase-0-audit.md` | 0 | Compatibility/performance audit report | Committed in `e47791c4` |
| `app/src/main/java/com/xnotes/EditorActivity.kt` | 1 | Provisional dispatch, replay/rollback, cancel, logs | Uncommitted |
| `app/src/main/java/com/xnotes/canvas/InputTouchRewriter.kt` | 1 | Force-first function, fast path, scratch arrays | Uncommitted |
| `app/src/main/java/com/xnotes/canvas/InteractionController.kt` | 1 | Provisional state, rollback, page-handover suppression | Uncommitted |
| `app/src/main/java/com/xnotes/ui/Editor.kt` | 1 | Paged provisional delegates | Uncommitted |
| `app/src/main/java/com/xnotes/ui/InputCustomizationPanel.kt` | 1 | Forward force function, reset latch | Uncommitted |
| `docs/phase-1-input-latency.md` | 1 | Phase 1 report | Untracked |
| `docs/phase0,1-implementations.md` | 0/1 | This combined report | Untracked |

---

# 4. Review / test / rollback

**Review the diff**
```bash
git checkout phase-1-input-latency
git status
git diff -- app/src/main/java/com/xnotes/EditorActivity.kt \
              app/src/main/java/com/xnotes/canvas/InputTouchRewriter.kt \
              app/src/main/java/com/xnotes/canvas/InteractionController.kt \
              app/src/main/java/com/xnotes/ui/Editor.kt \
              app/src/main/java/com/xnotes/ui/InputCustomizationPanel.kt
```

**Build / test**
```bat
cmd.exe /c "gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline --console=plain"
```

**Install and watch input transitions**
```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s xnotes.input:D
```

**Rollback Phase 1** (uncommitted)
```bash
git restore app/src/main/java/com/xnotes/EditorActivity.kt \
            app/src/main/java/com/xnotes/canvas/InputTouchRewriter.kt \
            app/src/main/java/com/xnotes/canvas/InteractionController.kt \
            app/src/main/java/com/xnotes/ui/Editor.kt \
            app/src/main/java/com/xnotes/ui/InputCustomizationPanel.kt
```
**Rollback Phase 0** (committed): `git revert e47791c4` (report only) or leave it; Phase 1 branches from it.

---

## 5. Final status

- Phase 0: complete (static audit, compatibility verdict, bottleneck list, baseline protocol); no source changes.
- Phase 1: implemented, compiles, all existing unit tests pass, debug APK assembles; **device verification pending**; changes left uncommitted for review.
- Phase 2: **not started**, per instruction.
