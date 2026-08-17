package dev.sift.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sift.data.db.MediaAsset
import dev.sift.imaging.ColorSpaceTag
import dev.sift.imaging.FloatImage
import dev.sift.imaging.SourceMetadata
import dev.sift.model.LifecycleState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MediaStore ingest and export (§7, §6.11).
 */
@Singleton
class MediaStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * §7 — paginated, newest first, 200-row pages.
     *
     * The projection lists only what is needed and deliberately never includes
     * `DATA`: it is the deprecated raw filesystem path, it is unreadable under
     * scoped storage, and querying it costs a column of string allocation per
     * row for nothing.
     */
    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
    )

    /**
     * One page of the library.
     *
     * Paging goes through the **query-argument Bundle**, not through a
     * `"... LIMIT n OFFSET m"` suffix on the sort order. Appending SQL to the
     * sort string is the pre-Android-11 idiom and it is worse than deprecated
     * here: since API 30 MediaProvider parses that argument and rejects
     * anything containing SQL keywords, so the query returns **no rows at all**
     * rather than failing loudly. The result is an app that looks like it has an
     * empty camera roll.
     *
     * `QUERY_ARG_LIMIT` and `QUERY_ARG_OFFSET` are API 30, which is `minSdk`,
     * so there is no compatibility branch to keep.
     */
    fun page(limit: Int = PAGE_SIZE, offset: Int = 0): List<MediaAsset> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val queryArgs = Bundle().apply {
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media._ID),
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
            )
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
        }

        val assets = mutableListOf<MediaAsset>()
        resolver.query(collection, projection, queryArgs, null)
            ?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    // DATE_TAKEN is null for images with no EXIF; DATE_ADDED is
                    // in seconds. Clustering (§7) compares timestamps within a
                    // 10s window, so a unit mix-up here would silently stop every
                    // burst from collapsing.
                    val taken = cursor.getLong(takenColumn)
                        .takeIf { it > 0 }
                        ?: (cursor.getLong(addedColumn) * 1000L)

                    assets += MediaAsset(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        dateTaken = taken,
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        sizeBytes = cursor.getLong(sizeColumn),
                        mimeType = cursor.getString(mimeColumn) ?: "image/jpeg",
                        dHash = 0L,
                        clusterId = null,
                        analysisJson = null,
                        contentClass = null,
                        lifecycleState = LifecycleState.UNTRIAGED,
                        seenAt = null,
                    )
                }
            }
        return assets
    }

    /** §7 — `ContentObserver` for new captures. */
    fun observeChanges(): Flow<Unit> = callbackFlow {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(collection, true, observer)
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    /**
     * Decode a source frame with its orientation already baked in (§6.1 steps
     * 1–2).
     *
     * Baking here rather than later is trap #2: move it downstream and every
     * crop, every face box and every aspect decision is computed against the
     * wrong axes.
     */
    /**
     * @param maxLongEdge decode no larger than this on the long edge, or null
     *   for full resolution.
     *
     *   Ingest passes a small value. §7 asks for "dHash and lightweight
     *   analysis" during the library scan, and the difference is not academic:
     *   a full-resolution decode of a 12MP frame costs a ~48MB bitmap and a
     *   ~144MB float buffer, and the scan was paying that for every photo in the
     *   library purely to derive a 64-bit hash and a content class. Sampling
     *   down first gives the same hash and the same routing decision for a
     *   fraction of the work. Grading still decodes at full resolution, because
     *   there the pixels are the point.
     */
    @Throws(IOException::class)
    fun decode(uri: Uri, maxLongEdge: Int? = null): DecodedFrame {
        val source = ImageDecoder.createSource(resolver, uri)
        var isP3 = false

        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false

            if (maxLongEdge != null) {
                val longEdge = maxOf(info.size.width, info.size.height)
                // Powers of two only: setTargetSampleSize rounds to one anyway,
                // and asking for an exact size makes the decoder do a scaling
                // pass instead of simply skipping samples.
                var sample = 1
                while (longEdge / (sample * 2) >= maxLongEdge) sample *= 2
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }

            // §6.2 — read the source colour space; Samsung shoots sRGB or
            // Display P3 depending on settings.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val space = info.colorSpace
                isP3 = space != null && space.name.contains("Display P3", ignoreCase = true)
            }
        }

        val argb = IntArray(bitmap.width * bitmap.height)
        val safe = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
        } else {
            bitmap
        }
        safe.getPixels(argb, 0, safe.width, 0, 0, safe.width, safe.height)
        val width = safe.width
        val height = safe.height

        // Recycled *before* the float buffer is allocated, not after.
        //
        // At 12MP the bitmap is ~48MB, the int array another ~48MB, and the
        // FloatImage ~144MB. Holding the bitmap across that last allocation put
        // peak decode at ~240MB against a `largeHeap` ceiling that measured
        // 512MB on the CI emulator — and a 12MP grade OOMs there, so every
        // frame falls back to §12's half-resolution retry and quietly ships a
        // 2048px master. Freeing here costs nothing and takes ~48MB off the
        // peak; the ARGB array is still live because fromArgb reads it.
        safe.recycle()
        if (safe !== bitmap) bitmap.recycle()

        val image = FloatImage.fromArgb(width, height, argb)

        val exif = readExif(uri)
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        ) ?: ExifInterface.ORIENTATION_NORMAL

        // NOT `Orientation.bake(image, orientation)`.
        //
        // `ImageDecoder` already applies EXIF orientation — unlike
        // `BitmapFactory`, which does not. Baking again rotated every photo
        // whose camera wrote a non-normal orientation tag a second time: a
        // portrait shot tagged ROTATE_90 came out on its side, and because the
        // export is then written with TAG_ORIENTATION = NORMAL, nothing
        // downstream could undo it. Landscape shots tagged NORMAL were
        // untouched, which is why it looked intermittent rather than total.
        //
        // §6.1 step 2 is still satisfied — the orientation *is* baked into the
        // pixels before anything measures or crops (trap #2), just by the
        // decoder rather than by us. `ExportMetadataTest` pins that on a real
        // device, because it is a platform behaviour rather than one this code
        // controls.
        //
        // [orientation] is still reported so the export can record NORMAL and
        // callers can reason about the source; it is deliberately not applied.

        val hasExposure = exif != null && (
            exif.getAttribute(ExifInterface.TAG_F_NUMBER) != null ||
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) != null
            )

        return DecodedFrame(
            image = image,
            metadata = SourceMetadata(
                hasExifExposure = hasExposure,
                isDisplayP3 = isP3,
                mimeType = null,
            ),
            sourceOrientation = orientation,
        )
    }

    data class DecodedFrame(
        val image: FloatImage,
        val metadata: SourceMetadata,
        val sourceOrientation: Int,
    )

    /**
     * §12 — corrupt EXIF must never fail a frame. A missing or unreadable EXIF
     * block costs metadata, not the photograph.
     */
    private fun readExif(uri: Uri): ExifInterface? = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it) }
    }.getOrNull()

    /**
     * §6.11 step 5 — insert into `Pictures/Sift` with `IS_PENDING` held during
     * the write, so a half-written file never appears in the gallery.
     */
    fun writeExport(
        jpeg: ByteArray,
        displayName: String,
        width: Int,
        height: Int,
        sourceUri: Uri?,
        /**
         * The original capture time, in epoch millis.
         *
         * **This is not the same thing as copying EXIF.** §2.5 requires the EXIF
         * block to carry over, and it does — but gallery apps do not sort by
         * EXIF. They sort by MediaStore's own `DATE_TAKEN` column, which the
         * system fills with "now" for any newly inserted row unless it is set
         * explicitly. Copying EXIF alone produces exports that are internally
         * correct and yet pile up at the top of the gallery dated today,
         * detached from the day they were actually shot.
         */
        dateTakenMillis: Long?,
    ): Uri? {
        // Neither date column is written through the ContentResolver, because a
        // non-system app cannot write them. Three releases tried: at insert, in
        // the same update that clears IS_PENDING, and in a separate update after
        // it. The instrumented run that finally measured it recorded
        //
        //   export date: wanted=1552555613000 updateRows=0 err=null
        //                readback=datetaken=null date_added=... date_modified=...
        //
        // — zero rows changed and no exception, on both API 30 and 35.
        // MediaProvider drops DATE_TAKEN and DATE_MODIFIED from an update by a
        // non-system caller, leaves the values map empty and returns 0. Every
        // previous fix was writing to a column the provider silently ignores,
        // which is why each one looked plausible and changed nothing.
        //
        // Both columns are *derived*, and the file is the only input:
        //
        //   DATE_MODIFIED  <- the file's modification time on disk
        //   DATE_TAKEN     <- EXIF DateTimeOriginal, but only when the scanner
        //                     is willing to trust it (see stageWithExif)
        //
        // So the whole job is to hand the scanner a file that already says the
        // right thing, and then let it run. Steps 1 and 3 below are the fix; the
        // insert carries no dates at all any more.
        val staged = stageWithExif(jpeg, sourceUri, width, height, dateTakenMillis)

        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, EXPORT_RELATIVE_PATH)
            put(MediaStore.Images.Media.WIDTH, width)
            put(MediaStore.Images.Media.HEIGHT, height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values)
        if (uri == null) {
            staged.delete()
            return null
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                staged.inputStream().use { it.copyTo(out) }
            } ?: return null
        } catch (e: IOException) {
            // Leave nothing half-written behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } finally {
            staged.delete()

            // Step 2 — backdate the file itself, before the scan reads it.
            //
            // Closing the output stream sets the modification time to now, so
            // this has to happen after the bytes are written and before
            // IS_PENDING clears. The scan that finalisation triggers then picks
            // the capture time up as DATE_MODIFIED, which is what every
            // "recently modified" sort in a file manager or gallery orders by.
            val backdated = if (dateTakenMillis != null && dateTakenMillis > 0) {
                backdateFile(uri, dateTakenMillis)
            } else {
                "not requested"
            }

            // Step 3 — clear IS_PENDING, and nothing else. This is what makes
            // MediaProvider scan the finished file and fill in the row.
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
                .onFailure { Log.w(TAG, "export could not be finalised: $uri", it) }

            // Kept from the diagnostic build. It costs one query per export and
            // it is the only way to see, from a phone or from CI, whether a
            // graded photo landed in the right place in the gallery — which is
            // the one thing about this path that four attempts have shown is
            // not safe to assume from the code.
            if (dateTakenMillis != null && dateTakenMillis > 0) {
                Log.i(
                    TAG,
                    "export date: wanted=$dateTakenMillis backdate=$backdated " +
                        "readback=${readDateColumns(uri)}",
                )
            }
        }
        return uri
    }

    /**
     * Set an exported file's modification time to the capture time.
     *
     * MediaStore has no writable column for this — `DATE_MODIFIED` is read back
     * off the filesystem — so it has to be done to the file. The path comes from
     * the row's `DATA` column, which is deprecated for discovery but still the
     * only way to reach a file the app itself just created, and writing to it is
     * allowed precisely because Sift owns the row.
     *
     * A failure here costs sort position under "date modified" and nothing else:
     * `DATE_TAKEN` comes from EXIF and is unaffected, so this never fails an
     * export (§12 — metadata must not cost a photograph). It returns what
     * happened rather than throwing, so the outcome reaches the log line above.
     */
    private fun backdateFile(uri: Uri, dateTakenMillis: Long): String = runCatching {
        val path = resolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
            ?: return@runCatching "no DATA path"
        val file = File(path)
        if (!file.setLastModified(dateTakenMillis)) return@runCatching "refused by filesystem"
        "ok mtime=${file.lastModified()}"
    }.getOrElse { "failed: $it" }

    /**
     * §2.5 / §6.11 step 4 — copy all EXIF, then override exactly three fields.
     *
     * Losing capture date, lens, exposure and GPS is unprofessional and
     * unrecoverable. The three overrides are required because the pixels changed:
     * orientation is now baked in (so re-applying it would rotate twice), the
     * software tag should say what produced the file, and the dimensions are new.
     *
     * `ACCESS_MEDIA_LOCATION` is what makes the GPS tags survive at all — without
     * it MediaStore silently redacts them and the loss is invisible for months
     * (trap #12).
     */
    private fun stageWithExif(
        jpeg: ByteArray,
        sourceUri: Uri?,
        width: Int,
        height: Int,
        dateTakenMillis: Long?,
    ): File {
        val staged = File.createTempFile("sift-export", ".jpg", context.cacheDir)
        staged.writeBytes(jpeg)

        // §12 — metadata must never cost a photograph. A failure here leaves a
        // valid, correctly-graded JPEG with poorer metadata, which is why the
        // file is written first and decorated second.
        runCatching {
            val original = sourceUri?.let { source ->
                resolver.openInputStream(
                    MediaStore.setRequireOriginal(source),
                )?.use { ExifInterface(it) }
            }

            val target = ExifInterface(staged)
            if (original != null) {
                for (tag in COPIED_EXIF_TAGS) {
                    original.getAttribute(tag)?.let { target.setAttribute(tag, it) }
                }
            }
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.setAttribute(ExifInterface.TAG_SOFTWARE, "Sift")
            target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())

            // Two audiences, one tag. MediaStore's scanner fills DATE_TAKEN from
            // `DateTimeOriginal`, and Google Photos — which never reads
            // MediaStore — dates a photo from it too. Plenty of sources have
            // none: screenshots, messaging-app saves, anything already stripped.
            // The capture time Sift already knows is written when the source did
            // not supply one, and never over one that did, because the camera's
            // own value beats a reconstruction from a database.
            val formatter = SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US)
            var offset: String? = null
            val existing = target.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            if (existing == null) {
                if (dateTakenMillis != null && dateTakenMillis > 0) {
                    val stamp = formatter.format(Date(dateTakenMillis))
                    target.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                    target.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
                    if (target.getAttribute(ExifInterface.TAG_DATETIME) == null) {
                        target.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                    }
                    // The stamp was formatted in the device's current zone, so
                    // that zone is the one to declare.
                    offset = formatUtcOffset(TimeZone.getDefault().getOffset(dateTakenMillis))
                }
            } else if (target.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) == null) {
                offset = dateTakenMillis
                    ?.takeIf { it > 0 }
                    ?.let { deriveUtcOffset(existing, it) }
            }

            // Why the offset tag is the actual fix.
            //
            // `DateTimeOriginal` is a wall clock with no zone, so on its own it
            // does not name an instant. MediaProvider takes it at face value
            // only when an offset tag says which zone it is in; otherwise it
            // guesses the zone by comparing against the file's modification
            // time, and — this is the part that bit — when the two disagree by
            // more than a day it discards the value entirely rather than
            // guessing wrong. A graded export of anything shot before yesterday
            // hit exactly that: correct EXIF, freshly-written file, DATE_TAKEN
            // null. Declaring the offset removes the guess.
            //
            // For a source that was already dated but zoneless, the offset is
            // not invented: the wall clock read as UTC, minus the instant
            // MediaStore holds for the same photograph, *is* the zone it was
            // shot in.
            if (offset != null) {
                target.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
                if (target.getAttribute(ExifInterface.TAG_OFFSET_TIME) == null) {
                    target.setAttribute(ExifInterface.TAG_OFFSET_TIME, offset)
                }
            }
            target.saveAttributes()
        }.onFailure { Log.w(TAG, "EXIF could not be applied to $staged", it) }

        return staged
    }

    /**
     * The zone a zoneless EXIF timestamp was written in, recovered from the
     * instant MediaStore recorded for the same photograph.
     *
     * Returns null when the two disagree by more than any real zone offset —
     * which means one of them is not what it claims to be, and a fabricated
     * offset would be worse than none.
     */
    private fun deriveUtcOffset(wallClock: String, instant: Long): String? {
        val asUtc = runCatching {
            SimpleDateFormat(EXIF_DATE_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(wallClock)
        }.getOrNull() ?: return null
        // Rounded to the quarter hour: every real zone is a multiple of 15
        // minutes, and the two sources are only second-accurate.
        val raw = asUtc.time - instant
        val rounded = Math.round(raw / QUARTER_HOUR_MILLIS.toDouble()) * QUARTER_HOUR_MILLIS
        if (kotlin.math.abs(rounded) > MAX_ZONE_OFFSET_MILLIS) return null
        return formatUtcOffset(rounded.toInt())
    }

    /** EXIF 2.31 `OffsetTime` — `+HH:MM` / `-HH:MM`. */
    private fun formatUtcOffset(millis: Int): String {
        val total = millis / 60_000
        val sign = if (total < 0) '-' else '+'
        val abs = kotlin.math.abs(total)
        return String.format(Locale.US, "%c%02d:%02d", sign, abs / 60, abs % 60)
    }

    /**
     * What MediaStore actually holds for a row, for diagnosis.
     *
     * Temporary in spirit but cheap enough to keep: when an export turns up in
     * the wrong place in the gallery, this is the first question worth asking
     * and the answer is otherwise invisible on a phone.
     */
    private fun readDateColumns(uri: Uri): String = runCatching {
        val columns = arrayOf(
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        resolver.query(uri, columns, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use "no row"
            columns.indices.joinToString(" ") { i ->
                "${columns[i]}=" + if (c.isNull(i)) "null" else c.getLong(i).toString()
            }
        } ?: "query returned null"
    }.getOrElse { "query failed: $it" }

    /** §9.6 — refuse a batch below 2 GB free, with a clear message. */
    fun freeBytes(): Long = runCatching {
        val stat = StatFs(context.filesDir.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(Long.MAX_VALUE)

    fun hasSpaceForBatch(): Boolean = freeBytes() >= MIN_FREE_BYTES

    companion object {
        /**
         * EXIF 2.3 §4.6.4 — `yyyy:MM:dd HH:mm:ss`, a wall clock with no zone.
         * `Locale.US` because the pattern is a wire format, not a display one:
         * a locale with non-Latin digits produces a stamp nothing can parse.
         *
         * The pattern rather than a shared `SimpleDateFormat`, because
         * `SimpleDateFormat` is not thread-safe and grading runs frames in
         * parallel — a shared instance corrupts stamps under concurrency.
         */
        private const val EXIF_DATE_PATTERN = "yyyy:MM:dd HH:mm:ss"

        private const val QUARTER_HOUR_MILLIS = 15L * 60 * 1000

        /** UTC+14 (Kiritimati) is the largest offset in use. */
        private const val MAX_ZONE_OFFSET_MILLIS = 14L * 60 * 60 * 1000

        private const val TAG = "SiftMediaStore"

        const val PAGE_SIZE = 200
        const val EXPORT_RELATIVE_PATH = "Pictures/Sift"

        /**
         * Long edge used for the library scan (§7).
         *
         * Large enough that the skin mask, edge density and bimodality still
         * classify content correctly, small enough that scanning a few thousand
         * photos is minutes rather than hours. Grading re-analyses at full
         * resolution, so nothing downstream inherits this approximation.
         */
        const val SCAN_LONG_EDGE = 1024

        /** §9.6 — holding original + graded + exports roughly triples footprint. */
        const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

        /** §9.6 — cap on the pending-review backlog. Spec calls 300 a guess. */
        const val PENDING_REVIEW_CAP = 300

        private val COPIED_EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
        )
    }
}
