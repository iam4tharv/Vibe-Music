package com.music.echo.ui.vibee

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.echo.LocalPlayerConnection
import com.music.echo.extensions.bounceClick
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private data class QuickPrompt(
    val label: String,
    val prompt: String,
    val icon: ImageVector,
    val accentColor: Color
)

@Composable
fun VibeeOverlay(
    manager: VibeeManager,
    modifier: Modifier = Modifier,
    artworkAccentColor: Color? = null
) {
    val state by manager.state.collectAsState()
    val recognizedText by manager.recognizedText.collectAsState()
    val spokenResponse by manager.spokenResponse.collectAsState()
    val rms by manager.rmsFlow.collectAsState()
    val activeTool by manager.activeToolFeedback.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            manager.startListening()
        }
    }
    val playerConnection = LocalPlayerConnection.current
    val mediaMetadataFlow = remember(playerConnection) { playerConnection?.mediaMetadata }
    val mediaMetadata by (mediaMetadataFlow ?: kotlinx.coroutines.flow.MutableStateFlow(null)).collectAsState()

    var textInputMode by remember { mutableStateOf(false) }
    var textPromptInput by remember { mutableStateOf("") }

    // Dynamic state-based flagship color palettes
    val primaryAccent = artworkAccentColor ?: MaterialTheme.colorScheme.primary
    val neonCyan = Color(0xFF00E5FF)
    val cosmicPurple = Color(0xFFA855F7)
    val electricCoral = Color(0xFFFF5252)
    val emeraldGreen = Color(0xFF10B981)
    val amberGold = Color(0xFFFFB300)

    val currentThemeColor by animateColorAsState(
        targetValue = when (state) {
            VibeeState.LISTENING -> neonCyan
            VibeeState.THINKING -> cosmicPurple
            VibeeState.SPEAKING -> electricCoral
            else -> primaryAccent
        },
        animationSpec = tween(500),
        label = "themeColor"
    )

    val secondaryThemeColor by animateColorAsState(
        targetValue = when (state) {
            VibeeState.LISTENING -> emeraldGreen
            VibeeState.THINKING -> amberGold
            VibeeState.SPEAKING -> Color(0xFFFF4081)
            else -> MaterialTheme.colorScheme.tertiary
        },
        animationSpec = tween(500),
        label = "secondaryThemeColor"
    )

    // Smooth physics-based audio reactivity
    val animatedRms by animateFloatAsState(
        targetValue = rms.coerceIn(0f, 1.8f),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 450f),
        label = "animatedRms"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "vibeeInfinite")

    // Slow ambient rotation for aura
    val slowRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slowRotation"
    )

    // Fast rotation for thinking reactor
    val fastRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "fastRotation"
    )

    // Gentle breathing pulse
    val breathingPulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingPulse"
    )

    // Dynamic wave phase
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val quickPrompts = remember {
        listOf(
            QuickPrompt("Next Track", "play next song", Icons.Rounded.SkipNext, Color(0xFF60A5FA)),
            QuickPrompt("Keep Vibe", "play songs with similar vibe to current track", Icons.Rounded.GraphicEq, Color(0xFF34D399)),
            QuickPrompt("Bass Boost", "set equalizer to bass boost", Icons.Rounded.Tune, Color(0xFFF472B6)),
            QuickPrompt("Favorite", "like current song", Icons.Rounded.Favorite, Color(0xFFFB7185)),
            QuickPrompt("Fresh Mix", "play a fresh mix of recommendations", Icons.Rounded.AutoAwesome, Color(0xFFA78BFA)),
            QuickPrompt("Sleep 30m", "set sleep timer for 30 minutes", Icons.Rounded.Tune, Color(0xFFFBBF24)),
            QuickPrompt("Vocal Boost", "boost vocals in equalizer", Icons.Rounded.Tune, Color(0xFF38BDF8))
        )
    }

    AnimatedVisibility(
        visible = state != VibeeState.IDLE,
        enter = fadeIn(tween(350)) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
        ),
        exit = fadeOut(tween(250)) + slideOutVertically(
            targetOffsetY = { it / 4 },
            animationSpec = tween(250)
        ),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE60A0A10))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusManager.clearFocus()
                    manager.close()
                },
            contentAlignment = Alignment.Center
        ) {
            // LAYER 1: Ambient Fluid Chromatic Backdrop Glow
            Box(
                modifier = Modifier
                    .size((340 + animatedRms * 180).dp)
                    .rotate(slowRotation)
                    .blur(90.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                currentThemeColor.copy(alpha = 0.50f + animatedRms * 0.35f),
                                secondaryThemeColor.copy(alpha = 0.35f + animatedRms * 0.20f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // LAYER 2: Secondary Offset Accent Orb for Depth
            Box(
                modifier = Modifier
                    .size((220 + animatedRms * 120).dp)
                    .rotate(-slowRotation * 0.8f)
                    .blur(75.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                secondaryThemeColor.copy(alpha = 0.40f),
                                currentThemeColor.copy(alpha = 0.20f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // MAIN CONTENT COLUMN
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // TOP BAR: Status, Song Pill, Close Action
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Status Badge with Pulsing LED
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            currentThemeColor.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp), spotColor = currentThemeColor)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            val statusLedScale by infiniteTransition.animateFloat(
                                initialValue = 0.8f,
                                targetValue = 1.3f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(900, easing = FastOutSlowInEasing),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "statusLed"
                            )

                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .scale(if (state == VibeeState.LISTENING || state == VibeeState.SPEAKING) statusLedScale else 1f)
                                    .clip(CircleShape)
                                    .background(currentThemeColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (state) {
                                    VibeeState.LISTENING -> "LISTENING..."
                                    VibeeState.THINKING -> "PROCESSING..."
                                    VibeeState.SPEAKING -> "SPEAKING"
                                    else -> "VIBEE AI"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                letterSpacing = 1.6.sp
                            )
                        }
                    }

                    // Context Track Pill (if media is playing)
                    if (mediaMetadata != null) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color.White.copy(alpha = 0.06f),
                            border = androidx.compose.foundation.BorderStroke(
                                0.7.dp,
                                Color.White.copy(alpha = 0.15f)
                            ),
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = currentThemeColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "${mediaMetadata?.title} • ${mediaMetadata?.artists?.firstOrNull()?.name.orEmpty()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White.copy(alpha = 0.8f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            manager.close()
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.10f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                            .bounceClick()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close Vibee",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // CENTER SECTION: Flagship Fluid Audio Reactor Orb & Soundwave Canvas
                Box(
                    modifier = Modifier
                        .size(250.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (state == VibeeState.LISTENING) {
                                manager.close()
                            } else {
                                if (ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    manager.startListening()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    // MULTI-FREQUENCY FLUID HARMONIC WAVEFORM CANVAS
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .rotate(if (state == VibeeState.THINKING) fastRotation else 0f)
                    ) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val baseRadius = size.minDimension / 2.7f
                        val pointsCount = 64
                        val step = (2 * Math.PI) / pointsCount

                        // Wave 1: Primary Chromatic Outer Wave
                        val waveBrush = Brush.linearGradient(
                            listOf(
                                currentThemeColor.copy(alpha = 0.85f),
                                secondaryThemeColor.copy(alpha = 0.70f)
                            )
                        )
                        for (i in 0 until pointsCount) {
                            val angle = i * step + wavePhase
                            val ampMod = (sin(angle * 4.0).toFloat() * (animatedRms * 28f + 5f)) +
                                    (cos(angle * 2.0).toFloat() * (animatedRms * 12f))
                            val r = baseRadius + ampMod
                            val x = center.x + r * cos(angle).toFloat()
                            val y = center.y + r * sin(angle).toFloat()

                            val nextAngle = (i + 1) * step + wavePhase
                            val nextAmp = (sin(nextAngle * 4.0).toFloat() * (animatedRms * 28f + 5f)) +
                                    (cos(nextAngle * 2.0).toFloat() * (animatedRms * 12f))
                            val nextR = baseRadius + nextAmp
                            val nextX = center.x + nextR * cos(nextAngle).toFloat()
                            val nextY = center.y + nextR * sin(nextAngle).toFloat()

                            drawLine(
                                brush = waveBrush,
                                start = Offset(x, y),
                                end = Offset(nextX, nextY),
                                strokeWidth = 3.5f + animatedRms * 4f,
                                cap = StrokeCap.Round
                            )
                        }

                        // Wave 2: Inner Harmonic Sweep Ring
                        drawCircle(
                            brush = Brush.sweepGradient(
                                listOf(
                                    currentThemeColor.copy(alpha = 0.75f),
                                    secondaryThemeColor.copy(alpha = 0.6f),
                                    Color(0xFFFFFFFF).copy(alpha = 0.8f),
                                    currentThemeColor.copy(alpha = 0.75f)
                                )
                            ),
                            radius = (baseRadius - 18f + (animatedRms * 14f)).coerceAtLeast(15f),
                            style = Stroke(width = 2.5f + animatedRms * 2f)
                        )

                        // Wave 3: Subtle Sub-Bass Glow Orbit
                        drawCircle(
                            color = currentThemeColor.copy(alpha = 0.18f + animatedRms * 0.25f),
                            radius = (baseRadius + 22f + (animatedRms * 20f)).coerceAtLeast(20f),
                            style = Stroke(width = 1.2f)
                        )
                    }

                    // GLOWING CORE REACTOR ORB
                    val coreScale = when (state) {
                        VibeeState.LISTENING -> (breathingPulse + animatedRms * 0.5f).coerceIn(0.85f, 1.7f)
                        VibeeState.SPEAKING -> (1.0f + animatedRms * 0.6f).coerceIn(0.9f, 1.8f)
                        VibeeState.THINKING -> breathingPulse
                        else -> 1f
                    }

                    Box(
                        modifier = Modifier
                            .size(118.dp)
                            .scale(coreScale)
                            .shadow(24.dp, CircleShape, spotColor = currentThemeColor)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        currentThemeColor.copy(alpha = 0.95f),
                                        secondaryThemeColor.copy(alpha = 0.85f),
                                        Color(0xFF130924)
                                    )
                                )
                            )
                            .border(
                                width = 2.dp,
                                brush = Brush.linearGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.8f),
                                        currentThemeColor.copy(alpha = 0.4f),
                                        secondaryThemeColor.copy(alpha = 0.6f)
                                    )
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when (state) {
                            VibeeState.LISTENING -> {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = "Listening",
                                    tint = Color.White,
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                            VibeeState.THINKING -> {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = "Thinking",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .rotate(fastRotation * 1.5f)
                                )
                            }
                            VibeeState.SPEAKING -> {
                                Icon(
                                    imageVector = Icons.Rounded.GraphicEq,
                                    contentDescription = "Speaking",
                                    tint = Color.White,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Rounded.Mic,
                                    contentDescription = "Idle",
                                    tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }
                    }
                }

                // DIALOGUE & TRANSCRIPTION BUBBLE
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Active Capability / Tool Badge
                    if (!activeTool.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = currentThemeColor.copy(alpha = 0.20f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, currentThemeColor.copy(alpha = 0.6f)),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = currentThemeColor,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = activeTool ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }

                    // Frosted Glass Dialogue Card
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.08f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.22f),
                                        currentThemeColor.copy(alpha = 0.15f),
                                        Color.White.copy(alpha = 0.04f)
                                    )
                                ),
                                RoundedCornerShape(24.dp)
                            )
                            .shadow(16.dp, RoundedCornerShape(24.dp), ambientColor = Color.Black)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 18.dp)
                        ) {
                            val displayText = when (state) {
                                VibeeState.LISTENING -> recognizedText.ifEmpty { "Listening for your command..." }
                                VibeeState.THINKING -> "Tuning the vibe for you..."
                                VibeeState.SPEAKING -> spokenResponse.ifEmpty { "Playing now..." }
                                else -> "Tap the mic or say \"Hey Vibee\""
                            }

                            Text(
                                text = displayText,
                                color = Color.White,
                                fontSize = if (displayText.length > 90) 16.sp else if (displayText.length > 40) 18.sp else 21.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 26.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Live Dynamic Equalizer Bars in Dialogue Box
                            if (state == VibeeState.LISTENING || state == VibeeState.SPEAKING) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val barCount = 18
                                    for (b in 0 until barCount) {
                                        val waveOffset = sin(wavePhase + b * 0.45).toFloat()
                                        val barHeight = ((animatedRms * 16f + 4f) * (0.4f + 0.6f * ((waveOffset + 1f) / 2f)))
                                            .coerceIn(4f, 26f)

                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(barHeight.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    Brush.verticalGradient(
                                                        listOf(currentThemeColor, secondaryThemeColor)
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // BOTTOM CONTROLS: Quick Prompt Pills & Text Input Option
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!textInputMode) {
                        // Quick Action Suggestion Pills Carousel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            quickPrompts.forEach { item ->
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        item.accentColor.copy(alpha = 0.35f)
                                    ),
                                    modifier = Modifier
                                        .bounceClick()
                                        .clickable {
                                            scope.launch {
                                                manager.submitPrompt(item.prompt)
                                            }
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                    ) {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = null,
                                            tint = item.accentColor,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.label,
                                            color = Color.White,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Switch to Keyboard Input Button
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { textInputMode = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = "Type with keyboard",
                                tint = Color.White.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Type a command instead",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        // Interactive Text Input Mode Bar
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                        ) {
                            OutlinedTextField(
                                value = textPromptInput,
                                onValueChange = { textPromptInput = it },
                                placeholder = {
                                    Text(
                                        "Ask Vibee (e.g. play energetic jazz)...",
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 14.sp
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(22.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.White.copy(alpha = 0.10f),
                                    unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = currentThemeColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    cursorColor = currentThemeColor
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        if (textPromptInput.isNotBlank()) {
                                            focusManager.clearFocus()
                                            val prompt = textPromptInput
                                            textPromptInput = ""
                                            textInputMode = false
                                            manager.submitPrompt(prompt)
                                        }
                                    }
                                ),
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = {
                                    if (textPromptInput.isNotBlank()) {
                                        focusManager.clearFocus()
                                        val prompt = textPromptInput
                                        textPromptInput = ""
                                        textInputMode = false
                                        manager.submitPrompt(prompt)
                                    } else {
                                        textInputMode = false
                                    }
                                },
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(currentThemeColor)
                                    .bounceClick()
                            ) {
                                Icon(
                                    imageVector = if (textPromptInput.isNotBlank()) Icons.Rounded.Send else Icons.Rounded.Mic,
                                    contentDescription = "Send prompt",
                                    tint = Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
