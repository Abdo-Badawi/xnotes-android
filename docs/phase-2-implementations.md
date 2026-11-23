# Phase 2 — Stroke Quality & Rendering: Full Implementation Report

**Branch:** `phase-2-stroke-quality`
**Base:** `phase-1-input-latency` @ `a5213af5`
**Phase 2 commit before this report:** `96748d31` — “Start Phase 2 stroke geometry audit with capsule and run tests”
**Status:** Phase 2 started with a code audit and structural tests. **No runtime ink behaviour was changed in this phase.**
**Reference:** `writing&performance_edits.txt` §4 and `docs/phase-0-audit.md`.

---

## 1. Scope and approach

The reference plan’s Phase 2 assumes four concrete defects:

1. freehand ink is connected with straight-line segments and needs Catmull-Rom/cubic interpolation;
2. `StrokeTessellator`’s `backL` join anchor and capsule `SWALLOWED` handling distort or disconnect strokes;
3. `FrontInk` has a handover race that can miss segments;
4. RDP simplification changes stroke shape at pen-up.

Per the plan’s own rule — **do not assume the Wet Ink algorithm is correct without examining it** — this phase began by verifying those assumptions against the actual code and existing tests before changing geometry. Blindly “fixing” a mature tessellator or changing the smoothing would risk the handwriting quality the phase is meant to protect.

**Outcome:** the code is substantially more mature than the plan assumes. No code-level defect was demonstrated for the tessellator, simplifier, or wet-cache paths. The phase therefore established a verified baseline and added the missing structural tests instead of making speculative geometry changes.

---

## 2. What was inspected

| Area | Files |
|---|---|
| Centreline and width | `core/stroke/StrokeEngine.kt`, `core/stroke/WetRibbon.kt` |
| Tessellation | `core/infinite/StrokeTessellator.kt`, `core/infinite/ItemMesher.kt` |
| Simplification | `core/stroke/StrokeSimplify.kt` |
| Wet ink / handover | `canvas/FrontInk.kt`, `canvas/WetInkCache.kt`, `gl/GlWetPad.kt`, `gl/GlWetPadInk.kt` |
| Existing tests | `StrokeTessellatorTest`, `StrokeSimplifyTest`, `CubicFitTest`, `WetRibbonTest`, `StrokeEngineTest`, `WetInkCacheTest` |

### Existing coverage found

- `StrokeTessellatorTest` already tests: single-sample disc, straight-line coverage, watertight sharp corner, rounded ends, mesh bounds, valid indices, non-degenerate triangles, dashed strokes, sub-pixel width, determinism.
- `StrokeSimplifyTest` already tests: straight-run reduction, per-sample eps, width spikes, sharp-corner neighbourhoods, near-dot strokes, mismatched/tiny input, re-pass tolerance, gap caps.
- `StrokeEngineTest` / `WetRibbonTest` cover the arc-length low-pass and incremental ribbon settle.
- `CubicFitTest` covers the cubic fitter used by **shape recognition**, not freehand ink.

---

## 3. Findings against the plan

### 3.1 Curve interpolation — the plan’s premise is only partly true

- `StrokeEngine.build` (`StrokeEngine.kt:692`) smooths positions **per unit of travel** with `emaByArc` (`:184`), `ALPHA = 0.5` (`:18`), `SMOOTH_LEN` (`:44`), plus pressure smoothing and `holdEnds` (`:125`).
- The rendered ribbon is a **disc swept along that smoothed polyline**, with rounded joins/caps (`StrokeTessellator`), not a chain of visible straight chords.
- `CubicFit` (`CubicFit.kt:18`) is used by `ShapeRecognizer` (`:257`) to fit a recognized shape, not by the freehand ink path.
- Adding a Catmull-Rom/cubic stage to freehand ink would change the user’s geometry, add points, and add per-event cost. The plan forbids changing handwriting geometry or adding latency without evidence.

**Decision:** no interpolation change in this phase. The existing arc-length low-pass is deliberate; whether it is visually insufficient on the target is a device question, not a code-reading one.

### 3.2 Tessellator `backL` / capsule swallowing — no defect demonstrated

- `tessellate` (`StrokeTessellator.kt:116`) marks a capsule `SWALLOWED` when one disc contains the other (`:148-153`): the larger disc is drawn whole, the smaller is suppressed.
- `join` (`:209`) fans from `anchor = backL[k]` (`:255-259`) to fill the **back half** of the join disc; the adjacent segment quad fills the forward half. `shares` (`:261`) collapses a side when the turn is small.
- The existing `theRibbonIsWatertightAcrossASharpCorner` test already proves no hole at a sharp corner and along the centerline.
- New tests (this phase) cover the untested cases:
  - `aSwallowedCapsuleIsDrawnAsTheLargerDisc`
  - `aWidthReversalAtATurnStaysCovered`

**Decision:** no tessellator change. The current `backL` fan is geometrically valid for the back-half fill; changing it without a failing visual case would be speculative.

### 3.3 FrontInk handover — already defensive

`FrontInk` already implements the plan’s requirements:

- `decide` (`FrontInk.kt:200`) waits while `awaitingPublish` or a previous `settle` is unpublished.
- `takePad` (`:279`) uses `pad.standDown` with a `handoffGen` guard.
- `take`/`hold` (`:341`) abandons the pad, increments `handoffGen`, and captures.
- `capture` (`:372`) has a 120 ms timeout; every failure path calls `finish`.
- `finish` (`:400`) settles held items and releases the pad after `publishThen`.
- `settle` (`:412`) tracks `awaitingPublish` with `publishThen`.

No confirmed race was found by inspection. A change is only justified by a device frame capture showing a blink/missing segment; none is available.

### 3.4 RDP simplification — already guarded

`StrokeSimplify` (`StrokeSimplify.kt:70`) already has the plan’s safeguards:

- `END_KEEP = CAP_HOLD_SAMPLES + 1` (`:51`) and `MIN_ARC = 10` (`:61`) leave short strokes untouched.
- `MAX_GAP = 3` (`:40`), `CORNER_KEEP_ARC = 2` / `CORNER_COS = 30°` (`:44-48`) preserve corners.
- RDP deviation includes the **rendered half-width channel** (`:134-160`), preserving pressure/speed spikes.
- `keepHead` (`:193`) protects the calligraphy head; `capGaps` (`:201`) bounds kept gaps.

**Decision:** no change without a measured pen-up shape change.

### 3.5 Incremental runs / wet cache — overlaps correctly

- `ItemMesher.meshRun` (`ItemMesher.kt:194`) and `StrokeTessellator.tessellate(from, count)` mesh settled runs; consecutive runs overlap by one point.
- `WetInkCache` (`WetInkCache.kt:65`) bakes only settled points (`MIN_BAKE_POINTS = 48`, `:182`), one point back, so the baked run and live tail share a disc.
- New tests:
  - `aRunCoversItsOwnSpanAndStaysInBounds`
  - `overlappingRunsLeaveNoGap`

**Decision:** no change.

---

## 4. Changes made in Phase 2

### 4.1 `app/src/test/java/com/xnotes/core/infinite/StrokeTessellatorTest.kt`

Added a manual `StrokeGeometry` helper and four structural tests:

1. **`aSwallowedCapsuleIsDrawnAsTheLargerDisc`** — a middle disc containing both neighbours must be drawn whole; the smaller discs must not punch holes.
2. **`aRunCoversItsOwnSpanAndStaysInBounds`** — an incremental run (`from=3, count=4`) covers its samples/midpoints, keeps rounded end caps, and stays inside the full mesh bounds.
3. **`overlappingRunsLeaveNoGap`** — two runs overlapping by one point cover the seam with no gap.
4. **`aWidthReversalAtATurnStaysCovered`** — width growth/shrink around a sharp turn stays watertight.

These are **geometry invariant** tests (coverage, bounds, indices), not screenshot tests. They close the two real gaps in the existing suite (capsule swallowing and incremental runs) without changing production code.

### 4.2 `docs/phase-2-stroke-quality-audit.md`

The starting audit for this phase: inspected areas, findings against the plan, the four new tests, verification results, device-dependent questions, and the recommended Phase 2 order.

### 4.3 This report

`docs/phase-2-implementations.md` — the full record of what was done and, importantly, what was deliberately **not** changed and why.

---

## 5. What was deliberately NOT changed

| Area | Why no change |
|---|---|
| Freehand spline / Catmull-Rom | The centreline is already arc-length low-passed; a spline would change handwriting geometry and add cost without device evidence. |
| `StrokeTessellator` `backL` fan / capsule logic | Existing sharp-corner watertight test and the new swallowed-capsule/width-reversal tests pass; no defect demonstrated. |
| `StrokeSimplify` tolerance/guards | Already covers ends, corners, gaps, width spikes, short strokes, and head protection; tests pass. |
| `FrontInk` handover | Already has generation guards, `awaitingPublish`, capture timeout, and `standDown`; no code-level race proven. |
| `WetInkCache` / `ItemMesher` run sizes | Run overlap and baking are correct; new run tests pass. |
| Phase 1 F1 (palm/second pointer) | Belongs to the Phase 1 input-priority issue (High, still open from the audit); Phase 2 is stroke geometry. |

The phase intentionally does not make a visual-quality claim from compilation or unit tests. Those only prove structure.

---

## 6. Verification

Commands (Windows toolchain, offline):

```bat
cmd.exe /c "gradlew.bat :app:testDebugUnitTest --tests com.xnotes.core.infinite.StrokeTessellatorTest --offline --console=plain"
cmd.exe /c "gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain"
```

Results:

| Check | Result |
|---|---|
| Focused `StrokeTessellatorTest` | **BUILD SUCCESSFUL** |
| Full `:app:testDebugUnitTest` | **BUILD SUCCESSFUL** |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** |
| New tests | 4, all passing |
| Production code changes | **none** |
| Pre-existing warning | `InteractionController.kt:1419` redundant `else` — untouched |

---

## 7. Device-dependent questions still open

1. Does the arc-length low-pass read as smooth on the target at 100%, high zoom, and low zoom?
2. Is there a visible seam at the pad→Skia handover, consecutive strokes, or a crossing confirmation?
3. Do translucent strokes (highlighter/neon) overdraw at joins on the infinite canvas?
4. Do 333–500 Hz fast strokes lose samples before simplification?
5. Does the handover ever drop a segment under load (large PDF, cold cache)?
6. Does the wet cache/run path keep up at high zoom on a 2 GB panel?

These require device captures/instrumentation; code inspection cannot answer them.

---

## 8. Recommended next Phase 2 steps

1. **Capture device evidence:** pad vs Skia ink at the handover, fast strokes, zoom levels, translucent joins, crossing confirmation, and a 2 GB load run.
2. **If faceting is visible:** evaluate a local, incremental curve refinement (not a whole-stroke Catmull-Rom) and measure per-event cost; do not change geometry without that evidence.
3. **If the handover blinks/drops:** fix from the captured frame sequence, not from assumptions.
4. Keep `StrokeSimplify` unless a pen-up shape change is measured.
5. Re-run the full suite and the new structural tests after any change.

---

## 9. Git state

- Branch: `phase-2-stroke-quality`
- Base: `a5213af5` (`phase-1-input-latency`)
- Phase 2 commit: `96748d31` (audit + 4 tests)
- This report: added in the follow-up commit on the same branch.
- No runtime behaviour changes in Phase 2; only tests and documentation.
- Phase 1 F1 remains open (High) and is not fixed by Phase 2.

---

## 10. Change inventory (Phase 2)

| File | Change |
|---|---|
| `app/src/test/java/com/xnotes/core/infinite/StrokeTessellatorTest.kt` | Added `manual(...)` helper and 4 structural tests. |
| `docs/phase-2-stroke-quality-audit.md` | Starting audit, findings, device questions, next steps. |
| `docs/phase-2-implementations.md` | This full report. |

---

## 11. Final status

**Phase 2 has started and established a verified baseline.** The code-level assumptions in the reference plan did not survive inspection: the freehand path is already smoothed, the tessellator/simplifier are already guarded and tested, and the handover already has the defensive machinery the plan asks for. The phase added the two missing structural tests and a full audit rather than changing ink geometry speculatively.

Visual stroke quality and the front-buffer handover remain **device-verified** work. The next Phase 2 action is to collect those captures, then make the smallest change the evidence supports.
