/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistCreationRepository
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        suspend fun createLocalPlaylist(name: String) =
            withContext(Dispatchers.IO) {
                database.withTransaction {
                    insert(
                        PlaylistEntity(
                            name = name,
                            bookmarkedAt = LocalDateTime.now(),
                            isEditable = true,
                        ),
                    )
                }
            }
    }
