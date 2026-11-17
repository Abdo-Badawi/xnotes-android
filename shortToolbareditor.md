# Short (Compact) Toolbar Editor

## Summary

The floating paged toolbar has three states:

1. **Collapsed** — one small draggable circular button.
2. **Compact** — the short floating row (state 2).
3. **Expanded** — the full customisable floating bar (state 3).

Previously the compact row was hardcoded: page navigation, Move, Pen, three colours, Eraser, Expand,
Collapse and a drag grip. This change makes that row a real user-editable `ToolbarLayout`, edited in
Preferences with exactly the same customiser used for the full toolbar.

Nothing about docked toolbars, the infinite canvas, tool selection, drawing, page navigation, colour
handling or the expanded bar was replaced.

---

## What changed, at a glance

| Area | Change |
| --- | --- |
| Model | Added `ToolbarLayout.COMPACT_DEFAULT` (all paged items, extras hidden) and an `appendedVisible` option on `fromRaw`. |
| Settings | Added `Settings.compactToolbarLayout`, stored as `compact_toolbar_layout`. |
| Editor | Added `compactToolbarLayout` state and `applyCompactToolbarLayout(layout)`. |
| Rendering | `Toolbar` now accepts a `layout` parameter; the short row uses the same renderer as the full bar. |
| Host | `ThreeStateToolbarHost` now takes `bar: @Composable (ToolbarLayout) -> Unit` and supplies the right layout per state. |
| Controls | Added `LocalToolbarExpand`; `LocalToolbarCollapse` is now used by both Compact and Expanded. |
| Preferences | New **Short toolbar** section, reusing a factored-out toolbar customiser. |
| Strings | Added `pref_toolbar_short` and `pref_toolbar_short_help`. |
| Tests | Added layout + persistence tests for the short row. |
| Docs | Updated `toolbar changes.md` and added this file. |

---

## 1. Toolbar layout model

File: `app/src/main/java/com/xnotes/core/tools/ToolbarLayout.kt`

### New default

```kotlin
/**
 * The floating bar's short row. The five items that make a page readable are shown; every
 * other paged item is present but hidden, so the same customiser can reveal it.
 */
val COMPACT_DEFAULT: ToolbarLayout = run {
    val shown = listOf(
        listOf(ToolbarItem.PAGE_NAV),
        listOf(ToolbarItem.PAN, ToolbarItem.PEN),
        listOf(ToolbarItem.COLORS),
        listOf(ToolbarItem.ERASER),
    ).map { g -> ToolbarSection(g.map { ToolbarEntry(it, true) }) }
    val visibleItems = shown.flatMap { s -> s.entries }.map { it.item }.toSet()
    val extras = ToolbarItem.entries
        .filter { it in NOTE_ITEMS && it !in visibleItems }
        .map { ToolbarEntry(it, false) }
    ToolbarLayout(shown + ToolbarSection(extras))
}
```

Every paged item is therefore offered to the short-row customiser exactly once. Only the five
starting items are visible; all other tools sit in a dimmed/hidden section and appear on the bar
only when the user taps them. `PAGE_NAV` still expands to previous / page index / next, and
`COLORS` still expands to the colour swatches.

### Hidden-by-default load behaviour

`fromRaw` always appended missing items so an old stored bar grew with new app versions. For the
short row that is still wanted, but the additions must start hidden. A new parameter controls
their visibility:

```kotlin
fun fromRaw(
    rawSections: List<List<Pair<String, Boolean>>>,
    among: Set<ToolbarItem> = NOTE_ITEMS,
    fallback: ToolbarLayout = DEFAULT,
    appendMissing: Boolean = true,
    appendedVisible: Boolean = true,
): ToolbarLayout {
    if (rawSections.isEmpty()) return fallback
    ...
    if (sections.all { it.entries.isEmpty() }) return fallback
    val built = ToolbarLayout(sections)
    return if (appendMissing) built.withMissingItemsAppended(among, appendedVisible) else built
}
```

`withMissingItemsAppended` gained the same `visible` flag and passes it to `insertMissing`, so
items added by a migration can be hidden.

- Full paged bar: `appendedVisible = true` (unchanged behaviour).
- Canvas bar: `appendedVisible = true` (unchanged behaviour).
- Short row: `appendedVisible = false` — extras are added hidden, never shown automatically.
- Missing or empty stored value falls back to `COMPACT_DEFAULT` (all extras hidden).
- A short row stored before this change (only the five items) gains every missing item hidden on
  the next load.

---

## 2. Persistence

File: `app/src/main/java/com/xnotes/settings/Settings.kt`

Added the field:

```kotlin
/** The floating bar's short row, edited on its own from Preferences. */
val compactToolbarLayout: ToolbarLayout = ToolbarLayout.COMPACT_DEFAULT,
```

Written by `toJson()`:

```kotlin
.put("compact_toolbar_layout", toolbarLayoutJson(compactToolbarLayout))
```

Read by `fromJson()`:

```kotlin
compactToolbarLayout = toolbarLayout(
    o.optJSONObject("compact_toolbar_layout"),
    ToolbarLayout.NOTE_ITEMS,
    ToolbarLayout.COMPACT_DEFAULT,
    appendedVisible = false,
),
```

The `toolbarLayout` helper gained matching `appendMissing` and `appendedVisible` parameters and
forwards them to `ToolbarLayout.fromRaw`.

Settings backups already merge arbitrary top-level keys, so `compact_toolbar_layout` travels with
exports and imports without extra code.

---

## 3. Editor state

File: `app/src/main/java/com/xnotes/ui/Editor.kt`

Added live state:

```kotlin
/** The floating bar's short row. Its own layout: the short row is not the full bar. */
var compactToolbarLayout by mutableStateOf(ToolbarLayout.COMPACT_DEFAULT)
    private set
```

`applySettings()` assigns it from the loaded settings:

```kotlin
compactToolbarLayout = settings.compactToolbarLayout
```

Added the same apply-and-save path the other bars use:

```kotlin
/** The same, for the floating bar's short row. */
fun applyCompactToolbarLayout(layout: ToolbarLayout) {
    compactToolbarLayout = layout
    settings = settings.copy(compactToolbarLayout = layout)
    settingsRepo.save(settings)
}
```

This is what the Preferences editor calls on every toggle, drop or section change, so edits are
live and persisted immediately.

---

## 4. Reusing the full bar renderer

File: `app/src/main/java/com/xnotes/ui/Toolbar.kt`

`Toolbar` used to always read `editor.toolbarLayout`. It now takes the layout as a parameter:

```kotlin
fun Toolbar(
    editor: Editor,
    ...
    layout: ToolbarLayout = editor.toolbarLayout,
) {
    ...
    layout.visibleSections.forEachIndexed { si, section ->
        if (si > 0) Separator()
        section.visibleEntries.forEach { entry ->
            ToolbarItemView(...)
        }
    }
}
```

The default keeps every existing call site unchanged, including docked bars.

### Trailing controls

The trailing area gained an Expand button driven by `LocalToolbarExpand`:

```kotlin
val drag = LocalToolbarDrag.current
val onExpand = LocalToolbarExpand.current
val onCollapse = LocalToolbarCollapse.current

// Order when everything is present:
// 1. drag grip
// 2. Expand (short row only)
// 3. Collapse
// 4. close-pane button (split panes only)
```

Separators are only inserted when the next control exists, so the full bar looks exactly as before
(`onExpand` is null there).

Because the short row now uses this same composable, it automatically supports every item renderer:
undo/redo, zoom, fit, styles, margins, view, sidebar, tools, swatches, popups, insert menu, and so
on. No tool-selection or drawing logic was duplicated.

---

## 5. Three-state host

File: `app/src/main/java/com/xnotes/ui/ThreeStateToolbar.kt`

### New composition local

```kotlin
/** How the compact row opens the full bar, supplied only while the short row is showing. */
internal val LocalToolbarExpand = compositionLocalOf<(() -> Unit)?> { null }
```

### New host contract

Before, the host received one lambda for the expanded bar. Now it receives a layout-aware bar so the
same call site can render either layout:

```kotlin
@Composable
fun ThreeStateToolbarHost(
    editor: Editor,
    modifier: Modifier = Modifier,
    bar: @Composable (ToolbarLayout) -> Unit,
) {
    ...
    when (state) {
        ToolbarState.COLLAPSED -> CollapsedToolbarIcon(...)

        ToolbarState.COMPACT -> CompositionLocalProvider(
            LocalToolbarDrag provides drag,
            LocalToolbarExpand provides { setState(ToolbarState.EXPANDED) },
            LocalToolbarCollapse provides { setState(ToolbarState.COLLAPSED) },
        ) { bar(editor.compactToolbarLayout) }

        ToolbarState.EXPANDED -> CompositionLocalProvider(
            LocalToolbarDrag provides drag,
            LocalToolbarCollapse provides { setState(ToolbarState.COMPACT) },
        ) { bar(editor.toolbarLayout) }
    }
}
```

The removed `CompactToolbar` and `CompactSwatches` composables are gone: the shared `Toolbar`
renderer now draws the short row from `editor.compactToolbarLayout`.

Position, drag, anchor persistence, animation and the collapsed icon are untouched.

---

## 6. Editor activity wiring

File: `app/src/main/java/com/xnotes/EditorActivity.kt`

The paged pane now builds one layout-aware bar and passes it to the host:

```kotlin
val pagedBar: @Composable (com.xnotes.core.tools.ToolbarLayout) -> Unit = { layout ->
    Box(Modifier.endsPageSelection(editor)) { Toolbar(
        editor,
        onToggleFullscreen = actions.onToggleFullscreen,
        onOpenBackstage = actions.onOpenBackstage,
        onInsertImage = { actions.onInsertImage(editor, null) },
        onAddStickers = actions.onAddStickers,
        onClosePane = onClose,
        onImportTemplate = actions.onImportTemplate,
        onInsertAudio = { actions.onInsertAudio(editor) },
        onRecordAudio = { actions.onRecordAudio(editor) },
        onTakePhoto = { actions.onTakePhoto(editor) },
        layout = layout,
    ) }
}

val threeState = editor.toolbarLook.floating
val bar: @Composable () -> Unit = if (threeState) {
    { com.xnotes.ui.ThreeStateToolbarHost(editor, bar = pagedBar) }
} else {
    { pagedBar(editor.toolbarLayout) }
}
```

Docked mode passes `editor.toolbarLayout`, exactly as before. Floating mode lets the host choose the
short or full layout per state.

---

## 7. Preferences editor

### 7.1 Factored-out editor

File: `app/src/main/java/com/xnotes/ui/ToolbarCustomizer.kt`

The drag bookkeeping was moved into a reusable state holder so two editors can coexist without
sharing stale bounds:

```kotlin
@Stable
internal class ToolbarEditorState {
    var dragItem by mutableStateOf<ToolbarItem?>(null)
    var dragFinger by mutableStateOf(Offset.Zero)
    var dragGrab by mutableStateOf(Offset.Zero)
    var dropTarget by mutableStateOf<Pair<Int, Int>?>(null)
    val chipBounds = mutableMapOf<ToolbarItem, Rect>()
    val sectionBounds = mutableMapOf<Int, Rect>()
    val sectionGrabBounds = mutableMapOf<Int, Rect>()
    var dragSection by mutableStateOf<Int?>(null)
    var sectionDropTarget by mutableStateOf<Int?>(null)

    val dragging: Boolean get() = dragItem != null || dragSection != null

    /** Drop stale bounds and any half-finished drag; used when switching the bar being edited. */
    fun reset() { ... }
}
```

A reusable composable wraps `ToolbarCustomizerBody` with all callbacks and the edge auto-scroll:

```kotlin
@Composable
internal fun ToolbarCustomizer(
    layout: ToolbarLayout,
    state: ToolbarEditorState,
    scrollState: ScrollState,
    viewport: Rect,
    onApply: (ToolbarLayout) -> Unit,
)
```

It handles:

- chip tap → show/hide;
- long-press chip drag within/cross sections;
- section drag by its header;
- add/delete sections;
- drop-slot resolution;
- auto-scroll near the viewport edges while dragging.

Each editor has its own `ToolbarEditorState`, so a drag in the full-bar editor cannot target a chip
that belongs to the short-row editor even though both bars reuse the same `ToolbarItem` ids.

### 7.2 New section in Preferences

File: `app/src/main/java/com/xnotes/ui/PreferencesPane.kt`

The full-bar section keeps the existing `xnote` / `xcanvas` tabs:

```kotlin
ToolbarCustomizer(
    layout = editedLayout(),
    state = mainToolbar,
    scrollState = scrollState,
    viewport = viewport,
    onApply = ::applyEdited,
)
```

A new **Short toolbar** section follows immediately:

```kotlin
HorizontalDivider(color = palette.border.toComposeColor())
SectionTitle(stringResource(R.string.pref_toolbar_short))
Text(
    stringResource(R.string.pref_toolbar_short_help),
    color = palette.textDim.toComposeColor(),
    fontSize = 12.sp,
)
Row(...) {
    Spacer(Modifier.weight(1f))
    TextButton(
        onClick = { editor.applyCompactToolbarLayout(ToolbarLayout.COMPACT_DEFAULT) },
    ) { Text(stringResource(R.string.reset), fontSize = 13.sp) }
}
ToolbarCustomizer(
    layout = editor.compactToolbarLayout,
    state = compactToolbar,
    scrollState = scrollState,
    viewport = viewport,
    onApply = { editor.applyCompactToolbarLayout(it) },
)
```

The floating drag ghost is still drawn once at the pane root, from whichever editor is active:

```kotlin
val ghost = when {
    mainToolbar.dragging -> mainToolbar
    compactToolbar.dragging -> compactToolbar
    else -> null
}
```

This preserves the old behaviour where the scroll container never clips the lifted chip or section.

---

## 8. Strings

File: `app/src/main/res/values/strings.xml`

```xml
<string name="pref_toolbar_short">Short toolbar</string>
<string name="pref_toolbar_short_help">The floating bar\'s compact row. Edit it the same way: tap a tool to show or hide it, long-press to drag it. Only shown when the toolbar style is Floating.</string>
```

Existing strings reused: `toolbar_expand`, `toolbar_compact`, `toolbar_collapse`, `toolbar_move`,
`reset`, `pref_toolbar_help`.

---

## 9. How it behaves

1. Preferences → Toolbar → Style → **Floating**.
2. Open a PDF or note and tap the collapsed icon to show the short row.
3. In Preferences, the **Short toolbar** section lists **every** paged-bar chip. The five starting
   tools are visible; all other tools are shown dimmed in the extras card.
4. Tap any dimmed chip to reveal that tool on the short row; tap a visible chip to hide it again.
5. Long-press a chip to drag it within or across cards.
6. Long-press a card's header strip to reorder the section.
7. Add or delete sections; Reset restores the default short row (extras hidden again).
8. Changes are applied live and saved to `compact_toolbar_layout`.
9. The short row still has the drag grip, Expand (more) and Collapse (chevron) controls; the full bar
   still has the drag grip and Collapse.
10. Docked toolbars and the infinite canvas are unchanged.

---

## 10. Tests

### Added / updated

`app/src/test/java/com/xnotes/core/tools/ToolbarLayoutTest.kt`

- `theCompactDefaultRoundTripsThroughRaw` — `COMPACT_DEFAULT` survives storage unchanged.
- `everyPagedItemCanBeOfferedOnTheShortRow` — the short row holds every paged item exactly once.
- `theCompactDefaultShowsOnlyItsFiveStartingItems` — all extras start hidden.
- `aShortRowStoredBeforeAnItemExistedGainsItHidden` — a migrated item is present but hidden.

`app/src/test/java/com/xnotes/settings/SettingsTest.kt`

- `compactToolbarLayoutDefaultsWhenAbsent` — old settings JSON falls back to `COMPACT_DEFAULT`.
- `compactToolbarLayoutRoundTripsShort` — a customised short row round-trips; every item is present
  but the extras remain hidden.

### Results

```text
:app:compileDebugKotlin          BUILD SUCCESSFUL
:app:testDebugUnitTest
  --tests com.xnotes.settings.SettingsTest
  --tests com.xnotes.core.tools.ToolbarLayoutTest
                                  BUILD SUCCESSFUL
:app:assembleDebug               BUILD SUCCESSFUL
```

The known unrelated local failure (`TemplateTest...AI.md` CRLF) was not touched.

---

## 11. Build

Command used (WSL → Windows Gradle):

```bash
/mnt/c/Windows/System32/cmd.exe /c "cd /d D:\xnotes\v1\xnotes-android && set \"JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\" && gradlew.bat :app:assembleDebug --console=plain"
```

Debug APK:

```text
Windows: D:\xnotes\v1\xnotes-android\app\build\outputs\apk\debug\app-debug.apk
WSL:     /mnt/d/xnotes/v1/xnotes-android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 12. Files changed

### Modified

- `app/src/main/java/com/xnotes/EditorActivity.kt`
- `app/src/main/java/com/xnotes/core/tools/ToolbarLayout.kt`
- `app/src/main/java/com/xnotes/settings/Settings.kt`
- `app/src/main/java/com/xnotes/ui/Editor.kt`
- `app/src/main/java/com/xnotes/ui/PreferencesPane.kt`
- `app/src/main/java/com/xnotes/ui/ThreeStateToolbar.kt`
- `app/src/main/java/com/xnotes/ui/Toolbar.kt`
- `app/src/main/java/com/xnotes/ui/ToolbarCustomizer.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/com/xnotes/core/tools/ToolbarLayoutTest.kt`
- `app/src/test/java/com/xnotes/settings/SettingsTest.kt`
- `toolbar changes.md`

### Created

- `shortToolbareditor.md` (this file)

---

## 13. Notes and limitations

- The short row can technically hold any paged-bar item because it shares the full renderer. Some
  clusters are wide (`ZOOM` is three controls, `PAGE_NAV` is three, `COLORS` is every visible
  swatch), so a very full short row may scroll horizontally/vertically like the full bar rather than
  staying compact. The default stays short.
- The short row is edited only in Preferences; there is no in-place edit mode inside the floating
  bar.
- The section is visible whenever the toolbar section is visible. It only affects the UI when the
  toolbar style is Floating.
- No device/emulator was attached for screenshots; verification was compilation, unit tests and a
  successful debug APK build.
