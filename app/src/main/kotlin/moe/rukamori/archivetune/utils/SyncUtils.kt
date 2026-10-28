/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.SongEntity
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

private const val SONG_LIKE_MUTATION_STRIPE_COUNT = 32

/**
 * Pixel Notes: saves likes for songs on the phone. The YouTube Music library sync that used to
 * live here was removed; khinsider favourites are synced by KhinsiderSyncManager.
 */
@Singleton
class SyncUtils
    @Inject
    constructor(
        private val database: MusicDatabase,
    ) {
        private val songLikeMutationMutexes = Array(SONG_LIKE_MUTATION_STRIPE_COUNT) { Mutex() }

        suspend fun likeSong(song: SongEntity): Result<SongEntity> {
            val mutationMutex = songLikeMutationMutexes[(song.id.hashCode() and Int.MAX_VALUE) % SONG_LIKE_MUTATION_STRIPE_COUNT]
            return mutationMutex.withLock {
                try {
                    Result.success(persistSongLike(song))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

        suspend fun likeSongs(songs: Collection<SongEntity>): Set<String> {
            val failedSongIds = linkedSetOf<String>()
            songs.distinctBy { it.id }.forEach { song ->
                if (likeSong(song).isFailure) failedSongIds += song.id
            }
            return failedSongIds
        }

        private suspend fun persistSongLike(song: SongEntity): SongEntity =
            database.withTransaction {
                val currentSong =
                    getSongById(song.id)?.song
                        ?: throw IllegalStateException("Cannot update missing song ${song.id}")
                val updatedSong =
                    currentSong.copy(
                        liked = song.liked,
                        likedDate =
                            if (song.liked) {
                                song.likedDate ?: currentSong.likedDate ?: LocalDateTime.now()
                            } else {
                                null
                            },
                        inLibrary = song.inLibrary,
                    )
                update(updatedSong)
                updatedSong
            }
    }
