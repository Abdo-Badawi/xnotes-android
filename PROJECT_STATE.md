# PROJECT_STATE — handoff for a new session

**Repo:** `/mnt/d/xnotes/v1/xnotes-android` (Android build runs from the Windows toolchain)
**Remote:** `mine` = `https://github.com/Abdo-Badawi/xnotes-android.git` (fork); `origin` = upstream `shardulvs/xnotes-android`
**Package:** `com.xnotes` (Pixora), version 0.9.9
**Reference plan:** `writing&performance_edits.txt` (untracked working copy; keep it)

---

## Current position

| Item | Value |
|---|---|
| Active branch | `phase-2-stroke-quality` |
| Branch base | `phase-1-input-latency` @ `a5213af5` |
| Phase 2 commits | `96748d31` (audit + 4 tests), `9cb7348b` (full report) |
| This handoff | added on `phase-2-stroke-quality` |
| Next phase | **Phase 3 — PDF Rendering Performance** (do not start until Phase 2 is reviewed) |

### Branches / commits

| Phase | Branch | Commits | State |
|---|---|---|---|
| 0 — Audit & baseline | `phase-0-performance-audit` | `e47791c4` | Complete; report only, no source changes |
| 1 — Input latency | `phase-1-input-latency` | `b80f6f7b`, `a5213af5` | Implemented; **F1 High open** |
| 2 — Stroke quality | `phase-2-stroke-quality` | `96748d31`, `9cb7348b` | Started: audit + 4 tests; **no runtime behaviour changes** |

---

## What Phase 0 did

- Static compatibility/performance audit; no source changes.
- **`minSdk = 26`**, target/compile 36, AGP 8.13.2, Kotlin 2.2.21, Gradle 8.13, NDK 27, ABIs `arm64-v8a/armeabi-v7a/x86_64`.
- Dependency floor is **API 23** (Compose 1.11.2), so API 26 is a project choice. API 23 would need core-library desugaring for `java.time`, a `PixelCopy` fallback, and `java.util.function` desugaring.
- Confirmed bottlenecks: 90 ms blocking input buffer, pad-gated unbuffered dispatch, uncancellable PDF renders, `kPageCache=4`, single PDFium/cache threads, GC-only page-cache eviction.
- Report: `docs/phase-0-audit.md`.

## What Phase 1 did

- Immediate provisional ink for ambiguous single-finger input; 90 ms window is now a **replay window**, not a render delay.
- Resolves to WRITE / ALT-WRITE / PAN / ERASE / multi; rolls back and replays from the original DOWN.
- Added `strokePinchable` so an unmatched provisional finger yields to a pinch (pre-Phase-1 finger-draw rule); matched WRITE/ALT/stylus still ignores a second pointer; recorded Pan keeps precedence.
- Page crossings are deferred, then replayed as a normal Write so `handOverStroke` preserves every point; Pan/Erase rollback files nothing.
- Added synthetic `ACTION_CANCEL` before replay so Compose/AndroidView reset.
- Tests: `CrossPageStrokeTest` (crossing detection + gap).
- Reports: `docs/phase-1-input-latency.md`, `docs/phase0,1-implementations.md`.
- **F1 remains open (High):** a palm/second pointer arriving **before** a provisional pen reaches its WRITE signature can pinch and discard the handwriting stroke. See “Open issues”.

## What Phase 2 did

- Verified the reference plan’s assumptions against the code and existing tests.
- Found freehand ink is already arc-length smoothed (`StrokeEngine.emaByArc`); `CubicFit` is only shape recognition. Tessellator joins/capsules, `FrontInk` handover, and `StrokeSimplify` guards are already implemented and tested. No code-level defect demonstrated.
- Added 4 structural tests to `StrokeTessellatorTest`: swallowed capsule, run span/caps, overlapping-run seam, width reversal at a turn.
- **No runtime ink behaviour changed.**
- Reports: `docs/phase-2-stroke-quality-audit.md`, `docs/phase-2-implementations.md`.

---

## Open issues / decisions

1. **Phase 1 F1 (High, unresolved):** `EditorActivity.beginProvisional`/`handleProvisional` + `InteractionController.handlePointerDown` (`strokePinchable`) let a second pointer pinch before the 1-finger WRITE match. Pre-Phase-1 could classify the pen WRITE at flush and ignore the palm. Needs a fix (prefer a pending WRITE/ALT or a grace before pinch) or an explicit product decision. Device test on the target panel required.
2. **Phase 2 is baseline only:** next step is device evidence (pad vs Skia handover, fast strokes, zoom, translucent joins, crossing confirmation, 2 GB load). Do not change stroke geometry from assumptions.
3. **Phase 0 compatibility:** API 23 is not supported (`minSdk 26`); desugaring + PixelCopy fallback are still pending if Android 6 is required.
4. Target interactive display was **not** available for baseline measurements; `adb` is available and a Realme RMX3844 phone was used to install/test debug builds.

---

## Key files to read first

- `writing&performance_edits.txt` — the phase plan.
- `docs/phase-0-audit.md`
- `docs/phase-1-input-latency.md` — includes the audit and follow-up.
- `docs/phase-2-stroke-quality-audit.md`
- `docs/phase-2-implementations.md`

## Build / test

```bat
cmd.exe /c "gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain"
```

Last result: **BUILD SUCCESSFUL**; all unit tests pass; debug APK assembles. One pre-existing warning at `InteractionController.kt` (redundant `else`).

Install/watch on a connected device:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s xnotes.input:D
```

---

## Hard constraints (keep)

- Do **not** remove, disable, or simplify Input Customization, gestures, palm erasing, signatures, GL Wet Pad, canvas fallback, or historical-sample replay.
- Do **not** require `TOOL_TYPE_STYLUS` for the target; the target reports the pen as `TOOL_TYPE_FINGER`.
- Preserve stroke quality and handwriting geometry; no speculative smoothing/spline changes.
- Android 6 is a product target but not yet supported (`minSdk 26`); no new APIs that block it.
- Work one phase at a time, test, and keep changes reviewable.

## Suggested bootstrap for a new session

> Read `PROJECT_STATE.md`, `writing&performance_edits.txt`, and `docs/phase-2-implementations.md`. Continue from branch `phase-2-stroke-quality`. Phase 1 F1 is still open; do not start Phase 3 until Phase 2 is reviewed.
