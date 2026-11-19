package com.xnotes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xnotes.R
import com.xnotes.core.tools.Tool
import com.xnotes.settings.ToolbarState
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * A drag source shared with the bars it moves. The expanded [Toolbar] and the compact row do not
 * drag from their buttons; they draw a [ToolbarDragHandle] that reports here, so a tap on a tool is
 * never mistaken for a move. The instance is stable across recompositions — a new one would cancel
 * the gesture it is in the middle of.
 */
internal class ToolbarDrag {
    var onDrag: (Offset) -> Unit = {}
    var onDragEnd: () -> Unit = {}
}

internal val LocalToolbarDrag = androidx.compose.runtime.compositionLocalOf<ToolbarDrag?> { null }

/** How the expanded bar folds back to the compact row, supplied only by [ThreeStateToolbarHost]. */
internal val LocalToolbarCollapse = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/** How the compact row opens the full bar, supplied only while the short row is showing. */
internal val LocalToolbarExpand = androidx.compose.runtime.compositionLocalOf<(() -> Unit)?> { null }

/**
 * The floating paged toolbar's three shapes. A single icon sits over the page; tapping it opens a
 * compact row of the tools a PDF is read and marked with; that row's expand button hands the same
 * customisable [Toolbar] back to the host for the full bar. The state and the bar's anchor live in
 * [com.xnotes.settings.ToolbarLook], so they survive rotation and the next launch.
 *
 * The host itself only occupies the area its current shape needs: it is a plain full-size box for
 * positioning, so PDF touches outside the bar reach the page untouched.
 */
@Composable
fun ThreeStateToolbarHost(
    editor: Editor,
    modifier: Modifier = Modifier,
    bar: @Composable (com.xnotes.core.tools.ToolbarLayout) -> Unit,
) {
    val look = editor.toolbarLook
    val density = LocalDensity.current
    var area by remember { mutableStateOf(IntSize.Zero) }
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    var anchorX by remember(editor) { mutableFloatStateOf(look.anchorX) }
    var anchorY by remember(editor) { mutableFloatStateOf(look.anchorY) }
    var dragging by remember { mutableStateOf(false) }

    // Preferences can change from the other pane or the settings screen; follow them unless a
    // finger currently owns the anchor.
    LaunchedEffect(look.anchorX, look.anchorY, dragging) {
        if (!dragging) {
            anchorX = look.anchorX
            anchorY = look.anchorY
        }
    }

    val inset = with(density) { TOOLBAR_EDGE_GAP.toPx() }
    val freeX = (area.width - barSize.width - 2 * inset).coerceAtLeast(0f)
    val freeY = (area.height - barSize.height - 2 * inset).coerceAtLeast(0f)
    val targetX = (inset + anchorX * freeX).roundToInt()
    val targetY = (inset + anchorY * freeY).roundToInt()

    fun move(delta: Offset) {
        if (freeX <= 0f || freeY <= 0f) return
        dragging = true
        anchorX = (anchorX + delta.x / freeX).coerceIn(0f, 1f)
        anchorY = (anchorY + delta.y / freeY).coerceIn(0f, 1f)
    }

    fun settle() {
        if (!dragging) return
        dragging = false
        editor.applyToolbarLook(look.copy(anchorX = anchorX, anchorY = anchorY))
    }

    fun setState(state: ToolbarState) {
        if (state != look.state) editor.applyToolbarLook(look.copy(state = state))
    }

    val drag = remember(editor) { ToolbarDrag() }
    drag.onDrag = ::move
    drag.onDragEnd = ::settle

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { area = it },
    ) {
        AnimatedContent(
            targetState = look.state,
            transitionSpec = {
                (fadeIn(tween(STATE_MS)) + scaleIn(tween(STATE_MS), initialScale = 0.9f))
                    .togetherWith(fadeOut(tween(STATE_MS / 2)) + scaleOut(tween(STATE_MS / 2), targetScale = 0.96f))
                    .using(SizeTransform(clip = false))
            },
            contentAlignment = Alignment.TopStart,
            label = "floatingToolbar",
            modifier = Modifier
                .offset { IntOffset(targetX, targetY) }
                .onSizeChanged { barSize = it },
        ) { state ->
            when (state) {
                ToolbarState.COLLAPSED -> CollapsedToolbarIcon(
                    editor = editor,
                    drag = drag,
                    onExpand = { setState(ToolbarState.COMPACT) },
                )
                ToolbarState.COMPACT -> CompositionLocalProvider(
                    LocalToolbarDrag provides drag,
                    LocalToolbarScale provides look.shortScale,
                    LocalToolbarExpand provides { setState(ToolbarState.EXPANDED) },
                    LocalToolbarCollapse provides { setState(ToolbarState.COLLAPSED) },
                ) { bar(editor.compactToolbarLayout) }
                ToolbarState.EXPANDED -> CompositionLocalProvider(
                    LocalToolbarDrag provides drag,
                    LocalToolbarScale provides look.fullScale,
                    LocalToolbarCollapse provides { setState(ToolbarState.COMPACT) },
                ) { bar(editor.toolbarLayout) }
            }
        }
    }
}

/** The one floating button: the armed tool's own icon, dragged anywhere, tapped to open the row. */
@Composable
private fun CollapsedToolbarIcon(editor: Editor, drag: ToolbarDrag, onExpand: () -> Unit) {
    val bar = barMetrics(editor.toolbarLook.size, editor.toolbarLook.shortScale)
    val palette = LocalPalette.current
    val icon = toolbarToolIcons()[editor.tool] ?: XnotesIcons.pan
    Box(
        Modifier
            .size(bar.button + 8.dp)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(palette.panel.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), CircleShape)
            .pointerInput(drag) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        drag.onDrag(amount)
                    },
                    onDragEnd = { drag.onDragEnd() },
                    onDragCancel = { drag.onDragEnd() },
                )
            }
            .clickable(onClick = onExpand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = stringResource(R.string.toolbar_open),
            tint = palette.text.toComposeColor(),
            modifier = Modifier.size(bar.icon),
        )
    }
}

/** The grip that moves a bar; only this reparents the toolbar, so tool taps stay taps. */
@Composable
internal fun ToolbarDragHandle(drag: ToolbarDrag) {
    val bar = LocalBar.current
    val palette = LocalPalette.current
    Box(
        Modifier
            .size(bar.button)
            .pointerInput(drag) {
                detectDragGestures(
                    onDrag = { change, amount ->
                        change.consume()
                        drag.onDrag(amount)
                    },
                    onDragEnd = { drag.onDragEnd() },
                    onDragCancel = { drag.onDragEnd() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            XnotesIcons.grip,
            contentDescription = stringResource(R.string.toolbar_move),
            tint = palette.textDim.toComposeColor(),
            modifier = Modifier.size(bar.icon),
        )
    }
}

/** How near the pane's edge a dragged bar may be parked. */
private val TOOLBAR_EDGE_GAP = 4.dp

/** How long one shape takes to fold into the next. */
private const val STATE_MS = 180
