/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.EventWithSong
import moe.rukamori.archivetune.utils.reportException
import timber.log.Timber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel
    @Inject
    constructor(
        val database: MusicDatabase,
    ) : ViewModel() {
        private val loadedEvents = mutableListOf<EventWithSong>()
        val events = MutableStateFlow<Map<DateAgo, List<EventWithSong>>>(emptyMap())
        val isLoadingMoreEvents = MutableStateFlow(false)
        val canLoadMoreEvents = MutableStateFlow(true)

        private val today = LocalDate.now()
        private val thisMonday = today.with(DayOfWeek.MONDAY)
        private val lastMonday = thisMonday.minusDays(7)

        init {
            loadMoreEvents()
        }

        fun loadMoreEvents() {
            if (isLoadingMoreEvents.value || !canLoadMoreEvents.value) return

            isLoadingMoreEvents.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val page = database.events(limit = HISTORY_PAGE_SIZE, offset = loadedEvents.size)
                    loadedEvents += page
                    events.value = loadedEvents.groupEventsByDate()
                    canLoadMoreEvents.value = page.size == HISTORY_PAGE_SIZE
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    reportException(exception)
                    canLoadMoreEvents.value = false
                } finally {
                    isLoadingMoreEvents.value = false
                }
            }
        }

        fun removeEventsFromHistory(eventIds: List<Long>) {
            val uniqueEventIds = eventIds.distinct()
            if (uniqueEventIds.isEmpty()) return

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    database.deleteEventsByIds(uniqueEventIds)
                    loadedEvents.clear()
                    events.value = emptyMap()
                    canLoadMoreEvents.value = true
                    loadMoreEvents()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Throwable) {
                    reportException(exception)
                }
            }
        }

        private fun List<EventWithSong>.groupEventsByDate(): Map<DateAgo, List<EventWithSong>> =
            groupBy {
                val date = it.event.timestamp.toLocalDate()
                val daysAgo = ChronoUnit.DAYS.between(date, today).toInt()
                when {
                    daysAgo == 0 -> DateAgo.Today
                    daysAgo == 1 -> DateAgo.Yesterday
                    date >= thisMonday -> DateAgo.ThisWeek
                    date >= lastMonday -> DateAgo.LastWeek
                    else -> DateAgo.Other(date.withDayOfMonth(1))
                }
            }.toSortedMap(
                compareBy { dateAgo ->
                    when (dateAgo) {
                        DateAgo.Today -> 0L
                        DateAgo.Yesterday -> 1L
                        DateAgo.ThisWeek -> 2L
                        DateAgo.LastWeek -> 3L
                        is DateAgo.Other -> ChronoUnit.DAYS.between(dateAgo.date, today)
                    }
                },
            ).mapValues { entry ->
                entry.value.distinctBy { it.song.id }
            }
    }

private const val HISTORY_PAGE_SIZE = 100

sealed class DateAgo {
    data object Today : DateAgo()

    data object Yesterday : DateAgo()

    data object ThisWeek : DateAgo()

    data object LastWeek : DateAgo()

    class Other(
        val date: LocalDate,
    ) : DateAgo() {
        override fun equals(other: Any?): Boolean {
            if (other is Other) return date == other.date
            return super.equals(other)
        }

        override fun hashCode(): Int = date.hashCode()
    }
}
