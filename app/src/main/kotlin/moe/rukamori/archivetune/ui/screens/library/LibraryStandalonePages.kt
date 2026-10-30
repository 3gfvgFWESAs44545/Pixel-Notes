/*
 * Pixel Notes — Playlists and Artists as their own pages (opened from Library buttons)
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.ui.screens.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import moe.rukamori.archivetune.R

const val LibraryPlaylistsRoute = "library_playlists"
const val LibraryAlbumsRoute = "library_albums"

@Composable
fun LibraryAlbumsPage(navController: NavController) {
    LibraryStandalonePage(title = "Liked albums", navController = navController) {
        LibraryAlbumsScreen(
            navController = navController,
            onDeselect = { navController.navigateUp() },
        )
    }
}

@Composable
fun LibraryPlaylistsPage(navController: NavController) {
    LibraryStandalonePage(title = stringResource(R.string.filter_playlists), navController = navController) {
        LibraryPlaylistsScreen(
            navController = navController,
            filterContent = null,
            selectedTagIds = emptySet(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryStandalonePage(
    title: String,
    navController: NavController,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp) {
                    Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
                }
            },
            windowInsets = WindowInsets(0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalLibraryHeaderPadding provides 4.dp) {
                content()
            }
        }
    }
}
