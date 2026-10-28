/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.library

import moe.rukamori.archivetune.innertube.khinsider.KhinsiderAccount
import moe.rukamori.archivetune.khinsider.KhinsiderSyncManager
import timber.log.Timber
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

enum class LibrarySyncTarget {
    All,
    LikedSongs,
    Songs,
    Artists,
    Albums,
    Playlists,
}

enum class LibrarySyncFailure {
    RequestFailed,
}

sealed interface RefreshLibraryResult {
    data object Success : RefreshLibraryResult

    data class Failure(val reason: LibrarySyncFailure) : RefreshLibraryResult
}

/** Pixel Notes: refreshing the library syncs with khinsider when signed in; otherwise there is nothing to do. */
class RefreshLibraryUseCase
    @Inject
    constructor(
        private val khinsiderSync: KhinsiderSyncManager,
    ) {
        @Suppress("UNUSED_PARAMETER")
        suspend operator fun invoke(target: LibrarySyncTarget): RefreshLibraryResult {
            if (!KhinsiderAccount.isSignedIn) return RefreshLibraryResult.Success
            return try {
                val result = khinsiderSync.syncNow()
                if (result.errors.isEmpty()) {
                    RefreshLibraryResult.Success
                } else {
                    RefreshLibraryResult.Failure(LibrarySyncFailure.RequestFailed)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Timber.e(e, "khinsider sync failed")
                RefreshLibraryResult.Failure(LibrarySyncFailure.RequestFailed)
            }
        }
    }
