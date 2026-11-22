# Phase 0 — Project Audit & Baseline Performance Measurement

**Project:** `/mnt/d/xnotes/v1/xnotes-android` (repo `Abdo-Badawi/xnotes-android`; Android is built on the Windows side)
**Branch:** `phase-0-performance-audit` (created from `feature/input-customization` per Rule 8)
**Date context:** Phase 0 of `writing&performance_edits.txt`
**Scope:** Audit only. **No source files were modified.** One report file added.
**Method:** Static inspection of Gradle/AGP/Kotlin/native configuration, merged-manifest verification, dependency AAR manifest inspection, and source review. Runtime measurement was attempted but is blocked (no device attached).

---

## 0. Phase 0 Status at a Glance

| Deliverable | Status |
|---|---|
| Android compatibility audit | **DONE (static)** — with a blocking finding |
| Android 6 (API 23) verdict | **NOT COMPATIBLE today** (`minSdk = 26`) |
| List of deps/APIs preventing API 23 | **DONE** |
| Confirmed bottleneck list | **DONE (static; device confirmation pending)** |
| Baseline memory/CPU/rendering/input measurements | **BLOCKED — no device attached** |
| Required test devices | **UNAVAILABLE — documented** |
| Code changes | **NONE** (report only) |
| Build verification | Manifest merge succeeded offline (`:app:processDebugMainManifest`) |

> The only Phase 0 criterion not satisfiable in this environment is runtime baseline measurement. The exact device protocol is in §4 so it can be run verbatim once hardware is connected.

---

## 1. Build & Toolchain Matrix (verified)

| Item | Value | Source |
|---|---|---|
| `applicationId` | `com.xnotes` | `app/build.gradle.kts:25` |
| `compileSdk` | **36** | `app/build.gradle.kts:20` |
| `minSdk` | **26** | `app/build.gradle.kts:26` |
| `targetSdk` | **36** | `app/build.gradle.kts:27` |
| `versionCode` / `versionName` | 70 / `0.9.9` | `app/build.gradle.kts:28-29` |
| AGP | **8.13.2** | `gradle/libs.versions.toml:2` |
| Kotlin | **2.2.21** | `gradle/libs.versions.toml:3` |
| Gradle wrapper | **8.13** | `gradle/wrapper/gradle-wrapper.properties:3` |
| Java source/target | 17 | `app/build.gradle.kts:92-93,108` |
| NDK | **27.0.12077973** | `app/build.gradle.kts:22` |
| CMake | 3.22.1 | `app/build.gradle.kts:43` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` | `app/build.gradle.kts:31` |
| Packaging | `useLegacyPackaging = true` (compressed JNI libs) | `app/build.gradle.kts:47-53` |
| `largeHeap` | `true` | `AndroidManifest.xml:31` |
| GLES feature | 3.0, `required=false` | `AndroidManifest.xml:6` |
| Native link | `-Wl,-z,max-page-size=16384` (16 KB pages) | `app/src/main/cpp/CMakeLists.txt:9` |
| PDFium revision | `8ca5b735df4263f43c830479b78213854a392c22` | `app/src/main/cpp/pdfium.cmake:9` |

**Verified merged manifest** (`app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml`):
`android:minSdkVersion="26"`, `android:targetSdkVersion="36"`.

> Note: the Android build succeeds offline on the Windows toolchain (Gradle 8.13 + JDK 21). The WSL shell has no Java, so all Gradle actions were run via `cmd.exe /c gradlew.bat …`. `local.properties` points at the Windows SDK (`C:\Users\abdob\AppData\Local\Android\Sdk`).

---

## 2. Android 6.0 (API 23) Compatibility Audit

### 2.1 Dependency floor (authoritative, read from AAR `AndroidManifest.xml`)

| Dependency | Resolved version | minSdk |
|---|---|---|
| `androidx.compose.ui:ui-android` | 1.11.2 | **23** |
| `androidx.compose.foundation:foundation-android` | 1.11.2 | **23** |
| `androidx.compose.runtime:runtime-android` | 1.11.2 | **23** |
| `androidx.compose.ui:ui-graphics-android` | 1.11.2 | **23** |
| `androidx.compose.ui:ui-tooling-preview-android` | 1.11.2 | **23** |
| `androidx.compose.material3:material3-android` | 1.4.0 | 21 |
| `androidx.core:core` | 1.16.0 | 21 |
| `androidx.activity:activity` / `activity-compose` | 1.10.1 | 21 |
| `androidx.lifecycle:*` | 2.9.4 | 21 |
| `com.tom-roush:pdfbox-android` | 2.0.27.0 | 14 |
| `com.caverock:androidsvg-aar` | 1.4 | 8 |
| `io.github.huarangmeng:latex-*` | 1.5.0-kt2.1.0 | jar (app minSdk) |
| `org.commonmark:*` | 0.30.1 | jar (app minSdk) |

**Important:** the dependency floor is **API 23** (Compose), not 26. The app's `minSdk = 26` is a project choice, not forced by a dependency.

### 2.2 App code that forces API 26

| API / symbol | Required API | Where | Blocks API 23? |
|---|---|---|---|
| `java.time.*` (`LocalDate`, `Instant`, `LocalDateTime`, `YearMonth`, `WeekFields`, `DateTimeFormatter`) | **26** | `EditorActivity.kt:1117`; `format/CanvasCodec.kt:140,600,663`; `format/DocumentCodec.kt:306,990,1016`; `format/MarkupsXfdf.kt:13-16,148,254,262`; `ui/Backstage.kt:136-138,1329,1455`; `ui/Editor.kt:6064,6117,8021-8022,8066-8072` | **YES** unless core-library desugaring is enabled |
| `android.view.PixelCopy` | **24** | `canvas/FrontInk.kt:7,387,390`; `ui/EyedropperOverlay.kt:8,200-211`; `ui/InfiniteEditor.kt:1966-1969` | **YES** on API 23 (class absent) |
| `java.util.function.Function` / `Supplier` | 24 | `vendor/materialcolor/dynamiccolor/*` | Covered by desugaring; otherwise YES |

### 2.3 APIs already guarded (verify on device at API 23/24)

| API | Required | Guard |
|---|---|---|
| `requestUnbufferedDispatch` | 30 | `CanvasView.kt:864` (`SDK_INT < 30 return`); `InfiniteCanvasView.kt:255` |
| `ViewTreeObserver.registerFrameCommitCallback` | 29 | `CanvasView.kt:848` (`SDK_INT >= 29`) |
| `SurfaceControl` / `Transaction` | 29 | `GlWetPad.kt:207` (`<31`), `:406` (`<29`), `:593` (`<31`) |
| `windowSplashScreenBackground/AnimatedIcon` | 31 | `values*/themes.xml` (`tools:targetApi="31"`) |
| `foregroundServiceType` | 29 | manifest attribute; ignored below 29 |
| `POST_NOTIFICATIONS` / `FOREGROUND_SERVICE_MICROPHONE` | 33/34 | unknown permissions ignored below |

### 2.4 Native / PDFium / ABI

- NDK 27 supports `__ANDROID_API__` down to 21; the PDFium build defines `__ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__` (`pdfium.cmake:33` etc.), which is the correct pattern for older runtime.
- `armeabi-v7a` is included, so 32-bit API 23 interactive displays are buildable.
- `x86` is **not** included (only `x86_64`), so a 32-bit x86 emulator cannot run the app.
- `useLegacyPackaging = true` means JNI libs are compressed and extracted on install; on low-end devices this increases install size/time but reduces APK size. Monitor startup.
- GLES 3.0 is optional in the manifest; the paged editor uses Skia, and the wet pad refuses to start when `EGL_KHR_mutable_render_buffer` is unavailable (`GlWetPad.kt:524 ready = single`).

### 2.5 Verdict

**The application cannot run on Android 6.0 (API 23) today.** `minSdk = 26` prevents installation, and even after lowering it:

1. `java.time` usage requires **core library desugaring** (`isCoreLibraryDesugaringEnabled = true` + `desugar_jdk_libs`) or replacement.
2. `PixelCopy` calls must be **guarded for API 23** with a fallback (the front-buffer handover and eyedropper depend on it).
3. `java.util.function` in vendored Material color code needs the same desugaring.
4. `SurfaceControl`/`requestUnbufferedDispatch` paths are guarded but should be smoke-tested on API 23 for verifier/lazy-resolution issues.

**Phase 0 makes no change.** These are recorded as the API-23 compatibility work items for a later phase.

---

## 3. Confirmed Bottlenecks (static evidence)

### 3.1 Input latency

| Finding | Evidence | Confidence |
|---|---|---|
| 90 ms classification buffer holds DOWN/MOVE/UP for single-pointer `TOOL_TYPE_FINGER`; replay only on UP/timeout | `EditorActivity.kt:120, 281-286, 300-352` | CONFIRMED |
| Buffer blocks the stroke controller entirely while active | `EditorActivity.kt:281-286` returns before `dispatchClassified` | CONFIRMED |
| `hasMultiPointerSignature` forces the full window even without UP | `EditorActivity.kt:334` | CONFIRMED |
| Flush runs on the main thread; jank extends the 90 ms | `EditorActivity.kt:256, 338-352` | HIGH |
| Unbuffered dispatch is requested only while the wet pad is live | `CanvasView.kt:863-869`; `FrontInk.kt:249,314,363` | CONFIRMED |
| Per-event `PointerProperties`/`PointerCoords` allocation | `InputTouchRewriter.kt:75-76` | CONFIRMED |
| Canvas suppresses live stroke when `frontInk.live == true` | `InteractionController.kt:4746-4747` | CONFIRMED |

### 3.2 Stroke quality

| Finding | Evidence | Confidence |
|---|---|---|
| Decimation gate `(1.0/zoom).coerceAtMost(1.0)` content px | `InteractionController.kt:5095` | CONFIRMED (present) |
| RDP simplify at commit, `SIMPLIFY_EPS = 0.2` | `InteractionController.kt:1639-1640, 5101`; `core/stroke/StrokeSimplify.kt` | CONFIRMED (present) |
| Tessellator has `backL` join anchor and `Geometry.SWALLOWED` capsule handling | `StrokeTessellator.kt:148,159,209-259` | CONFIRMED (present; correctness to be measured in Phase 2) |
| Wet pad qualifies only `InkPass.OPAQUE`, `rotationDeg == 0`, `pad.ready` | `FrontInk.kt:203-226`; `GlWetPad.kt:524` | CONFIRMED |
| Two rasterizers (GL pad live vs Skia committed) meet at handover | `GlWetPadInk.kt` vs `CanvasState.paintLiveStroke:1668` | HIGH |
| Empty-damage present draws nothing while canvas suppresses live ink | `GlWetPadInk.kt:260-300` | MEDIUM |

### 3.3 PDF loading / rendering

| Finding | Evidence | Confidence |
|---|---|---|
| Full embedded STORED PDF copied to temp on open | `DocumentCodec.kt:784-790` | CONFIRMED |
| Autosave avoids re-copying via tail splice | `Editor.kt:5204, 5247-5290` | CONFIRMED (good) |
| `PdfSource.render` never passes `CancelToken` | `PdfSource.kt:168-180`; `PdfiumDocument.kt:141-151` | CONFIRMED |
| Single PDFium thread, priority queue | `PdfiumThread.kt` | CONFIRMED |
| Native page cache = 4 | `pdf_jni.cpp:31,111-129` | CONFIRMED |
| Single cache executor for bg + ink builds | `CanvasView.kt:369` | CONFIRMED |
| Per-frame prefetch/prune | `CanvasView.kt:590`; `CanvasState.kt:1764,1825` | CONFIRMED |
| Thumbnails re-read whole note/PDF per tile | `Editor.kt:4054,4125`; `FilePreview.kt:131` | MEDIUM |

### 3.4 Memory / threads

| Finding | Evidence | Confidence |
|---|---|---|
| Page caches up to `maxCachePx = 2048` (≈16 MB per bg and ink layer) | `CanvasState.kt:140` | CONFIRMED |
| Eviction drops map entries without explicit Bitmap recycling | `CanvasState.kt:1764` | HIGH |
| Main thread: input, buffer flush, PixelCopy, cache callbacks | `EditorActivity.kt`; `CanvasState.kt:1410-1444`; `FrontInk.kt:365-395` | HIGH |
| PDFium thread: all open/render/text/link work | `PdfiumThread.kt` | CONFIRMED |

---

## 4. Baseline Performance Measurement

### 4.1 Why it is blocked

- `adb devices` (Windows `platform-tools/adb.exe`) returns **no attached devices**.
- The WSL shell has no JDK; Gradle was run through the Windows toolchain. That is fine for builds, but **runtime metrics require the physical interactive display**.
- Per the plan’s own Phase 0 wording, measurements must come from representative hardware; none is connected.

### 4.2 Ready-to-run measurement protocol (once a device is attached)

Enable the debug build and capture each run in a fixed scenario (see §5):

**Memory**
```bash
adb shell dumpsys meminfo com.xnotes.debug
adb shell dumpsys meminfo com.xnotes.debug -d   # per-category detail
adb shell am send-trim-memory com.xnotes.debug RUNNING_CRITICAL
```
Record: Java heap, Native heap, Graphics, total PSS; peak during open, zoom, and a 60 s writing burst.

**CPU / rendering**
```bash
adb shell dumpsys gfxinfo com.xnotes.debug reset
# … run scenario …
adb shell dumpsys gfxinfo com.xnotes.debug
adb shell dumpsys SurfaceFlinger --latency <layer>
```
Record: frame time p50/p90/p99, janky frames, missed vsyncs, background render activity.

**Perfetto trace (input → ink, PDF work)**
```bash
adb shell perfetto -o /data/misc/perfetto-traces/p0.perfetto-trace -t 20s \
  sched freq idle am wm gfx view binder_driver hal dalvik camera input res
adb pull /data/misc/perfetto-traces/p0.perfetto-trace
```
Analyze: input dispatch → `InteractionController.onTouch` → first pad/Skia present; PDFium thread occupancy; cache executor occupancy; GC.

**GC**
```bash
adb logcat -s art:* | grep -i "gc\|pause"
```
Record GC count/duration during a 60 s continuous writing burst.

**PDF open / first page**
```bash
adb logcat -s xnotes.save:* xnotes.pdf:*
```
The code already logs `open read … ms = inflate + parse + assets + compact` (`Editor.kt:3750-3756`) and has HUD timings (`PdfiumDocument.lastOpenMs`, text/render timings). Capture for a 400-page PDF.

### 4.3 Lightweight instrumentation to add in a later phase (not now)

- Buffer: enqueue→flush elapsed; samples buffered; `waitForMorePointers` outcome.
- Stroke: events received vs samples appended vs decimated; tip updates.
- Pad: `GlWetPad.hud` / `FrontInk.hud` (already implemented) surfaced in the debug overlay.
- PDF: queue depth and per-job wait time by `PdfPriority`; cancelled-job count (after Phase 3).
- GC: `Runtime` counters sampled per stroke (cheap, optional).

> All instrumentation must be debug-only and allocation-free on the hot path.

---

## 5. Required Test Device Matrix (status)

| Device class | Requirement | Status |
|---|---|---|
| Android 6.0 (API 23), ~2 GB RAM interactive display | Primary compatibility target | **Unavailable** |
| Mid-range interactive display | Typical target | **Unavailable** |
| Modern high-performance interactive display | Upper target | **Unavailable** |

Because none is connected, Phase 0 cannot produce baseline numbers or true API-23 behavior. The static compatibility verdict in §2 stands on its own.

### 5.1 Scenario matrix to run per device (from the plan)

| # | Input Customization | Document |
|---|---|---|
| 1 | Enabled | Blank note |
| 2 | Enabled | Small PDF |
| 3 | Enabled | 400-page PDF |
| 4 | Enabled | 400-page PDF + zoom |
| 5 | Enabled | 400-page PDF + filters |
| 6 | Enabled | PDF during background rendering |
| 7 | Enabled | Multi-pointer gestures |
| 8 | Enabled | Palm erasing |
| 9 | Enabled | All stroke types |

Plus: true `TOOL_TYPE_STYLUS` vs `TOOL_TYPE_FINGER` comparison (never disabling customization).

---

## 6. Phase 0 Acceptance Checklist

- [x] Inspect actual project architecture and toolchain.
- [x] Verify Android 6 compatibility **statically** → **blocked by `minSdk 26`, `java.time`, `PixelCopy`**.
- [x] Inspect dependencies and native libraries (minSdk floor = 23 from Compose).
- [x] Identify confirmed static bottlenecks with file/line evidence.
- [ ] Baseline memory/CPU/rendering/input measurements → **blocked: no device**.
- [x] No code changes (report only).
- [x] Phase branch created (`phase-0-performance-audit`).

---

## 7. Risks & Unknowns

1. **API 23 desugaring** may surface additional `java.time`/`java.util.function` edges at build time; must be validated with an actual `minSdk 23` build.
2. **`PixelCopy` fallback** for API 23 is non-trivial: the front-buffer handover relies on it. A `View.draw(Canvas)` fallback is possible but slower and may change handover timing; must not introduce ghost/lost ink.
3. **PDFium at API 23**: weak-symbol strategy is present, but the exact PDFium revision’s runtime calls must be smoke-tested on API 23.
4. **Low-RAM behavior of Compose 1.11** and `largeHeap=true` on 2 GB devices is unmeasured.
5. Device-side behavior of the target panel’s tool type (`FINGER` vs `STYLUS`) and contact size remains unknown; it decides whether the 90 ms buffer engages.

---

## 8. Recommended Next Step

1. Connect the target interactive display(s) and run §4/§5 to capture the baseline. This is the only missing Phase 0 deliverable.
2. In parallel (no code), confirm the target panel’s `getToolType`, `touchMinor`/`touchMajor`, and whether `EGL_KHR_mutable_render_buffer` is granted (debug HUD shows `single`/`back`).
3. Only then begin Phase 1 on branch `phase-1-input-latency`, preserving every Input Customization behavior per Rule 1.

**No source files were changed in Phase 0.**
