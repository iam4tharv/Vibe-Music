package com.music.vibe.ui.components.backdrop.internal

import android.graphics.BlurMaskFilter
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.unit.IntSize

internal fun Canvas.clipOutline(outline: Outline, clipPath: Path?) {
    when (outline) {
        is Outline.Rectangle -> clipRect(outline.rect)
        is Outline.Rounded -> {
            val path = clipPath ?: Path()
            path.reset()
            path.addRoundRect(outline.roundRect)
            clipPath(path)
        }
        is Outline.Generic -> clipPath(outline.path)
    }
}

internal fun recordLayer(
    node: DelegatableNode,
    layer: GraphicsLayer,
    size: IntSize = IntSize.Zero,
    block: DrawScope.() -> Unit
) {
    val density = node.requireDensity()
    val layoutDirection = node.requireLayoutDirection()
    val recordSize = if (size != IntSize.Zero) size else IntSize(1, 1)
    layer.record(density = density, layoutDirection = layoutDirection, size = recordSize, block = block)
}

internal fun Paint.blur(radius: Float) {
    if (radius > 0f) {
        asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
    } else {
        asFrameworkPaint().maskFilter = null
    }
}

