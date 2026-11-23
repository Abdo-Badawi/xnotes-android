# Phase 2 — Stroke Quality & Rendering: Starting Audit

**Branch:** `phase-2-stroke-quality` (from `phase-1-input-latency` @ `a5213af5`)
**Scope:** Baseline audit + structural tests. **No runtime behaviour was changed.**
**Reference:** `writing&performance_edits.txt` §4 and the Phase 0 report.

---

## 1. What was inspected

- `StrokeEngine.kt` — arc-length smoothing, pressure, half-width, caps/head.
- `WetRibbon.kt` — incremental ribbon and `settleTarget`.
- `StrokeTessellator.kt` — disc-swept ribbon, joins, capsule swallowing, runs.
- `StrokeSimplify.kt` — RDP with width channel and guards.
- `FrontInk.kt` / `GlWetPad.kt` / `GlWetPadInk.kt` — front-buffer handover.
- `WetInkCache.kt`, `ItemMesher.kt` — wet ink baking/meshing.
- Existing tests: `StrokeTessellatorTest`, `StrokeSimplifyTest`, `CubicFitTest`, `WetRibbonTest`, `StrokeEngineTest`, `WetInkCacheTest`.

---

## 2. Findings against the Phase 2 plan

### 2.1 “Curve interpolation uses straight-line segments”

**Partially invalid.** Freehand ink is not a chain of unsmoothed chords:

- `StrokeEngine.build` (`StrokeEngine.kt:692`) low-passes positions **per unit of travel** with `emaByArc` (`:184`), `ALPHA = 0.5` (`:18`), `SMOOTH_LEN` (`:44`), plus pressure smoothing and `holdEnds` (`:125`). Tangents are finite differences; the tessellator sweeps a disc along that centreline.
- `CubicFit` (`CubicFit.kt:18`) exists but is used by `ShapeRecognizer` (`:257`) to turn a recognised **shape** into a cubic Bézier. It is **not** the freehand ink path.
- Therefore the plan’s “implement Catmull-Rom/cubic” is not a missing, drop-in feature; adding a spline to freehand ink would **change the user’s geometry**, add points, and add per-event cost. The plan itself forbids changing handwriting geometry or adding latency without evidence.

**Conclusion:** do not add spline interpolation until a device visual capture shows the existing arc-length low-pass is insufficient. The existing `emaByArc` + dense sampling + disc-swept joins is a deliberate design, not an oversight.

### 2.2 “Fixed Join Anchor in `backL`” and “Capsule Swallowing”

- `tessellate` (`StrokeTessellator.kt:116`) marks a segment `SWALLOWED` when one disc contains the other (`:148-153`): the larger disc is drawn whole and the smaller is suppressed.
- `join` (`:209`) fans from `anchor = backL[k]` (`:255-259`) to fill the **back half** of the join disc; the adjacent segment quad fills the forward half. `shares` (`:261`) collapses a side when the turn is small.
- Existing `StrokeTessellatorTest` already covers a sharp-corner watertight ribbon, rounded caps, mesh bounds, valid indices, non-degenerate triangles, and determinism.
- New tests added (passing) cover the two gaps:
  - `aSwallowedCapsuleIsDrawnAsTheLargerDisc` — a swallowed middle disc must not punch a hole.
  - `aWidthReversalAtATurnStaysCovered` — growth/shrink around a sharp turn stays watertight.
- **No code defect was demonstrated.** The `backL` fan is geometrically valid for the back-half fill; changing it without a failing visual case would be speculative and could regress the working joins.

### 2.3 FrontInk handover race

`FrontInk` already implements the plan’s requirements:

- `decide` (`FrontInk.kt:200`) waits while `awaitingPublish` or a previous `settle` is unpublished.
- `takePad` (`:279`) uses `pad.standDown` with a `handoffGen` guard.
- `take`/`hold` (`:341`) sets `abandonToHold`, increments `handoffGen`, and captures.
- `capture` (`:372`) has a 120 ms timeout; every failure path calls `finish`.
- `finish` (`:400`) settles held items and releases the pad after `publishThen`.
- `settle` (`:412`) tracks `awaitingPublish` with `publishThen`.

No confirmed race was found by inspection. A change here is only justified by a device capture of a blink/missing segment; none is available.

### 2.4 RDP simplification

`StrokeSimplify` (`StrokeSimplify.kt:70`) already has the plan’s safeguards:

- `END_KEEP = CAP_HOLD_SAMPLES + 1` (`:51`), `MIN_ARC = 10` (`:61`) leaves short strokes untouched.
- `MAX_GAP = 3` (`:40`), `CORNER_KEEP_ARC = 2` / `CORNER_COS = 30°` (`:44-48`) preserve corners.
- The RDP deviation includes the **rendered half-width channel** (`:134-160`), so pressure/speed spikes survive.
- `keepHead` (`:193`) protects the calligraphy head; `capGaps` (`:201`) bounds chord length.
- `StrokeSimplifyTest` covers straight reduction, eps deviation, width spikes, corners, near-dot strokes, tiny/mismatched input, re-pass tolerance, and gap caps.

No change is justified without a measured pen-up shape change.

### 2.5 Incremental runs / wet cache

- `ItemMesher.meshRun` (`ItemMesher.kt:194`) and `StrokeTessellator.tessellate(from, count)` mesh the settled runs; consecutive runs overlap by one point.
- New tests:
  - `aRunCoversItsOwnSpanAndStaysInBounds` — a run covers its samples/midpoints, keeps rounded caps, and stays within the full mesh bounds.
  - `overlappingRunsLeaveNoGap` — two runs overlapping by one point cover the seam.
- `WetInkCache` (`WetInkCache.kt:65`) bakes only settled points (`MIN_BAKE_POINTS = 48`, `:182`), one point back, so the live tail and baked run share a disc.

No missing run/seam was demonstrated.

---

## 3. Tests added this phase

In `app/src/test/java/com/xnotes/core/infinite/StrokeTessellatorTest.kt`:

1. `aSwallowedCapsuleIsDrawnAsTheLargerDisc`
2. `aRunCoversItsOwnSpanAndStaysInBounds`
3. `overlappingRunsLeaveNoGap`
4. `aWidthReversalAtATurnStaysCovered`

They are structural (coverage / bounds / indices), not screenshot tests. All pass.

---

## 4. Verification

- `:app:testDebugUnitTest` — **BUILD SUCCESSFUL** (full suite, including the four new tests).
- `:app:assembleDebug` — **BUILD SUCCESSFUL**.
- Focused `--tests com.xnotes.core.infinite.StrokeTessellatorTest` — **BUILD SUCCESSFUL**.

Compilation alone is not treated as proof of visual quality; these tests prove geometry invariants, not device appearance.

---

## 5. Device-dependent questions still open

1. Does the current arc-length low-pass actually read as smooth on the target display at 100%, high zoom, and low zoom? (Screenshots / slow-motion capture.)
2. Is there any visible seam at the pad→Skia handover, consecutive strokes, or a crossing confirmation?
3. Do translucent strokes (highlighter/neon) show overdraw at joins on the infinite canvas? (The join fan and segment quads may overlap only on the back/front half; test visually.)
4. Do very fast strokes at 333–500 Hz lose samples before `StrokeSimplify`?
5. Does the pad handover ever drop a segment under load (cache render, large PDF)?
6. On a 2 GB panel, does the wet cache/run path keep up at high zoom?

---

## 6. Recommended Phase 2 order

1. Capture device evidence (pad vs Skia, zoom levels, fast strokes, translucent joins, handover frames).
2. If the centreline is visibly faceted, evaluate a **local, incremental** curve refinement (not a whole-stroke Catmull-Rom) and measure its per-event cost; do not change geometry without that evidence.
3. If the handover shows a blink/gap, fix it from the captured frame sequence, not from assumptions.
4. Keep `StrokeSimplify` as-is unless a pen-up shape change is measured.
5. Re-run the structural tests and the existing suite after any change.

**Status:** Phase 2 started; baseline tests and audit only. No runtime behaviour changed, no commit of behaviour changes. Phase 1 F1 (palm/second-pointer) remains open from the audit.
