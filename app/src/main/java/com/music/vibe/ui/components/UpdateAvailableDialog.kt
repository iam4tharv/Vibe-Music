package com.music.vibe.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Upgrade
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.music.vibe.R
import com.music.vibe.data.AppUpdateChecker
import com.music.vibe.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** UIAlertController's own metrics: fixed narrow width, 14pt corner, 44pt rows. */
internal val ALERT_WIDTH = 270.dp
internal val ALERT_CORNER = 14.dp
internal val ACTION_HEIGHT = 44.dp

/**
 * The dim behind the alert. Flat on purpose — the glass is the card, and
 * blurring the wallpaper *behind* it too leaves nothing for the card to be
 * frosted against, which is what made this read as a grey box before.
 */
internal val SCRIM_COLOR = Color.Black.copy(alpha = 0.28f)

private val DOWNLOAD_ROW_HEIGHT = 5.dp

/** How much of the card's height the release notes are allowed to fill before scrolling. */
private val NOTES_MAX_HEIGHT = 220.dp

/**
 * Light-black Liquid Glass software update modal with smooth scale + fade
 * entrance/exit transitions, live download progress bar, and Markdown release notes.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun UpdateAvailableDialog(
    version: String,
    notes: String?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleasePage: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val state by AppUpdateChecker.download.collectAsStateWithLifecycle()
    val glassSupported = isGlassSupported()
    val isLightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    val innerGroupFill = if (isLightSurface) {
        Color.Black.copy(alpha = 0.05f)
    } else {
        Color(0xFF282830).copy(alpha = 0.88f)
    }
    val innerGroupBorder = if (isLightSurface) {
        Color.Black.copy(alpha = 0.08f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }

    if (visible) {
        BackHandler(onBack = onDismiss)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.60f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onDismiss,
                    ),
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = scaleIn(
                initialScale = 0.94f,
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) + fadeIn(tween(220)),
            exit = scaleOut(
                targetScale = 0.94f,
                animationSpec = tween(160),
            ) + fadeOut(tween(160)),
        ) {
            if (version.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 340.dp)
                        .fillMaxWidth()
                        .clip(POPUP_CORNER_SHAPE)
                        .then(
                            when {
                                glassSupported && !reduceDynamicBlur -> {
                                    Modifier.popupLiquidGlass(
                                        shape = POPUP_CORNER_SHAPE,
                                        isLightSurface = isLightSurface,
                                    )
                                }
                                !reduceDynamicBlur -> {
                                    Modifier
                                        .optimizedHazeEffect(
                                            state = hazeState,
                                            style = HazeMaterials.regular(
                                                if (isLightSurface) Color(0xFFF2F2F7) else Color(0xFF1C1C22),
                                            ),
                                        )
                                        .background(
                                            if (isLightSurface) {
                                                Color(0xFFF2F2F7).copy(alpha = 0.90f)
                                            } else {
                                                Color(0xFF1C1C22).copy(alpha = 0.90f)
                                            },
                                        )
                                        .border(
                                            width = 0.75.dp,
                                            color = if (isLightSurface) {
                                                Color.Black.copy(alpha = 0.10f)
                                            } else {
                                                Color.White.copy(alpha = 0.16f)
                                            },
                                            shape = POPUP_CORNER_SHAPE,
                                        )
                                }
                                else -> {
                                    Modifier
                                        .background(
                                            if (isLightSurface) Color(0xFFF2F2F7) else Color(0xFF1C1C22),
                                        )
                                        .border(
                                            width = 0.75.dp,
                                            color = if (isLightSurface) {
                                                Color.Black.copy(alpha = 0.10f)
                                            } else {
                                                Color.White.copy(alpha = 0.14f)
                                            },
                                            shape = POPUP_CORNER_SHAPE,
                                        )
                                }
                            },
                        )
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        )
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                shape = CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Upgrade,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.software_update),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = when (state) {
                            is AppUpdateChecker.DownloadState.Downloading ->
                                stringResource(R.string.update_downloading_body, version)
                            is AppUpdateChecker.DownloadState.Ready ->
                                stringResource(R.string.update_ready_body, version)
                            is AppUpdateChecker.DownloadState.Failed ->
                                stringResource(R.string.update_failed_body, version)
                            else ->
                                stringResource(R.string.update_available_body, version)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    val downloading = state as? AppUpdateChecker.DownloadState.Downloading
                    if (downloading != null || state is AppUpdateChecker.DownloadState.Failed) {
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(DOWNLOAD_ROW_HEIGHT)
                                .clip(RoundedCornerShape(DOWNLOAD_ROW_HEIGHT / 2))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
                        ) {
                            if (downloading != null && downloading.fraction > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(downloading.fraction)
                                        .height(DOWNLOAD_ROW_HEIGHT)
                                        .clip(RoundedCornerShape(DOWNLOAD_ROW_HEIGHT / 2))
                                        .background(MaterialTheme.colorScheme.primary),
                                )
                            }
                        }
                        if (downloading != null) {
                            Text(
                                text = "${(downloading.fraction * 100).toInt()}%",
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }

                    if (state is AppUpdateChecker.DownloadState.Failed) {
                        Text(
                            text = (state as AppUpdateChecker.DownloadState.Failed).message,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                    }

                    if (!notes.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(GROUP_CORNER_SHAPE)
                                .background(innerGroupFill)
                                .border(0.5.dp, innerGroupBorder, GROUP_CORNER_SHAPE)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.whats_new),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .heightIn(max = NOTES_MAX_HEIGHT)
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                                    RichText(style = RichTextStyle.Default) {
                                        Markdown(notes)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    val primaryContainerColor = if (isLightSurface) Color(0xFF141416) else Color.White
                    val primaryContentColor = if (isLightSurface) Color.White else Color.Black

                    when (state) {
                        is AppUpdateChecker.DownloadState.Downloading -> {
                            TextButton(
                                onClick = onCancelDownload,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.cancel),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        is AppUpdateChecker.DownloadState.Ready -> {
                            Button(
                                onClick = onInstall,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryContainerColor,
                                    contentColor = primaryContentColor,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.install_now),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.later),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is AppUpdateChecker.DownloadState.Failed -> {
                            Button(
                                onClick = onDownload,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryContainerColor,
                                    contentColor = primaryContentColor,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.try_again),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = onOpenReleasePage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.open_releases_page),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            Button(
                                onClick = onDownload,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryContainerColor,
                                    contentColor = primaryContentColor,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.download_now),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            TextButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(42.dp),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.remind_me_later),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-bleed action row. Tinted rather than filled, so the two read as equals
 * in weight and only the font differentiates the default action — the alert's
 * whole point is that neither choice is a trap.
 *
 * [destructive] is the one exception to that: an action that deletes something
 * is red in this lineage, because the point there *is* to break the symmetry.
 * It stays a plain row rather than a filled button, so it reads as a warning
 * and not as the thing to press.
 */
@Composable
internal fun AlertAction(
    label: String,
    emphasised: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(ACTION_HEIGHT)
            // iOS washes the whole row instead of drawing a ripple inside it.
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = 17.sp,
                fontWeight = if (emphasised) FontWeight.W600 else FontWeight.W400,
            ),
            color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                .copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/** Hairline separator — [HorizontalDivider][androidx.compose.material3.HorizontalDivider]'s 1dp reads as a bar at this scale. */
@Composable
internal fun AlertRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
    )
}
