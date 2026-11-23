# Phase 1 — Fix Input Latency

**Branch:** `phase-1-input-latency` (from `phase-0-performance-audit`)
**Scope:** Paged editor handwriting path. Infinite canvas and `fingerDraws=false` ambiguity keep the exact legacy buffer.
**Reference:** `writing&performance_edits.txt` §3, Phase 0 audit (`docs/phase-0-audit.md`).

---

## 1. Root causes addressed

| Root cause | Evidence (before) | Change |
|---|---|---|
| Ambiguous single-finger input was **not dispatched at all** until UP or the 90 ms timeout. | `EditorActivity.dispatchTouchEvent` returned `true` while `inputBufferActive`; no event reached `InteractionController`. | Dispatch happens immediately; the 90 ms window is now only a **replay window**, not a render delay. |
| The 90 ms flush ran on the main thread, so UI jank stretched the delay. | `inputBufferTimeout` on the main looper; `flushInputBuffer` synchronous. | The stroke is already on screen before the timeout; jank can no longer hide the first ink. |
| A multi-finger Pan could lose to the first finger's write once the contact grew. | Latch is write-once; replay could relatch pointer 0 as Write. | The resolved function now **overrides** the first pointer during replay (`forceFirstPointerFunction`), so a resolved Pan/Erase wins. |
| Per-event `PointerProperties`/`PointerCoords` garbage on the input hot path. | Rewriter allocated `2 × pointerCount` objects for every event, even when no tool type changed. | No-change fast path allocates nothing; the arrays are reusable scratch and `obtain`/`addBatch` copy them. |

**Not addressed here (by design):**
- Infinite canvas (`InfiniteInteraction`) keeps its own pipeline.
- `fingerDraws=false` (stylus-capable device) ambiguous finger input keeps the exact blocking buffer; real stylus input already bypasses it.
- No change to the API 23 compatibility status (`minSdk` still 26).

---

## 2. Files modified and exact changes

### `app/src/main/java/com/xnotes/EditorActivity.kt`
- `inputBufferTimeout` now branches: `confirmProvisional()` when a provisional stroke is live, else the legacy `flushInputBuffer()`.
- `dispatchTouchEvent` routing:
  1. provisional live → `handleProvisional`;
  2. legacy buffer live → `bufferInput`;
  3. `shouldProvisional` → `beginProvisional`;
  4. legacy `shouldBufferInput` → `beginInputBuffer`;
  5. otherwise the existing classified dispatch.
- `shouldProvisional`: `ACTION_DOWN`, `TOOL_TYPE_FINGER`, no eyedropper target, `Editor.canBeginProvisionalInput()`, customization enabled, a 1-pointer signature exists, and `match(1, size) == null`.
- `beginProvisional`: latch `WRITE`, buffer the copy, arm the 90 ms classification window, dispatch **immediately** with `forceFirstPointerFunction = WRITE`.
- `handleProvisional`: buffers the copy, tracks the per-count peak size, then
  - resolves to `ERASE` / `PAN` / `ALT_WRITE` / any multi-pointer signature → rollback + replay;
  - confirms `WRITE` → keep ink;
  - no match on UP / writing-pointer lift → keep ink (the default finger behaviour is draw on this path);
  - otherwise dispatch the move immediately.
- `resolveProvisional`: detaches the replay log, sends a synthetic `CANCEL` to the view tree (so Compose/AndroidView reset), rolls back the provisional stroke, resets the classifier latch, then replays every held event from DOWN with the resolved function and the per-count peak.
- `sendCancelLike`: builds an `ACTION_CANCEL` copy and passes it to `super.dispatchTouchEvent`.
- `confirmProvisional` / `cancelProvisional`: stop the window, recycle all held copies, update the controller, and (on cancel) clear the rewriter latch so the next gesture on the same pointer id is not inherited.
- `dispatchClassified` gained `forceFirstPointerFunction` and forwards it.
- `onDestroy` cancels any provisional stroke.
- Debug-only transition logs under tag `xnotes.input` (`begin` with `dispatchLag`, `resolve`, `confirm`, `cancel`); no per-move logging.

### `app/src/main/java/com/xnotes/canvas/InputTouchRewriter.kt`
- New `forceFirstPointerFunction` parameter. For the first unlatched finger it latches that function **instead of** a signature match, so a replay resolved to Pan/Erase is authoritative.
- No-change fast path: scans for a required tool-type change before allocating anything.
- Reusable `PointerProperties` / `PointerCoords` / historical batch scratch arrays (safe: `MotionEvent.obtain` and `addBatch` copy their inputs). Falls back to fresh arrays above 20 pointers.
- `desiredToolType` centralizes the function→tool mapping.
- Historical samples are still replayed in order via `addBatch` (no sample loss).

### `app/src/main/java/com/xnotes/canvas/InteractionController.kt`
- New `provisionalStroke` and `nextStrokeProvisional` state.
- `canBeginProvisionalStroke()`: stroke tool, finger-draw on, `IDLE`, and no selection / text edit / PDF text selection / ruler / polygon / shape / tape / screenshot / room button.
- `beginProvisionalStroke`, `confirmProvisionalStroke`, `cancelProvisionalStroke`.
- `cancelProvisionalStroke` drops the live stroke (which releases the front pad via `frontInk.abandon()`), cancels long-press/dwell/tap, drops pending shape, clears draw state, and never pushes an undo command.
- `handleDown` consumes `nextStrokeProvisional` and passes it to `beginDraw`; `beginDraw` records it.
- `addStrokePoint` suppresses page hand-over while provisional, so nothing is filed that could not be rolled back.
- `endDraw`, `abortGesture`, `resetGestureState` clear the provisional flags.

### `app/src/main/java/com/xnotes/ui/Editor.kt`
- `canBeginProvisionalInput()` (`!canvasOpen && tool.isStroke && controller.fingerDraws && controller.canBeginProvisionalStroke()`).
- `beginProvisionalInput`, `confirmProvisionalInput`, `cancelProvisionalInput` (paged controller only).

### `app/src/main/java/com/xnotes/ui/InputCustomizationPanel.kt`
- `InputCustomizationState.rewrite` forwards `forceFirstPointerFunction`.
- `resetClassification()` clears the rewriter latch for replay.

---

## 3. Before/after latency

**No device was attached** (`adb tools present; adb devices` empty), so no runtime numbers are claimed. Structurally:

| Metric | Before | After |
|---|---|---|
| First dispatch of an ambiguous writing DOWN | withheld until UP or `INPUT_CLASSIFY_MS` (90 ms), and later under main-thread jank | same `dispatchTouchEvent` call |
| Input → first ink on the paged path | bounded below by the classification window | no longer bounded by it; bounded by the existing draw/present path |
| Replay cost | only when the buffer flushed | only when classification resolves to a different function |

Debug instrumentation is in place to measure this on hardware:
```bash
adb logcat -s xnotes.input:D
# provisional begin size=... dispatchLag=<N>ms
# provisional resolve -> PAN in <N>ms, 2 ptr, <N> events
# provisional confirm in <N>ms (no settled match)
```
`dispatchLag` is `SystemClock.uptimeMillis() − eventTime` (hardware event to our dispatch). Compare it with the old build, where the equivalent path would log only at flush.

---

## 4. Build and test results

All run from the Windows toolchain (`cmd.exe /c gradlew.bat … --offline`), JDK 21, Gradle 8.13, AGP 8.13.2:

| Command | Result |
|---|---|
| `:app:compileDebugKotlin` | **BUILD SUCCESSFUL** |
| `:app:testDebugUnitTest` | **BUILD SUCCESSFUL** (all existing tests) |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** (APK packaged, native libs linked) |

- One pre-existing warning remains: `InteractionController.kt:1367 'when' is exhaustive so 'else' is redundant` (not introduced by Phase 1; left untouched per no-unrelated-refactor).
- `git diff --check` reports CR-at-EOL on added lines because the repository files are CRLF and `core.whitespace` does not set `cr-at-eol`; line endings match each file's existing style, and no EOL conversion was performed.

---

## 5. Known limitations and unverified device behaviour

1. **No physical interactive display attached**, so the following are unverified:
   - actual input-to-first-ink latency and `dispatchLag`;
   - gesture regression on hardware;
   - palm erasing and multi-finger Pan/zoom with the new replay path;
   - the synthetic `CANCEL` behaviour in Compose/AndroidView on the target.
2. **Scope:** provisional ink requires a stroke tool, `fingerDraws=true` (auto-enabled on stylus-less panels), and a safe idle controller state. Other cases keep the exact old buffer.
3. **`fingerDraws=false` devices** (real stylus available): real pen input is `STYLUS` and bypasses the buffer; ambiguous finger input keeps the old 90 ms buffer exactly.
4. **Infinite canvas** is untouched (keeps its own input/front-buffer pipeline).
5. **Ambiguous pan/erase after provisional draw**: the ink is shown for up to the classification window, then rolled back and the held events replayed as the resolved gesture. Ink is correctly removed (no model edit, no undo entry); a brief visible ink flash is possible on panels whose contact size resolves late. This is the deliberate trade for immediate handwriting; it does not affect the final gesture.
6. **Pressure on a no-match short stroke** is the panel's pressure (the provisional pointer is a stylus), not the finger default of `1.0`; width-only, no shape/position change.
7. **Page crossing while provisional** is deferred, not clipped: on confirmation the held events are replayed as a normal Write so the existing hand-over splits the stroke onto each page. Pan/Erase resolution still rolls back without filing anything. Straight lines keep the pre-existing single-segment behaviour.
8. **API 23:** no new APIs were used; the app's `minSdk` remains 26, so Android 6 compatibility is unchanged from Phase 0.

---

## 6. Remaining issues

- Device verification is the gating step. Required tests: single-pointer writing (FINGER and STYLUS), a short stroke that ends inside the window, a stroke that resolves to ERASE mid-window, a two-finger Pan starting after the first finger moved, palm resting while writing, pinch zoom (unrecorded), alt-write colour, and consecutive fast strokes.
- The 90 ms constant now bounds only classification/replay, not rendering. Its value can be tuned after device measurement; it was **not** reduced arbitrarily.
- No unit test covers the rewriter/controller provisional path (they need real `MotionEvent`s; the existing suite uses Android stubs and cannot construct them). Existing tests pass.
- The infinite-canvas phase-1 items (`InfiniteCanvasView.kt`) from the reference plan remain for a later increment.

---

**Status:** Phase 1 code complete on `phase-1-input-latency`; build and existing tests green. Waiting for review before Phase 2.

---

## 7. Review follow-up — pinch and cross-page corrections (uncommitted, pending review)

### 7.1 Issue 1 — pinch regression

**Root cause:** the provisional stroke was rewritten to `TOOL_TYPE_STYLUS`. `InteractionController.handlePointerDown` returns early for `mode == DRAW && drawingIsStylus`, so a second pointer was ignored. Before Phase 1, an **unmatched** finger draw stayed `FINGER`; a second pointer reached `beginPinch(e)`, which discarded the live stroke. (A matched Write/real stylus ignored the second pointer, as intended.)

**Fix:**
- `InteractionController.strokePinchable`: true for a provisional stroke and for a no-match confirmation (the default finger draw); false for a matched `WRITE`/`ALT_WRITE` or a real stylus.
- `handlePointerDown`: when `DRAW && drawingIsStylus && strokePinchable && pointerCount >= 2`, and no recorded Pan won, clear the stroke/snap state and call `beginPinch(e)`. The pinch anchors on the **current** two-pointer positions, so the viewport does not jump; `beginPinch` clears `liveStroke`, so no ink is committed.
- Recorded Pan keeps precedence: `inputPanOverride`/`inputMultiPan` fall through to the existing Pan handling.
- `EditorActivity` checks `isInputPinching()` after dispatching a provisional second pointer and stops its replay window (`stopProvisionalForGesture`).
- Matched Write/Alt-Write/stylus still ignore a resting second pointer, preserving palm rejection.

### 7.2 Issue 2 — page crossing

**Root cause:** `addStrokePoint` suppressed `handOverStroke` while provisional. The samples outside the starting page stayed in that page's stroke and were clipped when confirmed. Pre-Phase-1 `handOverStroke` split the stroke, filed the completed segment, bridged the last point, and continued on the new page; nothing was discarded.

**Fix:**
- `CanvasState.strokeCrossesPage(pageIndex, stroke)` maps each sample back to content and asks `pageIndexAtContent`; gap samples do not count, straight strokes are excluded (they never handed over before).
- Before confirming a Write or a no-match stroke, `EditorActivity.confirmProvisional` checks `inputStrokeCrossedPage()`. If true, it rolls back and replays the held events as a **normal** Write (`resolveProvisional(WRITE, pinchable)`), so the proven `handOverStroke` split/files/bridges every page. `confirmProvisional` returns `true` so the caller does not dispatch the current event twice.
- Pan/Erase resolution still cancels with no hand-over and therefore files nothing.
- A crossing replay that settled as the default finger draw remains pinchable (`markInputStrokePinchable`), matching pre-Phase-1 finger-draw → pinch behavior.

### 7.3 Tests and verification

- `CrossPageStrokeTest`: added `aSampleThatMapsToAnotherPageIsDetected` and `aGapSampleIsNotAnotherPage`; the focused run and the full suite pass.
- `:app:testDebugUnitTest` and `:app:assembleDebug` both **BUILD SUCCESSFUL**.
- Pinch/Compose/GL-pad behavior remains **device-dependent** (needs real `MotionEvent`s and the panel): a JVM test cannot construct the multi-pointer events.

### 7.4 Remaining device-dependent limitations

- Pinch vs palm/second pointer after a no-match confirmation on the target panel.
- Recorded-Pan precedence over pinch with a real second finger.
- Synthetic `ACTION_CANCEL` and the replay-as-Write crossing path in Compose/AndroidView and across the GL Wet Pad handover.
- A crossing confirmation can show a brief rollback/redraw flash (correctness over a clipped stroke).
- No-match pressure width vs the finger default `1.0` (width-only).
- Android 6 compatibility is unchanged from Phase 0 (`minSdk` 26).

**Status:** follow-up fixes are in the working tree of `phase-1-input-latency`, built and unit-tested, **not committed**; waiting for review. Phase 2 not started.
