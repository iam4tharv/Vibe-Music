package com.music.vibe.ui.components.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.music.vibe.ui.components.backdrop.Backdrop

@Composable
fun rememberLayerBackdrop(
    onDraw: (DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit)? = null
): LayerBackdrop {
    return remember(onDraw) { LayerBackdrop(onDraw) }
}

@Composable
fun rememberLayerBackdrop(
    onDraw: ContentDrawScope.() -> Unit
): LayerBackdrop {
    val block: DrawScope.(DrawScope.() -> Unit) -> Unit = { drawBackdrop ->
        if (this is ContentDrawScope) {
            onDraw()
        } else {
            drawBackdrop()
        }
    }
    return remember(onDraw) { LayerBackdrop(block) }
}

@Stable
class LayerBackdrop(
    val onDraw: (DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit)? = null
) : Backdrop {
    var graphicsLayer: GraphicsLayer? = null
    var layerCoordinates: LayoutCoordinates? = null

    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val layer = graphicsLayer ?: return
        val layerCoords = layerCoordinates
        val targetCoords = coordinates
        if (layerCoords != null && targetCoords != null && layerCoords.isAttached && targetCoords.isAttached) {
            val offset = runCatching {
                layerCoords.localPositionOf(targetCoords, Offset.Zero)
            }.getOrDefault(Offset.Zero)
            translate(-offset.x, -offset.y) {
                drawLayer(layer)
            }
        } else {
            drawLayer(layer)
        }
    }
}

fun Modifier.layerBackdrop(layerBackdrop: LayerBackdrop): Modifier =
    this.then(LayerBackdropElement(layerBackdrop))

private data class LayerBackdropElement(
    val layerBackdrop: LayerBackdrop
) : ModifierNodeElement<LayerBackdropNode>() {
    override fun create(): LayerBackdropNode = LayerBackdropNode(layerBackdrop)
    override fun update(node: LayerBackdropNode) {
        node.layerBackdrop = layerBackdrop
    }
}

private class LayerBackdropNode(
    var layerBackdrop: LayerBackdrop
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    override fun onAttach() {
        super.onAttach()
        val context = requireGraphicsContext()
        val layer = context.createGraphicsLayer()
        layerBackdrop.graphicsLayer = layer
    }

    override fun onDetach() {
        layerBackdrop.graphicsLayer?.let { layer ->
            requireGraphicsContext().releaseGraphicsLayer(layer)
        }
        layerBackdrop.graphicsLayer = null
        layerBackdrop.layerCoordinates = null
        super.onDetach()
    }

    override fun ContentDrawScope.draw() {
        val layer = layerBackdrop.graphicsLayer
        if (layer != null && size.width > 0f && size.height > 0f) {
            runCatching {
                val density = requireDensity()
                val layoutDirection = requireLayoutDirection()
                val recordSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
                layer.record(density = density, layoutDirection = layoutDirection, size = recordSize) {
                    val onDraw = layerBackdrop.onDraw
                    if (onDraw != null) {
                        onDraw { this@draw.drawContent() }
                    } else {
                        this@draw.drawContent()
                    }
                }
                drawLayer(layer)
            }.onFailure {
                drawContent()
            }
        } else {
            drawContent()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            layerBackdrop.layerCoordinates = coordinates
        }
    }
}
