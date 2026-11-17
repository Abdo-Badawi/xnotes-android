# Clear writings toolbar button

## What it does

A new **Clear writings** item (trash icon) sits on the paged toolbar right after the eraser.
Tapping it opens a confirmation dialog:

- **This page** — removes every drawing, shape, text, image and other item from the current page.
- **All pages** — removes every item from every page; only offered when the note has more than one page.
- **Cancel** — closes the dialog without changing anything.

The pages themselves and their backgrounds are kept. The whole clear is a single history step, so
Undo restores everything at once.

The item is a normal toolbar entry: it can be hidden, reordered or shown on the short floating
toolbar through the toolbar customiser, and it is appended to existing saved layouts (after the
eraser) by the usual migration.

## Implementation

- `ToolbarItem.CLEAR_WRITINGS("clear_writings")` added after `ERASER` in
  `app/src/main/java/com/xnotes/core/tools/ToolbarLayout.kt`, with
  `INSERT_AFTER[CLEAR_WRITINGS] = ERASER` for migration.
- Placed after the eraser in `ToolbarLayout.DEFAULT`; as a note item it is also part of the short
  floating toolbar's available items.
- `Editor.clearCurrentPageWritings()` and `Editor.clearAllPageWritings()` added in
  `app/src/main/java/com/xnotes/ui/Editor.kt`; both reuse `erasePages(...)`, which clears the
  items and pushes one `EraseItems` command.
- `ToolbarItemView` in `app/src/main/java/com/xnotes/ui/Toolbar.kt` renders the trash button and
  the confirmation dialog.
- Icon mapping in `ToolbarCustomizer.kt`; label mapping in `Labels.kt`.
- New strings: `clear_writings`, `clear_writings_help`, `clear_this_page`, `clear_all_pages`.
- `ToolbarLayoutTest.defaultMirrorsToolbarGrouping` updated for the new item.

## Verification

- `:app:testDebugUnitTest --tests com.xnotes.core.tools.ToolbarLayoutTest --tests com.xnotes.settings.SettingsTest` — passed.
- `:app:assembleDebug` — built.
- Debug APK installed on the tablet (Realme `RMX3844`, device `aad13020`) and launched without a crash.
- Branch `feature/input-customization`, remote `mine` (`https://github.com/Abdo-Badawi/xnotes-android.git`).
