package com.rift.music.feature.wrapped.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rift.music.R
import com.rift.music.feature.wrapped.LocalWrappedManager
import com.rift.music.feature.wrapped.WrappedConstants
import com.rift.music.feature.wrapped.components.AnimatedBackground
import com.rift.music.feature.wrapped.components.ShapeType
import kotlinx.coroutines.delay

@Composable
fun ConclusionPage(onClose: () -> Unit) {
    val manager = LocalWrappedManager.current
    val state by manager.state.collectAsState()

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(150)
        visible = true
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowScale"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedBackground(elementCount = 28, shapeTypes = listOf(ShapeType.Circle, ShapeType.Line))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(800)) + slideInVertically(tween(800))
            ) {
                // App Logo with glowing ring
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(96.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(90.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF6C5CE7).copy(alpha = 0.5f),
                                        Color(0xFFE84393).copy(alpha = 0.25f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    Image(
                        painter = painterResource(id = R.drawable.icon),
                        contentDescription = stringResource(R.string.wrapped_logo_content_description),
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .border(2.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f), Color(0xFF6C5CE7))), CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(800, delayMillis = 200))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.wrapped_end_thanks),
                        style = TextStyle(
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.wrapped_special_thanks),
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Summary Recap Card
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(800, delayMillis = 350)) + slideInVertically(tween(800, delayMillis = 350))
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp)),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "RIFT ${WrappedConstants.YEAR} RECAP",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6C5CE7),
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = "${state.totalMinutes} min listened",
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = 0.75f)
                            )
                        }

                        // Top Song Row
                        val topSong = state.topSongs.firstOrNull()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.music_note),
                                    contentDescription = null,
                                    tint = Color(0xFF00CEC9),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Top Song",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = topSong?.title ?: "No plays yet",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Top Artist Row
                        val topArtist = state.topArtists.firstOrNull()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.person),
                                    contentDescription = null,
                                    tint = Color(0xFFE84393),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Top Artist",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = topArtist?.title ?: "No plays yet",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Unique Songs
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.1f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.queue_music),
                                    contentDescription = null,
                                    tint = Color(0xFFFDCB6E),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Unique Songs",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = "${state.uniqueSongCount} tracks played",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(800, delayMillis = 500))
            ) {
                Button(
                    onClick = onClose,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(50.dp)
                ) {
                    Text(
                        text = stringResource(R.string.wrapped_close),
                        style = TextStyle(
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    )
                }
            }
        }
    }
}
