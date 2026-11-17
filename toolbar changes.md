# Toolbar Changes

## Summary

The paged (PDF/note) floating toolbar is now a real three-state floating toolbar:

1. **Collapsed** — one small draggable circular button showing the armed tool's icon.
2. **Compact** — a small floating row with page navigation, page index, Move, Pen, the main
   colours, Eraser, Expand, Collapse, and a drag grip.
3. **Expanded** — the complete existing customisable toolbar, still floating, with a Collapse
   button back to Compact.

The existing toolbar was reused. No second toolbar was built and no tool-selection or drawing
logic was duplicated.

The three-state mode is active when the existing toolbar style is **Floating**
(Preferences → Toolbar → Style → Floating). Docked mode and the infinite canvas are unchanged.

---

## Existing architecture (before the change)

- `app/src/main/java/com/xnotes/ui/Toolbar.kt`
  - Builds the paged toolbar from the user-customisable `ToolbarLayout`.
  - `ToolbarItemView` renders each item (`ToolButton`, page navigation, swatches, menus, etc.).
  - `ToolbarFrame` wraps the content, handles scrolling, look metrics and the floating pill.
- `app/src/main/java/com/xnotes/ui/ToolbarFrame.kt`
  - `ToolbarAround` places the bar docked along an edge or floating over the content.
  - `LocalBar` provides button/icon/swatch metrics.
  - `LocalToolbarCover` tells the page/canvas how much edge space the bar occupies.
- `app/src/main/java/com/xnotes/EditorActivity.kt`
  - `EditorPane` chooses the paged bar or the infinite-canvas bar and passes it to `ToolbarAround`.
  - The resulting `floatingBar()` is drawn over the native PDF/note view.
- `app/src/main/java/com/xnotes/ui/Editor.kt`
  - Owns per-pane UI state, the active tool, colours, current page, and settings persistence.

The implementation extends these pieces instead of replacing them.

---

## New state model

`app/src/main/java/com/xnotes/settings/ToolbarLook.kt`

```kotlin
enum class ToolbarState { COLLAPSED, COMPACT, EXPANDED }
```

`ToolbarLook` gained:

```kotlin
val state: ToolbarState = ToolbarState.COLLAPSED
val anchorX: Float = 0.5f
val anchorY: Float = 0.9f
```

- `state` is persisted so the bar reopens in the last used shape.
- `anchorX` / `anchorY` are fractions of the bar's free space, so the position survives rotation,
  window resizing, split-screen and different screen densities.

Defaults keep the bar collapsed on first use.

---

## Persistence

`app/src/main/java/com/xnotes/settings/Preferences.kt`

Read/write of the new fields:

- `toolbar_state`
- `toolbar_anchor_x`
- `toolbar_anchor_y`

Values are clamped to `0f..1f` on load. Missing/invalid keys fall back to the defaults, so old
settings files still load.

`app/src/main/java/com/xnotes/ui/Editor.kt`

Added:

```kotlin
fun applyToolbarLook(look: ToolbarLook)
```

It updates the live toolbar state and writes `settings.prefs.toolbarLook` through the existing
`saveSettingsSoon()` mechanism. A drag only persists when it ends, not on every pixel, so dragging
does not flood the settings store.

---

## The three-state host

New file: `app/src/main/java/com/xnotes/ui/ThreeStateToolbar.kt`

### `ThreeStateToolbarHost`

- Measures its available area and the current bar size.
- Converts the stored anchor fractions into an on-screen offset.
- Computes free space as `area - bar size - edge gap`.
- Clamps the position so the bar can never leave the screen.
- Renders the current state through `AnimatedContent`.
- Owns the drag callbacks and persists the anchor on drag end.

Position maths:

```text
free = area - barSize - 2 * gap
x    = gap + anchorX * free.width
y    = gap + anchorY * free.height
```

Dragging updates the anchor:

```text
anchorX = (anchorX + deltaX / free.width).coerceIn(0, 1)
anchorY = (anchorY + deltaY / free.height).coerceIn(0, 1)
```

Because the anchor is a fraction of the *free* area, a bar near the bottom-right stays near the
bottom-right after rotation or a size change instead of going off-screen.

### Collapsed state

`CollapsedToolbarIcon`:

- A circular floating button using the existing panel/border palette colours.
- Shows the icon of the currently armed tool (pen, eraser, pan, etc.).
- Tap → Compact.
- Drag → moves the button.
- Clamped to the visible area by the host.

Tap and drag are separated by gesture handling: the drag detector only consumes movement after
touch slop, so a clean tap still opens the bar.

### Compact state

The compact row is not hardcoded any more. It is driven by its own `ToolbarLayout`, so the user
edits it in Preferences exactly as they edit the full bar, and the same `Toolbar` composable draws
it:

- `ThreeStateToolbarHost` calls the shared `Toolbar` with `editor.compactToolbarLayout`.
- Its default (`ToolbarLayout.COMPACT_DEFAULT`) is the old row: page navigation, Move, Pen, the
  colour swatches and Eraser.
- Any paged-bar item can be added, hidden, reordered or grouped into sections; separators follow
  the sections exactly as in the full bar.
- The host appends the trailing controls through composition locals: the drag grip, an Expand
  button (`toolbar_expand` → Expanded) and a Collapse button (`toolbar_compact` → Collapsed).
- The full bar's trailing area is unchanged; it only gains the Expand button while the short row
  is showing (`LocalToolbarExpand` is null elsewhere).

The compact row is deliberately small by default, but it is now the user's small row, not a
hidden copy of the full toolbar.

### Expanded state

- Calls the existing `Toolbar` composable unchanged.
- Therefore it keeps the entire customisable layout: undo/redo, zoom, fit, styles, margins, view
  menu, sidebar, fullscreen, all tools, all swatches, popups and actions.
- It is still a floating pill, not a docked strip.
- The host adds a drag grip and a Collapse button that returns to Compact.
- Closing a split pane still uses the existing pinned close-pane button.

---

## Reuse instead of duplication

### `app/src/main/java/com/xnotes/ui/Toolbar.kt`

- Extracted the tool icon map into:

```kotlin
@Composable
internal fun toolbarToolIcons(): Map<Tool, ImageVector>
```

  Both the expanded bar and the compact row use exactly the same icons, including the designed
  stroke drawables.

- Made `ToolButton` and `PageCounter` `internal` so the compact row can reuse them.
- Added drag/collapse support through two composition locals:

```kotlin
LocalToolbarDrag
LocalToolbarCollapse
```

  Both are `null` outside the three-state host, so docked bars, the infinite canvas and all
  existing call sites behave exactly as before.

- The expanded bar's trailing area now renders, when provided:
  1. drag grip,
  2. Collapse button,
  3. the existing close-pane button.

  Separators are only inserted when the next item exists.

### `app/src/main/java/com/xnotes/ui/ToolbarFrame.kt`

- `ToolbarAround` gained `keepClear: Boolean = true`.
- For the three-state bar it is called with `keepClear = false`, because the bar floats over the
  page instead of reserving a full edge. This avoids blank page margins that the old floating pill
  reserved.
- Docked mode and the infinite canvas keep the original behaviour.

### `app/src/main/java/com/xnotes/EditorActivity.kt`

In the paged pane:

- The existing `Toolbar` call is kept as `expandedBar`.
- When `editor.toolbarLook.floating` is true, the overlay becomes:

```kotlin
ThreeStateToolbarHost(editor, expanded = expandedBar)
```

- When docked, `expandedBar` is used directly, exactly as before.
- The page toolbar cover is set to zero for three-state mode.
- `ToolbarAround` receives `keepClear = !threeState`.

---

## Interaction details

- **Tool taps vs dragging**
  - Tool buttons never start a drag.
  - Dragging is only possible from the collapsed icon or the dedicated grip handle.
  - This prevents accidental tool selection or accidental movement while tapping tools.
- **Touch pass-through**
  - The host is a full-size transparent `Box` used only for positioning.
  - It has no pointer-input modifier of its own, so touches outside the visible bar reach the PDF
    view below.
  - The bar is composed after the native PDF/note `AndroidView`, so it is drawn above the page.
  - Other overlays (selection menus, text bars, etc.) remain composed after the toolbar, so they
    keep their existing z-order.
- **Scrolling and zooming**
  - The host does not consume events outside the bar.
  - Existing PDF pan/zoom/stylus handling is untouched.
- **Screen edges**
  - The anchor is clamped, so the bar cannot be dragged outside the visible area.
- **Rotation / resizing**
  - Anchor fractions and fresh measurements on layout keep the bar on screen.
- **Orientation**
  - Compact and Expanded follow the existing toolbar Position setting:
    - Top / Bottom → horizontal.
    - Left / Right → vertical.
  - No extra orientation setting was added; the existing Position control is the option to change
    it.

---

## Animation

The three shapes are switched with `AnimatedContent`:

- Fade in + scale from 0.9.
- Fade out + scale to 0.96.
- 180 ms, with `SizeTransform(clip = false)` so the growing/shrinking bar is not clipped.

No abrupt visibility toggles are used.

---

## Files changed

### Modified

- `app/src/main/java/com/xnotes/EditorActivity.kt`
- `app/src/main/java/com/xnotes/settings/Preferences.kt`
- `app/src/main/java/com/xnotes/settings/ToolbarLook.kt`
- `app/src/main/java/com/xnotes/ui/Editor.kt`
- `app/src/main/java/com/xnotes/ui/Toolbar.kt`
- `app/src/main/java/com/xnotes/ui/ToolbarFrame.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/xnotes/settings/SettingsTest.kt`

### Created

- `app/src/main/java/com/xnotes/ui/ThreeStateToolbar.kt`

### New strings

- `toolbar_open`
- `toolbar_expand`
- `toolbar_compact`
- `toolbar_collapse`
- `toolbar_move`

---

## Tests

Added to `SettingsTest`:

- `floatingToolbarDefaultsToCollapsed`
- `floatingToolbarRoundTrip`
- `floatingToolbarAnchorIsClamped`

Results:

- `SettingsTest`: **70/70 passed**.
- Full unit-test suite: **2525 tests, 1 failure**.
  - Failure: `TemplateTest.theBundledAiGuideHoldsTheSpecAndExamplesThatRead`.
  - Cause: the test reads `app/src/main/assets/templates/AI.md` from the working tree, which is
    CRLF on this machine while the committed blob is LF. The test's exact `\n` markers do not match
    the CRLF file. This is a line-ending/environment issue unrelated to the toolbar changes.

---

## Build

Command:

```bash
gradlew.bat :app:assembleDebug
```

Result: **BUILD SUCCESSFUL**

Debug APK:

```text
D:\xnotes\v1\xnotes-android\app\build\outputs\apk\debug\app-debug.apk
```

WSL path:

```text
/mnt/d/xnotes/v1/xnotes-android/app/build/outputs/apk/debug/app-debug.apk
```

---

## How to use

1. Open a PDF or note.
2. Preferences → Toolbar → Style → **Floating**.
3. A small circular button appears over the page.
4. Tap it for the compact row.
5. Tap the three-dot button to expand the full toolbar.
6. Use the chevron buttons to go back to Compact or Collapsed.
7. Drag the circular button or the grip handle to reposition the bar.
8. Choose Top/Bottom for a horizontal bar or Left/Right for a vertical bar.

---

## Assumptions / limitations

- The three-state floating mode is tied to the existing **Floating** toolbar style. Docked mode is
  intentionally preserved for users who prefer it.
- The infinite canvas keeps its existing floating/docked behaviour; the three-state mode is applied
  to the paged PDF/note toolbar.
- No emulator or physical device was connected, so screenshots/video of the running states could
  not be captured.
- One unrelated unit test fails locally because of CRLF in `AI.md`, as described above.

---

## Short (compact) toolbar customisation

The compact row is now a first-class, user-editable bar rather than a fixed list.

### Layout and storage

- `ToolbarLayout.COMPACT_DEFAULT` holds the short row: `PAGE_NAV`, `PAN` + `PEN`, `COLORS` and
  `ERASER` visible, plus every other paged item present but **hidden**. The customiser lists them
  all, so any tool can be revealed without leaving a dead chip on the bar.
- `Settings.compactToolbarLayout` persists it under `compact_toolbar_layout`.
- `ToolbarLayout.fromRaw(..., appendedVisible = false)` is used for this layout:
  - an empty/missing key falls back to `COMPACT_DEFAULT`;
  - a saved short row keeps its own choices, and any missing item (for example from an older
    install or a newer app version) is appended hidden.
- `Editor.applyCompactToolbarLayout(layout)` applies and saves it live.

### Preferences

A new **Short toolbar** section sits directly under the main toolbar customiser. It reuses the
same editor (`ToolbarCustomizer` + `ToolbarDragGhost` + `SectionCardGhost`) and therefore the same
interactions:

- tap a chip to show/hide it;
- long-press a chip to drag it within or across sections;
- long-press a section's header to reorder it;
- add and delete sections;
- Reset restores `COMPACT_DEFAULT`.

The drag state is per editor (`ToolbarEditorState`), so a drag in the full-bar editor cannot aim at
a chip in the short-row editor. The floating ghost is still drawn once at the pane root from the
editor that is currently dragging.

### Rendering

`ThreeStateToolbarHost` now takes a single `bar: @Composable (ToolbarLayout) -> Unit` and passes
`editor.compactToolbarLayout` for COMPACT and `editor.toolbarLayout` for EXPANDED. Both states use
the same `Toolbar` composable, so every item renderer, popup and gesture is shared. The host adds:

- `LocalToolbarExpand` only in COMPACT (the "more" button that opens EXPANDED);
- `LocalToolbarCollapse` in both COMPACT (to COLLAPSED) and EXPANDED (to COMPACT);
- `LocalToolbarDrag` in both.

Docked and infinite-canvas bars are unchanged: the new locals are null there, and `Toolbar`'s
default `layout` argument remains `editor.toolbarLayout`.

### Tests

- `ToolbarLayoutTest.theCompactDefaultRoundTripsThroughRaw`
- `ToolbarLayoutTest.aShortRowStoredBeforeAnItemExistedGainsItHidden`
- `SettingsTest.compactToolbarLayoutDefaultsWhenAbsent`
- `SettingsTest.compactToolbarLayoutRoundTripsShort`

Both test classes pass.
