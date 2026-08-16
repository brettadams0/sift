package dev.sift.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import dev.sift.app.work.GradeLog
import javax.inject.Inject

@HiltAndroidApp
class SiftApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * Image loading tuned for a triage deck rather than a feed.
     *
     * The default loader gave every screen a cold decode: swiping back one photo
     * re-read and re-decoded a frame that had been on screen a second earlier,
     * which is the stutter you feel on a fast run through a burst. Three
     * settings, each for a specific symptom:
     *
     * - **A generous memory cache** — a quarter of the app heap. The deck holds
     *   only a handful of full-size frames at a time, and `largeHeap` is already
     *   requested for the grading pipeline (§2.1), so this buys instant
     *   re-swipes and an instant before/after toggle in review using memory that
     *   would otherwise sit idle while the UI is up.
     * - **A disk cache**, so grid thumbnails survive a process restart and the
     *   second launch is not another full decode of the library.
     * - **Crossfade**, so frames fade in rather than appearing abruptly. The
     *   deck used to flicker on every decision.
     *
     * All of it is local. Coil is used here purely as a decoder and cache for
     * `content://` URIs — there is no network component to configure, and §3
     * means there could not be one.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_FRACTION)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(IMAGE_CACHE_DIR))
                    .maxSizeBytes(DISK_CACHE_BYTES)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        // §12 — failures go to a rotating local file; there is no network to
        // report them to and silently losing them is how a systematic problem
        // stays invisible.
        GradeLog.install(this)
    }

    private companion object {
        const val MEMORY_CACHE_FRACTION = 0.25
        const val DISK_CACHE_BYTES = 96L * 1024 * 1024
        const val IMAGE_CACHE_DIR = "image_cache"
    }
}
