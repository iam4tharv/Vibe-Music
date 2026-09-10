package com.music.echo.extensions

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

fun Modifier.bounceClick(
    scaleDown: Float = 0.95f,
) = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.65f,
            stiffness = 500f
        ),
        label = "bounceClickScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    waitForUpOrCancellation()
                    isPressed = false
                }
            }
        }
}

fun Modifier.SwipeGesture(
    enabled: Boolean = true,
    threshold: Float = 60f,
    onSwipeRight: () -> Unit = {},
    onSwipeLeft: () -> Unit = {}
): Modifier = composed {
    if (!enabled) return@composed this

    var totalDragX by remember { mutableStateOf(0f) }
    var triggered by remember { mutableStateOf(false) }

    this.pointerInput(Unit) {
        detectDragGestures(
            onDragStart = {
                totalDragX = 0f
                triggered = false
            },
            onDragEnd = {
                if (!triggered) {
                    if (totalDragX > threshold) {
                        onSwipeRight()
                    } else if (totalDragX < -threshold) {
                        onSwipeLeft()
                    }
                }
                totalDragX = 0f
                triggered = false
            },
            onDragCancel = {
                totalDragX = 0f
                triggered = false
            },
            onDrag = { change, dragAmount ->
                val (x, y) = dragAmount
                if (abs(x) > abs(y)) {
                    totalDragX += x
                    if (!triggered) {
                        if (totalDragX > threshold * 1.5f) {
                            triggered = true
                            onSwipeRight()
                            change.consume()
                        } else if (totalDragX < -threshold * 1.5f) {
                            triggered = true
                            onSwipeLeft()
                            change.consume()
                        }
                    }
                }
            }
        )
    }
}
