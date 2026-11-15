package com.music.vibe.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.vibe.R
import com.music.vibe.data.AppLaunchTracker
import com.music.vibe.data.AppUpdateChecker
import com.music.vibe.data.settings.AppSettings
import com.music.vibe.data.stats.Backup
import com.music.vibe.ui.components.backdrop.BackdropEffectScope
import com.music.vibe.ui.components.backdrop.drawBackdrop
import com.music.vibe.ui.components.backdrop.effects.blur
import com.music.vibe.ui.components.backdrop.effects.colorControls
import com.music.vibe.ui.components.backdrop.effects.lens
import com.music.vibe.ui.components.backdrop.highlight.Highlight
import com.music.vibe.ui.components.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** Exact same UPI URI as the About section in SettingsSheet. */
internal const val URL_DONATE_UPI =
    "upi://pay?pa=dev.atharv@fam&pn=Atharv&cu=INR&tn=Donation%20to%20Vibe%20Music"
private const val URL_COMMUNITY_TELEGRAM = "https://t.me/vibemusicupdates"
private const val URL_STAR_GITHUB = "https://github.com/iam4tharv/vibe-music"

internal val POPUP_CORNER_SHAPE = RoundedCornerShape(28.dp)
internal val GROUP_CORNER_SHAPE = RoundedCornerShape(18.dp)

private const val POPUP_GLASS_SCALE = 0.33f
private const val POPUP_BLUR_DP = 28f
private const val POPUP_LENS_DP = 20f

/**
 * Light-black Liquid Glass surface modifier tailored for modal popup cards:
 * combines deep frosted backdrop blur + lens refraction + specular rim highlight
 * with a rich light-black glass body (`#1C1C22` at 90% opacity) so underlying
 * artwork/text never bleeds sharply through the dialog.
 */
@Composable
internal fun Modifier.popupLiquidGlass(
    shape: CornerBasedShape,
    isLightSurface: Boolean,
): Modifier {
    val baseGlassColor = if (isLightSurface) {
        Color(0xFFF2F2F7).copy(alpha = 0.92f)
    } else {
        Color(0xFF1C1C22).copy(alpha = 0.90f)
    }
    val topSheenColor = if (isLightSurface) {
        Color.White.copy(alpha = 0.45f)
    } else {
        Color.White.copy(alpha = 0.08f)
    }
    if (!isGlassSupported()) {
        return background(baseGlassColor, shape)
    }
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) {
        return background(
            color = if (isLightSurface) Color(0xFFF2F2F7) else Color(0xFF1C1C22),
            shape = shape,
        )
    }

    val backdrop = LocalAppBackdrop.current
    val density = LocalDensity.current
    val blurPx = with(density) { POPUP_BLUR_DP.dp.toPx() } * POPUP_GLASS_SCALE
    val lensPx = with(density) { POPUP_LENS_DP.dp.toPx() } * POPUP_GLASS_SCALE
    val shapeLambda = remember<() -> Shape>(shape) { { shape } }
    val effectsLambda = remember<BackdropEffectScope.() -> Unit>(blurPx, lensPx) {
        {
            colorControls(saturation = 1.25f)
            blur(blurPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens(
                    refractionHeight = lensPx,
                    refractionAmount = lensPx,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        }
    }
    val onDrawSurfaceLambda = remember<DrawScope.() -> Unit>(baseGlassColor, topSheenColor) {
        {
            drawRect(color = baseGlassColor, size = size)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(topSheenColor, Color.Transparent),
                    startY = 0f,
                    endY = size.height * 0.45f,
                ),
                size = size,
            )
        }
    }

    return drawBackdrop(
        backdrop = backdrop,
        shape = shapeLambda,
        effects = effectsLambda,
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = onDrawSurfaceLambda,
        backdropScale = POPUP_GLASS_SCALE,
    )
}

/**
 * Light-black Liquid Glass modal overlay presented on first install/reinstall, after an update,
 * or when previewed on demand from Settings -> About.
 *
 * Includes smooth entrance/exit scale (`0.94f -> 1.0f`) + fade animations and fetches live
 * release notes from GitHub Releases inline (falling back to `R.array.release_highlights` if offline).
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ReleasePromptDialog(
    promptState: AppLaunchTracker.LaunchPromptState?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var retainedPrompt by remember { mutableStateOf(promptState) }
    if (promptState != null) {
        retainedPrompt = promptState
    }

    AnimatedVisibility(
        visible = promptState != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxSize(),
    ) {
        val activePrompt = retainedPrompt ?: return@AnimatedVisibility
        val isFreshInstall = activePrompt.type == AppLaunchTracker.LaunchPromptType.FRESH_INSTALL
        val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
        val isLightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f

        val fallbackHighlights = stringArrayResource(R.array.release_highlights).toList()
        var changelogNotes by remember(activePrompt.versionName) {
            mutableStateOf(fallbackHighlights)
        }
        LaunchedEffect(activePrompt.versionName) {
            val fetched = AppUpdateChecker.fetchReleaseNotes(activePrompt.versionName)
            if (fetched.isNotEmpty()) {
                changelogNotes = fetched
            }
        }

        val primaryTextColor = if (isLightSurface) Color(0xFF111114) else Color.White
        val secondaryTextColor = if (isLightSurface) {
            Color(0xFF111114).copy(alpha = 0.68f)
        } else {
            Color.White.copy(alpha = 0.72f)
        }
        val groupContainerColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.05f)
        } else {
            Color(0xFF282830).copy(alpha = 0.88f)
        }
        val groupBorderColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.08f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
        val outerBorderColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.16f)
        }
        val dividerColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.08f)
        } else {
            Color.White.copy(alpha = 0.10f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.60f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .animateEnterExit(
                        enter = scaleIn(
                            initialScale = 0.94f,
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        ) + fadeIn(tween(180)),
                        exit = scaleOut(
                            targetScale = 0.94f,
                            animationSpec = tween(160),
                        ) + fadeOut(tween(160)),
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .clip(POPUP_CORNER_SHAPE)
                    .then(
                        when {
                            isGlassSupported() -> Modifier.popupLiquidGlass(
                                shape = POPUP_CORNER_SHAPE,
                                isLightSurface = isLightSurface,
                            )
                            reduceDynamicBlur -> Modifier.background(
                                if (isLightSurface) Color(0xFFF2F2F7) else Color(0xFF1C1C22),
                            )
                            else -> Modifier
                                .optimizedHazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.regular(
                                        if (isLightSurface) Color(0xFFF2F2F7) else Color(0xFF1C1C22),
                                    ),
                                )
                                .background(
                                    if (isLightSurface) {
                                        Color(0xFFF2F2F7).copy(alpha = 0.88f)
                                    } else {
                                        Color(0xFF1C1C22).copy(alpha = 0.88f)
                                    },
                                )
                        },
                    )
                    .border(0.75.dp, outerBorderColor, POPUP_CORNER_SHAPE)
                    // Consume taps inside the card so they don't dismiss via the scrim
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 1. App logo on top
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(groupContainerColor)
                            .border(0.75.dp, groupBorderColor, RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_vibe_logo),
                            contentDescription = stringResource(R.string.app_name),
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // 2. Adaptive Title & Subtitle
                    val titleText = if (isFreshInstall) {
                        stringResource(R.string.welcome_to_vibe_music)
                    } else {
                        stringResource(R.string.whats_new_in_vibe_music, "v${activePrompt.versionName}")
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(4.dp))

                    val subtitleText = if (isFreshInstall) {
                        stringResource(R.string.release_subtitle_fresh, activePrompt.versionName)
                    } else {
                        stringResource(R.string.release_subtitle_update, activePrompt.versionName)
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        textAlign = TextAlign.Center,
                    )

                    // 3. Inline GitHub Release Changelog Card
                    if (changelogNotes.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(GROUP_CORNER_SHAPE)
                                .background(groupContainerColor)
                                .border(0.5.dp, groupBorderColor, GROUP_CORNER_SHAPE)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(
                                        R.string.release_changelog_header,
                                        "v${activePrompt.versionName}",
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = primaryTextColor,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 144.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                changelogNotes.forEach { bullet ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 6.dp)
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.primary),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = bullet,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = secondaryTextColor,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // 4. Action Rows (Donate with UPI, Join Community, Star on GitHub)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(GROUP_CORNER_SHAPE)
                            .background(groupContainerColor)
                            .border(0.5.dp, groupBorderColor, GROUP_CORNER_SHAPE),
                    ) {
                        // Donate with UPI — same title, subtitle, badge & UPI link as About section
                        ReleaseActionRow(
                            icon = Icons.Rounded.Favorite,
                            iconTint = Color(0xFFE91E63),
                            title = stringResource(R.string.about_donate_title),
                            subtitle = stringResource(R.string.about_donate_subtitle),
                            badge = "UPI",
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                            onClick = { onOpenUrl(URL_DONATE_UPI) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp),
                            thickness = 0.5.dp,
                            color = dividerColor,
                        )

                        // Join Community
                        ReleaseActionRow(
                            icon = Icons.Rounded.Groups,
                            iconTint = Color(0xFF29B6F6),
                            title = stringResource(R.string.release_action_community),
                            subtitle = stringResource(R.string.about_community_subtitle),
                            badge = "Telegram",
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                            onClick = { onOpenUrl(URL_COMMUNITY_TELEGRAM) },
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 62.dp),
                            thickness = 0.5.dp,
                            color = dividerColor,
                        )

                        // Star on GitHub
                        ReleaseActionRow(
                            icon = Icons.Rounded.Star,
                            iconTint = Color(0xFFFFCA28),
                            title = stringResource(R.string.about_source_code_title),
                            subtitle = stringResource(R.string.about_source_code_subtitle),
                            badge = "GitHub",
                            primaryTextColor = primaryTextColor,
                            secondaryTextColor = secondaryTextColor,
                            onClick = { onOpenUrl(URL_STAR_GITHUB) },
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // 5. Primary Dismiss / Continue Button
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = if (isFreshInstall) {
                                stringResource(R.string.release_action_get_started)
                            } else {
                                stringResource(R.string.release_action_continue)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Light-black Liquid Glass summary modal displayed after importing a `.vibemusic` backup,
 * detailing restored playlists, saved & playlist tracks, listening history months,
 * preferences, and connected accounts.
 */
@OptIn(ExperimentalHazeMaterialsApi::class, ExperimentalLayoutApi::class)
@Composable
fun BackupRestoreSummaryDialog(
    summary: Backup.Summary?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retainedSummary by remember { mutableStateOf(summary) }
    if (summary != null) {
        retainedSummary = summary
    }

    AnimatedVisibility(
        visible = summary != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = modifier.fillMaxSize(),
    ) {
        val activeSummary = retainedSummary ?: return@AnimatedVisibility
        val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
        val isLightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f

        val primaryTextColor = if (isLightSurface) Color(0xFF111114) else Color.White
        val secondaryTextColor = if (isLightSurface) {
            Color(0xFF111114).copy(alpha = 0.68f)
        } else {
            Color.White.copy(alpha = 0.72f)
        }
        val groupContainerColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.05f)
        } else {
            Color(0xFF282830).copy(alpha = 0.88f)
        }
        val groupBorderColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.08f)
        } else {
            Color.White.copy(alpha = 0.12f)
        }
        val outerBorderColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.16f)
        }
        val dividerColor = if (isLightSurface) {
            Color.Black.copy(alpha = 0.08f)
        } else {
            Color.White.copy(alpha = 0.10f)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.60f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .animateEnterExit(
                        enter = scaleIn(
                            initialScale = 0.94f,
                            animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                        ) + fadeIn(tween(180)),
                        exit = scaleOut(
                            targetScale = 0.94f,
                            animationSpec = tween(160),
                        ) + fadeOut(tween(160)),
                    )
                    .padding(horizontal = 20.dp, vertical = 24.dp)
                    .widthIn(max = 400.dp)
                    .fillMaxWidth()
                    .clip(POPUP_CORNER_SHAPE)
                    .then(
                        when {
                            isGlassSupported() -> Modifier.popupLiquidGlass(
                                shape = POPUP_CORNER_SHAPE,
                                isLightSurface = isLightSurface,
                            )
                            reduceDynamicBlur -> Modifier.background(
                                if (isLightSurface) Color(0xFFF2F2F7) else Color(0xFF1C1C22),
                            )
                            else -> Modifier
                                .optimizedHazeEffect(
                                    state = hazeState,
                                    style = HazeMaterials.regular(
                                        if (isLightSurface) Color(0xFFF2F2F7) else Color(0xFF1C1C22),
                                    ),
                                )
                                .background(
                                    if (isLightSurface) {
                                        Color(0xFFF2F2F7).copy(alpha = 0.88f)
                                    } else {
                                        Color(0xFF1C1C22).copy(alpha = 0.88f)
                                    },
                                )
                        },
                    )
                    .border(0.75.dp, outerBorderColor, POPUP_CORNER_SHAPE)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF4CAF50).copy(alpha = 0.18f))
                            .border(0.75.dp, Color(0xFF4CAF50).copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF66BB6A),
                            modifier = Modifier.size(34.dp),
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.backup_restored_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = primaryTextColor,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(4.dp))

                    val fromVersion = activeSummary.from.ifBlank { "1.0" }.removePrefix("v")
                    Text(
                        text = stringResource(R.string.backup_restored_subtitle, fromVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryTextColor,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(18.dp))

                    // Item counts group
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(GROUP_CORNER_SHAPE)
                            .background(groupContainerColor)
                            .border(0.5.dp, groupBorderColor, GROUP_CORNER_SHAPE),
                    ) {
                        BackupSummaryMetricRow(
                            icon = Icons.Rounded.LibraryMusic,
                            iconTint = Color(0xFF42A5F5),
                            label = stringResource(R.string.backup_restored_playlists),
                            value = activeSummary.playlistsCount.toString(),
                            primaryTextColor = primaryTextColor,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            thickness = 0.5.dp,
                            color = dividerColor,
                        )
                        BackupSummaryMetricRow(
                            icon = Icons.Rounded.MusicNote,
                            iconTint = Color(0xFFAB47BC),
                            label = stringResource(R.string.backup_restored_tracks),
                            value = activeSummary.tracksCount.toString(),
                            primaryTextColor = primaryTextColor,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            thickness = 0.5.dp,
                            color = dividerColor,
                        )
                        BackupSummaryMetricRow(
                            icon = Icons.Rounded.History,
                            iconTint = Color(0xFFFFA726),
                            label = stringResource(R.string.backup_restored_months),
                            value = activeSummary.months.toString(),
                            primaryTextColor = primaryTextColor,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 58.dp),
                            thickness = 0.5.dp,
                            color = dividerColor,
                        )
                        BackupSummaryMetricRow(
                            icon = Icons.Rounded.Tune,
                            iconTint = Color(0xFF26A69A),
                            label = stringResource(R.string.backup_restored_settings),
                            value = activeSummary.settings.toString(),
                            primaryTextColor = primaryTextColor,
                        )

                        if (activeSummary.restoredAccounts.isNotEmpty()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 58.dp),
                                thickness = 0.5.dp,
                                color = dividerColor,
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFE91E63).copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Person,
                                            contentDescription = null,
                                            tint = Color(0xFFE91E63),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = stringResource(R.string.backup_restored_accounts),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = primaryTextColor,
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                FlowRow(
                                    modifier = Modifier.padding(start = 42.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    activeSummary.restoredAccounts.forEach { accountName ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(primaryTextColor.copy(alpha = 0.10f))
                                                .border(0.5.dp, groupBorderColor, RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = accountName,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = primaryTextColor.copy(alpha = 0.92f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(
                            text = stringResource(R.string.backup_restored_done),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupSummaryMetricRow(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    primaryTextColor: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = primaryTextColor,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = primaryTextColor,
        )
    }
}

@Composable
private fun ReleaseActionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String? = null,
    badge: String? = null,
    primaryTextColor: Color,
    secondaryTextColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconTint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = primaryTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (badge != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(primaryTextColor.copy(alpha = 0.12f))
                    .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = primaryTextColor.copy(alpha = 0.9f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = secondaryTextColor,
            modifier = Modifier.size(20.dp),
        )
    }
}
