package com.music.vibe.ui.components

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.vibe.R
import com.music.vibe.data.model.PlaylistPrivacy
import com.music.vibe.data.model.Song
import com.music.vibe.data.spotify.SpotifyImportManager
import com.music.vibe.data.spotify.SpotifyImportState
import com.music.vibe.data.spotify.SpotifyImporter
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi

/**
 * Paste a Spotify playlist or album link, match its songs on YouTube Music in the
 * background via [SpotifyImportManager] & [com.music.vibe.data.spotify.SpotifyImportService],
 * and support running in the background, saving partial progress, or cancelling.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun SpotifyImportAlert(
    hazeState: HazeState,
    signedIn: Boolean,
    /** Called once the songs are matched; [privacy] is meaningful only when signed in. */
    onImported: (title: String, privacy: PlaylistPrivacy, songs: List<Song>) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val importState by SpotifyImportManager.state.collectAsState()

    var link by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    var localError by remember { mutableStateOf<String?>(null) }

    val working = importState is SpotifyImportState.FetchingMetadata ||
        importState is SpotifyImportState.Matching ||
        importState is SpotifyImportState.Saving

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Whether granted or denied, proceed with the import (banner still shows if denied)
        SpotifyImportManager.startImport(
            context = context,
            rawUrl = link,
            privacy = privacy,
            signedIn = signedIn,
        )
    }

    fun start() {
        val target = SpotifyImporter.extractTarget(link)
        if (target == null) {
            localError = context.getString(R.string.spotify_import_invalid)
            return
        }
        localError = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !SpotifyImportManager.hasNotificationPermission(context)
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SpotifyImportManager.startImport(
                context = context,
                rawUrl = link,
                privacy = privacy,
                signedIn = signedIn,
            )
        }
    }

    AlertScaffold(
        hazeState = hazeState,
        onDismiss = {
            if (importState is SpotifyImportState.Completed || importState is SpotifyImportState.Failed) {
                SpotifyImportManager.dismissTerminalState()
            }
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 19.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.spotify_import_title),
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            when (val current = importState) {
                is SpotifyImportState.Completed -> {
                    val missed = current.unmatchedTracks
                    Text(
                        text = stringResource(
                            R.string.spotify_import_saved_count,
                            current.matchedCount,
                            current.totalCount,
                            current.title,
                        ),
                        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    if (missed.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.spotify_import_unmatched, missed.size),
                            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            missed.forEach { track ->
                                Text(
                                    text = if (track.artist.isBlank()) track.title else "${track.title} · ${track.artist}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                is SpotifyImportState.Interrupted -> {
                    Text(
                        text = stringResource(R.string.spotify_import_interrupted_title, current.title),
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = current.lastError ?: stringResource(
                            R.string.spotify_import_interrupted_subtitle,
                            current.completed,
                            current.total,
                            current.matchedSongs.size,
                        ),
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = if (current.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> {
                    val statusText = when {
                        localError != null -> localError
                        current is SpotifyImportState.FetchingMetadata -> stringResource(R.string.spotify_import_fetching)
                        current is SpotifyImportState.Matching -> stringResource(
                            R.string.spotify_import_matching,
                            current.completed,
                            current.total,
                        )
                        current is SpotifyImportState.Saving -> stringResource(
                            R.string.spotify_import_saving,
                            current.matchedCount,
                            current.totalCount,
                        )
                        current is SpotifyImportState.Failed -> stringResource(
                            R.string.spotify_import_failed,
                            current.message,
                        )
                        else -> null
                    }
                    val isError = localError != null || current is SpotifyImportState.Failed

                    Text(
                        text = statusText ?: stringResource(
                            if (signedIn) R.string.spotify_import_description
                            else R.string.spotify_import_description_local,
                        ),
                        modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        color = when {
                            statusText == null -> MaterialTheme.colorScheme.onSurface
                            isError -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        },
                        textAlign = TextAlign.Center,
                    )

                    if (current is SpotifyImportState.Matching && current.total > 0) {
                        LinearProgressIndicator(
                            progress = { (current.completed.toFloat() / current.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .testTag("spotify_import_progress_bar"),
                        )
                    }

                    PillTextField(
                        value = link,
                        onValueChange = {
                            link = it
                            if (localError != null) localError = null
                            if (current is SpotifyImportState.Failed) {
                                SpotifyImportManager.dismissTerminalState()
                            }
                        },
                        placeholder = stringResource(R.string.spotify_import_hint),
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (link.isNotBlank() && !working) start() }),
                    )
                    if (signedIn) {
                        Text(
                            text = stringResource(R.string.who_can_see_it),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, bottom = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PlaylistPrivacy.entries.forEach { option ->
                                val selected = option == privacy
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(percent = 50))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                        )
                                        .clickable(enabled = !working) { privacy = option }
                                        .padding(vertical = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = option.label,
                                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
                                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        AlertRule()
        when (val current = importState) {
            is SpotifyImportState.Completed -> {
                AlertAction(
                    label = stringResource(R.string.done),
                    emphasised = true,
                    onClick = {
                        SpotifyImportManager.dismissTerminalState()
                        onDismiss()
                    },
                )
            }

            is SpotifyImportState.Interrupted -> {
                AlertAction(
                    label = stringResource(R.string.spotify_import_resume),
                    emphasised = true,
                    onClick = { SpotifyImportManager.resumeImport(context, signedIn) },
                )
                if (current.matchedSongs.isNotEmpty()) {
                    AlertRule()
                    AlertAction(
                        label = stringResource(
                            R.string.spotify_import_save_what_we_got,
                            current.matchedSongs.size,
                            current.total,
                        ),
                        emphasised = false,
                        onClick = { SpotifyImportManager.saveInterruptedWhatWeGot(context, signedIn) },
                    )
                }
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.spotify_import_discard),
                    emphasised = false,
                    onClick = {
                        SpotifyImportManager.discardInterrupted(context)
                        onDismiss()
                    },
                )
            }

            is SpotifyImportState.Matching,
            is SpotifyImportState.FetchingMetadata,
            is SpotifyImportState.Saving,
            -> {
                AlertAction(
                    label = stringResource(R.string.spotify_import_run_in_background),
                    emphasised = true,
                    onClick = onDismiss,
                )
                if (current is SpotifyImportState.Matching && current.matchedCount > 0) {
                    AlertRule()
                    AlertAction(
                        label = stringResource(R.string.spotify_import_save_partial),
                        emphasised = false,
                        onClick = { SpotifyImportManager.requestSavePartial(context) },
                    )
                }
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.cancel),
                    emphasised = false,
                    onClick = {
                        SpotifyImportManager.cancelImport(context)
                        onDismiss()
                    },
                )
            }

            else -> {
                AlertAction(
                    label = stringResource(R.string.spotify_import_button),
                    emphasised = true,
                    onClick = ::start,
                    enabled = link.isNotBlank() && !working,
                )
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.cancel),
                    emphasised = false,
                    onClick = onDismiss,
                    enabled = !working,
                )
            }
        }
    }
}

/**
 * Floating in-app progress and recovery banner shown when a Spotify import is running
 * in the background or when a previous import was interrupted by process death / error.
 */
@Composable
fun SpotifyImportBanner(
    state: SpotifyImportState,
    signedIn: Boolean,
    onExpandDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    when (state) {
        is SpotifyImportState.FetchingMetadata,
        is SpotifyImportState.Matching,
        is SpotifyImportState.Saving,
        -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onExpandDialog)
                    .testTag("spotify_import_banner"),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val titleText = when (state) {
                                is SpotifyImportState.Matching -> state.title.ifBlank { stringResource(R.string.spotify_import_title) }
                                is SpotifyImportState.Saving -> state.title.ifBlank { stringResource(R.string.spotify_import_title) }
                                else -> stringResource(R.string.spotify_import_title)
                            }
                            val subText = when (state) {
                                is SpotifyImportState.FetchingMetadata -> stringResource(R.string.spotify_import_fetching)
                                is SpotifyImportState.Matching -> stringResource(
                                    R.string.spotify_import_matching,
                                    state.completed,
                                    state.total,
                                )
                                is SpotifyImportState.Saving -> stringResource(
                                    R.string.spotify_import_saving,
                                    state.matchedCount,
                                    state.totalCount,
                                )
                                else -> ""
                            }
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = subText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state is SpotifyImportState.Matching && state.matchedCount > 0) {
                                TextButton(
                                    onClick = { SpotifyImportManager.requestSavePartial(context) },
                                    modifier = Modifier.testTag("spotify_import_banner_save_partial"),
                                ) {
                                    Text(stringResource(R.string.spotify_import_save_partial))
                                }
                            }
                            TextButton(
                                onClick = { SpotifyImportManager.cancelImport(context) },
                                modifier = Modifier.testTag("spotify_import_banner_cancel"),
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                    if (state is SpotifyImportState.Matching && state.total > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (state.completed.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        is SpotifyImportState.Interrupted -> {
            Surface(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onExpandDialog)
                    .testTag("spotify_import_interrupted_banner"),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.spotify_import_interrupted_title, state.title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.lastError ?: stringResource(
                            R.string.spotify_import_interrupted_subtitle,
                            state.completed,
                            state.total,
                            state.matchedSongs.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { SpotifyImportManager.discardInterrupted(context) },
                            modifier = Modifier.testTag("spotify_import_banner_discard"),
                        ) {
                            Text(stringResource(R.string.spotify_import_discard))
                        }
                        if (state.matchedSongs.isNotEmpty()) {
                            TextButton(
                                onClick = { SpotifyImportManager.saveInterruptedWhatWeGot(context, signedIn) },
                                modifier = Modifier.testTag("spotify_import_banner_save_what_we_got"),
                            ) {
                                Text(
                                    stringResource(
                                        R.string.spotify_import_save_what_we_got,
                                        state.matchedSongs.size,
                                        state.total,
                                    ),
                                )
                            }
                        }
                        TextButton(
                            onClick = { SpotifyImportManager.resumeImport(context, signedIn) },
                            modifier = Modifier.testTag("spotify_import_banner_resume"),
                        ) {
                            Text(stringResource(R.string.spotify_import_resume))
                        }
                    }
                }
            }
        }

        else -> Unit
    }
}
