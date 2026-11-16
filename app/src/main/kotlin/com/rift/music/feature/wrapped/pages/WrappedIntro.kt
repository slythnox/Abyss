/**
 * Rift (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.feature.wrapped.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rift.music.R
import com.rift.music.feature.wrapped.WrappedConstants
import com.rift.music.feature.wrapped.components.WrappedBackground
import com.rift.music.ui.theme.bbhBartle
import kotlinx.coroutines.delay

private const val FADE_IN_DURATION = 900
private const val SLIDE_IN_DURATION = 900
private const val INITIAL_DELAY = 150
private const val ICON_DELAY = 200
private const val TITLE_DELAY = 400
private const val CHIPS_DELAY = 600
private const val SUBTITLE_DELAY = 750
private const val BUTTON_DELAY = 950
private val BOTTOM_PADDING = 56.dp

@Composable
fun AutoResizingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle
) {
    var scaledTextStyle by remember { mutableStateOf(style) }
    var readyToDraw by remember { mutableStateOf(false) }

    Text(
        text = text,
        style = scaledTextStyle,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithContent {
            if (readyToDraw) {
                drawContent()
            }
        },
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowWidth) {
                scaledTextStyle =
                    scaledTextStyle.copy(fontSize = scaledTextStyle.fontSize * 0.9)
            } else {
                readyToDraw = true
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WrappedIntro(onNext: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(INITIAL_DELAY.toLong())
        visible = true
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )

    WrappedBackground(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Icon with pulsing ambient aura
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = ICON_DELAY)) +
                        scaleIn(initialScale = 0.8f, animationSpec = tween(SLIDE_IN_DURATION, delayMillis = ICON_DELAY))
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(120.dp)
                ) {
                    // Ambient halo glow
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF6C5CE7).copy(alpha = 0.45f),
                                        Color(0xFF00CEC9).copy(alpha = 0.2f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    // App icon with border
                    Image(
                        painter = painterResource(id = R.drawable.icon),
                        contentDescription = stringResource(id = R.string.wrapped_logo_content_description),
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .border(2.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f), Color(0xFF6C5CE7))), CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Rift Title with Layered Glow Effect
            val manager = com.rift.music.feature.wrapped.LocalWrappedManager.current
            val periodTitle = manager?.state?.collectAsState()?.value?.periodTitle ?: stringResource(id = R.string.wrapped_intro_title)

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = TITLE_DELAY)) +
                        slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = TITLE_DELAY))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box {
                        val baseStyle = TextStyle(
                            fontFamily = bbhBartle,
                            textAlign = TextAlign.Center,
                            letterSpacing = 2.sp,
                            fontSize = 46.sp
                        )
                        AutoResizingText(
                            text = periodTitle,
                            style = baseStyle.copy(color = Color(0xFF3B1E78).copy(alpha = 0.6f)),
                            modifier = Modifier.padding(start = 2.dp, top = 2.dp)
                        )
                        AutoResizingText(
                            text = periodTitle,
                            style = baseStyle.copy(color = Color(0xFF7F5AF0)),
                            modifier = Modifier.padding(start = 1.dp, top = 1.dp)
                        )
                        AutoResizingText(
                            text = periodTitle,
                            style = baseStyle.copy(color = Color.White)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Year Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Brush.horizontalGradient(listOf(Color(0xFF6C5CE7), Color(0xFFE84393))))
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${WrappedConstants.YEAR} REPLAY",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Subtitle
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = SUBTITLE_DELAY)) +
                        slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = SUBTITLE_DELAY))
            ) {
                Text(
                    text = stringResource(id = R.string.wrapped_intro_subtitle),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Highlight pills
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = CHIPS_DELAY)) +
                        slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = CHIPS_DELAY))
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val chips = listOf("Top Songs", "Top Artists", "Top Albums", "Total Minutes")
                    chips.forEach { chip ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.12f))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = chip,
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // Action Button at the bottom
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = BUTTON_DELAY)) +
                    slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = BUTTON_DELAY)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = BOTTOM_PADDING, start = 32.dp, end = 32.dp)
        ) {
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(id = R.string.wrapped_intro_button),
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(id = R.drawable.arrow_forward),
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
