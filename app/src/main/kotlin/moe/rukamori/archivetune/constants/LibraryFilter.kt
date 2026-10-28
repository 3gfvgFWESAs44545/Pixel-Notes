/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.constants

enum class LibraryFilter {
    SONGS,
    ARTISTS,
    ALBUMS,
    PLAYLISTS,
    LIBRARY,
}

val DefaultLibraryFilterOrder =
    // Pixel Notes: albums first; no Podcasts tab (khinsider has no podcasts).
    // Playlists and Artists open from buttons on the Library page instead of tabs.
    listOf(
        LibraryFilter.LIBRARY,
        LibraryFilter.ALBUMS,
        LibraryFilter.SONGS,
    )

val DefaultLibraryFilterOrderPreference =
    DefaultLibraryFilterOrder.joinToString(",") { it.name }

fun String.toLibraryFilterOrder(): List<LibraryFilter> {
    val savedOrder =
        split(",")
            .mapNotNull { savedFilter ->
                DefaultLibraryFilterOrder.firstOrNull { it.name == savedFilter.trim() }
            }.distinct()

    return savedOrder + DefaultLibraryFilterOrder.filterNot(savedOrder::contains)
}

fun List<LibraryFilter>.toLibraryFilterPreference(): String {
    val orderedFilters = distinct()
    return (orderedFilters + DefaultLibraryFilterOrder.filterNot(orderedFilters::contains))
        .joinToString(",") { it.name }
}
