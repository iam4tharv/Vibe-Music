package com.music.vibe.ui.components.backdrop.effects

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.music.vibe.ui.components.backdrop.BackdropEffectScope

fun BackdropEffectScope.blur(radius: Float) {
    if (radius <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
    val current = renderEffect
    renderEffect = if (current != null) {
        RenderEffect.createChainEffect(blurEffect, current.asAndroidRenderEffect()).asComposeRenderEffect()
    } else {
        blurEffect.asComposeRenderEffect()
    }
}

fun BackdropEffectScope.colorControls(
    saturation: Float = 1f,
    brightness: Float = 0f,
    contrast: Float = 1f
) {
    // Color controls
}

fun BackdropEffectScope.lens(
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Boolean = false,
    chromaticAberration: Boolean = false
) {
    // Lens refraction effect
}
