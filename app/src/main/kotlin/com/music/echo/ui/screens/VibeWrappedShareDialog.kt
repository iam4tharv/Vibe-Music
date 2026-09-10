/**
 * Vibe Music Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.echo.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.echo.R
import com.music.echo.utils.VibeCardTheme
import com.music.echo.utils.VibeWrappedData
import com.music.echo.utils.VibeWrappedImageGenerator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeWrappedShareDialog(
    data: VibeWrappedData,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var selectedTheme by remember { mutableStateOf(VibeCardTheme.AURORA) }
    var generatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isGenerating by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(selectedTheme, data) {
        isGenerating = true
        generatedBitmap = VibeWrappedImageGenerator.generateWrappedBitmap(context, data, selectedTheme)
        isGenerating = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier.testTag("vibe_wrapped_share_dialog")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.share_wrapped),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.share_wrapped_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Theme Selection Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                VibeCardTheme.entries.forEach { theme ->
                    val isSelected = theme == selectedTheme
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedTheme = theme },
                        label = {
                            Text(
                                text = when (theme) {
                                    VibeCardTheme.AURORA -> stringResource(R.string.theme_aurora)
                                    VibeCardTheme.SUNSET -> stringResource(R.string.theme_sunset)
                                    VibeCardTheme.EMERALD -> stringResource(R.string.theme_emerald)
                                },
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Live Image Preview Container
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(9f / 16f)
                    .shadow(12.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp)),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                AnimatedContent(
                    targetState = generatedBitmap,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "PreviewTransition"
                ) { bitmap ->
                    if (bitmap != null && !isGenerating) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Vibe Wrapped Summary Card",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(9f / 16f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.generating_wrapped_image),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons (Share & Save)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val bitmap = generatedBitmap ?: return@OutlinedButton
                        isSaving = true
                        coroutineScope.launch {
                            val savedUri = VibeWrappedImageGenerator.saveBitmapToPictures(context, bitmap)
                            isSaving = false
                            if (savedUri != null) {
                                Toast.makeText(context, context.getString(R.string.image_saved), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = generatedBitmap != null && !isGenerating && !isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("save_wrapped_image_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.download),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.save_to_gallery),
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = {
                        val bitmap = generatedBitmap ?: return@Button
                        coroutineScope.launch {
                            try {
                                val uri = VibeWrappedImageGenerator.saveBitmapToCache(context, bitmap)
                                VibeWrappedImageGenerator.shareWrappedImage(context, uri, data.periodLabel)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = generatedBitmap != null && !isGenerating,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(52.dp)
                        .testTag("share_wrapped_image_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        painter = painterResource(R.drawable.share),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.share_image),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
