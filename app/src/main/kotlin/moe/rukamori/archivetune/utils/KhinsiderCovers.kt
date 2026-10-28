/*
 * Pixel Notes — khinsider cover sizes.
 *
 * khinsider keeps four copies of every cover on its image server, in the album's folder:
 *   <dir>/cover.jpg               full size (often 1000–3000 px, ~1.8 MB on average)
 *   <dir>/thumbs_large/cover.jpg  200 px  (~50 KB)
 *   <dir>/thumbs/cover.jpg        117 px  (~20 KB)
 *   <dir>/thumbs_small/cover.jpg  60 px   (~6 KB)
 *
 * The app stores the full-size URL everywhere. [KhinsiderCoverInterceptor] swaps in the smallest
 * copy that looks sharp for the space being drawn, unless a better copy is already on the phone.
 * [LikedCoverStore] keeps full-size covers of liked albums and songs permanently (outside the
 * image cache, so browsing never clears them), resized to fit the screen.
 * GPL-3.0 License
 */

package moe.rukamori.archivetune.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil3.intercept.Interceptor
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.SuccessResult
import coil3.request.ImageResult
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.downloads.DownloadedArtworkRepository
import moe.rukamori.archivetune.innertube.khinsider.KhinsiderTraffic
import moe.rukamori.archivetune.innertube.khinsider.TrafficCategory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class KhinsiderCoverSize(
    val folder: String?,
    val pixels: Int,
) {
    THUMB("thumbs", 117),
    LARGE("thumbs_large", 200),
    FULL(null, Int.MAX_VALUE),
}

object KhinsiderCovers {
    private val thumbFolders = setOf("thumbs_small", "thumbs", "thumbs_large")

    /** True for covers on khinsider's image servers. */
    fun isKhinsiderCover(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val host = parsed.host
        val onImageHost = host.endsWith("vgmtreasurechest.com") || host.endsWith("vgmsite.com")
        return onImageHost && parsed.pathSegments.contains("soundtracks")
    }

    /** The full-size URL for any of the four copies. Keeps the original encoding of the path. */
    fun fullUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        val segments = parsed.encodedPathSegments
        if (segments.size < 2 || segments[segments.size - 2] !in thumbFolders) return url
        return rebuild(url, segments.dropLast(2) + segments.last())
    }

    /** The URL of [size]'s copy of the cover at [url]. */
    fun urlFor(
        url: String,
        size: KhinsiderCoverSize,
    ): String {
        val full = fullUrl(url)
        val folder = size.folder ?: return full
        val segments = full.toHttpUrlOrNull()?.encodedPathSegments ?: return full
        if (segments.isEmpty()) return full
        return rebuild(full, segments.dropLast(1) + folder + segments.last())
    }

    private fun rebuild(
        url: String,
        encodedSegments: List<String>,
    ): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        return parsed
            .newBuilder()
            .encodedPath("/" + encodedSegments.joinToString("/"))
            .build()
            .toString()
    }

    /**
     * Smallest copy that stays sharp in a [px]-pixel space. The 200 px copy is used up to grid-cover
     * size (it looked as good as full size there on a Pixel 9 Pro); anything bigger gets full size.
     */
    fun sizeFor(px: Int?): KhinsiderCoverSize =
        when {
            px == null -> KhinsiderCoverSize.FULL
            px <= 180 -> KhinsiderCoverSize.THUMB
            px <= 640 -> KhinsiderCoverSize.LARGE
            else -> KhinsiderCoverSize.FULL
        }
}

/** Swaps khinsider covers for the right-sized copy, preferring anything better already on the phone. */
class KhinsiderCoverInterceptor(
    private val context: Context,
    private val downloadedArtwork: DownloadedArtworkRepository,
    private val isInDiskCache: (String) -> Boolean,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val source =
            when (val data = request.data) {
                is String -> data
                is coil3.Uri -> data.toString()
                is android.net.Uri -> data.toString()
                else -> return chain.proceed()
            }
        if (!KhinsiderCovers.isKhinsiderCover(source)) return chain.proceed()
        val full = KhinsiderCovers.fullUrl(source)

        // 1. Liked album/song covers kept on the phone.
        LikedCoverStore.savedFile(context, full)?.let { file ->
            KhinsiderTraffic.recordFromPhone(TrafficCategory.COVER_FULL, "liked copy · " + full.substringAfterLast('/'))
            return chain.withRequest(request.newBuilder().data(file).memoryCacheKey("liked:$full").build()).proceed()
        }
        // 2. Covers saved with downloaded songs (handled by the downloads mapper).
        if (downloadedArtwork.resolveLocalFile(full) != null) {
            KhinsiderTraffic.recordFromPhone(TrafficCategory.COVER_FULL, "downloaded song · " + full.substringAfterLast('/'))
            return chain.withRequest(request.newBuilder().data(full).build()).proceed()
        }

        val wanted = KhinsiderCovers.sizeFor(chain.size.longestSidePx())
        // 3. A copy at least as good as the one wanted that's already in the image cache.
        val cached =
            KhinsiderCoverSize.entries
                .sortedByDescending { it.pixels }
                .filter { it.pixels >= wanted.pixels }
                .map { KhinsiderCovers.urlFor(full, it) }
                .firstOrNull(isInDiskCache)
        val target = cached ?: KhinsiderCovers.urlFor(full, wanted)

        val result = chain.withRequest(request.copyFor(target)).proceed()
        // Network downloads are counted by the HTTP client; count covers shown from the phone here.
        if (result is SuccessResult && result.dataSource != DataSource.NETWORK) {
            target.toHttpUrlOrNull()?.let { url ->
                KhinsiderTraffic.recordFromPhone(KhinsiderTraffic.categorize(url), "cache · " + url.pathSegments.takeLast(2).joinToString("/"))
            }
        }
        // 4. If a thumbnail is missing on the server, fall back to the full-size cover.
        if (result is ErrorResult && target != full) {
            return try {
                chain.withRequest(request.copyFor(full)).proceed()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                result
            }
        }
        return result
    }

    private fun coil3.request.ImageRequest.copyFor(url: String) =
        newBuilder()
            .data(url)
            .diskCacheKey(url)
            .memoryCacheKey(url)
            .build()

    private fun Size.longestSidePx(): Int? {
        val w = (width as? Dimension.Pixels)?.px
        val h = (height as? Dimension.Pixels)?.px
        return listOfNotNull(w, h).maxOrNull()
    }
}

/** Full-size covers of liked albums and songs, kept permanently and resized to fit the screen. */
object LikedCoverStore {
    private const val MAX_SIDE = 1280
    private const val DIRECTORY = "liked_covers"

    private val client by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(KhinsiderTraffic.interceptor(TrafficCategory.LIKED_COVERS))
            .build()
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    private fun fileName(fullUrl: String): String =
        MessageDigest
            .getInstance("SHA-1")
            .digest(fullUrl.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".jpg"

    fun savedFile(
        context: Context,
        fullUrl: String,
    ): File? = File(directory(context), fileName(fullUrl)).takeIf { it.isFile && it.length() > 0 }

    /** Makes the store hold exactly the covers in [urls]: saves new ones, deletes unliked ones. */
    suspend fun sync(
        context: Context,
        urls: Collection<String>,
    ) = withContext(Dispatchers.IO) {
        val dir = directory(context).apply { mkdirs() }
        val wanted =
            urls
                .filter(KhinsiderCovers::isKhinsiderCover)
                .map(KhinsiderCovers::fullUrl)
                .associateBy(::fileName)
        dir.listFiles()?.forEach { file -> if (file.name !in wanted) file.delete() }
        for ((name, url) in wanted) {
            val target = File(dir, name)
            if (target.isFile && target.length() > 0) continue
            try {
                save(url, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Try again on the next sync; the image cache still shows the cover meanwhile.
                target.delete()
            }
        }
    }

    private fun save(
        url: String,
        target: File,
    ) {
        val bytes =
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return
                response.body.bytes()
            }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return
        val longest = maxOf(decoded.width, decoded.height)
        val bitmap =
            if (longest > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / longest
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
            } else {
                decoded
            }
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        temp.renameTo(target)
    }
}
