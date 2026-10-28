/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.common.collect.ImmutableList
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DataSyncIdKey
import moe.rukamori.archivetune.constants.HideExplicitKey
import moe.rukamori.archivetune.constants.HideVideoKey
import moe.rukamori.archivetune.constants.QuickPicks
import moe.rukamori.archivetune.constants.QuickPicksKey
import moe.rukamori.archivetune.constants.SpeedDialSongIdsKey
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.*
import moe.rukamori.archivetune.extensions.filterBlockedArtists
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.home.HomeAction
import moe.rukamori.archivetune.home.HomePresentationPreferences
import moe.rukamori.archivetune.home.HomeScreenState
import moe.rukamori.archivetune.home.HomeUiState
import moe.rukamori.archivetune.home.hasVisibleContent
import moe.rukamori.archivetune.home.LoadCommunityPlaylistPreviewsUseCase
import moe.rukamori.archivetune.home.LoadPersonalizedQuickPicksUseCase
import moe.rukamori.archivetune.home.ObserveHomePresentationPreferencesUseCase
import moe.rukamori.archivetune.home.PrepareCommunityHomePageUseCase
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.khinsider.KhinsiderIds
import moe.rukamori.archivetune.home.JumpBackInItem
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.models.filterExplicit
import moe.rukamori.archivetune.innertube.models.filterVideo
import moe.rukamori.archivetune.innertube.pages.HomePage
import moe.rukamori.archivetune.innertube.utils.completed
import moe.rukamori.archivetune.models.SimilarRecommendation
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.utils.SpeedDialPinType
import moe.rukamori.archivetune.utils.SyncUtils
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.parseSpeedDialPins
import moe.rukamori.archivetune.utils.serializeSpeedDialPins
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

private data class HomeLocalContent(
    val jumpBackIn: List<JumpBackInItem>,
    val quickPicks: List<Song>,
    val speedDialItems: List<LocalItem>,
    val forgottenFavorites: List<Song>,
    val keepListening: List<LocalItem>,
)

private data class HomeRemoteContent(
    val homePage: HomePage?,
    val remoteQuickPicks: HomePage.Section?,
    val communitySection: HomePage.Section?,
    val similarRecommendations: List<SimilarRecommendation>,
)

private data class HomeContent(
    val local: HomeLocalContent,
    val remote: HomeRemoteContent,
    val selectedChip: HomePage.Chip?,
) {
    val hasContent: Boolean
        get() =
            local.jumpBackIn.isNotEmpty() ||
                local.quickPicks.isNotEmpty() ||
                local.speedDialItems.isNotEmpty() ||
                local.forgottenFavorites.isNotEmpty() ||
                local.keepListening.isNotEmpty() ||
                remote.remoteQuickPicks?.items?.isNotEmpty() == true ||
                remote.communitySection?.featuredCards?.isNotEmpty() == true ||
                remote.similarRecommendations.isNotEmpty() ||
                remote.homePage?.sections?.any { it.items.isNotEmpty() } == true
}

private data class HomeStateInputs(
    val content: HomeContent,
    val preferences: HomePresentationPreferences,
    val isLoading: Boolean,
    val isInitialLoadComplete: Boolean,
    val loadError: Int?,
) {
    fun toScreenState(
        isRefreshing: Boolean,
        isLoadingMore: Boolean,
    ): HomeScreenState {
        if (!isInitialLoadComplete) {
            return HomeScreenState.Loading
        }

        if (!content.hasContent) {
            if (loadError != null) {
                return HomeScreenState.Error(loadError)
            }
            if (isLoading) {
                return HomeScreenState.Loading
            }
            return HomeScreenState.Empty
        }

        return HomeScreenState.Success(
            HomeUiState(
                jumpBackIn = ImmutableList.copyOf(content.local.jumpBackIn),
                quickPicks = ImmutableList.copyOf(content.local.quickPicks),
                speedDialItems = ImmutableList.copyOf(content.local.speedDialItems),
                forgottenFavorites = ImmutableList.copyOf(content.local.forgottenFavorites),
                keepListening = ImmutableList.copyOf(content.local.keepListening),
                similarRecommendations = ImmutableList.copyOf(content.remote.similarRecommendations),
                homePage = content.remote.homePage,
                remoteQuickPicks = content.remote.remoteQuickPicks,
                communitySection = content.remote.communitySection,
                selectedChip = content.selectedChip,
                quickPicksDisplayMode = preferences.quickPicksDisplayMode,
                quickPicksMode = preferences.quickPicksMode,
                showCategoryChips = preferences.showCategoryChips,
                showTonalBackdrop = preferences.showTonalBackdrop,
                isRefreshing = isRefreshing,
                isLoadingMore = isLoadingMore,
            ),
        )
    }
}

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        observeHomePresentationPreferences: ObserveHomePresentationPreferencesUseCase,
        private val loadPersonalizedQuickPicksUseCase: LoadPersonalizedQuickPicksUseCase,
        private val prepareCommunityHomePageUseCase: PrepareCommunityHomePageUseCase,
        private val loadCommunityPlaylistPreviewsUseCase: LoadCommunityPlaylistPreviewsUseCase,
    ) : ViewModel() {
        private val isRefreshing = MutableStateFlow(false)
        private val isLoading = MutableStateFlow(false)
        private val isInitialLoadComplete = MutableStateFlow(false)
        private val loadError = MutableStateFlow<Int?>(null)
        private val isLoadingMore = MutableStateFlow(false)
        private val homeLoadMutex = Mutex()

        private val quickPicksMode =
            context.dataStore.data
                .map {
                    it[QuickPicksKey].toEnum(QuickPicks.QUICK_PICKS)
                }.distinctUntilChanged()

        private val quickPicks = MutableStateFlow<List<Song>?>(null)
        private val speedDialItems = MutableStateFlow<List<LocalItem>>(emptyList())
        private val forgottenFavorites = MutableStateFlow<List<Song>?>(null)
        private val keepListening = MutableStateFlow<List<LocalItem>?>(null)
        private val similarRecommendations = MutableStateFlow<List<SimilarRecommendation>?>(null)
        private val homePage = MutableStateFlow<HomePage?>(null)
        private val remoteQuickPicks = MutableStateFlow<HomePage.Section?>(null)
        private val communitySection = MutableStateFlow<HomePage.Section?>(null)
        private val selectedChip = MutableStateFlow<HomePage.Chip?>(null)
        private val previousHomePage = MutableStateFlow<HomePage?>(null)
        private val previousRemoteQuickPicks = MutableStateFlow<HomePage.Section?>(null)
        private val previousCommunitySection = MutableStateFlow<HomePage.Section?>(null)
        private val communityPreviewGeneration = AtomicLong(0L)
        private var communityPreviewJob: Job? = null

        private val _allLocalItems = MutableStateFlow<List<LocalItem>>(emptyList())
        val allLocalItems: StateFlow<List<LocalItem>> = _allLocalItems.asStateFlow()
        private val _allYtItems = MutableStateFlow<List<YTItem>>(emptyList())
        val allYtItems: StateFlow<List<YTItem>> = _allYtItems.asStateFlow()

        private val presentationPreferences = observeHomePresentationPreferences()

        /** Pixel Notes "Jump back in": most recently played albums, newest first, one card each. */
        private val jumpBackIn =
            database
                .recentSongs(300)
                .map { songs ->
                    songs
                        .asSequence()
                        .filter { !it.song.isPodcast }
                        .mapNotNull { song ->
                            val albumId =
                                song.album?.id
                                    ?: song.song.albumId
                                    ?: KhinsiderIds.albumSlug(song.id)?.takeIf { KhinsiderIds.isSong(song.id) }?.let(KhinsiderIds::albumId)
                                    ?: return@mapNotNull null
                            JumpBackInItem(
                                albumId = albumId,
                                albumTitle = song.album?.title ?: song.song.albumName ?: song.song.title,
                                thumbnailUrl = song.album?.thumbnailUrl ?: song.song.thumbnailUrl,
                                lastSongId = song.id,
                                lastSongTitle = song.song.title,
                            )
                        }.distinctBy { it.albumId }
                        .take(JUMP_BACK_IN_SIZE)
                        .toList()
                }.flowOn(Dispatchers.IO)
                .catch { emit(emptyList()) }

        private val localContent =
            combine(
                jumpBackIn,
                quickPicks,
                speedDialItems,
                forgottenFavorites,
                keepListening,
            ) { jumpBackIn, quickPicks, speedDialItems, forgottenFavorites, keepListening ->
                HomeLocalContent(
                    jumpBackIn = jumpBackIn,
                    quickPicks = quickPicks.orEmpty(),
                    speedDialItems = speedDialItems,
                    forgottenFavorites = forgottenFavorites.orEmpty(),
                    keepListening = keepListening.orEmpty(),
                )
            }

        /** Pixel Notes: "Liked songs" and "Liked albums" rows, slotted in after "Latest additions". */
        private val likedHomeSections =
            combine(
                database.likedSongsByCreateDateAsc(),
                database.albumsLikedByCreateDateAsc(),
            ) { songs, albums ->
                buildList {
                    val likedSongItems =
                        songs
                            .asReversed()
                            .take(LIKED_ROW_SIZE)
                            .map { song ->
                                SongItem(
                                    id = song.id,
                                    title = song.title,
                                    artists =
                                        song.artists.map { artist ->
                                            moe.rukamori.archivetune.innertube.models.Artist(name = artist.name, id = artist.id)
                                        },
                                    album =
                                        song.album?.let { album ->
                                            moe.rukamori.archivetune.innertube.models.Album(name = album.title, id = album.id)
                                        },
                                    duration = song.song.duration.takeIf { it >= 0 },
                                    thumbnail = song.song.thumbnailUrl.orEmpty(),
                                    explicit = song.song.explicit,
                                    endpoint = WatchEndpoint(videoId = song.id),
                                )
                            }
                    if (likedSongItems.isNotEmpty()) {
                        add(
                            HomePage.Section(
                                title = context.getString(R.string.liked_songs),
                                label = null,
                                thumbnail = null,
                                endpoint = BrowseEndpoint(browseId = LIKED_SONGS_SECTION_ID),
                                items = likedSongItems,
                            ),
                        )
                    }
                    val likedAlbumItems =
                        albums
                            .asReversed()
                            .take(LIKED_ROW_SIZE)
                            .map { album ->
                                AlbumItem(
                                    browseId = album.id,
                                    playlistId = album.album.playlistId ?: album.id,
                                    title = album.title,
                                    artists =
                                        album.artists
                                            .map { artist ->
                                                moe.rukamori.archivetune.innertube.models.Artist(name = artist.name, id = artist.id)
                                            }.ifEmpty { null },
                                    year = album.album.year,
                                    thumbnail = album.thumbnailUrl.orEmpty(),
                                    explicit = album.album.explicit,
                                )
                            }
                    if (likedAlbumItems.isNotEmpty()) {
                        add(
                            HomePage.Section(
                                title = "Liked albums",
                                label = null,
                                thumbnail = null,
                                endpoint = null,
                                items = likedAlbumItems,
                            ),
                        )
                    }
                }
            }.flowOn(Dispatchers.IO)

        private val homePageWithLiked =
            combine(homePage, likedHomeSections, selectedChip) { page, likedSections, chip ->
                if (page == null || chip != null || likedSections.isEmpty()) {
                    page
                } else {
                    val insertAt = if (page.sections.firstOrNull()?.title == LATEST_ADDITIONS_TITLE) 1 else 0
                    page.copy(
                        sections =
                            page.sections.toMutableList().apply {
                                addAll(insertAt.coerceAtMost(size), likedSections)
                            },
                    )
                }
            }

        private val remoteContent =
            combine(
                homePageWithLiked,
                remoteQuickPicks,
                similarRecommendations,
            ) { homePage, remoteQuickPicks, similarRecommendations ->
                HomeRemoteContent(
                    homePage = homePage,
                    remoteQuickPicks = remoteQuickPicks,
                    communitySection = null,
                    similarRecommendations = similarRecommendations.orEmpty(),
                )
            }.combine(communitySection) { content, communitySection ->
                content.copy(communitySection = communitySection)
            }

        private val homeContent =
            combine(
                localContent,
                remoteContent,
                selectedChip,
            ) { localContent, remoteContent, selectedChip ->
                HomeContent(
                    local = localContent,
                    remote = remoteContent,
                    selectedChip = selectedChip,
                )
            }

        val screenState: StateFlow<HomeScreenState> =
            combine(
                homeContent,
                presentationPreferences,
                isLoading,
                isInitialLoadComplete,
                loadError,
            ) { content, preferences, isLoading, isInitialLoadComplete, loadError ->
                HomeStateInputs(
                    content = content,
                    preferences = preferences,
                    isLoading = isLoading,
                    isInitialLoadComplete = isInitialLoadComplete,
                    loadError = loadError,
                )
            }.combine(
                combine(isRefreshing, isLoadingMore) { isRefreshing, isLoadingMore ->
                    isRefreshing to isLoadingMore
                },
            ) { inputs, loadingState ->
                inputs.toScreenState(
                    isRefreshing = loadingState.first,
                    isLoadingMore = loadingState.second,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeScreenState.Loading,
            )

        private var chipLoadJob: Job? = null

        private fun HomePage.extractQuickPicks(): Pair<HomePage, HomePage.Section?> {
            val quickPicksIndex =
                sections.indexOfFirst { section ->
                    section.title.equals(context.getString(R.string.quick_picks), ignoreCase = true) ||
                        section.title.contains("quick pick", ignoreCase = true)
                }
            if (quickPicksIndex < 0) return this to null

            return copy(sections = sections.toMutableList().apply { removeAt(quickPicksIndex) }) to sections[quickPicksIndex]
        }

        private fun applyRemoteHomePage(
            page: HomePage,
            replaceCommunitySection: Boolean,
        ) {
            val prepared =
                prepareCommunityHomePageUseCase(
                    homePage = page,
                    allowStructuralFallback = replaceCommunitySection || communitySection.value == null,
                )
            homePage.value = prepared.homePage
            val newCommunitySection = prepared.communitySection
            if (newCommunitySection != null) {
                updateCommunitySection(newCommunitySection)
            } else if (replaceCommunitySection) {
                updateCommunitySection(null)
            }
        }

        private fun updateCommunitySection(section: HomePage.Section?) {
            val generation = communityPreviewGeneration.incrementAndGet()
            communityPreviewJob?.cancel()
            communityPreviewJob = null
            communitySection.value = section
            if (section == null || section.featuredCards.all { card -> card.itemIds.isNotEmpty() }) return

            communityPreviewJob =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val enrichedSection =
                            filterCommunityPreviewSection(
                                loadCommunityPlaylistPreviewsUseCase(section),
                            )
                        if (communityPreviewGeneration.get() == generation) {
                            communitySection.value = enrichedSection
                            updateAllYtItems()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                    }
                }
        }

        private suspend fun filterCommunityPreviewSection(section: HomePage.Section): HomePage.Section {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            val blockedArtistIds = database.getBlockedArtistIds().toSet()
            val filteredItems =
                section.items
                    .filterExplicit(hideExplicit)
                    .filterVideo(hideVideo)
                    .filterBlockedArtists(blockedArtistIds)
            val visibleSongIds = filteredItems.filterIsInstance<SongItem>().mapTo(mutableSetOf(), SongItem::id)
            return section.copy(
                items = filteredItems,
                featuredCards =
                    section.featuredCards.map { card ->
                        card.copy(itemIds = card.itemIds.filter(visibleSongIds::contains))
                    },
            )
        }

        private suspend fun loadPersonalizedQuickPicks(): Boolean {
            if (quickPicksMode.first() != QuickPicks.QUICK_PICKS) return false
            val excludedSongIds = remoteQuickPicks.value?.items.orEmpty().mapTo(mutableSetOf(), YTItem::id)
            val songs =
                loadPersonalizedQuickPicksUseCase(excludedSongIds).getOrElse { throwable ->
                    if (throwable is CancellationException) throw throwable
                    reportException(throwable)
                    return false
                }
            if (songs.isEmpty()) return false

            remoteQuickPicks.value =
                HomePage.Section(
                    title = "",
                    label = null,
                    thumbnail = null,
                    endpoint = null,
                    items = songs,
                    numItemsPerColumn = 4,
                )
            return true
        }

        private fun List<Song>.toQuickPickSample(): List<Song> =
            filter { song -> !song.song.isPodcast && song.artists.none { it.blockedAt != null } }
                .distinctBy { it.id }
                .shuffled()
                .take(20)

        private fun List<Song>.hasSameSongIdsAs(other: List<Song>): Boolean {
            if (size != other.size) return false

            val ids = HashSet<String>(size)
            for (song in this) {
                ids += song.id
            }
            for (song in other) {
                if (!ids.remove(song.id)) return false
            }
            return ids.isEmpty()
        }

        private fun Flow<List<Song>>.distinctUntilSongIdsChanged(): Flow<List<Song>> =
            distinctUntilChanged { old, new -> old.hasSameSongIdsAs(new) }

        private fun updateAllLocalItems() {
            _allLocalItems.value =
                (quickPicks.value.orEmpty() + forgottenFavorites.value.orEmpty() + keepListening.value.orEmpty())
                    .filter { it is Song || it is Album }
        }

        private fun updateAllYtItems() {
            _allYtItems.value =
                similarRecommendations.value?.flatMap { it.items }.orEmpty() +
                    remoteQuickPicks.value?.items.orEmpty() +
                    communitySection.value?.items.orEmpty() +
                    homePage.value?.sections?.flatMap { it.items }.orEmpty()
        }

        private suspend fun quickPicksWithFallback(primary: List<Song>): List<Song> {
            val primaryPicks = primary.toQuickPickSample()
            if (primaryPicks.isNotEmpty()) return primaryPicks

            val recentPicks = database.recentSongs(limit = 60).first().toQuickPickSample()
            if (recentPicks.isNotEmpty()) return recentPicks

            return database.allSongs().first().toQuickPickSample()
        }

        private fun lastListenQuickPicksFlow(): Flow<List<Song>> =
            database
                .lastEventSongId()
                .distinctUntilChanged()
                .flatMapLatest { lastSongId ->
                    flow {
                        if (!lastSongId.isNullOrBlank() && database.hasRelatedSongs(lastSongId)) {
                            val relatedSongs = database.getRelatedSongs(lastSongId).first().toQuickPickSample()
                            if (relatedSongs.isNotEmpty()) {
                                emit(relatedSongs)
                                return@flow
                            }
                        }

                        emitAll(
                            database
                                .quickPicks()
                                .distinctUntilSongIdsChanged()
                                .map { songs -> quickPicksWithFallback(songs) },
                        )
                    }
                }

        private fun observeQuickPicks() {
            viewModelScope.launch(Dispatchers.IO) {
                quickPicksMode
                    .flatMapLatest { mode ->
                        when (mode) {
                            QuickPicks.QUICK_PICKS -> {
                                flowOf<List<Song>?>(null)
                            }

                            QuickPicks.LAST_LISTEN -> {
                                lastListenQuickPicksFlow()
                            }

                            QuickPicks.DONT_SHOW -> {
                                flowOf(null)
                            }
                        }
                    }.catch { throwable ->
                        reportException(throwable)
                        emit(quickPicksWithFallback(emptyList()))
                    }.collect { picks ->
                        quickPicks.value = picks
                        updateAllLocalItems()
                    }
            }
        }

        private suspend fun refreshQuickPicks() {
            val picks =
                when (quickPicksMode.first()) {
                    QuickPicks.QUICK_PICKS -> {
                        null
                    }

                    QuickPicks.LAST_LISTEN -> {
                        lastListenQuickPicksFlow().first()
                    }

                    QuickPicks.DONT_SHOW -> {
                        null
                    }
                }
            quickPicks.value = picks
            updateAllLocalItems()
        }

        private suspend fun loadSpeedDialItems() {
            val pins = parseSpeedDialPins(context.dataStore.get(SpeedDialSongIdsKey, ""))
            if (pins.isEmpty()) {
                speedDialItems.value = emptyList()
                return
            }
            val songIds = pins.filter { it.type == SpeedDialPinType.SONG }.map { it.id }
            val albumIds = pins.filter { it.type == SpeedDialPinType.ALBUM }.map { it.id }
            val artistIds = pins.filter { it.type == SpeedDialPinType.ARTIST }.map { it.id }
            val playlistIds = pins.filter { it.type == SpeedDialPinType.PLAYLIST }.map { it.id }

            val songsById = database.getSongsByIds(songIds).associateBy { it.id }
            val albumsById = albumIds.associateWith { id -> database.album(id).first() }
            val artistsById = artistIds.associateWith { id -> database.artist(id).first() }
            val playlistsById = playlistIds.mapNotNull { id -> database.getPlaylistById(id) }.associateBy { it.id }

            if (albumsById.any { (id, album) -> album != null && id != album.id } ||
                artistsById.any { (id, artist) -> artist != null && id != artist.id }
            ) {
                context.dataStore.edit { preferences ->
                    val currentPins = parseSpeedDialPins(preferences[SpeedDialSongIdsKey].orEmpty())
                    val resolvedPins = currentPins.map { pin ->
                        val canonicalId = when (pin.type) {
                            SpeedDialPinType.ALBUM -> albumsById[pin.id]?.id
                            SpeedDialPinType.ARTIST -> artistsById[pin.id]?.id
                            else -> null
                        }
                        canonicalId?.let { pin.copy(id = it) } ?: pin
                    }.distinct()
                    if (resolvedPins != currentPins) {
                        preferences[SpeedDialSongIdsKey] = serializeSpeedDialPins(resolvedPins)
                    }
                }
            }

            speedDialItems.value =
                pins
                    .mapNotNull { pin ->
                        when (pin.type.value) {
                            SpeedDialPinType.SONG.value -> songsById[pin.id]
                            SpeedDialPinType.ALBUM.value -> albumsById[pin.id]
                            SpeedDialPinType.ARTIST.value -> artistsById[pin.id]
                            SpeedDialPinType.PLAYLIST.value -> playlistsById[pin.id]
                            else -> null
                        }
                    }.filter { item ->
                        when (item) {
                            is Song -> item.artists.none { it.blockedAt != null }
                            is Album -> item.artists.none { it.blockedAt != null }
                            is Artist -> item.artist.blockedAt == null
                            else -> true
                        }
                    }
        }

        private suspend fun load() {
            homeLoadMutex.withLock {
                loadInternal()
            }
        }

        private suspend fun loadInternal() {
            isLoading.value = true
            loadError.value = null

            try {
                supervisorScope {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideo = context.dataStore.get(HideVideoKey, false)
                    val blockedArtistIds = database.getBlockedArtistIds().toSet()
                    val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2

                    launch { loadSpeedDialItems() }
                    val personalizedQuickPicks = async { loadPersonalizedQuickPicks() }
                    launch {
                        forgottenFavorites.value =
                            database
                                .forgottenFavorites()
                                .first()
                                .filter { song -> !song.song.isPodcast && song.artists.none { it.blockedAt != null } }
                                .shuffled()
                                .take(20)
                    }

                    launch {
                        val keepListeningSongs =
                            database
                                .mostPlayedSongs(fromTimeStamp, limit = 15, offset = 5)
                                .first()
                                .filter { song -> !song.song.isPodcast && song.artists.none { it.blockedAt != null } }
                                .shuffled()
                                .take(10)
                        val keepListeningAlbums =
                            database
                                .mostPlayedAlbums(fromTimeStamp, limit = 8, offset = 2)
                                .first()
                                .filter { it.album.thumbnailUrl != null && it.artists.none { artist -> artist.blockedAt != null } }
                                .shuffled()
                                .take(5)
                        val keepListeningArtists =
                            database
                                .mostPlayedMusicArtists(fromTimeStamp)
                                .first()
                                .filter {
                                    it.artist.blockedAt == null &&
                                        it.artist.isYouTubeArtist &&
                                        it.artist.thumbnailUrl != null
                                }.shuffled()
                                .take(5)
                        // Pixel Notes: one entry per album. Songs from an album already shown (or from the same
                        // album as another song) are dropped.
                        val shownAlbumIds = keepListeningAlbums.mapTo(HashSet()) { it.id }
                        val songsOnePerAlbum =
                            keepListeningSongs
                                .filter { song -> song.album?.id == null || song.album?.id !in shownAlbumIds }
                                .distinctBy { song -> song.album?.id ?: song.id }
                        keepListening.value = (songsOnePerAlbum + keepListeningAlbums + keepListeningArtists).shuffled()
                    }

                    launch {
                        val page =
                            YouTube.home().getOrElse { throwable ->
                                reportException(throwable)
                                loadError.value = R.string.error_unknown
                                return@launch
                            }
                        val filteredPage =
                            page.copy(
                                chips = page.chips,
                                sections =
                                    page.sections.map { section ->
                                        section.copy(
                                            items =
                                                section.items
                                                    .filterExplicit(hideExplicit)
                                                    .filterVideo(hideVideo)
                                                    .filterBlockedArtists(blockedArtistIds),
                                        )
                                    }.filter(HomePage.Section::hasVisibleContent),
                            )
                        val (pageWithoutQuickPicks, remoteQuickPicksFallback) = filteredPage.extractQuickPicks()
                        if (
                            quickPicksMode.first() == QuickPicks.QUICK_PICKS &&
                            !personalizedQuickPicks.await()
                        ) {
                            remoteQuickPicksFallback?.takeIf { it.items.isNotEmpty() }?.let { fallback ->
                                remoteQuickPicks.value = fallback
                            }
                        }
                        applyRemoteHomePage(
                            page = pageWithoutQuickPicks,
                            replaceCommunitySection = true,
                        )
                    }
                }

                updateAllLocalItems()

                // Pixel Notes: no "Similar to" rows on Home.
                similarRecommendations.value = emptyList()

                updateAllYtItems()

                isInitialLoadComplete.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportException(e)
                loadError.value = R.string.error_unknown
            } finally {
                isInitialLoadComplete.value = true
                isLoading.value = false
            }
        }

        private suspend fun loadSimilarRecommendations() {
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)
            val blockedArtistIds = database.getBlockedArtistIds().toSet()
            val fromTimeStamp = System.currentTimeMillis() - 86400000 * 7 * 2

            val artistRecommendations =
                database
                    .mostPlayedMusicArtists(fromTimeStamp, limit = 10)
                    .first()
                    .filter { it.artist.blockedAt == null && it.artist.isYouTubeArtist }
                    .shuffled()
                    .take(3)
                    .mapNotNull {
                        val items = mutableListOf<YTItem>()
                        YouTube.artist(it.id).onSuccess { page ->
                            items +=
                                page.sections
                                    .getOrNull(page.sections.size - 2)
                                    ?.items
                                    .orEmpty()
                            items +=
                                page.sections
                                    .lastOrNull()
                                    ?.items
                                    .orEmpty()
                        }
                        SimilarRecommendation(
                            title = it,
                            items =
                                items
                                    .filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo)
                                    .filterBlockedArtists(blockedArtistIds).shuffled()
                                    .ifEmpty { return@mapNotNull null },
                        )
                    }

            val songRecommendations =
                database
                    .mostPlayedSongs(fromTimeStamp, limit = 10)
                    .first()
                    .filter { !it.song.isPodcast && it.album != null }
                    .shuffled()
                    .take(2)
                    .mapNotNull { song ->
                        val endpoint =
                            YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull()?.relatedEndpoint
                                ?: return@mapNotNull null
                        val page = YouTube.related(endpoint).getOrNull() ?: return@mapNotNull null
                        SimilarRecommendation(
                            title = song,
                            items =
                                (
                                    page.songs.shuffled().take(8) +
                                        page.albums.shuffled().take(4) +
                                        page.artists.shuffled().take(4) +
                                        page.playlists.shuffled().take(4)
                                ).filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo)
                                    .filterBlockedArtists(blockedArtistIds).shuffled()
                                    .ifEmpty { return@mapNotNull null },
                        )
                    }

            similarRecommendations.value = (artistRecommendations + songRecommendations).shuffled()

            updateAllYtItems()
        }

        private fun loadMoreYouTubeItems(continuation: String?) {
            if (continuation == null || isLoadingMore.value) return
            val hideExplicit = context.dataStore.get(HideExplicitKey, false)
            val hideVideo = context.dataStore.get(HideVideoKey, false)

            viewModelScope.launch(Dispatchers.IO) {
                isLoadingMore.value = true
                try {
                    val blockedArtistIds = database.getBlockedArtistIds().toSet()
                    val nextSections = YouTube.home(continuation).getOrNull() ?: return@launch
                    val mergedSections = homePage.value?.sections.orEmpty() + nextSections.sections
                    val mergedPage =
                        nextSections.copy(
                            chips = homePage.value?.chips,
                            sections =
                                mergedSections.map { section ->
                                    section.copy(
                                        items =
                                            section.items
                                                .filterExplicit(hideExplicit)
                                                .filterVideo(hideVideo)
                                                .filterBlockedArtists(blockedArtistIds),
                                    )
                                }.filter(HomePage.Section::hasVisibleContent),
                        )
                    val (pageWithoutQuickPicks, _) = mergedPage.extractQuickPicks()
                    applyRemoteHomePage(
                        page = pageWithoutQuickPicks,
                        replaceCommunitySection = false,
                    )
                    updateAllYtItems()
                } finally {
                    isLoadingMore.value = false
                }
            }
        }

        private fun toggleChip(chip: HomePage.Chip?) {
            chipLoadJob?.cancel()
            if (chip == null || chip == selectedChip.value && previousHomePage.value != null) {
                homePage.value = previousHomePage.value
                remoteQuickPicks.value = previousRemoteQuickPicks.value
                updateCommunitySection(previousCommunitySection.value)
                previousHomePage.value = null
                previousRemoteQuickPicks.value = null
                previousCommunitySection.value = null
                selectedChip.value = null
                updateAllYtItems()
                return
            }

            if (selectedChip.value == null) {
                previousHomePage.value = homePage.value
                previousRemoteQuickPicks.value = remoteQuickPicks.value
                previousCommunitySection.value = communitySection.value
            }

            chipLoadJob =
                viewModelScope.launch(Dispatchers.IO) {
                    val hideExplicit = context.dataStore.get(HideExplicitKey, false)
                    val hideVideo = context.dataStore.get(HideVideoKey, false)
                    val blockedArtistIds = database.getBlockedArtistIds().toSet()
                    val nextSections =
                        YouTube.home(params = chip?.endpoint?.params).getOrElse { throwable ->
                            if (throwable is CancellationException) throw throwable
                            reportException(throwable)
                            return@launch
                        }
                    val filteredPage =
                        nextSections.copy(
                            chips = homePage.value?.chips,
                            sections =
                                nextSections.sections.map { section ->
                                    section.copy(
                                        items =
                                            section.items
                                                .filterExplicit(hideExplicit)
                                                .filterVideo(hideVideo)
                                                .filterBlockedArtists(blockedArtistIds),
                                    )
                                }.filter(HomePage.Section::hasVisibleContent),
                        )
                    val (pageWithoutQuickPicks, selectedQuickPicks) = filteredPage.extractQuickPicks()
                    remoteQuickPicks.value = selectedQuickPicks?.takeIf { it.items.isNotEmpty() }
                    applyRemoteHomePage(
                        page = pageWithoutQuickPicks,
                        replaceCommunitySection = true,
                    )
                    selectedChip.value = chip
                    updateAllYtItems()
                }
        }

        fun onAction(action: HomeAction) {
            when (action) {
                HomeAction.Refresh -> refresh()
                is HomeAction.SelectChip -> toggleChip(action.chip)
                is HomeAction.LoadMore -> loadMoreYouTubeItems(action.continuation)
            }
        }

        private fun refresh() {
            if (isRefreshing.value) return
            viewModelScope.launch(Dispatchers.IO) {
                isRefreshing.value = true
                try {
                    supervisorScope {
                        launch { load() }
                        launch { refreshQuickPicks() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportException(e)
                } finally {
                    isRefreshing.value = false
                }
            }
        }

        init {
            observeQuickPicks()

            viewModelScope.launch(Dispatchers.IO) {
                load()
            }

            viewModelScope.launch(Dispatchers.IO) {
                context.dataStore.data
                    .map { it[SpeedDialSongIdsKey].orEmpty() }
                    .distinctUntilChanged()
                    .collect {
                        loadSpeedDialItems()
                    }
            }


        }
    }

/** Pixel Notes: id of the Home "Liked songs" row; its header opens the liked songs playlist. */
const val LikedSongsHomeSectionId = "INSIDERTUNE_LIKED_SONGS"
private const val LIKED_SONGS_SECTION_ID = LikedSongsHomeSectionId
private const val LIKED_ROW_SIZE = 20
private const val LATEST_ADDITIONS_TITLE = "Latest additions"

private const val JUMP_BACK_IN_SIZE = 12
