

package com.music.echo.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.innertube.models.WatchEndpoint
import com.music.echo.LocalPlayerAwareWindowInsets
import com.music.echo.LocalPlayerConnection
import com.music.echo.R
import com.music.echo.constants.StatPeriod
import com.music.echo.extensions.bounceClick
import com.music.echo.extensions.toMediaItem
import com.music.echo.models.toMediaMetadata
import com.music.echo.playback.queues.ListQueue
import com.music.echo.playback.queues.YouTubeQueue
import com.music.echo.ui.component.ChoiceChipsRow
import com.music.echo.ui.component.HideOnScrollFAB
import com.music.echo.ui.component.IconButton
import com.music.echo.ui.component.LocalAlbumsGrid
import com.music.echo.ui.component.LocalArtistsGrid
import com.music.echo.ui.component.LocalMenuState
import com.music.echo.ui.component.LocalSongsGrid
import com.music.echo.ui.component.NavigationTitle
import com.music.echo.ui.menu.AlbumMenu
import com.music.echo.ui.menu.ArtistMenu
import com.music.echo.ui.menu.SongMenu
import com.music.echo.ui.utils.backToMain
import com.music.echo.utils.TopAlbumItem
import com.music.echo.utils.TopArtistItem
import com.music.echo.utils.TopSongItem
import com.music.echo.utils.VibeWrappedData
import com.music.echo.utils.joinByBullet
import com.music.echo.utils.makeTimeString
import com.music.echo.viewmodels.StatsViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun StatsScreen(
    navController: NavController,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val context = LocalContext.current

    val indexChips by viewModel.indexChips.collectAsState()
    val mostPlayedSongs by viewModel.mostPlayedSongs.collectAsState()
    val mostPlayedSongsStats by viewModel.mostPlayedSongsStats.collectAsState()
    val mostPlayedArtists by viewModel.mostPlayedArtists.collectAsState()
    val mostPlayedAlbums by viewModel.mostPlayedAlbums.collectAsState()
    val firstEvent by viewModel.firstEvent.collectAsState()
    val currentDate = LocalDateTime.now()

    val totalPlayTime by viewModel.totalPlayTime.collectAsState()
    val allTimePlayTime by viewModel.allTimePlayTime.collectAsState()
    val uniqueSongsCount by viewModel.uniqueSongsCount.collectAsState()
    val uniqueArtistsCount by viewModel.uniqueArtistsCount.collectAsState()
    val uniqueAlbumsCount by viewModel.uniqueAlbumsCount.collectAsState()

    var showHistorySheet by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    val selectedOption by viewModel.selectedOption.collectAsState()

    val weeklyDates =
        if (currentDate != null && firstEvent != null) {
            generateSequence(currentDate) { it.minusWeeks(1) }
                .takeWhile { it.isAfter(firstEvent?.event?.timestamp?.minusWeeks(1)) }
                .mapIndexed { index, date ->
                    val endDate = date.plusWeeks(1).minusDays(1).coerceAtMost(currentDate)
                    val formatter = DateTimeFormatter.ofPattern("dd MMM")

                    val startDateFormatted = formatter.format(date)
                    val endDateFormatted = formatter.format(endDate)

                    val startMonth = date.month
                    val endMonth = endDate.month
                    val startYear = date.year
                    val endYear = endDate.year

                    val text =
                        when {
                            startYear != currentDate.year -> "$startDateFormatted, $startYear - $endDateFormatted, $endYear"
                            startMonth != endMonth -> "$startDateFormatted - $endDateFormatted"
                            else -> "${date.dayOfMonth} - $endDateFormatted"
                        }
                    Pair(index, text)
                }.toList()
        } else {
            emptyList()
        }

    val monthlyDates =
        if (currentDate != null && firstEvent != null) {
            generateSequence(
                currentDate.plusMonths(1).withDayOfMonth(1).minusDays(1)
            ) { it.minusMonths(1) }
                .takeWhile {
                    it.isAfter(
                        firstEvent
                            ?.event
                            ?.timestamp
                            ?.withDayOfMonth(1),
                    )
                }.mapIndexed { index, date ->
                    val formatter = DateTimeFormatter.ofPattern("MMM")
                    val formattedDate = formatter.format(date)
                    val text =
                        if (date.year != currentDate.year) {
                            "$formattedDate ${date.year}"
                        } else {
                            formattedDate
                        }
                    Pair(index, text)
                }.toList()
        } else {
            emptyList()
        }

    val yearlyDates =
        if (currentDate != null && firstEvent != null) {
            generateSequence(
                currentDate
                    .plusYears(1)
                    .withDayOfYear(1)
                    .minusDays(1),
            ) { it.minusYears(1) }
                .takeWhile {
                    it.isAfter(
                        firstEvent
                            ?.event
                            ?.timestamp,
                    )
                }.mapIndexed { index, date ->
                    Pair(index, "${date.year}")
                }.toList()
        } else {
            emptyList()
        }

    val currentPeriodLabel = when (selectedOption) {
        OptionStats.WEEKS -> weeklyDates.getOrNull(indexChips)?.second.orEmpty()
        OptionStats.MONTHS -> monthlyDates.getOrNull(indexChips)?.second.orEmpty()
        OptionStats.YEARS -> yearlyDates.getOrNull(indexChips)?.second.orEmpty()
        OptionStats.CONTINUOUS -> {
            when (indexChips) {
                StatPeriod.WEEK_1.ordinal -> pluralStringResource(R.plurals.n_week, 1, 1)
                StatPeriod.MONTH_1.ordinal -> pluralStringResource(R.plurals.n_month, 1, 1)
                StatPeriod.MONTH_3.ordinal -> pluralStringResource(R.plurals.n_month, 3, 3)
                StatPeriod.MONTH_6.ordinal -> pluralStringResource(R.plurals.n_month, 6, 6)
                StatPeriod.YEAR_1.ordinal -> pluralStringResource(R.plurals.n_year, 1, 1)
                StatPeriod.ALL.ordinal -> stringResource(R.string.filter_all)
                else -> ""
            }
        }
    }

    val wrappedData = remember(
        currentPeriodLabel,
        totalPlayTime,
        allTimePlayTime,
        uniqueSongsCount,
        uniqueArtistsCount,
        uniqueAlbumsCount,
        mostPlayedSongsStats,
        mostPlayedArtists,
        mostPlayedAlbums
    ) {
        VibeWrappedData(
            periodLabel = currentPeriodLabel,
            totalPlayTimeMs = totalPlayTime,
            allTimePlayTimeMs = allTimePlayTime,
            uniqueSongs = uniqueSongsCount,
            uniqueArtists = uniqueArtistsCount,
            uniqueAlbums = uniqueAlbumsCount,
            topSongs = mostPlayedSongsStats.take(5).map { song ->
                TopSongItem(
                    title = song.title,
                    artistName = song.artistName.orEmpty(),
                    playCount = song.songCountListened,
                    timePlayedMs = song.timeListened,
                    thumbnailUrl = song.thumbnailUrl
                )
            },
            topArtists = mostPlayedArtists.take(5).map { artist ->
                TopArtistItem(
                    name = artist.artist.name,
                    playCount = artist.songCount,
                    timePlayedMs = artist.timeListened?.toLong(),
                    thumbnailUrl = artist.artist.thumbnailUrl
                )
            },
            topAlbums = mostPlayedAlbums.take(5).map { album ->
                TopAlbumItem(
                    title = album.album.title,
                    playCount = album.songCountListened ?: 0,
                    timePlayedMs = album.timeListened,
                    thumbnailUrl = album.album.thumbnailUrl
                )
            }
        )
    }

    val playerInsets = LocalPlayerAwareWindowInsets.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats)) },
                navigationIcon = {
                    IconButton(
                        onClick = navController::navigateUp,
                        onLongClick = navController::backToMain,
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showShareDialog = true },
                        modifier = Modifier.testTag("share_wrapped_button")
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.share),
                            contentDescription = stringResource(R.string.share_wrapped),
                        )
                    }
                    IconButton(
                        onClick = { showHistorySheet = true }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.history),
                            contentDescription = "History Summary",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            LazyColumn(
                state = lazyListState,
                contentPadding = PaddingValues(
                    start = playerInsets.asPaddingValues().calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = playerInsets.asPaddingValues().calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    top = 8.dp,
                    bottom = playerInsets.asPaddingValues().calculateBottomPadding() + 80.dp
                ),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "choice_chips") {
                    ChoiceChipsRow(
                        chips =
                        when (selectedOption) {
                            OptionStats.WEEKS -> weeklyDates
                            OptionStats.MONTHS -> monthlyDates
                            OptionStats.YEARS -> yearlyDates
                            OptionStats.CONTINUOUS -> {
                                listOf(
                                    StatPeriod.WEEK_1.ordinal to pluralStringResource(
                                        R.plurals.n_week,
                                        1,
                                        1
                                    ),
                                    StatPeriod.MONTH_1.ordinal to pluralStringResource(
                                        R.plurals.n_month,
                                        1,
                                        1
                                    ),
                                    StatPeriod.MONTH_3.ordinal to pluralStringResource(
                                        R.plurals.n_month,
                                        3,
                                        3
                                    ),
                                    StatPeriod.MONTH_6.ordinal to pluralStringResource(
                                        R.plurals.n_month,
                                        6,
                                        6
                                    ),
                                    StatPeriod.YEAR_1.ordinal to pluralStringResource(
                                        R.plurals.n_year,
                                        1,
                                        1
                                    ),
                                    StatPeriod.ALL.ordinal to stringResource(R.string.filter_all),
                                )
                            }
                        },
                        options =
                        listOf(
                            OptionStats.CONTINUOUS to stringResource(id = R.string.continuous),
                            OptionStats.WEEKS to stringResource(R.string.weeks),
                            OptionStats.MONTHS to stringResource(R.string.months),
                            OptionStats.YEARS to stringResource(R.string.years),
                        ),
                        selectedOption = selectedOption,
                        onSelectionChange = {
                            viewModel.selectedOption.value = it
                            viewModel.indexChips.value = 0
                        },
                        currentValue = indexChips,
                        onValueUpdate = { viewModel.indexChips.value = it },
                    )
                }

                item(key = "wrapped_hero_card") {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .clickable { showShareDialog = true }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.70f)
                                        )
                                    )
                                )
                                .padding(18.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    painter = painterResource(R.drawable.stats),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "VIBE WRAPPED",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.5.sp,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            if (currentPeriodLabel.isNotEmpty()) {
                                                Text(
                                                    text = currentPeriodLabel,
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }

                                    FilledTonalButton(
                                        onClick = { showShareDialog = true },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.share),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Share", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = "Listening Time",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = makeTimeString(totalPlayTime),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "Songs",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = uniqueSongsCount.toString(),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "Artists",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = uniqueArtistsCount.toString(),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val hasStatsData = mostPlayedSongsStats.isNotEmpty() || mostPlayedArtists.isNotEmpty() || mostPlayedAlbums.isNotEmpty()

                if (!hasStatsData) {
                    item(key = "empty_stats_card") {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.stats),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    text = "No listening history for this period",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = "Play songs and albums in the app to build up your Vibe Wrapped statistics!",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    if (mostPlayedSongsStats.isNotEmpty()) {
                        item(key = "mostPlayedSongs") {
                            NavigationTitle(
                                title = "${mostPlayedSongsStats.size} ${stringResource(id = R.string.songs)}",
                                modifier = Modifier.animateItem(),
                            )

                            LazyRow(
                                modifier = Modifier.animateItem(),
                            ) {
                                itemsIndexed(
                                    items = mostPlayedSongsStats,
                                    key = { _, song -> song.id },
                                ) { index, song ->
                                    LocalSongsGrid(
                                        title = "${index + 1}. ${song.title}",
                                        subtitle =
                                        joinByBullet(
                                            pluralStringResource(
                                                R.plurals.n_time,
                                                song.songCountListened,
                                                song.songCountListened,
                                            ),
                                            makeTimeString(song.timeListened),
                                        ),
                                        thumbnailUrl = song.thumbnailUrl,
                                        isActive = song.id == mediaMetadata?.id,
                                        isPlaying = isPlaying,
                                        modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .bounceClick()
                                            .combinedClickable(
                                                onClick = {
                                                    if (song.id == mediaMetadata?.id) {
                                                        playerConnection.togglePlayPause()
                                                    } else {
                                                        playerConnection.playQueue(
                                                            YouTubeQueue(
                                                                endpoint = WatchEndpoint(song.id),
                                                                preloadItem = mostPlayedSongs.getOrNull(index)?.toMediaMetadata(),
                                                            ),
                                                        )
                                                    }
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    mostPlayedSongs.getOrNull(index)?.let { targetSong ->
                                                        menuState.show {
                                                            SongMenu(
                                                                originalSong = targetSong,
                                                                navController = navController,
                                                                onDismiss = menuState::dismiss,
                                                            )
                                                        }
                                                    }
                                                },
                                            )
                                            .animateItem(),
                                    )
                                }
                            }
                        }
                    }

                    if (mostPlayedArtists.isNotEmpty()) {
                        item(key = "mostPlayedArtists") {
                            NavigationTitle(
                                title = "${mostPlayedArtists.size} ${stringResource(id = R.string.artists)}",
                                modifier = Modifier.animateItem(),
                            )

                            LazyRow(
                                modifier = Modifier.animateItem(),
                            ) {
                                itemsIndexed(
                                    items = mostPlayedArtists,
                                    key = { _, artist -> artist.id },
                                ) { index, artist ->
                                    LocalArtistsGrid(
                                        title = "${index + 1}. ${artist.artist.name}",
                                        subtitle =
                                        joinByBullet(
                                            pluralStringResource(
                                                R.plurals.n_time,
                                                artist.songCount,
                                                artist.songCount
                                            ),
                                            makeTimeString(artist.timeListened?.toLong()),
                                        ),
                                        thumbnailUrl = artist.artist.thumbnailUrl,
                                        modifier =
                                        Modifier
                                            .bounceClick()
                                            .combinedClickable(
                                                onClick = {
                                                    navController.navigate("artist/${artist.id}")
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuState.show {
                                                        ArtistMenu(
                                                            originalArtist = artist,
                                                            coroutineScope = coroutineScope,
                                                            onDismiss = menuState::dismiss,
                                                        )
                                                    }
                                                },
                                            )
                                            .animateItem(),
                                    )
                                }
                            }
                        }
                    }

                    if (mostPlayedAlbums.isNotEmpty()) {
                        item(key = "mostPlayedAlbums") {
                            NavigationTitle(
                                title = "${mostPlayedAlbums.size} ${stringResource(id = R.string.albums)}",
                                modifier = Modifier.animateItem(),
                            )

                            LazyRow(
                                modifier = Modifier.animateItem(),
                            ) {
                                itemsIndexed(
                                    items = mostPlayedAlbums,
                                    key = { _, album -> album.id },
                                ) { index, album ->
                                    LocalAlbumsGrid(
                                        title = "${index + 1}. ${album.album.title}",
                                        subtitle =
                                        joinByBullet(
                                            pluralStringResource(
                                                R.plurals.n_time,
                                                album.songCountListened ?: 0,
                                                album.songCountListened ?: 0
                                            ),
                                            makeTimeString(album.timeListened),
                                        ),
                                        thumbnailUrl = album.album.thumbnailUrl,
                                        isActive = album.id == mediaMetadata?.album?.id,
                                        isPlaying = isPlaying,
                                        modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .bounceClick()
                                            .combinedClickable(
                                                onClick = {
                                                    navController.navigate("album/${album.id}")
                                                },
                                                onLongClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    menuState.show {
                                                        AlbumMenu(
                                                            originalAlbum = album,
                                                            navController = navController,
                                                            onDismiss = menuState::dismiss,
                                                        )
                                                    }
                                                },
                                            )
                                            .animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(24.dp))
                }
            }

            if (mostPlayedSongs.isNotEmpty()) {
                HideOnScrollFAB(
                    visible = true,
                    lazyListState = lazyListState,
                    icon = R.drawable.shuffle,
                    onClick = {
                        playerConnection.playQueue(
                            shuffle = true,
                            queue = ListQueue(
                                title = context.getString(R.string.most_played_songs),
                                items = mostPlayedSongs.map { it.toMediaMetadata().toMediaItem() }
                            )
                        )
                    }
                )
            }
        }

        if (showHistorySheet) {
            ActivityHistoryBottomSheet(
                onDismiss = { showHistorySheet = false },
                totalPlayTimeMs = totalPlayTime,
                allTimePlayTimeMs = allTimePlayTime,
                uniqueSongs = uniqueSongsCount,
                uniqueArtists = uniqueArtistsCount,
                uniqueAlbums = uniqueAlbumsCount,
                periodLabel = currentPeriodLabel,
                onShareClick = {
                    showHistorySheet = false
                    showShareDialog = true
                }
            )
        }

        if (showShareDialog) {
            VibeWrappedShareDialog(
                data = wrappedData,
                onDismiss = { showShareDialog = false }
            )
        }
    }
}

enum class OptionStats { WEEKS, MONTHS, YEARS, CONTINUOUS }

