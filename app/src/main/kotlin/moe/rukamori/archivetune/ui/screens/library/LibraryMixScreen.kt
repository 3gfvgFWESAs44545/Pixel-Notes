/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AlbumFilter
import moe.rukamori.archivetune.constants.AlbumFilterKey
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.extensions.toMediaItem
import moe.rukamori.archivetune.playback.queues.ListQueue
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.utils.navigateToAlbum
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.viewmodels.LibraryMixViewModel
import moe.rukamori.archivetune.viewmodels.LibraryRefreshState
import moe.rukamori.archivetune.viewmodels.MostPlayedAlbumUiModel
import moe.rukamori.archivetune.viewmodels.MostPlayedAlbumUiState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryMixScreen(
    navController: NavController,
    filterContent: (@Composable () -> Unit)?,
    selectedTagIds: Set<String>,
    onTabSelected: (LibraryFilter) -> Unit,
    viewModel: LibraryMixViewModel = hiltViewModel(),
) {
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit =
        remember(coroutineScope, snackbarHostState) {
            { message ->
                coroutineScope.launch { snackbarHostState.showSnackbar(message) }
                Unit
            }
        }
    val database = LocalDatabase.current

    val likedSongsCount by database.likedSongsCount().collectAsState(initial = 0)
    val likedAlbums by database.albumsLikedByCreateDateAsc().collectAsState(initial = emptyList())
    val (_, setAlbumFilter) = rememberEnumPreference(AlbumFilterKey, AlbumFilter.LIKED)
    val topSize by viewModel.topValue.collectAsStateWithLifecycle(initialValue = "50")
    val myTopTitle = stringResource(R.string.my_top)
    val topPlaylistTitle = remember(myTopTitle, topSize) { "$myTopTitle $topSize" }

    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val refreshState by viewModel.refreshState.collectAsStateWithLifecycle()
    val refreshLibrary = remember(viewModel) { { viewModel.syncAllLibrary() } }
    val onRefreshErrorShown = remember(viewModel) { { viewModel.onRefreshErrorShown() } }
    val mostPlayedAlbumUiState by viewModel.mostPlayedAlbumUiState.collectAsStateWithLifecycle()

    val filteredPlaylistIds by database
        .playlistIdsByTags(
            if (selectedTagIds.isEmpty()) emptyList() else selectedTagIds.toList(),
        ).collectAsState(initial = emptyList())

    val visiblePlaylists =
        remember(playlists, selectedTagIds, filteredPlaylistIds) {
            playlists.filter { playlist ->
                val name = playlist.playlist.name
                val matchesName = !name.contains("episode", ignoreCase = true)
                val matchesTags = selectedTagIds.isEmpty() || playlist.id in filteredPlaylistIds
                matchesName && matchesTags
            }
        }

    LibraryRefreshFeedbackEffect(
        state = refreshState,
        snackbarHostState = snackbarHostState,
        onErrorShown = onRefreshErrorShown,
    )

    val playerAwareBottomPadding =
        LocalPlayerAwareWindowInsets.current
            .only(WindowInsetsSides.Bottom)
            .asPaddingValues()
            .calculateBottomPadding() + 12.dp

    Box(modifier = Modifier.fillMaxSize()) {
        ExpressivePullToRefreshBox(
            isRefreshing = refreshState is LibraryRefreshState.Loading,
            onRefresh = refreshLibrary,
            modifier = Modifier.fillMaxSize(),
            indicatorOffset = LibraryPullToRefreshIndicatorOffset,
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding =
                    PaddingValues(
                        top = 8.dp,
                        bottom = playerAwareBottomPadding,
                    ),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "most_played_album_spotlight", contentType = "spotlight") {
                    val state = mostPlayedAlbumUiState
                    if (state is MostPlayedAlbumUiState.Success) {
                        val album = state.album
                        val playAlbum =
                            remember(album.tracks, playerConnection) {
                                {
                                    playerConnection.playQueue(
                                        ListQueue(items = album.tracks.map { it.toMediaItem() }),
                                    )
                                }
                            }
                        val shuffleAlbum =
                            remember(album.tracks, playerConnection) {
                                {
                                    playerConnection.playQueue(
                                        ListQueue(items = album.tracks.shuffled().map { it.toMediaItem() }),
                                    )
                                }
                            }

                        MostPlayedAlbumSpotlightCard(
                            album = album,
                            onOpenAlbum = { navController.navigateToAlbum(album.id) },
                            onPlayAll = playAlbum,
                            onShuffle = shuffleAlbum,
                        )
                    }
                }

                // 2. Shortcuts Grid
                item(key = "shortcuts_grid", contentType = "shortcuts_grid") {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Liked Songs
                            ShortcutCard(
                                title = stringResource(R.string.liked_songs),
                                countText = "$likedSongsCount ${stringResource(R.string.tracks_label)}",
                                iconRes = R.drawable.favorite,
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                iconColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                                onClick = { navController.navigate("auto_playlist/liked") },
                            )

                            // Liked Albums: opens the Albums tab filtered to liked albums
                            ShortcutCard(
                                title = "Liked albums",
                                countText = "${likedAlbums.size} albums",
                                iconRes = R.drawable.album,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                iconColor = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    setAlbumFilter(AlbumFilter.LIKED)
                                    onTabSelected(LibraryFilter.ALBUMS)
                                },
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Recently played songs and albums
                            ShortcutCard(
                                title = stringResource(R.string.recently_played),
                                countText = "Songs & albums",
                                iconRes = R.drawable.history,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                iconColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f),
                                onClick = { navController.navigate("history") },
                            )

                            ShortcutCard(
                                title = "Liked artists",
                                countText = "${artists.size} artists",
                                iconRes = R.drawable.person,
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f),
                                iconColor = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.weight(1f),
                                onClick = { navController.navigate(LibraryArtistsRoute) },
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ShortcutCard(
                                title = topPlaylistTitle,
                                countText = stringResource(R.string.all_time),
                                iconRes = R.drawable.trending_up,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                iconColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.weight(1f),
                                onClick = { navController.navigate("top_playlist/$topSize") },
                            )

                            ShortcutCard(
                                title = stringResource(R.string.filter_playlists),
                                countText = "${playlists.size} playlists",
                                iconRes = R.drawable.queue_music,
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                iconColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                onClick = { navController.navigate(LibraryPlaylistsRoute) },
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // Offline/Downloaded
                            ShortcutCard(
                                title = stringResource(R.string.offline_shortcut),
                                countText = stringResource(R.string.downloaded_desc),
                                iconRes = R.drawable.offline,
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                iconColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                                onClick = { navController.navigate("auto_playlist/downloaded") },
                            )

                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }

                if (supportArchiveTuneAvailable) {
                    item(key = "support_archive_tune", contentType = "support_ad") {
                        SupportArchiveTuneSection(
                            onMessage = showMessage,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        )
                    }
                }

                val playlistTagFilterContent = filterContent
                if (playlistTagFilterContent != null) {
                    item(key = "playlist_tag_filters") {
                        playlistTagFilterContent()
                    }
                }

                // Playlists Row
                if (visiblePlaylists.isNotEmpty()) {
                    item(key = "your_playlists") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.your_playlists),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = stringResource(R.string.see_all),
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        ),
                                    modifier =
                                        Modifier
                                            .clip(CircleShape)
                                            .clickable { navController.navigate(LibraryPlaylistsRoute) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }

                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(
                                    items = visiblePlaylists.take(8),
                                    key = { playlist -> "playlist_${playlist.id}" },
                                    contentType = { "library_playlist" },
                                ) { playlist ->
                                    val cardBgColor =
                                        rememberArtworkCardColor(
                                            thumbnailUrl = playlist.thumbnails.getOrNull(0),
                                            fallbackColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                        )

                                    val interactionSource = remember { MutableInteractionSource() }
                                    val isPressed by interactionSource.collectIsPressedAsState()
                                    val scale by animateFloatAsState(
                                        targetValue = if (isPressed) 0.97f else 1.0f,
                                        animationSpec =
                                            spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessLow,
                                            ),
                                        label = "MixPlaylistCardScale",
                                    )

                                    Column(
                                        modifier =
                                            Modifier
                                                .width(130.dp)
                                                .graphicsLayer {
                                                    scaleX = scale
                                                    scaleY = scale
                                                }.clip(RoundedCornerShape(32.dp))
                                                .background(cardBgColor)
                                                .clickable(
                                                    interactionSource = interactionSource,
                                                    indication = null,
                                                    onClick = {
                                                        if (!playlist.playlist.isEditable && playlist.songCount == 0 &&
                                                            playlist.playlist.remoteSongCount != 0
                                                        ) {
                                                            navController.navigate("online_playlist/${playlist.playlist.browseId}")
                                                        } else {
                                                            navController.navigate("local_playlist/${playlist.id}")
                                                        }
                                                    },
                                                ).padding(12.dp),
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(106.dp)
                                                    .clip(RoundedCornerShape(24.dp)),
                                        ) {
                                            AsyncImage(
                                                model = playlist.thumbnails.getOrNull(0),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                            // Play Overlay button
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .padding(6.dp)
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.primary)
                                                        .clickable {
                                                            playerConnection.let { conn ->
                                                                coroutineScope.launch {
                                                                    database.playlistSongs(playlist.id).firstOrNull()?.let { songs ->
                                                                        if (songs.isNotEmpty()) {
                                                                            conn.playQueue(
                                                                                ListQueue(items = songs.map { it.song.toMediaItem() }),
                                                                            )
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        },
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.play),
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(14.dp),
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = playlist.playlist.name,
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                        Text(
                                            text = "${playlist.songCount} ${stringResource(R.string.tracks_label)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                                        )
                                    }
                                }

                                // Ending "More" card
                                item {
                                    Column(
                                        modifier =
                                            Modifier
                                                .width(130.dp)
                                                .height(168.dp)
                                                .clip(RoundedCornerShape(32.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                                .clickable {
                                                    navController.navigate(LibraryPlaylistsRoute)
                                                },
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(56.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.expand_more),
                                                contentDescription = stringResource(R.string.more_playlists_desc),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = stringResource(R.string.more_label),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Your Artists Row
                if (artists.isNotEmpty()) {
                    item(key = "your_artists") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.your_artists),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = stringResource(R.string.see_all),
                                    style =
                                        MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary,
                                        ),
                                    modifier =
                                        Modifier
                                            .clip(CircleShape)
                                            .clickable { navController.navigate(LibraryArtistsRoute) }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(artists.take(10)) { item ->
                                    val artist = item.artist
                                    Column(
                                        modifier =
                                            Modifier
                                                .width(80.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        AsyncImage(
                                            model = artist.thumbnailUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier =
                                                Modifier
                                                    .size(72.dp)
                                                    .clip(CircleShape),
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = artist.name,
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }

                                // Ending "+" button
                                item {
                                    Column(
                                        modifier =
                                            Modifier
                                                .width(80.dp)
                                                .clickable {
                                                    navController.navigate(LibraryArtistsRoute)
                                                },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .size(72.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.add),
                                                contentDescription = stringResource(R.string.more_label),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = stringResource(R.string.more_label),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onBackground,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = playerAwareBottomPadding),
        )
    }
}

@Composable
private fun MostPlayedAlbumSpotlightCard(
    album: MostPlayedAlbumUiModel,
    onOpenAlbum: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val isDark =
        MaterialTheme.colorScheme.surface.let {
            ColorUtils.calculateLuminance(it.toArgb()) < 0.5
        }
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val spotlightBg =
        remember(surfaceContainer, primaryColor, isDark) {
            if (isDark) {
                Color(ColorUtils.blendARGB(surfaceContainer.toArgb(), primaryColor.toArgb(), 0.12f))
            } else {
                Color(ColorUtils.blendARGB(surfaceContainer.toArgb(), primaryColor.toArgb(), 0.08f))
            }
        }
    val trackCountText = pluralStringResource(R.plurals.n_song, album.trackCount, album.trackCount)
    val backgroundBrush =
        remember(spotlightBg) {
            Brush.verticalGradient(
                colors =
                    listOf(
                        spotlightBg,
                        spotlightBg.copy(alpha = 0.9f),
                    ),
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(backgroundBrush)
                .clickable(onClick = onOpenAlbum)
                .padding(16.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(primaryColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumbnailUrl = album.thumbnailUrl
                    if (thumbnailUrl != null) {
                        AsyncImage(
                            model = thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            painter = painterResource(id = R.drawable.album),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(primaryColor.copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.star),
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(10.dp),
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.most_played_badge),
                            style =
                                MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp,
                                ),
                            color = primaryColor,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = album.title,
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = trackCountText,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlayAll,
                    shape = CircleShape,
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.play),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.play_all),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }

                IconButton(
                    onClick = onShuffle,
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.shuffle),
                        contentDescription = stringResource(R.string.shuffle),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ShortcutCard(
    title: String,
    countText: String,
    iconRes: Int,
    containerColor: Color,
    iconColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "ShortcutCardScale",
    )

    val isDark =
        MaterialTheme.colorScheme.surface.let {
            ColorUtils.calculateLuminance(it.toArgb()) < 0.5
        }

    val surfaceContainerColor = MaterialTheme.colorScheme.surfaceContainer
    val finalBgColor =
        remember(surfaceContainerColor, iconColor, isDark) {
            if (isDark) {
                Color(ColorUtils.blendARGB(surfaceContainerColor.toArgb(), iconColor.toArgb(), 0.08f))
            } else {
                Color(ColorUtils.blendARGB(surfaceContainerColor.toArgb(), iconColor.toArgb(), 0.06f))
            }
        }

    val iconBgColor =
        remember(iconColor, isDark) {
            if (isDark) {
                iconColor.copy(alpha = 0.16f)
            } else {
                iconColor.copy(alpha = 0.10f)
            }
        }

    Box(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(RoundedCornerShape(26.dp))
                .background(finalBgColor)
                .clickable(
                    interactionSource = interactionSource,
                    onClick = onClick,
                ).padding(12.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(iconBgColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = countText,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
