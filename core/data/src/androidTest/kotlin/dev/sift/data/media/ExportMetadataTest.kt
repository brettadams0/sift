package dev.sift.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sift.imaging.JpegEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * The two things a graded export has to get right besides its pixels: where it
 * lands in the gallery, and which way up it is.
 *
 * Both were wrong on real hardware while every unit test passed, and both are
 * platform behaviour rather than logic — MediaStore rescanning a file when
 * `IS_PENDING` clears, and `ImageDecoder` applying EXIF orientation. Neither is
 * observable off-device, which is why they are here.
 */
@RunWith(AndroidJUnit4::class)
class ExportMetadataTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = MediaStoreRepository(context)
    private val written = mutableListOf<Uri>()

    @After
    fun tearDown() {
        ApprovalFixtures.cleanUp(context, written)
        // Exports go to Pictures/Sift rather than the fixture directory.
        for (uri in exported) runCatching { context.contentResolver.delete(uri, null, null) }
    }

    private val exported = mutableListOf<Uri>()

    /**
     * The reported bug: approved exports piled up at the top of the gallery
     * dated today instead of sitting beside the photograph they came from.
     *
     * `DATE_TAKEN` was being set at insert time, then silently overwritten when
     * clearing `IS_PENDING` made MediaStore rescan the finished file. The value
     * has to be written *after* the row settles.
     */
    @Test
    fun anExportKeepsTheCaptureDateOfItsSource() {
        val uri = export(dateTaken = CAPTURE_MILLIS)

        val actual = readLong(uri, MediaStore.Images.Media.DATE_TAKEN)
        assertNotNull("DATE_TAKEN was not set at all", actual)
        assertEquals(
            "the export is dated ${asDate(actual!!)} rather than its capture date",
            CAPTURE_MILLIS,
            actual,
        )
        assertTrue(
            "DATE_TAKEN must not be anywhere near now — that is the bug",
            abs(System.currentTimeMillis() - actual) > YEAR_MILLIS,
        )
    }

    /** DATE_MODIFIED is in seconds; a millisecond value here reads as year 56000. */
    @Test
    fun theModifiedDateIsInSecondsAndMatches() {
        val uri = export(dateTaken = CAPTURE_MILLIS)

        val modified = readLong(uri, MediaStore.Images.Media.DATE_MODIFIED)
        assertNotNull(modified)
        assertEquals(CAPTURE_MILLIS / 1000, modified)
    }

    /**
     * Google Photos does not read MediaStore — it dates a photo from EXIF
     * `DateTimeOriginal`. A source with no EXIF at all (a screenshot, a
     * messaging-app save) produced an export that was correct on the device and
     * dated today in the cloud.
     */
    @Test
    fun anExportFromAnUndatedSourceStillCarriesAnExifCaptureTime() {
        val uri = export(dateTaken = CAPTURE_MILLIS, sourceUri = null)

        val stamp = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        }
        assertNotNull("no EXIF capture time was written", stamp)
        assertEquals(
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(java.util.Date(CAPTURE_MILLIS)),
            stamp,
        )
    }

    /**
     * `DateTimeOriginal` is a wall clock with no zone, so on its own it does not
     * name an instant — and MediaProvider will not guess. It fills `DATE_TAKEN`
     * from the tag outright only when an offset tag says which zone the wall
     * clock is in; without one it compares against the file's modification time
     * and, when they disagree by more than a day, discards the value rather than
     * guessing wrong. Every export is a file written just now, so *every* photo
     * older than yesterday hit that and came back with `DATE_TAKEN` null.
     *
     * The assertion is a round trip rather than a literal offset, because the
     * right offset depends on the device's zone and the property that matters is
     * that the pair resolves to the original instant.
     */
    @Test
    fun anExportNamesTheZoneItsCaptureTimeIsIn() {
        val uri = export(dateTaken = CAPTURE_MILLIS, sourceUri = null)

        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        assertNotNull(exif)
        assertNotNull(
            "no offset tag — MediaStore will not trust the capture time without one",
            exif!!.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
        )
        assertEquals(
            "capture time and offset must resolve to the instant it was shot",
            CAPTURE_MILLIS,
            exif.dateTimeOriginal,
        )
    }

    /**
     * The real-world case: the source is a photograph that already carries a
     * capture time, and its camera never wrote an offset — which is most of
     * them. The zone is not invented, it is recovered, because the wall clock
     * read as UTC minus the instant MediaStore holds for the same photograph is
     * by definition the offset it was written in.
     */
    @Test
    fun aZonelessSourceHasItsZoneRecoveredRatherThanDropped() {
        val source = writeTaggedJpeg(
            width = 32,
            height = 24,
            orientation = ExifInterface.ORIENTATION_NORMAL,
            dateTimeOriginal = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .format(java.util.Date(CAPTURE_MILLIS)),
        )

        val uri = export(dateTaken = CAPTURE_MILLIS, sourceUri = source)

        val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        assertNotNull(exif)
        assertNotNull(
            "the source's zoneless capture time was carried over without a zone",
            exif!!.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),
        )
        assertEquals(CAPTURE_MILLIS, exif.dateTimeOriginal)
    }

    /**
     * The other reported bug: photos coming out rotated.
     *
     * `ImageDecoder` applies EXIF orientation itself — unlike `BitmapFactory` —
     * so the pipeline's own `Orientation.bake` on top of it rotated every frame
     * whose camera wrote a non-normal tag a *second* time. Landscape shots
     * tagged NORMAL were unaffected, which made it look intermittent.
     *
     * This asserts the contract rather than the mechanism: a source tagged
     * ROTATE_90 must decode to an upright frame, whichever layer does the work.
     * If a future Android stops orienting for us, this fails and says so.
     */
    @Test
    fun aRotatedSourceDecodesUprightExactlyOnce() {
        // 64 wide by 32 tall, with the top-left corner marked. Tagged ROTATE_90,
        // so an upright decode is 32 wide by 64 tall.
        val uri = writeTaggedJpeg(width = 64, height = 32, orientation = ExifInterface.ORIENTATION_ROTATE_90)

        val decoded = repo.decode(uri)

        assertEquals("width after orientation", 32, decoded.image.width)
        assertEquals("height after orientation", 64, decoded.image.height)
        assertEquals(
            "the orientation tag should still be reported for the export to record",
            ExifInterface.ORIENTATION_ROTATE_90,
            decoded.sourceOrientation,
        )
    }

    /** A normally-oriented source must come through untouched. */
    @Test
    fun anUprightSourceIsNotRotated() {
        val uri = writeTaggedJpeg(width = 64, height = 32, orientation = ExifInterface.ORIENTATION_NORMAL)

        val decoded = repo.decode(uri)

        assertEquals(64, decoded.image.width)
        assertEquals(32, decoded.image.height)
    }

    // ---- helpers ----------------------------------------------------------

    private fun export(dateTaken: Long, sourceUri: Uri? = null): Uri {
        val rgb = ByteArray(32 * 24 * 3) { 0x60 }
        val jpeg = JpegEncoder.encode(rgb, 32, 24, quality = 85)
        val uri = requireNotNull(
            repo.writeExport(
                jpeg = jpeg,
                displayName = "sift-export-test-${UUID.randomUUID()}.jpg",
                width = 32,
                height = 24,
                sourceUri = sourceUri,
                dateTakenMillis = dateTaken,
            ),
        ) { "writeExport returned null" }
        exported += uri
        return uri
    }

    /** A real JPEG in MediaStore carrying a chosen EXIF orientation tag. */
    private fun writeTaggedJpeg(
        width: Int,
        height: Int,
        orientation: Int,
        dateTimeOriginal: String? = null,
    ): Uri {
        val rgb = ByteArray(width * height * 3)
        for (i in rgb.indices) rgb[i] = 0x20
        // Mark the first row so a rotation is detectable beyond dimensions.
        for (x in 0 until width) {
            rgb[x * 3] = 0xF0.toByte()
            rgb[x * 3 + 1] = 0xF0.toByte()
            rgb[x * 3 + 2] = 0xF0.toByte()
        }
        val jpeg = JpegEncoder.encode(rgb, width, height, quality = 95)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "sift-orient-${UUID.randomUUID()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, ApprovalFixtures.TEST_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)?.use { it.write(jpeg) }
        resolver.openFileDescriptor(uri, "rw")?.use { fd ->
            ExifInterface(fd.fileDescriptor).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                // Deliberately no offset tag: that is what a real camera writes.
                dateTimeOriginal?.let { setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
                saveAttributes()
            }
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        written += uri
        return uri
    }

    private fun readLong(uri: Uri, column: String): Long? =
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    private fun asDate(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(millis))

    private companion object {
        /** 2019-03-14 09:26:53 UTC — comfortably far from any test run. */
        const val CAPTURE_MILLIS = 1_552_555_613_000L
        const val YEAR_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}
