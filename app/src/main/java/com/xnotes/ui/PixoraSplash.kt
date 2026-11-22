package com.xnotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xnotes.R

private val NavyStart = Color(0xFF303FA0)
private val NavyEnd = Color(0xFF111B68)
private val Cyan = Color(0xFF27D6E1)

private val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_bold, FontWeight.Bold),
)

/** The branded launch poster, shown over the editor while the session restores. */
@Composable
fun PixoraSplash(modifier: Modifier = Modifier, onShown: () -> Unit = {}) {
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        onShown()
        entrance.animateTo(
            1f,
            animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(NavyStart, NavyEnd),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset.Infinite,
                ),
            )
            .safeDrawingPadding(),
    ) {
        val landscape = maxWidth > maxHeight
        val logoSize = if (landscape) {
            minOf(maxWidth.value * 0.22f, maxHeight.value * 0.26f)
        } else {
            minOf(maxWidth.value * 0.40f, maxHeight.value * 0.30f)
        }.dp
        val titleSize = (logoSize.value * 0.23f).coerceAtMost(64f).sp
        val baseSize = if (landscape) {
            minOf(maxWidth.value * 0.022f, maxHeight.value * 0.04f).coerceIn(10f, 16f)
        } else {
            minOf(maxWidth.value * 0.03f, maxHeight.value * 0.04f).coerceIn(10f, 18f)
        }
        val bodySize = baseSize.sp
        val smallSize = (baseSize * 0.84f).sp
        val tinyGap = (baseSize * 0.4f).dp
        val dividerGap = (baseSize * 1.3f).dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(if (landscape) 0.6f else 1f))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer { alpha = entrance.value },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .size(logoSize * 1.8f)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                                ),
                                shape = CircleShape,
                            ),
                    )
                    Image(
                        painter = painterResource(R.drawable.pixora_splash_logo),
                        contentDescription = "PIXORA PDF",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(logoSize)
                            .graphicsLayer {
                                val progress = entrance.value
                                scaleX = 0.88f + 0.12f * progress
                                scaleY = 0.88f + 0.12f * progress
                            },
                    )
                }
                Spacer(Modifier.height((logoSize.value * 0.07f).dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White)) { append("PIXORA ") }
                        withStyle(SpanStyle(color = Cyan)) { append("PDF") }
                    },
                    color = Color.White,
                    fontFamily = Poppins,
                    fontWeight = FontWeight.Bold,
                    fontSize = titleSize,
                    letterSpacing = 0.07.em,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(if (landscape) 0.9f else 1.2f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Powered by Smart Project",
                    color = Color.White.copy(alpha = 0.72f),
                    fontFamily = Poppins,
                    fontWeight = FontWeight.Normal,
                    fontSize = bodySize,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(tinyGap))
                Text(
                    text = "Innovation for Education",
                    color = Color.White.copy(alpha = 0.92f),
                    fontFamily = Poppins,
                    fontWeight = FontWeight.Medium,
                    fontSize = bodySize,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(dividerGap))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(2.dp)
                        .background(Cyan.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.height(dividerGap))
                Text(
                    text = "Developed by",
                    color = Color.White.copy(alpha = 0.62f),
                    fontFamily = Poppins,
                    fontWeight = FontWeight.Normal,
                    fontSize = smallSize,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(tinyGap))
                Text(
                    text = "Abdelrahman Badawi & Moaaz Mohamed",
                    color = Color.White.copy(alpha = 0.95f),
                    fontFamily = Poppins,
                    fontWeight = FontWeight.Medium,
                    fontSize = bodySize,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(if (landscape) 10.dp else (baseSize * 2.4f).dp))
        }
    }
}
