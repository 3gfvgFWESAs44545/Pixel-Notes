/*
 * Pixel Notes — "Save to phone": real audio files in Music/Pixel Notes/<Album>/.
 *
 * Unlike offline downloads (kept inside the app in the player's own format), these are normal
 * MP3/FLAC files the user can see in their Files app, play elsewhere, copy or share. If a song is
 * already downloaded for offline use, the file is copied from that copy instead of downloading
 * it from khinsider again.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.constants.AudioQualityKey
import moe.rukamori.archivetune.di.DownloadCache
import moe.rukamori.archivetune.extensions.toEnum
import moe.rukamori.archivetune.innertube.khinsider.Khinsider
import moe.rukamori.archivetune.innertube.khinsider.KhinsiderIds
import moe.rukamori.archivetune.innertube.khinsider.KhinsiderTraffic
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SaveToPhoneEntryPoint {
    @DownloadCache
    fun downloadCache(): Cache
}

object SaveToPhone {
    private const val CHANNEL_ID = "save_to_phone"
    private const val BASE_FOLDER = "Pixel Notes"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Mutex()
    private val notificationIds = AtomicInteger(42_000)

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor(KhinsiderTraffic.interceptor())
            .build()
    }

    /** True when this phone can save to the Music folder without extra permissions (Android 10+). */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Saves the album with id [albumId] (khinsider "KHA_…" id), or only [songIds] from it.
     * Runs in the background; progress is shown in a notification.
     */
    fun save(
        context: Context,
        albumId: String?,
        songIds: Collection<String>? = null,
    ) {
        val app = context.applicationContext
        if (!isSupported) {
            Toast.makeText(app, "Saving to your Music folder needs Android 10 or newer", Toast.LENGTH_LONG).show()
            return
        }
        val slug = albumId?.let(KhinsiderIds::albumSlug)
        if (slug == null) {
            Toast.makeText(app, "Only khinsider albums and songs can be saved", Toast.LENGTH_LONG).show()
            return
        }
        val count = songIds?.size
        Toast
            .makeText(
                app,
                if (count == 1) "Saving to Music/Pixel Notes…" else "Saving album to Music/Pixel Notes…",
                Toast.LENGTH_SHORT,
            ).show()
        scope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queue.withLock { runSave(app, slug, songIds?.toSet()) }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun runSave(
        context: Context,
        slug: String,
        onlySongIds: Set<String>?,
    ) {
        val notificationId = notificationIds.incrementAndGet()
        val notifications = Notifier(context, notificationId)
        try {
            val album = Khinsider.album(slug)
            val folder = sanitize(album.title).ifBlank { slug }
            val tracks =
                album.tracks.filter { track -> onlySongIds == null || KhinsiderIds.songId(track.path) in onlySongIds }
            if (tracks.isEmpty()) {
                notifications.done("Nothing to save", album.title)
                return
            }
            val preferFlac = context.dataStore[AudioQualityKey].toEnum(AudioQuality.AUTO) == AudioQuality.HIGHEST
            val numberWidth = maxOf(2, tracks.maxOf { it.number }.toString().length)
            val downloadCache =
                runCatching {
                    EntryPointAccessors.fromApplication(context, SaveToPhoneEntryPoint::class.java).downloadCache()
                }.getOrNull()

            var saved = 0
            var skipped = 0
            var failed = 0
            tracks.forEachIndexed { index, track ->
                notifications.progress(album.title, index, tracks.size)
                val songId = KhinsiderIds.songId(track.path)
                val prefix =
                    buildString {
                        track.disc?.takeIf { disc -> album.tracks.mapNotNull { it.disc }.distinct().size > 1 }?.let { append(it).append('-') }
                        append(track.number.toString().padStart(numberWidth, '0'))
                    }
                val baseName = "$prefix - ${sanitize(track.title)}"
                try {
                    when (saveTrack(context, folder, baseName, songId, preferFlac, downloadCache)) {
                        TrackResult.SAVED -> saved++
                        TrackResult.ALREADY_THERE -> skipped++
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    reportException(e)
                    failed++
                }
            }
            val summary =
                buildList {
                    if (saved > 0) add("$saved saved")
                    if (skipped > 0) add("$skipped already there")
                    if (failed > 0) add("$failed failed")
                }.joinToString(", ")
            notifications.done("Saved to Music/$BASE_FOLDER/$folder", "${album.title} · $summary")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            reportException(e)
            notifications.done("Couldn't save", e.message ?: "Check your connection and try again")
        }
    }

    private enum class TrackResult { SAVED, ALREADY_THERE }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveTrack(
        context: Context,
        folder: String,
        baseName: String,
        songId: String,
        preferFlac: Boolean,
        downloadCache: Cache?,
    ): TrackResult =
        withContext(Dispatchers.IO) {
            val relativePath = "${Environment.DIRECTORY_MUSIC}/$BASE_FOLDER/$folder/"
            val resolver = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            // 1. Offline download already on the phone: copy it.
            val cachedLength = downloadCache?.fullyCachedLength(songId)
            if (downloadCache != null && cachedLength != null) {
                val extension = sniffExtension { openCache(downloadCache, songId) } ?: "mp3"
                val fileName = "$baseName.$extension"
                if (exists(context, relativePath, fileName)) return@withContext TrackResult.ALREADY_THERE
                write(context, collection, relativePath, fileName, mimeFor(extension)) { out ->
                    openCache(downloadCache, songId).use { it.copyTo(out) }
                }
                return@withContext TrackResult.SAVED
            }

            // 2. Otherwise fetch the file khinsider links on the track page.
            val stream = Khinsider.stream(songId, preferFlac = preferFlac)
            val extension = stream.url.substringBefore('?').substringAfterLast('.').lowercase().ifBlank { "mp3" }
            val fileName = "$baseName.$extension"
            if (exists(context, relativePath, fileName)) return@withContext TrackResult.ALREADY_THERE
            val request =
                Request
                    .Builder()
                    .url(stream.url)
                    .apply { Khinsider.streamHeaders.forEach { (name, value) -> header(name, value) } }
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("khinsider answered HTTP ${response.code}")
                write(context, collection, relativePath, fileName, stream.mimeType) { out ->
                    response.body.byteStream().use { it.copyTo(out) }
                }
            }
            TrackResult.SAVED
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exists(
        context: Context,
        relativePath: String,
        fileName: String,
    ): Boolean {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver
            .query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relativePath, fileName),
                null,
            )?.use { cursor -> return cursor.count > 0 }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun write(
        context: Context,
        collection: Uri,
        relativePath: String,
        fileName: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ) {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create $fileName")
        try {
            resolver.openOutputStream(uri)?.use(body) ?: throw IOException("Couldn't open $fileName")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            // Don't leave a half-written file behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun Cache.fullyCachedLength(key: String): Long? {
        val length = ContentMetadata.getContentLength(getContentMetadata(key))
        return if (length > 0 && isCached(key, 0, length)) length else null
    }

    /** Reads an offline download straight from the download cache (no network). */
    private fun openCache(
        cache: Cache,
        key: String,
    ): InputStream {
        val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(null).createDataSource()
        source.open(DataSpec.Builder().setUri(Uri.parse("pixelnotes://$key")).setKey(key).build())
        return object : InputStream() {
            private val one = ByteArray(1)

            override fun read(): Int = if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                val n = source.read(b, off, len)
                return if (n == C.RESULT_END_OF_INPUT) -1 else n
            }

            override fun close() = source.close()
        }
    }

    /** Works out whether cached audio is FLAC or MP3 from its first bytes. */
    private fun sniffExtension(open: () -> InputStream): String? =
        runCatching {
            open().use { input ->
                val head = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = input.read(head, read, 4 - read)
                    if (n <= 0) break
                    read += n
                }
                when {
                    read >= 4 && String(head, Charsets.US_ASCII) == "fLaC" -> "flac"
                    read >= 3 && String(head, 0, 3, Charsets.US_ASCII) == "ID3" -> "mp3"
                    read >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
                    read >= 4 && String(head, Charsets.US_ASCII) == "OggS" -> "ogg"
                    else -> null
                }
            }
        }.getOrNull()

    private fun mimeFor(extension: String): String =
        when (extension) {
            "flac" -> "audio/flac"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            else -> "audio/mpeg"
        }

    /** Makes a title safe to use as a file or folder name. */
    private fun sanitize(name: String): String =
        name
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
            .take(120)

    private class Notifier(
        private val context: Context,
        private val id: Int,
    ) {
        private val manager = NotificationManagerCompat.from(context)

        init {
            val system = context.getSystemService(NotificationManager::class.java)
            if (system?.getNotificationChannel(CHANNEL_ID) == null) {
                system?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Save to phone", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun allowed(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun progress(
            title: String,
            done: Int,
            total: Int,
        ) {
            if (!allowed()) return
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.download)
                    .setContentTitle("Saving to phone")
                    .setContentText("$title · ${done + 1} of $total")
                    .setProgress(total, done, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            runCatching { manager.notify(id, notification) }
        }

        fun done(
            title: String,
            text: String,
        ) {
            if (!allowed()) return
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.download)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build()
            runCatching { manager.notify(id, notification) }
        }
    }
}
