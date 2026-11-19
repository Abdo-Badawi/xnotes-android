package com.xnotes.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import org.json.JSONTokener
import kotlin.math.roundToInt

/** The largest the calculator grows, however wide the pane or tablet is. */
private val MAX_WINDOW_WIDTH = 460.dp
private val MAX_WINDOW_HEIGHT = 760.dp

/**
 * The floating calculator over a note. It is a WebView running the bundled fx-calc build, with a
 * title bar to drag, a corner to resize and a button that drops the current result on the page as a
 * text box. There is no scrim: the page around it still takes ink, so a result can be worked out and
 * placed without putting the calculator away.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CalculatorWindow(editor: Editor) {
    BackHandler { editor.closeCalculator() }

    val palette = LocalPalette.current
    val density = LocalDensity.current
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxW = with(density) { maxWidth.toPx() }
        val maxH = with(density) { maxHeight.toPx() }
        val minW = with(density) { 280.dp.toPx() }
        val minH = with(density) { 380.dp.toPx() }

        fun fit(value: Float, low: Float, high: Float) =
            value.coerceIn(minOf(low, high), high.coerceAtLeast(0f))

        var size by remember(maxW) {
            mutableStateOf(
                Offset(
                    fit(minOf(maxW * 0.92f, with(density) { MAX_WINDOW_WIDTH.toPx() }), minW, maxW),
                    fit(minOf(maxH * 0.86f, with(density) { MAX_WINDOW_HEIGHT.toPx() }), minH, maxH),
                ),
            )
        }
        var offset by remember(maxW) {
            mutableStateOf(Offset((maxW - size.x) / 2f, (maxH - size.y) / 2f))
        }

        // The keyboard changes maxH without changing maxW, which must not reset the window; just keep
        // it inside the pane as that room comes and goes.
        LaunchedEffect(maxW, maxH) {
            val w = fit(size.x, minW, maxW)
            val h = fit(size.y, minH, maxH)
            if (w != size.x || h != size.y) size = Offset(w, h)
            offset = Offset(
                offset.x.coerceIn(0f, (maxW - w).coerceAtLeast(0f)),
                offset.y.coerceIn(0f, (maxH - h).coerceAtLeast(0f)),
            )
        }

        Box(
            Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(with(density) { size.x.toDp() }, with(density) { size.y.toDp() })
                .shadow(20.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(palette.panel.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(16.dp)),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(palette.surfaceHi.toComposeColor())
                        .pointerInput(maxW, maxH, size) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                offset = Offset(
                                    (offset.x + drag.x).coerceIn(0f, (maxW - size.x).coerceAtLeast(0f)),
                                    (offset.y + drag.y).coerceIn(0f, (maxH - size.y).coerceAtLeast(0f)),
                                )
                            }
                        }
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.calculator),
                        color = palette.text.toComposeColor(),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editor.closeCalculator() }) {
                        Icon(
                            XnotesIcons.minus,
                            contentDescription = stringResource(R.string.calculator_minimize),
                            tint = palette.textDim.toComposeColor(),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(AndroidColor.rgb(17, 19, 23))
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.setSupportZoom(false)
                            settings.builtInZoomControls = false
                            webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        Log.e("Calculator", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                                    }
                                    return true
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    Log.e("Calculator", "Failed ${request.url}: ${error.description}")
                                }
                            }
                            loadUrl("file:///android_asset/calculator/index.html")
                            webView = this
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onRelease = { web ->
                        web.loadUrl("about:blank")
                        web.destroy()
                    },
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(start = 12.dp, end = 32.dp, top = 7.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.accent.toComposeColor())
                            .clickable {
                                val web = webView ?: return@clickable
                                web.evaluateJavascript("(window.__fxResultText ? window.__fxResultText() : '')") { raw ->
                                    val text = runCatching {
                                        JSONTokener(raw).nextValue() as? String
                                    }.getOrNull().orEmpty()
                                    if (text.isBlank()) {
                                        editor.message = context.getString(R.string.calculator_no_result)
                                    } else {
                                        editor.insertCalculatorResult(text)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.calculator_send),
                            color = palette.onAccent.toComposeColor(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(32.dp)
                    .pointerInput(maxW, maxH, offset) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            size = Offset(
                                fit(size.x + drag.x, minW, maxW - offset.x),
                                fit(size.y + drag.y, minH, maxH - offset.y),
                            )
                        }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Canvas(Modifier.size(14.dp)) {
                    val drawSize = this.size
                    val tint = palette.textDim.toComposeColor()
                    val stroke = 1.5.dp.toPx()
                    val inset = 2.dp.toPx()
                    drawLine(
                        tint,
                        Offset(inset, drawSize.height - inset),
                        Offset(drawSize.width - inset, inset),
                        stroke,
                    )
                    drawLine(
                        tint,
                        Offset(drawSize.width * 0.5f, drawSize.height - inset),
                        Offset(drawSize.width - inset, drawSize.height * 0.5f),
                        stroke,
                    )
                }
            }
        }
    }
}
