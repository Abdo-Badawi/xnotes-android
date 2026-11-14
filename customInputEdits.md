# Input Customization — implementation notes

This document records every edit made for the Input Customization feature, why each edit exists, and
how the feature was verified. The feature is intended for drawing tablets/boards that report a pen as
`TOOL_TYPE_FINGER`, so the app can learn the board's real touch signatures and reclassify touches at
runtime.

## Summary

- Off by default. When off, the touch path behaves as before (no tool-type changes, no allocations
  for rewriting, no visible UI changes).
- A new notes-toolbar button ("Input customization") opens a panel where the user records, per
  function, how the device reports that touch:
  - **Write**
  - **Pan / Flip pages**
  - **Erase** (open-ended upper bound: this size and larger)
  - **Write in another colour** (with a colour picker)
- Each function needs three repeat touches. The recorder builds a size range from the samples with a
  relative 10% margin and stores the pointer count.
- Runtime matching is pure and signature-based. Only `TOOL_TYPE_FINGER` pointers are reclassified:
  - Write / Alt-write -> `TOOL_TYPE_STYLUS`
  - Erase -> `TOOL_TYPE_ERASER`
  - Pan -> stays `TOOL_TYPE_FINGER` but drives centroid panning instead of pinch.
- Real stylus and eraser pointers pass through untouched.
- MotionEvent history is replayed with `addBatch` so fast strokes keep full resolution.
- While the feature is enabled, the app forces effective `fingerDraws = false` (without changing the
  stored preference), so an unclassified finger pans and a classified "write" draws.
- All settings live in JSON preferences and survive restarts.

## Design constraints followed

- Feature disabled means an early return in the activity touch path and no rewriting.
- No hardcoded device sizes or absolute thresholds. The only margin is a fraction
  (`MARGIN_FRACTION = 0.10f`).
- Only finger pointers are reclassified; stylus/eraser pointers are left exactly as the platform
  reported them.
- A pointer keeps the function it was first classified as until it goes up or is cancelled.
- Pinch is preserved when no multi-finger Pan signature has been recorded.
- Recording UI is an in-window overlay (not a Compose `Dialog`), because the activity must see the raw
  `MotionEvent` to read `touchMinor`/`touchMajor`.
- Back cancels recording/testing because capture consumes all touches.
- Stored `fingerDraws` preference is never modified by this feature.

## New files

### `app/src/main/java/com/xnotes/core/tools/InputCustomization.kt`

Pure Kotlin model, no Android dependencies.

- `enum class InputFunction { WRITE, PAN, ERASE, ALT_WRITE }`.
- `data class TouchSignature(minSize, maxSize, pointerCount, openMax)`:
  - `contains(size)` — open-ended for erase (`size >= minSize`), bounded otherwise.
  - `distanceTo(size)` — distance outside the range; 0 inside.
  - `toJson()` / `fromJson()`.
- `data class InputMatch(function, signature, distance)`.
- `data class InputCustomization(enabled, write, pan, erase, altWrite, altColor)`:
  - `signatureFor(function)`
  - `withSignature(function, signature)`
  - `hasAnySignature`
  - `match(pointerCount, size)` — returns the closest accepting signature. Erase ignores pointer
    count; everything else requires an exact count.
  - `overlaps(candidate, forFunction)` — used by the recorder to warn about ambiguous recordings.
  - `signaturesOverlap(...)` — shared overlap rule (erase is open-ended and count-agnostic).
  - `toJson()` / `fromJson()`.
  - `MARGIN_FRACTION = 0.10f`.
- `object InputSignatureBuilder`:
  - `REQUIRED_SAMPLES = 3`
  - `Sample(size, pointerCount)`
  - `build(samples, openMax)` — min/max from samples, 10% relative margin, most common pointer
    count, open upper bound for erase.
- `class InputGestureLatch`:
  - `functionFor(pointerId)`
  - `latch(pointerId, function)` — keeps the first classification for a pointer (no mid-gesture
    changes).
  - `release(pointerId)`
  - `clear()`
  - `isEmpty`

### `app/src/main/java/com/xnotes/canvas/InputTouchRewriter.kt`

Turns recordings into rewritten `MotionEvent`s.

- `rewrite(event, customization, onAltColor, onMultiPan): MotionEvent?`
  - Returns `null` when nothing changes, so callers dispatch the original event with no allocation.
  - On `ACTION_DOWN` / `ACTION_POINTER_DOWN`, classifies every unlatched finger pointer using the
    current `pointerCount` and `touchMinor` (falling back to `touchMajor` when minor is absent).
  - Reports the alt-write colour for the pointer at the action index.
  - Arms multi-finger Pan on `ACTION_POINTER_DOWN` when a Pan signature exists whose recorded
    pointer count is at least 2. It arms on the second finger even if the recording used three
    fingers, so a pinch cannot begin before the third finger arrives.
  - Rebuilds the event only when a latched finger's tool type actually differs.
  - Preserves every historical sample with `addBatch` (`getHistoricalX/Y/Pressure/Size/touchMajor/
    touchMinor/toolMajor/toolMinor/orientation` and historical event time).
  - Releases the lifting pointer on `ACTION_POINTER_UP`; clears the latch and disarms multi-pan on
    `ACTION_UP` / `ACTION_CANCEL`.
  - Clears the latch immediately when the feature is disabled.
- `reset()` clears the latch.
- Only `TOOL_TYPE_FINGER` pointers are considered; `properties[i].toolType` is changed only for those.

### `app/src/main/java/com/xnotes/ui/InputCustomizationPanel.kt`

State holder plus the overlay UI.

`InputCustomizationState(read, write)`:

- Observable state: `open`, `activeFunction`, `samplesDone`, `overlapWarning`, `savedFunction`,
  `testing`, `testMatch`, `tested`, `colorPickerOpen`.
- Actions: `toggleOpen`, `openPanel`, `closePanel`, `startRecording`, `startTest`,
  `cancelGesture`, `toggleEnabled`, `reset`, `resetAll`, `setAltColor`, `openColorPicker`,
  `dismissColorPicker`.
- `capture(event)`:
  - While recording: tracks the peak contact size across all pointers and the maximum pointer count
    for the gesture; on `ACTION_UP` adds one sample. After three samples builds the signature, checks
    overlap, writes it, or shows the overlap warning. Always returns `true` while recording.
  - While testing: classifies the first pointer on down, records the result, stops on up/cancel.
    Always returns `true` while testing.
  - Otherwise returns `false`.
- `rewrite(event, onAltColor, onMultiPan)` delegates to `InputTouchRewriter`.
- Resets the rewriter latch on panel close and on enabling/disabling.

`InputCustomizationPanel(editor, modifier)`:

- Full-pane scrim, centered scrollable card, blocks the canvas.
- Global enable `Switch`.
- One row per function with a localised name, recorded summary ("Size 10–20 · 1 pointer",
  "Size 50 and larger · any pointers" for erase), Record and Reset.
- Alt-write row has a colour swatch that opens the existing `ColorPickerPopup`.
- Reset all / Done / Live test.
- Recording prompt, "N of 3 touches recorded", Back-to-cancel hint, overlap warning, saved
  confirmation, live-test prompt, match result, and "No recorded touch matched".
- A single `BackHandler` cancels recording/testing or closes the panel.

### `app/src/test/java/com/xnotes/core/tools/InputCustomizationTest.kt`

13 pure JUnit tests:

- overlap needs the same pointer count
- disjoint ranges do not overlap
- palm/open-ended erase overlaps larger touches
- erase matches its size and everything larger
- disabled feature never matches
- no recordings never match
- pointer count is part of the signature
- closest signature wins when ranges overlap
- latch keeps the first function until released
- latch tracks each pointer separately through down/up order
- builder adds a margin and keeps the common pointer count
- builder leaves the palm upper bound open
- JSON round trip keeps every signature

## Modified files

### `app/src/main/java/com/xnotes/settings/Preferences.kt`

- Imported `com.xnotes.core.tools.InputCustomization`.
- Added `val inputCustomization: InputCustomization = InputCustomization()`.
- Serialised it under the `input_customization` key in `toJson`.
- Restored it in `fromJson` with
  `InputCustomization.fromJson(o.optJSONObject("input_customization"))`.

JSON shape:

```json
{
  "input_customization": {
    "enabled": false,
    "alt_color": "#ff6b9a",
    "write":     { "min": 10.0, "max": 20.0, "count": 1, "open": false },
    "pan":       { "min": 10.0, "max": 20.0, "count": 2, "open": false },
    "erase":     { "min": 50.0, "max": 3.4028235E38, "count": 1, "open": true },
    "alt_write": null
  }
}
```

### `app/src/main/java/com/xnotes/EditorActivity.kt`

- Reworked `dispatchTouchEvent`:
  1. Keeps the existing `StylusButtonLatch` side-button tracking.
  2. Calls `active.inputCustomization.capture(ev)` before anything else; if it returns `true`, the
     event is consumed (recording/live test).
  3. Keeps the existing Samsung stylus action-code rewrite (`standardPenAction`) exactly as before.
  4. Computes `panelOpen`; while the panel is open, feature rewriting is skipped so panel touches
     are ordinary UI.
  5. `featureOn` requires the panel to be closed and the preference to be enabled.
  6. With neither Samsung nor the feature active, dispatches the original event via the existing
     `eyedropperTouch.dispatch(ev, superTouch)` path.
  7. With the feature active, calls `rewrite(...)` with:
     - `onAltColor = { active.stageInputStrokeColor(it) }`
     - `onMultiPan = { if (it) active.armInputMultiPan() else active.resetInputMultiPan() }`
  8. Dispatches the rewritten event (or a Samsung copy) and recycles it in a `finally` block.
- Added the panel overlay at the end of the outer `Box` in `EditorPane`, above `ColorDropOverlay`:

```kotlin
if (editor.inputCustomization.open) {
    com.xnotes.ui.InputCustomizationPanel(editor, Modifier.fillMaxSize())
}
```

### `app/src/main/java/com/xnotes/ui/Editor.kt`

- Added the shared state object (one per device, shared by split panes):

```kotlin
var inputCustomization: InputCustomizationState = InputCustomizationState(
    read = { settings.prefs.inputCustomization },
    write = { customization -> applyPreferences(settings.prefs.copy(inputCustomization = customization)) },
)
```

- `openCanvasDocument`: passes `settings.prefs.fingerDraws && !inputCustomization.enabled` into
  `canvas.applyInputPrefs`.
- `maybeAutoEnableFingerDraw`: returns early when the feature is enabled, so the forced finger-draw
  gate is not auto-enabled behind the feature.
- `applyPagePrefsToState`:
  - `infiniteOrNull?.applyInputPrefs(p.fingerDraws && !p.inputCustomization.enabled, ...)`
  - `controller.fingerDraws = p.fingerDraws && !p.inputCustomization.enabled`
  - Stored `p.fingerDraws` is never modified.
- Added routing methods:
  - `stageInputStrokeColor(color)` -> infinite canvas or paged controller.
  - `armInputMultiPan()` / `resetInputMultiPan()` -> infinite canvas or paged controller.
- `secondaryPane()` assigns `it.inputCustomization = inputCustomization`, so both panes share the
  panel, recorder, and rewriter.

### `app/src/main/java/com/xnotes/canvas/InteractionController.kt` (paged canvas)

- Added `var inputStrokeColor: Rgba?`; `beginDraw` now picks
  `inputStrokeColor ?: cfg0.colorOverride ?: inkColor`, then clears it.
- Added private `inputMultiPan`.
- Reset multi-pan in `takeBackTouch` and `handleDown`.
- `handlePointerDown`:
  - When `inputMultiPan` and at least two pointers are down, moves `lastPan` to the pointer centroid
    and returns before `beginPinch` (so a recorded multi-finger Pan never starts a pinch / view jump).
- `handleMove`:
  - In `PointerMode.PAN`, when multi-pan is active with at least two pointers, extends the pan using
    the pointer centroid.
- `handlePointerUp`:
  - For multi-pan, updates `lastPan` to the remaining pointers' centroid, keeps the pan alive, and
    moves `drawingPointerId` when only one remains.
- Added `pointerCentroid(event, skipIndex)`.
- Added public `armInputMultiPan()` and `resetInputMultiPan()`.

### `app/src/main/java/com/xnotes/ui/InfiniteInteraction.kt` (infinite canvas)

Mirrors the paged canvas:

- Added `var inputStrokeColor: Rgba?` and private `inputMultiPan`.
- `resetGestureState` and `handleDown` clear multi-pan.
- `handlePointerDown`: with multi-pan and at least two pointers, moves `lastPan` to the centroid and
  returns (does not start a pinch or abandon the stroke/tape/erase).
- `handleMove`: centroid pan when multi-pan is active.
- `handlePointerUp`: keeps panning on remaining fingers and moves `lastPan` to their centroid.
- `beginDraw`: `inputStrokeColor` overrides the tool colour, then clears.
- Added `pointerCentroid`, `armInputMultiPan`, `resetInputMultiPan`.

Note: the existing early returns for `DRAW`/`ERASE`/`TAPE` with a stylus still run first, so a second
finger does not disturb a stroke being drawn by a reclassified pen.

### `app/src/main/java/com/xnotes/ui/InfiniteEditor.kt`

- `stageInputStrokeColor(color)` -> `interaction.inputStrokeColor = color`.
- `armInputMultiPan()` / `resetInputMultiPan()` -> interaction.

### `app/src/main/java/com/xnotes/core/tools/ToolbarLayout.kt`

- Added `ToolbarItem.INPUT_CUSTOM("input_custom")` (notes only; it is automatically part of
  `NOTE_ITEMS` because it is not `WAYPOINTS`/`MINIMAP`).
- Added it to the default notes layout after `VIEW`.
- Added `INSERT_AFTER[INPUT_CUSTOM] = VIEW`, so stored layouts from older versions also get the new
  button beside View.
- `CANVAS_ITEMS` / `CANVAS_DEFAULT` intentionally unchanged.

### `app/src/main/java/com/xnotes/ui/Toolbar.kt`

Added the renderer branch:

```kotlin
ToolbarItem.INPUT_CUSTOM -> ToolbarIcon(
    XnotesIcons.inputCustom,
    stringResource(R.string.input_customization),
    active = editor.inputCustomization.open,
) { editor.inputCustomization.toggleOpen() }
```

### `app/src/main/java/com/xnotes/ui/ToolbarCustomizer.kt`

- Added `ToolbarItem.INPUT_CUSTOM -> XnotesIcons.inputCustom` to the exhaustive icon mapping.

### `app/src/main/java/com/xnotes/ui/Labels.kt`

- Added `ToolbarItem.INPUT_CUSTOM -> R.string.input_customization` to the exhaustive label mapping.
  (This was caught by the compiler as a missing branch.)

### `app/src/main/java/com/xnotes/ui/icons/XnotesIcons.kt`

- Added `XnotesIcons.inputCustom`, a finger-touching-a-surface icon built from the existing `icon`
  path helper.

### `app/src/main/res/values/strings.xml`

Added:

- `input_customization`
- `input_customization_enable`
- `input_customization_hint`
- `input_function_write`
- `input_function_pan`
- `input_function_erase`
- `input_function_alt_write`
- `input_customization_record`
- `input_customization_reset`
- `input_customization_reset_all`
- `input_customization_done`
- `input_customization_not_recorded`
- `input_customization_signature`
- `input_customization_palm`
- `input_customization_touch_prompt`
- `input_customization_sample_progress`
- `input_customization_overlap`
- `input_customization_saved`
- `input_customization_cancel_back`
- `input_customization_test`
- `input_customization_test_prompt`
- `input_customization_test_result`
- `input_customization_test_no_match`
- `input_customization_colour`

## Runtime flow

1. `EditorActivity.dispatchTouchEvent` receives every event first.
2. If the panel is recording or live-testing, the state consumes the event and the normal UI never
   sees it.
3. Otherwise the existing Samsung stylus rewrite is computed.
4. When the feature is enabled and the panel is closed, `InputTouchRewriter.rewrite` classifies new
   finger pointers and, only if a tool type changes, builds a replacement event with history.
5. Side effects are routed to the active editor:
   - alt-write colour staged for the next `beginDraw`;
   - multi-finger Pan armed/disarmed.
6. The rewritten event is dispatched and recycled.
7. On up/cancel, the rewriter clears its per-pointer latch.

## Verification performed

Build environment note: WSL had no `java` on `PATH`, and `local.properties` points at the Windows
Android SDK. The Windows Gradle wrapper was used through `cmd.exe`, with the Windows machine's
JDK 21 (`JAVA_HOME` already set) and Gradle 8.13.

Commands used:

```bat
gradlew.bat :app:compileDebugKotlin --console=plain
gradlew.bat :app:testDebugUnitTest --tests com.xnotes.core.tools.InputCustomizationTest --console=plain
gradlew.bat :app:testDebugUnitTest --tests com.xnotes.core.tools.InputCustomizationTest --tests com.xnotes.core.tools.ToolbarLayoutTest --console=plain
gradlew.bat :app:assembleDebug --console=plain
gradlew.bat :app:testDebugUnitTest --console=plain
```

Results:

- `:app:compileDebugKotlin` — successful.
- `InputCustomizationTest` — 13/13 passing.
- `ToolbarLayoutTest` — passing after the new toolbar item.
- `:app:assembleDebug` — BUILD SUCCESSFUL (APK produced).
- Full unit suite — 2520 tests, 2519 passing, 1 failure:
  `TemplateTest.theBundledAiGuideHoldsTheSpecAndExamplesThatRead`.

### Pre-existing unrelated test failure

`TemplateTest` reads `src/main/assets/templates/AI.md` with `File(...).readText()` and asserts it
contains strings with `\n`. This checkout has CRLF line endings in `AI.md` (and throughout the
working tree), so the assertion cannot find `\n# xtemplate for AI assistants\n`. This is a
line-ending/environment issue present before the Input Customization work and was not modified.

The `:app:assembleDebug` output also contains `CXX5304` messages about a malformed/inconsistent
Android SDK system-image package path. Those are SDK-repository warnings during CMake location
resolution; the build still completed successfully.

## Issues found during verification and fixes

1. **Missing exhaustive branches.** `Labels.kt` needed `INPUT_CUSTOM`; the compiler flagged it. Added
   the branch (and the `ToolbarCustomizer`/icon mappings were already handled).
2. **Latch could be overwritten.** The first version replaced an existing latch entry. The test
   `latchKeepsTheFirstFunctionUntilReleased` caught it. Changed `latch` to keep the first value
   (`if (!latched.containsKey(pointerId)) ...`).
3. **JSON round-trip test representation.** The test used `maxSize = 0f` with `openMax = true`; the
   persisted form normalises the upper bound to `Float.MAX_VALUE`. The test now uses the normalised
   value so it compares like with like.
4. **Three-finger Pan could start a pinch first.** Arming only when the newly down pointer matched
   the Pan signature with the exact count allowed a pinch to begin at two fingers. The rewriter now
   arms multi-finger Pan on the second finger whenever a Pan signature with count >= 2 exists.
5. **Panel touches were being reclassified.** Added `panelOpen` to `dispatchTouchEvent` so touches on
   the open panel are treated as ordinary UI unless recording/testing is active.
6. **Stale test/latch state.** Added a `tested` flag, "No recorded touch matched" feedback, and a
   rewriter reset on panel close and enable/disable.

## Assumptions and limitations

- Write/Alt-write are expected to be single-pointer gestures in practice. The recorder technically
  allows any pointer count, but a multi-finger Write would only match once that many fingers are
  down; the intended use is a pen tip.
- If Pan is recorded with two or more fingers, the feature intentionally disables pinch-zoom for
  multi-finger touches while enabled (this is the point of recording a multi-finger Pan).
- The feature affects all finger touches in the editor activity, not only the canvas. Panel UI is
  excluded, but toolbar and in-window menu touches still pass through the rewriter when the panel is
  closed. Touch streams in separate windows (for example, a Compose Dialog) never reach
  `EditorActivity.dispatchTouchEvent` and are therefore unaffected.
- Only default (English) strings were added; other locales fall back to English.
- No physical device or emulator was available in this session, so the following remain unverified:
  actual contact sizes on the target board, real `touchMinor`/`touchMajor` values, behaviour of the
  Samsung stylus action rewrite combined with this feature, and split-pane focus edge cases.

## Manual test checklist

1. Open the notes toolbar and tap **Input customization**; confirm the panel opens above the canvas
   and the toolbar button shows as active.
2. Record each function with three touches. Confirm the sample counter advances, the summary appears,
   and the panel reports Saved.
3. Deliberately record an overlapping range; confirm the overlap warning and that the recording is
   discarded.
4. Use **Live test** for each function; confirm the matched function name, and the no-match message
   for an unrecorded touch.
5. Enable the feature. Confirm:
   - a recorded Write touch draws;
   - a recorded Erase touch erases, including larger palm-sized contacts;
   - a recorded two-finger Pan pans by the centroid and does not pinch;
   - alt-write uses the chosen colour;
   - an unrecorded finger pans instead of drawing.
6. If Pan is not recorded (or is recorded with one finger), confirm two-finger pinch still zooms.
7. Press Back while recording; confirm the recording is cancelled and no touch reaches the canvas.
8. Turn the feature off; confirm the original finger/pen behaviour returns immediately and the stored
   finger-draw preference was not changed.
9. Restart the app; confirm the enabled state, recordings, and alt colour persist.
10. Repeat the checks in two-pane mode and confirm the panel is shared and touches are routed to the
    focused pane.

## v2 Changes (after phone test feedback)

### User requirements
- Toolbar tool selection still respected: custom settings override defaults when a recording matches;
  unmatched touches fall back to the default behaviour (one-finger draw, two-finger zoom/scroll).
- Gestures distinguished by **both** contact size **and** finger count (same size, different counts = different
  gestures).
- Build as v2 without deleting the v1 APK.

### Root causes fixed
1. **Global `fingerDraws = false` forced fallback to pan** — reverted the gating in `Editor.openCanvasDocument`,
   `applyPagePrefsToState`, and `maybeAutoEnableFingerDraw`. Now unmatched touches use the normal defaults.
2. **Multi-finger Write didn't start** — first finger matched 1-finger Pan → mode PAN; second finger
   matched Write but controller called `beginPinch`. Fixed by:
   - Adding a per-gesture **force-pan override** (`inputPanOverride`) set from the rewriter on every
     `ACTION_DOWN`/`ACTION_POINTER_DOWN`.
   - In `handlePointerDown`, starting a draw/erase on a newly down pointer classified as STYLUS/ERASER
     even when mode is PAN/DRAW/PINCH (transitions: DRAW→PAN, PINCH→PAN, ERASE→DRAW).
   - Ending the draw/erase on the lifting of the drawing/erasing pointer (new `handlePointerUp` branch).
3. **Erase ignored pointer count** — changed `match` and `signaturesOverlap` to require exact pointer count
   for all functions including ERASE (open upper bound retained).
4. **Gesture size mismatch** — recorder used max contact size across pointers; runtime matcher now uses the
   same gesture-level max across finger pointers for consistency.

### Files changed (v2)
- `InputCustomization.kt`: `match` and `signaturesOverlap` now exact-count for erase; comments updated.
- `InputTouchRewriter.kt`: added `onPan` callback; `gestureSize()` returns max finger contact size;
  `onPan`/`onMultiPan` driven by action-pointer function (not just signature existence).
- `InputCustomizationPanel.kt`: live test uses gesture max size; palm signature text includes count.
- `strings.xml`: `input_customization_palm` now shows pointer count.
- `EditorActivity.kt`: routes `onPan` to `armInputPan`/`resetInputPan`.
- `Editor.kt`: removed `!inputCustomization.enabled` gating from fingerDraws; added `armInputPan`/
  `resetInputPan` routing.
- `InteractionController.kt`: added `inputPanOverride`; force-pan in `effectiveTool`; `handlePointerDown`
  transitions for new STYLUS/ERASER pointers; `handlePointerUp` ends draw/erase on pointer lift;
  `takeBackTouch` resets `inputPanOverride`; new `armInputPan`/`resetInputPan`.
- `InfiniteInteraction.kt`: same changes mirrored; uses `eraseSession` and `mode = IDLE` for pinch end.
- `InfiniteEditor.kt`: routes `armInputPan`/`resetInputPan`.
- Tests: updated `palmIsOpenEndedSoAnythingLargerOverlaps`, `eraseMatchesItsSizeAndEverythingLarger`,
  added `eraseRequiresExactPointerCount` and `sizeAndCountTogetherDistinguishGestures`.

### Build outputs
- v1 APK preserved at `app/build/outputs/apk/debug/app-debug-v1.apk` (~60 MB).
- v2 APK at `app/build/outputs/apk/debug/app-debug-v2.apk` (~60 MB).
- All unit tests pass except pre-existing `TemplateTest` CRLF failure.
