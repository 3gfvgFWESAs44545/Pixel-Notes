/*
 * Pixel Notes — khinsider source for ArchiveTune
 * GPL-3.0 License
 *
 * Scrapes downloads.khinsider.com and maps it onto ArchiveTune's YouTube-shaped models,
 * so the existing screens (home, search, album, browse, player) work unchanged.
 * Parsing rules follow the page structure used by khinsiderdl-web.
 */

package moe.rukamori.archivetune.innertube.khinsider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.models.Album
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.AlbumReleaseType
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.TimeUnit

object Khinsider {
    const val BASE_URL = "https://downloads.khinsider.com"
    private const val ALBUM_PATH = "/game-soundtracks/album/"
    internal const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    /** Headers the audio CDN expects. Passed to the player for every stream request. */
    val streamHeaders: Map<String, String> =
        mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$BASE_URL/",
        )

    internal val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(KhinsiderTraffic.interceptor())
            .build()
    }

    // ---------------------------------------------------------------- models

    data class KhAlbum(
        val slug: String,
        val title: String,
        val coverUrl: String?,
        val year: Int?,
        val albumType: String?,
        val artistName: String?,
        val platforms: List<String>,
        val tracks: List<KhTrack>,
        val related: List<KhAlbumSummary>,
    )

    data class KhTrack(
        val path: String,
        val title: String,
        val number: Int,
        val disc: Int?,
        val durationSeconds: Int?,
    )

    data class KhAlbumSummary(
        val slug: String,
        val title: String,
        val coverUrl: String?,
        val year: Int?,
        val albumType: String?,
        val platform: String?,
    )

    data class KhListPage(
        val title: String?,
        val albums: List<KhAlbumSummary>,
        val hasNextPage: Boolean,
    )

    data class KhStream(
        val url: String,
        val mimeType: String,
        val isFlac: Boolean,
        val contentLength: Long,
    )

    // ---------------------------------------------------------------- caches

    private class LruCache<K, V>(
        private val maxSize: Int,
    ) {
        private val map =
            Collections.synchronizedMap(
                object : LinkedHashMap<K, Pair<Long, V>>(maxSize, 0.75f, true) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Long, V>>?): Boolean = size > maxSize
                },
            )

        fun get(
            key: K,
            maxAgeMs: Long,
        ): V? {
            val entry = map[key] ?: return null
            if (System.currentTimeMillis() - entry.first > maxAgeMs) {
                map.remove(key)
                return null
            }
            return entry.second
        }

        fun put(
            key: K,
            value: V,
        ) {
            map[key] = System.currentTimeMillis() to value
        }
    }

    private val albumCache = LruCache<String, KhAlbum>(64)
    private val listCache = LruCache<String, KhListPage>(48)
    private val streamCache = LruCache<String, KhStream>(256)
    private const val ALBUM_TTL_MS = 30 * 60 * 1000L
    private const val LIST_TTL_MS = 15 * 60 * 1000L
    private const val STREAM_TTL_MS = 3 * 60 * 60 * 1000L

    // ---------------------------------------------------------------- http

    // ---------------------------------------------------------------- disk cache

    /** Set by the app (cacheDir/khinsider). Pages are stored here so the site isn't re-fetched constantly. */
    @Volatile
    var diskCacheDir: java.io.File? = null

    const val LIST_DISK_TTL_MS = 6 * 60 * 60 * 1000L
    const val HOME_DISK_TTL_MS = 3 * 60 * 60 * 1000L
    const val ALBUM_DISK_TTL_MS = 3 * 24 * 60 * 60 * 1000L
    private const val DISK_CACHE_MAX_FILES = 400

    private fun cacheFile(url: String): java.io.File? {
        val dir = diskCacheDir ?: return null
        val name =
            java.security.MessageDigest
                .getInstance("SHA-1")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$name.html")
    }

    /** Returns (finalUrl, html) if cached and younger than [maxAgeMs] (any age when [maxAgeMs] < 0). */
    private fun readCache(
        url: String,
        maxAgeMs: Long,
    ): Pair<String, String>? {
        val file = cacheFile(url)?.takeIf { it.isFile } ?: return null
        if (maxAgeMs >= 0 && System.currentTimeMillis() - file.lastModified() > maxAgeMs) return null
        return runCatching {
            val text = file.readText()
            text.substringBefore('\n') to text.substringAfter('\n')
        }.getOrNull()
    }

    private fun writeCache(
        url: String,
        finalUrl: String,
        html: String,
    ) {
        val file = cacheFile(url) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(finalUrl + "\n" + html)
            tmp.renameTo(file)
            val files = file.parentFile?.listFiles { f -> f.name.endsWith(".html") }.orEmpty()
            if (files.size > DISK_CACHE_MAX_FILES) {
                files.sortedBy { it.lastModified() }.take(files.size - DISK_CACHE_MAX_FILES).forEach { it.delete() }
            }
        }
    }

    private fun parse(
        html: String,
        baseUrl: String,
    ): Document =
        Jsoup.parse(html, baseUrl).apply {
            // Keep the original markup so the "Label: <b>value</b>" regexes match reliably.
            outputSettings().prettyPrint(false)
        }

    /**
     * Fetches a page. With [diskTtlMs] > 0 (and no cookie) a fresh-enough copy on the phone is used
     * instead of the network, and a stale copy is used if the network fails.
     */
    internal suspend fun fetchDocument(
        url: String,
        cookie: String? = null,
        referer: String = "$BASE_URL/",
        diskTtlMs: Long = 0,
    ): Document =
        withContext(Dispatchers.IO) {
            val useDisk = diskTtlMs > 0 && cookie.isNullOrBlank()
            if (useDisk) {
                readCache(url, diskTtlMs)?.let { (finalUrl, html) ->
                    KhinsiderTraffic.recordFromPhone(TrafficCategory.PAGES, "saved page · " + url.substringAfter(BASE_URL))
                    return@withContext parse(html, finalUrl)
                }
            }
            try {
                fetchNetwork(url, cookie, referer).also { (finalUrl, html) ->
                    if (useDisk) writeCache(url, finalUrl, html)
                }.let { (finalUrl, html) -> parse(html, finalUrl) }
            } catch (e: IOException) {
                if (useDisk) {
                    readCache(url, -1)?.let { (finalUrl, html) ->
                        KhinsiderTraffic.recordFromPhone(TrafficCategory.PAGES, "offline copy · " + url.substringAfter(BASE_URL))
                        return@withContext parse(html, finalUrl)
                    }
                }
                throw e
            }
        }

    private fun fetchNetwork(
        url: String,
        cookie: String?,
        referer: String,
    ): Pair<String, String> {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", referer)
                    .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                    .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("khinsider returned HTTP ${response.code} for $url")
                }
                response.request.url.toString() to response.body.string()
            }
    }

    private suspend fun headContentLength(url: String): Long =
        withContext(Dispatchers.IO) {
            runCatching {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .head()
                        .apply { streamHeaders.forEach { (name, value) -> header(name, value) } }
                        .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.header("Content-Length")?.toLongOrNull() ?: -1L else -1L
                }
            }.getOrDefault(-1L)
        }

    // ---------------------------------------------------------------- helpers

    internal fun clean(text: String?): String = text.orEmpty().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    internal fun Document.content(): Element = selectFirst("#pageContent") ?: body()

    /** Album slug from an album link or url, lower-cased like the site's canonical links. */
    fun slugFromHref(href: String?): String? {
        val value = href?.trim().orEmpty()
        if (value.isEmpty()) return null
        val path = runCatching { URI(value).path }.getOrNull() ?: value
        val index = path.indexOf(ALBUM_PATH)
        if (index < 0) return null
        return path
            .substring(index + ALBUM_PATH.length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    /**
     * khinsider only links small thumbnails in lists ("thumbs_small" 60px / "thumbs" 117px).
     * The full-size cover lives at the same path without the thumbs folder.
     */
    fun fullSizeImage(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val absolute = if (value.startsWith("//")) "https:$value" else value
        return absolute
            .replace("/thumbs_small/", "/")
            .replace("/thumbs_large/", "/")
            .replace("/thumbs/", "/")
    }

    private fun parseYear(text: String?): Int? =
        Regex("\\b(19|20)\\d{2}\\b")
            .find(text.orEmpty())
            ?.value
            ?.toIntOrNull()

    /** "1:05", "12:34" or "1:02:03" to seconds. */
    fun parseDuration(text: String?): Int? {
        val parts = clean(text).split(':')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.fold(0) { total, part -> total * 60 + part.toInt() }
    }

    /** Titles in ranked lists end with " (2011)"; drop it, the year is kept separately. */
    private fun stripTrailingYear(title: String): String = title.replace(Regex("\\s*\\((19|20)\\d{2}\\)\\s*$"), "").trim()

    // ---------------------------------------------------------------- lists

    internal fun parseAlbumRows(root: Element): List<KhAlbumSummary> {
        val albums = LinkedHashMap<String, KhAlbumSummary>()

        fun addRow(row: Element) {
            val links = row.select("a[href*=$ALBUM_PATH]")
            val titleLink = links.firstOrNull { clean(it.text()).isNotEmpty() } ?: return
            val slug = slugFromHref(titleLink.attr("href")) ?: return
            if (albums.containsKey(slug)) return
            val cells = row.children().filter { it.tagName() == "td" }
            val rawTitle = clean(titleLink.text())
            val trailingYear = Regex("\\((19|20)\\d{2}\\)\\s*$").find(rawTitle)?.value?.let(::parseYear)
            // Full browse/search tables: icon | title | platform | type | year
            val hasMetaColumns = cells.size >= 5
            val platform = if (hasMetaColumns) clean(cells[2].text()).takeIf { it.isNotEmpty() } else null
            val albumType = if (hasMetaColumns) clean(cells[3].text()).takeIf { it.isNotEmpty() } else null
            val year = if (hasMetaColumns) parseYear(cells[4].text()) else trailingYear
            albums[slug] =
                KhAlbumSummary(
                    slug = slug,
                    title = stripTrailingYear(rawTitle).ifEmpty { rawTitle },
                    coverUrl = fullSizeImage(row.selectFirst("img")?.absUrl("src")),
                    year = year,
                    albumType = albumType,
                    platform = platform,
                )
        }

        val listRows = root.select("table.albumList tr")
        if (listRows.isNotEmpty()) {
            listRows.forEach(::addRow)
        } else {
            // Ranked charts (Top 40, Most Favorites, ...) use plain rows with an albumIcon cell.
            root.select("tr").forEach { row ->
                if (row.selectFirst("td.albumIcon") != null || row.select("a[href*=$ALBUM_PATH]").size in 1..3) {
                    addRow(row)
                }
            }
        }
        return albums.values.toList()
    }

    /** The "Top 12" cover grid shown above paged lists (popular albums for that list). */
    internal fun parseTopGrid(root: Element): List<KhAlbumSummary> =
        root.select("td.albumIconLarge").mapNotNull { cell ->
            val link = cell.selectFirst("a[href*=$ALBUM_PATH]") ?: return@mapNotNull null
            val slug = slugFromHref(link.attr("href")) ?: return@mapNotNull null
            val title = clean(cell.selectFirst("p")?.text()).ifEmpty { clean(link.text()) }.ifEmpty { slug }
            KhAlbumSummary(
                slug = slug,
                title = title,
                coverUrl = fullSizeImage(cell.selectFirst("img")?.absUrl("src")),
                year = null,
                albumType = null,
                platform = null,
            )
        }

    internal fun parseHasNextPage(
        root: Element,
        page: Int,
    ): Boolean {
        val counter = clean(root.selectFirst(".pagination")?.text())
        val match = Regex("Page\\s+(\\d+)\\s+of\\s+(\\d+)", RegexOption.IGNORE_CASE).find(counter)
        if (match != null) {
            val total = match.groupValues[2].toIntOrNull() ?: return false
            return page < total
        }
        return root.select("a[href*=page=${page + 1}]").isNotEmpty()
    }

    suspend fun category(
        category: KhinsiderCategory,
        page: Int = 1,
    ): KhListPage {
        val cacheKey = "${category.key}|$page"
        listCache.get(cacheKey, LIST_TTL_MS)?.let {
            KhinsiderTraffic.recordFromPhone(TrafficCategory.PAGES, "memory · ${category.title}")
            return it
        }
        val url = if (page > 1) "$BASE_URL${category.path}?page=$page" else "$BASE_URL${category.path}"
        val document = fetchDocument(url, diskTtlMs = LIST_DISK_TTL_MS)
        val root = document.content()
        val rows = parseAlbumRows(root)
        val albums =
            if (page == 1 && category.paged) {
                // Popular albums first, then the alphabetical list.
                (parseTopGrid(root) + rows).distinctBy { it.slug }
            } else {
                rows
            }
        val result =
            KhListPage(
                title = category.title,
                albums = albums,
                hasNextPage = category.paged && parseHasNextPage(root, page),
            )
        listCache.put(cacheKey, result)
        return result
    }

    /** Albums from the "Latest Soundtracks" block on the homepage, newest first. */
    suspend fun latest(): List<KhAlbumSummary> {
        listCache.get("home-latest", LIST_TTL_MS)?.albums?.let { return it }
        val document = fetchDocument("$BASE_URL/", diskTtlMs = HOME_DISK_TTL_MS)
        val root = document.selectFirst("#homepageLatestSoundtracks") ?: document.content()
        val albums = parseAlbumRows(root)
        listCache.put("home-latest", KhListPage("Latest", albums, hasNextPage = false))
        return albums
    }

    suspend fun search(query: String): List<KhAlbumSummary> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val encoded = java.net.URLEncoder.encode(trimmed, "UTF-8")
        val document = fetchDocument("$BASE_URL/search?search=$encoded&sort=popularity")
        // A search with a single match can redirect straight to the album page.
        slugFromHref(document.location())?.let { slug ->
            val album = parseAlbum(slug, document)
            albumCache.put(slug, album)
            return listOf(album.toSummary())
        }
        return parseAlbumRows(document.content())
    }

    // ---------------------------------------------------------------- album

    suspend fun album(slug: String): KhAlbum {
        albumCache.get(slug, ALBUM_TTL_MS)?.let {
            KhinsiderTraffic.recordFromPhone(TrafficCategory.PAGES, "memory · album $slug")
            return it
        }
        val document = fetchDocument("$BASE_URL$ALBUM_PATH$slug", diskTtlMs = ALBUM_DISK_TTL_MS)
        val album = parseAlbum(slug, document)
        albumCache.put(slug, album)
        return album
    }

    private fun metaValue(
        html: String,
        label: String,
    ): String? {
        val escaped = Regex.escape(label)
        val patterns =
            listOf(
                Regex("$escaped\\s*:\\s*<b>\\s*<a[^>]*>\\s*([^<]+?)\\s*</a>", RegexOption.IGNORE_CASE),
                Regex("$escaped\\s*:\\s*<b>\\s*([^<]+?)\\s*</b>", RegexOption.IGNORE_CASE),
                Regex("$escaped\\s*:\\s*<a[^>]*>\\s*([^<]+?)\\s*</a>", RegexOption.IGNORE_CASE),
                Regex("$escaped\\s*:\\s*([^<\\n\\r]+)", RegexOption.IGNORE_CASE),
            )
        for (pattern in patterns) {
            val value = pattern.find(html)?.groupValues?.get(1)?.let { Jsoup.parse(it).text() }?.let(::clean)
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun linkedValues(
        html: String,
        label: String,
    ): List<String> {
        val block =
            Regex("${Regex.escape(label)}\\s*:(.*?)<br", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: return emptyList()
        val names = Jsoup.parse(block).select("a").map { clean(it.text()) }.filter { it.isNotEmpty() }
        return names.ifEmpty { listOfNotNull(clean(Jsoup.parse(block).text()).takeIf { it.isNotEmpty() }) }
    }

    private fun parseAlbum(
        requestedSlug: String,
        document: Document,
    ): KhAlbum {
        val root = document.content()
        val slug =
            slugFromHref(document.selectFirst("link[rel=canonical]")?.attr("href"))
                ?: slugFromHref(document.location())
                ?: requestedSlug
        val html = root.html()
        val title = clean(root.selectFirst("h2")?.text()).ifEmpty { slug.replace('-', ' ') }

        val images = root.select(".albumImage")
        val coverUrl =
            images.firstNotNullOfOrNull { element ->
                element.selectFirst("a")?.absUrl("href")?.takeIf { it.isNotBlank() }
                    ?: fullSizeImage(element.selectFirst("img")?.absUrl("src"))
            }

        val composers = linkedValues(html, "Composed by").ifEmpty { linkedValues(html, "Performed by") }
        val developers = linkedValues(html, "Developed by")
        val publishers = linkedValues(html, "Published by")
        val artistName =
            (composers.ifEmpty { developers }.ifEmpty { publishers })
                .take(3)
                .joinToString(", ")
                .takeIf { it.isNotEmpty() }

        val platforms = linkedValues(html, "Platforms")
        val year = metaValue(html, "Year")?.let(::parseYear)
        val albumType = metaValue(html, "Album type")

        val tracks = mutableListOf<KhTrack>()
        val songList = root.selectFirst("#songlist")
        val headers = songList?.selectFirst("tr#songlist_header")?.select("th")?.map { clean(it.text()).lowercase() }.orEmpty()
        val discColumn = headers.indexOf("cd")
        songList?.select("tr")?.forEach { row ->
            if (row.id().isNotEmpty()) return@forEach
            val cells = row.select("td")
            if (cells.size < 2) return@forEach
            val anchor =
                row
                    .select("a[href*=$ALBUM_PATH]")
                    .firstOrNull { clean(it.text()).isNotEmpty() && parseDuration(it.text()) == null }
                    ?: return@forEach
            val href = anchor.attr("href")
            val pathIndex = href.indexOf(ALBUM_PATH)
            if (pathIndex < 0) return@forEach
            val trackPath = href.substring(pathIndex + ALBUM_PATH.length).substringBefore('?')
            if (!trackPath.contains('/')) return@forEach
            val duration = cells.firstNotNullOfOrNull { parseDuration(it.text()) }
            val disc =
                if (discColumn >= 0) {
                    cells.getOrNull(discColumn)?.text()?.let(::clean)?.toIntOrNull()
                } else {
                    null
                }
            tracks +=
                KhTrack(
                    path = trackPath,
                    title = clean(anchor.text()),
                    number = tracks.size + 1,
                    disc = disc,
                    durationSeconds = duration,
                )
        }

        val related = mutableListOf<KhAlbumSummary>()
        root
            .select("h2")
            .firstOrNull { it.text().contains("also viewed", ignoreCase = true) }
            ?.nextElementSiblings()
            ?.firstOrNull { it.tagName() == "table" }
            ?.select("td")
            ?.forEach { cell ->
                val link = cell.selectFirst("a[href*=$ALBUM_PATH]") ?: return@forEach
                val relatedSlug = slugFromHref(link.attr("href")) ?: return@forEach
                if (relatedSlug == slug || related.any { it.slug == relatedSlug }) return@forEach
                related +=
                    KhAlbumSummary(
                        slug = relatedSlug,
                        title = clean(link.text()).ifEmpty { clean(cell.text()) }.ifEmpty { relatedSlug },
                        coverUrl = fullSizeImage(cell.selectFirst("img")?.absUrl("src")),
                        year = null,
                        albumType = null,
                        platform = null,
                    )
            }

        return KhAlbum(
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            year = year,
            albumType = albumType,
            artistName = artistName,
            platforms = platforms,
            tracks = tracks,
            related = related,
        )
    }

    // ---------------------------------------------------------------- streams

    /**
     * Resolves the direct audio file for a song id.
     * @param preferFlac use the FLAC file when the album offers one.
     */
    suspend fun stream(
        songId: String,
        preferFlac: Boolean,
    ): KhStream {
        val cacheKey = "$songId|$preferFlac"
        streamCache.get(cacheKey, STREAM_TTL_MS)?.let { return it }
        val trackPath = KhinsiderIds.songPath(songId) ?: throw IOException("Invalid khinsider song id: $songId")
        val document = fetchDocument("$BASE_URL$ALBUM_PATH$trackPath")
        val candidates = LinkedHashSet<String>()
        document.selectFirst("audio#audio")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let(candidates::add)
        document.select("a[href]").forEach { link ->
            val href = link.absUrl("href")
            if (Regex("\\.(mp3|flac|m4a|ogg)$", RegexOption.IGNORE_CASE).containsMatchIn(href.substringBefore('?'))) {
                candidates += href
            }
        }
        val mp3 = candidates.firstOrNull { it.substringBefore('?').endsWith(".mp3", ignoreCase = true) }
        val flac = candidates.firstOrNull { it.substringBefore('?').endsWith(".flac", ignoreCase = true) }
        val chosen =
            (if (preferFlac) flac ?: mp3 else mp3 ?: flac)
                ?: candidates.firstOrNull()
                ?: throw IOException("No audio file found for $trackPath")
        val extension = chosen.substringBefore('?').substringAfterLast('.').lowercase()
        val mimeType =
            when (extension) {
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                else -> "audio/mpeg"
            }
        val result =
            KhStream(
                url = chosen,
                mimeType = mimeType,
                isFlac = extension == "flac",
                contentLength = headContentLength(chosen),
            )
        streamCache.put(cacheKey, result)
        return result
    }

    // ---------------------------------------------------------------- mapping

    private fun KhAlbum.toSummary() =
        KhAlbumSummary(
            slug = slug,
            title = title,
            coverUrl = coverUrl,
            year = year,
            albumType = albumType,
            platform = platforms.firstOrNull(),
        )

    private fun releaseType(albumType: String?): AlbumReleaseType =
        if (albumType?.contains("single", ignoreCase = true) == true) AlbumReleaseType.SINGLE else AlbumReleaseType.ALBUM

    /** Subtitle shown under album cards: platform or album type. */
    private fun subtitleArtist(summary: KhAlbumSummary): List<Artist>? =
        listOfNotNull(summary.platform ?: summary.albumType)
            .filter { it.isNotBlank() }
            .map { Artist(name = it, id = null) }
            .ifEmpty { null }

    fun KhAlbumSummary.toAlbumItem(): AlbumItem =
        AlbumItem(
            browseId = KhinsiderIds.albumId(slug),
            playlistId = KhinsiderIds.playlistId(slug),
            title = title,
            artists = subtitleArtist(this),
            year = year,
            thumbnail = coverUrl.orEmpty(),
            releaseType = releaseType(albumType),
        )

    fun KhAlbum.toAlbumItem(): AlbumItem =
        AlbumItem(
            browseId = KhinsiderIds.albumId(slug),
            playlistId = KhinsiderIds.playlistId(slug),
            title = title,
            artists = listOfNotNull(artistName ?: platforms.firstOrNull()).map { Artist(name = it, id = null) }.ifEmpty { null },
            year = year,
            thumbnail = coverUrl.orEmpty(),
            releaseType = releaseType(albumType),
        )

    fun KhAlbum.toSongItems(): List<SongItem> {
        val albumRef = Album(name = title, id = KhinsiderIds.albumId(slug))
        val artists = listOf(Artist(name = artistName ?: title, id = null))
        val playlistId = KhinsiderIds.playlistId(slug)
        return tracks.mapIndexed { index, track ->
            val id = KhinsiderIds.songId(track.path)
            SongItem(
                id = id,
                title = track.title,
                artists = artists,
                album = albumRef,
                duration = track.durationSeconds,
                thumbnail = coverUrl.orEmpty(),
                endpoint = WatchEndpoint(videoId = id, playlistId = playlistId, index = index),
            )
        }
    }

    fun relatedEndpoint(slug: String) = BrowseEndpoint(browseId = KhinsiderIds.relatedId(slug))

    /** Decodes a track file name for display/debugging. */
    fun displayFileName(path: String): String = URLDecoder.decode(URLDecoder.decode(path.substringAfterLast('/'), "UTF-8"), "UTF-8")

    // ---------------------------------------------------------------- composite loads

    /** Loads several categories in parallel, skipping any that fail. */
    suspend fun categories(list: List<KhinsiderCategory>): List<Pair<KhinsiderCategory, KhListPage>> =
        coroutineScope {
            list
                .map { category -> category to async { runCatching { category(category) }.getOrNull() } }
                .mapNotNull { (category, deferred) -> deferred.await()?.let { category to it } }
        }
}
