/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playlist

import moe.rukamori.archivetune.repository.PlaylistCreationRepository
import javax.inject.Inject

class CreatePlaylistUseCase
    @Inject
    constructor(
        private val repository: PlaylistCreationRepository,
    ) {
        suspend operator fun invoke(name: String) {
            val normalizedName = name.trim()
            require(normalizedName.isNotEmpty())
            repository.createLocalPlaylist(normalizedName)
        }
    }
