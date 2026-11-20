package com.xnotes.ui

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.canvas.InputTouchRewriter
import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.InputCustomization
import com.xnotes.core.tools.InputFunction
import com.xnotes.core.tools.InputMatch
import com.xnotes.core.tools.InputSignatureBuilder
import com.xnotes.core.tools.TouchSignature
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The live state of the Input Customization panel and its recorder. The activity feeds every raw
 * MotionEvent here before normal dispatch, so recording and live testing see the platform's real
 * contact size (which Compose does not expose). Every setting comes from these recordings; no
 * size is built in.
 */
class InputCustomizationState(
    private val read: () -> InputCustomization,
    private val write: (InputCustomization) -> Unit,
) {
    var open by mutableStateOf(false)
        private set

    /** The function being recorded, or null when not recording. */
    var activeFunction by mutableStateOf<InputFunction?>(null)
        private set

    var samplesDone by mutableStateOf(0)
        private set

    var overlapWarning by mutableStateOf(false)
        private set

    var savedFunction by mutableStateOf<InputFunction?>(null)
        private set

    var testing by mutableStateOf(false)
        private set

    var testMatch by mutableStateOf<InputMatch?>(null)
        private set

    var tested by mutableStateOf(false)
        private set

    var colorPickerOpen by mutableStateOf(false)
        private set

    private val samples = ArrayList<InputSignatureBuilder.Sample>()
    private var gesturePeak = 0f
    private var gestureCount = 0
    private var gestureActive = false
    private val rewriter = InputTouchRewriter()

    fun toggleOpen() {
        if (open) closePanel() else openPanel()
    }

    fun openPanel() {
        open = true
        cancelGesture()
        clearTest()
        colorPickerOpen = false
    }

    fun closePanel() {
        open = false
        cancelGesture()
        clearTest()
        colorPickerOpen = false
        rewriter.reset()
    }

    private fun clearTest() {
        testMatch = null
        tested = false
        testing = false
    }

    fun startRecording(function: InputFunction) {
        savedFunction = null
        overlapWarning = false
        clearTest()
        samples.clear()
        samplesDone = 0
        gestureActive = false
        gesturePeak = 0f
        gestureCount = 0
        activeFunction = function
    }

    fun startTest() {
        cancelGesture()
        testMatch = null
        tested = false
        testing = true
    }

    fun cancelGesture() {
        activeFunction = null
        testing = false
        samples.clear()
        samplesDone = 0
        gestureActive = false
    }

    fun toggleEnabled(enabled: Boolean) {
        rewriter.reset()
        write(read().copy(enabled = enabled))
    }

    fun reset(function: InputFunction) {
        savedFunction = null
        overlapWarning = false
        write(read().withSignature(function, null))
    }

    fun resetAll() {
        savedFunction = null
        overlapWarning = false
        val current = read()
        write(current.copy(write = null, pan = null, erase = null, altWrite = null))
    }

    fun setAltColor(color: Rgba) {
        write(read().copy(altColor = color))
        colorPickerOpen = false
    }

    fun openColorPicker() {
        colorPickerOpen = true
    }

    fun dismissColorPicker() {
        colorPickerOpen = false
    }

    /**
     * Feed the raw touch stream before it reaches any view. Consumes the events while recording or
     * live-testing; returns false so normal dispatch is untouched the rest of the time.
     */
    fun capture(event: MotionEvent): Boolean {
        val function = activeFunction
        if (function != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureActive = true
                    gesturePeak = 0f
                    gestureCount = event.pointerCount
                    collect(event)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    gestureCount = maxOf(gestureCount, event.pointerCount)
                    collect(event)
                }
                MotionEvent.ACTION_MOVE -> if (gestureActive) collect(event)
                MotionEvent.ACTION_UP -> if (gestureActive) {
                    collect(event)
                    finishGesture(function)
                }
                MotionEvent.ACTION_CANCEL -> gestureActive = false
            }
            return true
        }
        if (testing) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Contact sizes ramp up after the first sample on some panels, so keep
                    // looking as the gesture develops instead of judging only the down sample.
                    testMatch = read().match(event.pointerCount, InputTouchRewriter.contactSize(event))
                    tested = true
                }
                MotionEvent.ACTION_MOVE -> if (testMatch == null) {
                    testMatch = read().match(event.pointerCount, InputTouchRewriter.contactSize(event))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> testing = false
            }
            return true
        }
        return false
    }

    private fun collect(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val minor = event.getTouchMinor(i)
            val size = if (minor > 0f) minor else event.getTouchMajor(i)
            gesturePeak = maxOf(gesturePeak, size)
        }
        gestureCount = maxOf(gestureCount, event.pointerCount)
    }

    private fun finishGesture(function: InputFunction) {
        gestureActive = false
        if (gesturePeak > 0f) {
            samples += InputSignatureBuilder.Sample(gesturePeak, gestureCount.coerceAtLeast(1))
        }
        samplesDone = samples.size
        if (samples.size < InputSignatureBuilder.REQUIRED_SAMPLES) return
        val signature = InputSignatureBuilder.build(samples, openMax = function == InputFunction.ERASE) ?: return
        val current = read()
        if (current.overlaps(signature, function)) {
            overlapWarning = true
            samples.clear()
            samplesDone = 0
            activeFunction = null
            return
        }
        write(current.withSignature(function, signature))
        savedFunction = function
        samples.clear()
        samplesDone = 0
        activeFunction = null
    }

    /**
     * Rewrite an event for the enabled feature. Returns null when no tool type changes, in which
     * case the caller dispatches the original event untouched.
     */
    fun rewrite(
        event: MotionEvent,
        onAltColor: (Rgba?) -> Unit,
        onPan: (Boolean) -> Unit,
        onMultiPan: (Boolean) -> Unit,
        classifySize: Float? = null,
    ): MotionEvent? = rewriter.rewrite(event, read(), onAltColor, onPan, onMultiPan, classifySize)
}

/** The in-window panel: a scrim over the pane, so the activity still sees every touch it records. */
@Composable
fun InputCustomizationPanel(editor: Editor, modifier: Modifier = Modifier) {
    val state = editor.inputCustomization
    // Subscribe to preference changes made from this panel (and any other pane).
    @Suppress("UNUSED_VARIABLE")
    val version = editor.prefsVersion
    val customization = editor.preferences.inputCustomization
    val palette = LocalPalette.current

    BackHandler(enabled = state.open) {
        if (state.activeFunction != null || state.testing) state.cancelGesture() else state.closePanel()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .clip(MaterialTheme.shapes.large)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.large)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.input_customization),
                    color = palette.text.toComposeColor(),
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { state.closePanel() }) { Text(stringResource(R.string.input_customization_done)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.input_customization_enable),
                    color = palette.text.toComposeColor(),
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = customization.enabled, onCheckedChange = state::toggleEnabled)
            }
            Text(
                stringResource(R.string.input_customization_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            HorizontalDivider(color = palette.border.toComposeColor())
            for (function in InputFunction.entries) {
                FunctionRow(
                    state = state,
                    customization = customization,
                    function = function,
                )
            }

            HorizontalDivider(color = palette.border.toComposeColor())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { state.resetAll() }) { Text(stringResource(R.string.input_customization_reset_all)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { state.startTest() }, enabled = customization.hasAnySignature) {
                    Text(stringResource(R.string.input_customization_test))
                }
            }

            RecordStatus(state)
        }

        if (state.colorPickerOpen) {
            ColorPickerPopup(
                initial = customization.altColor,
                recents = emptyList(),
                onDismiss = state::dismissColorPicker,
                onPick = state::setAltColor,
            )
        }
    }
}

@Composable
private fun FunctionRow(
    state: InputCustomizationState,
    customization: InputCustomization,
    function: InputFunction,
) {
    val palette = LocalPalette.current
    val signature = customization.signatureFor(function)
    val label = functionLabel(function)
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, color = palette.text.toComposeColor(), fontSize = 15.sp)
                Text(
                    signatureText(signature),
                    color = palette.textDim.toComposeColor(),
                    fontSize = 12.sp,
                )
                if (function == InputFunction.ALT_WRITE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { state.openColorPicker() }.padding(top = 4.dp),
                    ) {
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(customization.altColor.toComposeColor())
                                .border(1.dp, palette.border.toComposeColor(), CircleShape),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.input_customization_colour),
                            color = palette.textDim.toComposeColor(),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            TextButton(onClick = { state.startRecording(function) }) {
                Text(stringResource(R.string.input_customization_record))
            }
            TextButton(onClick = { state.reset(function) }, enabled = signature != null) {
                Text(stringResource(R.string.input_customization_reset))
            }
        }
    }
}

@Composable
private fun functionLabel(function: InputFunction): String = stringResource(
    when (function) {
        InputFunction.WRITE -> R.string.input_function_write
        InputFunction.PAN -> R.string.input_function_pan
        InputFunction.ERASE -> R.string.input_function_erase
        InputFunction.ALT_WRITE -> R.string.input_function_alt_write
    },
)

@Composable
private fun signatureText(signature: TouchSignature?): String {
    if (signature == null) return stringResource(R.string.input_customization_not_recorded)
    if (signature.openMax) {
        return stringResource(R.string.input_customization_palm, signature.minSize, signature.pointerCount)
    }
    return stringResource(
        R.string.input_customization_signature,
        signature.minSize,
        signature.maxSize,
        signature.pointerCount,
    )
}

@Composable
private fun RecordStatus(state: InputCustomizationState) {
    val palette = LocalPalette.current
    val function = state.activeFunction
    if (function != null) {
        Text(
            stringResource(R.string.input_customization_touch_prompt),
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            stringResource(
                R.string.input_customization_sample_progress,
                state.samplesDone.coerceAtMost(InputSignatureBuilder.REQUIRED_SAMPLES),
                InputSignatureBuilder.REQUIRED_SAMPLES,
            ),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
        Text(
            stringResource(R.string.input_customization_cancel_back),
            color = palette.textDim.toComposeColor(),
            fontSize = 12.sp,
        )
    }
    if (state.overlapWarning) {
        Text(
            stringResource(R.string.input_customization_overlap),
            color = palette.accent.toComposeColor(),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    state.savedFunction?.let {
        Text(
            stringResource(R.string.input_customization_saved),
            color = palette.accent.toComposeColor(),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (state.testing) {
        Text(
            stringResource(R.string.input_customization_test_prompt),
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    state.testMatch?.let { match ->
        Text(
            stringResource(R.string.input_customization_test_result, functionLabel(match.function)),
            color = palette.accent.toComposeColor(),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (!state.testing && state.activeFunction == null && state.tested && state.testMatch == null) {
        Text(
            stringResource(R.string.input_customization_test_no_match),
            color = palette.textDim.toComposeColor(),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
