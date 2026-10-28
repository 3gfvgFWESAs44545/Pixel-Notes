/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune

import moe.rukamori.archivetune.constants.KhinsiderCookieKey
import moe.rukamori.archivetune.constants.KhinsiderSyncFavoritesKey
import moe.rukamori.archivetune.constants.KhinsiderSyncHistoryKey
import moe.rukamori.archivetune.innertube.khinsider.KhinsiderAccount
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.appicon.RestoreLauncherEntryUseCase
import moe.rukamori.archivetune.canvas.StartCanvasPolicyUseCase
import moe.rukamori.archivetune.constants.*
import moe.rukamori.archivetune.downloads.DownloadedArtworkRepository
import moe.rukamori.archivetune.extensions.*
import moe.rukamori.archivetune.gatekeeper.GatekeeperResult
import moe.rukamori.archivetune.gatekeeper.RunGatekeeperCheckUseCase
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.YouTubeLocale
import moe.rukamori.archivetune.playback.stream.YoutubeiStreamRepository
import moe.rukamori.archivetune.storage.StorageFolderKind
import moe.rukamori.archivetune.storage.StorageLocationRepository
import moe.rukamori.archivetune.ui.screens.settings.ThemePalettes
import moe.rukamori.archivetune.ui.theme.ThemeSeedPalette
import moe.rukamori.archivetune.ui.theme.ThemeSeedPaletteCodec
import moe.rukamori.archivetune.utils.PlaylistCoverInterceptor
import moe.rukamori.archivetune.utils.PreferenceStore
import moe.rukamori.archivetune.utils.YTPlayerUtils
import moe.rukamori.archivetune.utils.clearPlaybackAuthSession
import moe.rukamori.archivetune.utils.clearPlaybackWebAuthSession
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.potoken.BotGuardTokenGenerator
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.utils.toPlaybackAuthState
import okhttp3.Dns
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import java.net.Proxy
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltAndroidApp
class App :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var runGatekeeperCheckUseCase: RunGatekeeperCheckUseCase

    @Inject
    lateinit var downloadedArtworkRepository: DownloadedArtworkRepository

    @Inject
    lateinit var playlistCoverInterceptor: PlaylistCoverInterceptor

    @Inject
    lateinit var database: moe.rukamori.archivetune.db.MusicDatabase

    @Inject
    lateinit var youtubeiStreamRepository: YoutubeiStreamRepository

    @Inject
    lateinit var startCanvasPolicy: StartCanvasPolicyUseCase

    @Inject
    lateinit var restoreLauncherEntry: RestoreLauncherEntryUseCase

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var isInitialized = false
    private val didRunImageCacheTrim = AtomicBoolean(false)

    private fun currentProcessName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val pid = android.os.Process.myPid()
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager
                ?.runningAppProcesses
                ?.firstOrNull { it.pid == pid }
                ?.processName
        }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
        if (currentProcessName()?.endsWith(":crash") == true) {
            Timber.plant(Timber.DebugTree())
            return
        }
        BotGuardTokenGenerator.initialize(this)
        PreferenceStore.start(this)
        LeakCanaryController.initialize(this)
        Timber.plant(Timber.DebugTree())
        try {
            Timber.plant(
                moe.rukamori.archivetune.utils
                    .GlobalLogTree(),
            )
        } catch (_: Exception) {
        }

        initializeGatekeeper()
        initializeCriticalSync()
        initializeDeferredAsync()
        initializeLauncherEntry()
    }

    private fun initializeLauncherEntry() {
        if (BuildConfig.DEVICE == "automotive") return
        applicationScope.launch(Dispatchers.IO) {
            try {
                restoreLauncherEntry()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.e(error, "Unable to restore launcher entry")
                reportException(error)
            }
        }
    }

    private fun initializeGatekeeper() {
        applicationScope.launch(Dispatchers.IO) {
            while (isActive) {
                val result = runGatekeeperCheckUseCase()
                when (result) {
                    GatekeeperResult.Allowed -> return@launch
                    GatekeeperResult.Unavailable -> Unit
                    is GatekeeperResult.Blocked -> if (!result.retryable) return@launch
                }
                delay(GATEKEEPER_RETRY_INTERVAL_MILLIS)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        youtubeiStreamRepository.trimMemory(level)
    }

    private fun initializeCriticalSync() {
        startCanvasPolicy.start(applicationScope)

        val locale = Locale.getDefault()
        val languageTag = locale.toLanguageTag().replace("-Hant", "")
        YouTube.locale =
            YouTubeLocale(
                gl = locale.country.takeIf { it in CountryCodeToName } ?: "US",
                hl =
                    locale.language.takeIf { it in LanguageCodeToName }
                        ?: languageTag.takeIf { it in LanguageCodeToName }
                        ?: "en",
            )
    }

    private fun initializeDeferredAsync() {
        // Pixel Notes: no YouTube warm-up at launch; everything plays from khinsider.
        applicationScope.launch(Dispatchers.IO) {
            try {
                val prefs = dataStore.data.first()

                prefs[ContentCountryKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { country ->
                    YouTube.locale = YouTube.locale.copy(gl = country)
                }
                prefs[ContentLanguageKey]?.takeIf { it != SYSTEM_DEFAULT }?.let { lang ->
                    YouTube.locale = YouTube.locale.copy(hl = lang)
                }

                if (prefs[UseLoginForBrowse] != false) {
                    YouTube.useLoginForBrowse = true
                }

                // Apply random theme on startup if enabled
                if (prefs[RandomThemeOnStartupKey] == true) {
                    val randomPalette = ThemePalettes.generateRandomPalette()
                    val seedPalette =
                        ThemeSeedPalette(
                            primary = randomPalette.primary,
                            secondary = randomPalette.secondary,
                            tertiary = randomPalette.tertiary,
                            neutral = randomPalette.neutral,
                        )
                    val encodedPalette = ThemeSeedPaletteCodec.encodeForPreference(seedPalette, "Random")
                    dataStore.edit { settings ->
                        settings[CustomThemeColorKey] = encodedPalette
                    }
                }

                isInitialized = true
            } catch (e: Exception) {
                Timber.e(e, "Error during deferred initialization")
                reportException(e)
            }
        }

        // Pixel Notes: proxy, DNS over HTTPS and ListenBrainz were removed. Clear any values
        // saved by older versions so passwords and tokens don't linger on the phone.
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                val removedKeys =
                    setOf(
                        "proxyEnabled", "proxyType", "proxyHost", "proxyPort", "proxyUsername", "proxyPassword",
                        "streamBypassProxy", "enableDnsOverHttps", "dnsOverHttpsProvider", "customDnsUrl",
                        "listenbrainz_enabled", "listenbrainz_token",
                    )
                dataStore.edit { prefs ->
                    prefs.asMap().keys.filter { it.name in removedKeys }.forEach { prefs.remove(it) }
                }
            }
        }

        // Pixel Notes: optional khinsider account settings.
        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { prefs ->
                    Triple(
                        prefs[KhinsiderCookieKey],
                        prefs[KhinsiderSyncFavoritesKey] ?: true,
                        prefs[KhinsiderSyncHistoryKey] ?: true,
                    )
                }.distinctUntilChanged()
                .collect { (cookie, syncFavorites, syncHistory) ->
                    if (cookie.isNullOrBlank()) KhinsiderAccount.signOut() else KhinsiderAccount.cookie = cookie
                    KhinsiderAccount.syncFavorites = syncFavorites
                    KhinsiderAccount.syncHistory = syncHistory
                }
        }
        // khinsider pages are cached on the phone so the site isn't re-fetched on every visit.
        moe.rukamori.archivetune.innertube.khinsider.Khinsider.diskCacheDir = java.io.File(cacheDir, "khinsider")
        // Favourite changes are made by pressing khinsider's own button in a hidden, signed-in WebView.
        KhinsiderAccount.browserFavoriteHandler = { slug, favorite ->
            moe.rukamori.archivetune.khinsider.KhinsiderWebActions.setFavorite(this, slug, favorite)
        }

        applicationScope.launch(Dispatchers.IO) {
            dataStore.data
                .map { it.toPlaybackAuthState() }
                .distinctUntilChanged()
                .collect { authState ->
                    val previousFingerprint = YouTube.currentPlaybackAuthState().fingerprint
                    YouTube.authState = authState
                    if (previousFingerprint != authState.fingerprint) {
                        YTPlayerUtils.clearPlaybackAuthCaches()
                        youtubeiStreamRepository.invalidateSessions()
                    }
                }
        }

        if (BuildConfig.DEVICE != "automotive") {
            try {
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    try {
                        val sw = StringWriter()
                        val pw = PrintWriter(sw)
                        throwable.printStackTrace(pw)
                        val stack = sw.toString()

                        val intent =
                            Intent(this@App, DebugActivity::class.java).apply {
                                putExtra(DebugActivity.EXTRA_STACK_TRACE, stack)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                        startActivity(intent)
                        try {
                            Thread.sleep(100)
                        } catch (_: InterruptedException) {
                        }
                    } catch (e: Exception) {
                        reportException(e)
                    } finally {
                        android.os.Process.killProcess(android.os.Process.myPid())
                        exitProcess(2)
                    }
                }
            } catch (e: Exception) {
                reportException(e)
            }
        }
        // Pixel Notes: khinsider traffic counter (Developer options), saved between launches.
        moe.rukamori.archivetune.utils.TrafficStatsStore.start(this, applicationScope)

        // Pixel Notes: keep full-size covers of liked albums and songs on the phone.
        applicationScope.launch(Dispatchers.IO) {
            combine(
                    database.likedAlbumThumbnailUrls(),
                    database.likedSongThumbnailUrls(),
                ) { albums, songs -> (albums + songs).toSet() }
                .distinctUntilChanged()
                .collectLatest { urls ->
                    kotlinx.coroutines.delay(3_000)
                    runCatching { moe.rukamori.archivetune.utils.LikedCoverStore.sync(this@App, urls) }
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val smartTrimmer = dataStore[SmartTrimmerKey] ?: false
        val imageCacheConfig = resolveImageDiskCacheConfig(dataStore[MaxImageCacheSizeKey])

        val diskCache =
            DiskCache
                .Builder()
                .directory(StorageLocationRepository.cacheDirectory(this, StorageFolderKind.IMAGE_CACHE))
                .maxSizeBytes(imageCacheConfig.maxSizeBytes)
                .build()

        if (smartTrimmer && imageCacheConfig.policy == CachePolicy.ENABLED && didRunImageCacheTrim.compareAndSet(false, true)) {
            applicationScope.launch(Dispatchers.IO) { trimImageDiskCache(diskCache) }
        }

        return ImageLoader
            .Builder(this)
            .crossfade(true)
            .allowHardware(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            .diskCache(diskCache)
            .diskCachePolicy(imageCacheConfig.policy)
            .components {
                // Pixel Notes: right-sized khinsider covers; better copies on the phone win.
                add(
                    moe.rukamori.archivetune.utils.KhinsiderCoverInterceptor(
                        context = this@App,
                        downloadedArtwork = downloadedArtworkRepository,
                        isInDiskCache = { key ->
                            val snapshot = runCatching { diskCache.openSnapshot(key) }.getOrNull()
                            snapshot?.close()
                            snapshot != null
                        },
                    ),
                )
                add(playlistCoverInterceptor)
                // Pixel Notes: count cover downloads for Developer options › khinsider traffic.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            okhttp3.OkHttpClient
                                .Builder()
                                .addInterceptor(moe.rukamori.archivetune.innertube.khinsider.KhinsiderTraffic.interceptor())
                                .build()
                        },
                    ),
                )
                add(downloadedArtworkRepository.coilMapper())
            }
            .build()
    }

    private fun trimImageDiskCache(diskCache: DiskCache) {
        try {
            val limitBytes = diskCache.maxSize
            if (limitBytes <= 0L || limitBytes == Long.MAX_VALUE) return

            val dir = java.io.File(diskCache.directory.toString())
            if (!dir.exists()) return

            val files =
                dir
                    .walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.lastModified() }
                    .toList()
            var currentSize = files.sumOf { it.length() }
            if (currentSize <= limitBytes) return

            for (file in files) {
                if (currentSize <= limitBytes) break
                val size = file.length()
                if (runCatching { file.delete() }.getOrDefault(false)) currentSize -= size
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val GATEKEEPER_RETRY_INTERVAL_MILLIS = 30_000L

        lateinit var instance: App
            private set

        fun forgetAccount(
            context: Context,
            clearWebAuthSession: Boolean = true,
        ) {
            if (clearWebAuthSession) {
                clearPlaybackWebAuthSession(context)
            }
            CoroutineScope(Dispatchers.IO).launch {
                context.dataStore.edit { settings ->
                    settings.clearPlaybackAuthSession()
                }
            }
        }
    }
}

internal data class ImageDiskCacheConfig(
    val policy: CachePolicy,
    val maxSizeBytes: Long,
)

internal fun resolveImageDiskCacheConfig(maxImageCacheSizeMb: Int?): ImageDiskCacheConfig {
    val sizeMb = maxImageCacheSizeMb ?: 512
    if (sizeMb == 0) return ImageDiskCacheConfig(policy = CachePolicy.DISABLED, maxSizeBytes = 1L)
    if (sizeMb < 0) return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = Long.MAX_VALUE)
    val bytesPerMb = 1024L * 1024L
    val safeSizeMb = sizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
    return ImageDiskCacheConfig(policy = CachePolicy.ENABLED, maxSizeBytes = safeSizeMb * bytesPerMb)
}
