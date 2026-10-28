/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.navigation.NavController
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AppBarHeight
import moe.rukamori.archivetune.constants.ChipSortTypeKey
import moe.rukamori.archivetune.constants.DefaultLibraryFilterOrderPreference
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.LibraryChipOrderKey
import moe.rukamori.archivetune.constants.LibraryFilter
import moe.rukamori.archivetune.constants.PlaylistTagOrderKey
import moe.rukamori.archivetune.constants.ShowTagsInLibraryKey
import moe.rukamori.archivetune.constants.toLibraryFilterOrder
import moe.rukamori.archivetune.constants.toPlaylistTagOrder
import moe.rukamori.archivetune.db.entities.TagEntity
import moe.rukamori.archivetune.ui.component.TagsManagementDialog
import moe.rukamori.archivetune.ui.utils.resetHeightOffset
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference

internal val LibraryHeaderContentPadding = 64.dp

/** Top padding for library pages: room for the tab row, or less on standalone pages. */
internal val LocalLibraryHeaderPadding = androidx.compose.runtime.staticCompositionLocalOf { LibraryHeaderContentPadding }
internal val LibraryPullToRefreshIndicatorOffset = 0.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val defaultFilter by rememberEnumPreference(ChipSortTypeKey, LibraryFilter.LIBRARY)
    val database = LocalDatabase.current
    val (selectedTagIds, onSelectedTagIdsChange) = rememberPlaylistTagFilterState(database)
    val allTags by database.allTags().collectAsState(initial = emptyList())
    val (showTagsInLibrary) = rememberPreference(ShowTagsInLibraryKey, defaultValue = true)
    val (disableBlur) = rememberPreference(DisableBlurKey, false)
    val (libraryChipOrderPreference) =
        rememberPreference(
            LibraryChipOrderKey,
            defaultValue = DefaultLibraryFilterOrderPreference,
        )
    val (playlistTagOrderPreference) = rememberPreference(PlaylistTagOrderKey, defaultValue = "")
    var showTagsManagementDialog by rememberSaveable { mutableStateOf(false) }
    val activeSelectedTagIds = if (showTagsInLibrary) selectedTagIds else emptySet()
    val orderedTags =
        remember(allTags, playlistTagOrderPreference) {
            val tagsById = allTags.associateBy(TagEntity::id)
            playlistTagOrderPreference
                .toPlaylistTagOrder(allTags.map(TagEntity::id))
                .mapNotNull { tagId -> tagsById[tagId] }
        }
    val libraryFilters =
        remember(libraryChipOrderPreference) {
            libraryChipOrderPreference.toLibraryFilterOrder()
        }

    if (showTagsManagementDialog) {
        TagsManagementDialog(
            onDismiss = { showTagsManagementDialog = false },
        )
    }

    val pagerState =
        rememberPagerState(
            initialPage = libraryFilters.indexOf(defaultFilter).takeIf { it >= 0 } ?: 0,
        ) { libraryFilters.size }

    val currentFilter = libraryFilters.getOrElse(pagerState.currentPage) { LibraryFilter.LIBRARY }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val tonalStart = MaterialTheme.colorScheme.primaryContainer
    val tonalMiddle = MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (!disableBlur) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(430.dp)
                        .align(Alignment.TopCenter)
                        .drawWithCache {
                            val brush =
                                Brush.verticalGradient(
                                    0f to tonalStart.copy(alpha = 0.30f),
                                    0.42f to tonalMiddle.copy(alpha = 0.14f),
                                    1f to Color.Transparent,
                                )
                            onDrawBehind { drawRect(brush) }
                        },
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val offset = scrollBehavior.state.heightOffset.roundToInt().coerceAtMost(0)
                        val placeable = measurable.measure(
                            constraints.copy(
                                minHeight = (constraints.minHeight - offset).coerceAtLeast(0),
                                maxHeight = (constraints.maxHeight - offset).coerceAtLeast(0),
                            ),
                        )
                        layout(placeable.width, constraints.maxHeight) {
                            placeable.placeRelative(0, offset)
                        }
                    }
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = AppBarHeight),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
                // Pixel Notes: no Library/Albums/Songs tabs; Library is the only page.
                LibraryMixScreen(
                    navController = navController,
                    filterContent = null,
                    selectedTagIds = activeSelectedTagIds,
                    onTabSelected = { targetFilter ->
                        when (targetFilter) {
                            LibraryFilter.ALBUMS -> navController.navigate(LibraryAlbumsRoute)
                            LibraryFilter.ARTISTS -> navController.navigate(LibraryArtistsRoute)
                            LibraryFilter.PLAYLISTS -> navController.navigate(LibraryPlaylistsRoute)
                            else -> Unit
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistTagFilterRow(
    tags: List<TagEntity>,
    selectedTagIds: Set<String>,
    onSelectedTagIdsChange: (Set<String>) -> Unit,
    onManageTagsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all_playlist_tags", contentType = "playlist_tag_filter_action") {
            PlaylistTagFilterChip(
                label = stringResource(R.string.filter_all),
                selected = selectedTagIds.isEmpty(),
                iconRes = R.drawable.filter_alt,
                onClick = { onSelectedTagIdsChange(emptySet()) },
            )
        }

        items(
            items = tags,
            key = TagEntity::id,
            contentType = { "playlist_tag_filter" },
        ) { tag ->
            PlaylistTagFilterChip(
                label = tag.name,
                selected = tag.id in selectedTagIds,
                accentColor =
                    remember(tag.color) {
                        runCatching { Color(tag.color.toColorInt()) }.getOrDefault(Color.Unspecified)
                    },
                onClick = {
                    val nextSelection =
                        if (tag.id in selectedTagIds) {
                            selectedTagIds - tag.id
                        } else {
                            selectedTagIds + tag.id
                        }
                    onSelectedTagIdsChange(nextSelection)
                },
            )
        }

        item(key = "manage_playlist_tags", contentType = "playlist_tag_filter_action") {
            PlaylistTagFilterChip(
                label = stringResource(R.string.manage_tags),
                selected = false,
                iconRes = R.drawable.add,
                onClick = onManageTagsClick,
            )
        }
    }
}

@Composable
private fun PlaylistTagFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val resolvedAccentColor =
        if (accentColor == Color.Unspecified) {
            MaterialTheme.colorScheme.primary
        } else {
            accentColor
        }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue =
            if (isPressed) {
                0.92f
            } else if (selected) {
                1.05f
            } else {
                1.0f
            },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "PlaylistTagFilterChipScale",
    )
    val containerColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "PlaylistTagFilterChipContainerColor",
    )
    val contentColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "PlaylistTagFilterChipContentColor",
    )

    Row(
        modifier =
            modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(containerColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Box(
                modifier =
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (selected) contentColor else resolvedAccentColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = contentColor,
        )
    }
}

@Composable
fun ExpressiveTabChip(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue =
            if (isPressed) {
                0.92f
            } else if (selected) {
                1.05f
            } else {
                1.0f
            },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "TabChipScale",
    )

    val bgColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "TabChipBgColor",
    )

    val contentColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "TabChipContentColor",
    )

    Row(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(CircleShape)
                .background(bgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style =
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                ),
            color = contentColor,
        )
    }
}
