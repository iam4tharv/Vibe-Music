package com.music.echo.ui.component.shimmer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import com.valentinilk.shimmer.shimmer
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.material3.Icon

import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext

@Composable
fun ShimmeringAsyncImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    colorFilter: ColorFilter? = null,
    alignment: Alignment = Alignment.Center,
    errorPainter: Painter? = null
) {
    val context = LocalContext.current
    val imageRequest = if (model is ImageRequest) {
        model
    } else {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(300)
            .build()
    }

    SubcomposeAsyncImage(
        model = imageRequest,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        colorFilter = colorFilter,
        alignment = alignment,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .shimmer()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        error = {
            if (errorPainter != null) {
                Image(
                    painter = errorPainter,
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Inside,
                    colorFilter = colorFilter,
                    alignment = alignment
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
        }
    )
}
