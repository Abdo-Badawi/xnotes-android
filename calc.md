# Floating calculator

## What it does

A **Calculator** toolbar item opens the fx-calc scientific calculator in a floating window over the
note. The window can be dragged by its title bar, resized from the bottom-right corner and minimised
with the minus button. It has no scrim, so the page underneath can still be edited while it is open.

The bottom **Send to page** button reads the current result from the calculator and places it on the
current PDF page as an undoable text box near the centre of the viewport. The calculator stays open
after sending so another result can be added.

## Toolbar entry

- `ToolbarItem.CALCULATOR("calculator")` was added in
  `app/src/main/java/com/xnotes/core/tools/ToolbarLayout.kt`.
- Migration uses `INSERT_AFTER[CALCULATOR] = TEXT_BOX`, so existing saved layouts receive the item
  after the text-box entry.
- The default layout places it after `TEXT_BOX` in the text/shape section.
- The icon is `XnotesIcons.calculator`; the label is `R.string.calculator`.
- It can be hidden, reordered or moved to the short floating toolbar through the toolbar customiser.
- `ToolbarLayoutTest.defaultMirrorsToolbarGrouping` was updated for the new entry.

## Floating window

`app/src/main/java/com/xnotes/ui/CalculatorWindow.kt` implements the window:

- A `BoxWithConstraints` fills the editor pane and clamps the floating window to it.
- The title bar uses `detectDragGestures` to move the window.
- The bottom-right handle resizes it, with minimum sizes of 280 dp wide and 380 dp tall.
- The maximum size is 460 dp wide and 760 dp tall, or the pane size if that is smaller.
- A `BackHandler` closes the calculator with the Android back gesture.
- The minus button calls `editor.closeCalculator()`.

`Editor` holds the open state:

- `calculatorOpen` (mutable Compose state)
- `toggleCalculator()`
- `closeCalculator()`

`EditorActivity`'s `EditorPane` renders `CalculatorWindow(editor)` when `calculatorOpen` is true.

## Bundled web app

The calculator is the fx-calc PWA bundled under
`app/src/main/assets/calculator/` and loaded with:

```kotlin
loadUrl("file:///android_asset/calculator/index.html")
```

Files include `index.html`, `style.css`, `app.js`, `engine.js`, `engine-x.js`, `editor.js`, the PWA
icons, `manifest.webmanifest` and `sw.js`. The service worker cannot register under `file://`; the
registration failure is ignored.

## Result bridge

`app.js` exposes a plain-text result function:

```js
window.__fxResultText = resultText;
window.__fx = { S, U, dispatch, press, render, resultText };
```

`resultText()` returns an empty string unless the calculator is showing a result. It serialises the
visible result, including:

- fractions as `(n)/(d)`
- powers as `^` and subscripts as `_`
- tables as `[row 1; row 2; ...]`
- the Unicode minus sign normalised to `-`

`CalculatorWindow` calls:

```kotlin
web.evaluateJavascript("(window.__fxResultText ? window.__fxResultText() : '')") { ... }
```

The JSON-encoded string is decoded with `JSONTokener`. Blank results show
`R.string.calculator_no_result`; sending from the infinite canvas shows
`R.string.calculator_paged_only`.

`Editor.insertCalculatorResult(text)`:

- ignores empty text and unbounded-canvas notes
- converts the viewport centre to page coordinates
- creates a `TextItem` with `controller.inkColor`, `TextItem.DEFAULT_POINT_SIZE` and
  `TextItem.DEFAULT_FACE`
- pushes one `AddItem` history entry
- appends the item to the page cache and the render cache, selects it and requests a redraw

## Rendering and LCD fixes

The LCD is ordinary HTML/CSS, not a canvas or bitmap-font surface. There is no custom
`devicePixelRatio`, canvas scaling or `imageSmoothing` handling; the Android WebView renders at the
device's native density.

Two problems were fixed:

1. **Zero-height LCD.** This WebView resolved `vh` units to `0px`, so the old
   `height: clamp(170px, 29vh, 290px)` and the short-screen `height: 25vh` collapsed the display.
   `app.js` now computes a pixel height from `window.innerHeight` (28%, clamped to 110-290 px) and
   stores it in `--lcd-height`; `style.css` uses that value with a 96 px `min-height`. The value is
   refreshed on `resize`.
2. **Cramped menu text.** Menu labels are literal strings in `app.js` and were not corrupted. The
   old rows had tight line-height and no box, so at small display sizes glyphs from adjacent rows
   appeared to overlap and looked garbled. Each menu item is now a complete button: bordered and
   shaded surface, its own padding, `min-height`, `line-height: 1.2`, and a dedicated `.lb` label
   span for ellipsis. SETUP/MENU dense lists use a smaller but still button-shaped row. List rows
   use the same button treatment. Selected rows invert to the dark LCD ink colour.

WebView lifecycle: the `AndroidView` factory loads the page and `onRelease` loads `about:blank`
before calling `destroy()`. This avoids the earlier bug where a stale `DisposableEffect` destroyed
the newly attached WebView.

## Strings

Added to `app/src/main/res/values/strings.xml`:

- `calculator` — window title and toolbar label
- `calculator_minimize` — minimise button description
- `calculator_send` — "Send to page"
- `calculator_no_result` — "Calculate something first"
- `calculator_paged_only` — "Open a PDF page to send the result"

## Build and verification

```text
gradlew.bat :app:testDebugUnitTest --console=plain
gradlew.bat :app:assembleDebug --console=plain
node tests/engine.test.js
node tests/editor.test.js
```

Current status:

- xnotes unit tests pass.
- fx-calc tests pass: 106 engine tests and 17 editor tests.
- Debug APK built and installed on the Realme `RMX3844` (`aad13020`) and Samsung `SM-T505N`
  (`R9KT5004FQM`).
- Toolbar opens the floating calculator, keypad works, LCD is visible, SETUP/MENU render as spaced
  buttons, and **Send to page** reports "Calculate something first" when there is no result.

## Known limitations

- Sending a result is supported on paged PDF notes only; the infinite canvas shows the
  `calculator_paged_only` message.
- The calculator is a WebView, so it does not share the note renderer's text/font stack. It is
  isolated from the editing surface and does not intercept ink input outside its window.
- The calculator does not remember its last window size or position between editor sessions.
