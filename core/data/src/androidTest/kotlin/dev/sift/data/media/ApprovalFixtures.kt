package dev.sift.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import dev.sift.data.db.EditJob
import dev.sift.data.db.MediaAsset
import dev.sift.data.db.SiftDatabase
import dev.sift.imaging.JpegEncoder
import dev.sift.model.ContentClass
import dev.sift.model.GradeProfile
import dev.sift.model.JobState
import dev.sift.model.LifecycleState
import java.util.UUID

/**
 * Shared setup for the §14 device tests.
 *
 * Everything here writes **real files into MediaStore** and builds a real
 * [LifecycleRepository] over a real `ContentResolver`. That is the entire point:
 * §9.3's invariant 3 is "the output decodes, checked now rather than at write
 * time", and a fake resolver that always returns a valid bitmap would assert
 * nothing. The tests that matter here are the ones a JVM cannot answer.
 *
 * Files land in `Pictures/SiftAndroidTest/` and are removed by [cleanUp]. Scoped
 * storage lets an app delete what it created without any permission, so the
 * teardown needs no dialog and leaves nothing behind.
 */
object ApprovalFixtures {

    const val TEST_DIR = "Pictures/SiftAndroidTest"

    fun inMemoryDb(context: Context): SiftDatabase =
        Room.inMemoryDatabaseBuilder(context, SiftDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    fun repository(context: Context, db: SiftDatabase): LifecycleRepository =
        LifecycleRepository(context, db, TrashCoordinator(context))

    /**
     * Write a real JPEG of exactly [width] x [height] into MediaStore.
     *
     * A flat mid-grey frame: the content is irrelevant, the decodable header and
     * the exact dimensions are not — invariant 4 compares them against what the
     * preset promised.
     */
    fun writeJpeg(context: Context, width: Int, height: Int, name: String = randomName()): Uri {
        val rgb = ByteArray(width * height * 3) { 0x7F }
        val bytes = JpegEncoder.encode(rgb, width, height, quality = 90)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, TEST_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = requireNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "MediaStore refused the insert" }

        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("could not open $uri for writing")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return uri
    }

    /** Truncate a written file so it stops decoding — invariant 3's failure case. */
    fun truncate(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(ByteArray(0)) }
    }

    fun asset(
        id: Long,
        uri: Uri,
        width: Int,
        height: Int,
        state: LifecycleState = LifecycleState.UNTRIAGED,
        sizeBytes: Long = 4_000_000L,
    ) = MediaAsset(
        id = id,
        uri = uri.toString(),
        dateTaken = 1_600_000_000_000L + id,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        mimeType = "image/jpeg",
        dHash = id,
        clusterId = null,
        analysisJson = null,
        contentClass = ContentClass.SCENE,
        lifecycleState = state,
        seenAt = null,
    )

    fun job(
        assetId: Long,
        outputUri: Uri?,
        fellBack: Boolean = false,
        state: JobState = JobState.DONE,
        approvedAt: Long? = System.currentTimeMillis(),
    ) = EditJob(
        id = UUID.randomUUID().toString(),
        sourceAssetId = assetId,
        outputUri = outputUri?.toString(),
        profile = GradeProfile.SCENE,
        profileWasManual = false,
        derivedParamsJson = "{}",
        upscaleFactor = 1f,
        gateResultsJson = "{}",
        fellBackToOriginal = fellBack,
        processingMs = 1_000L + assetId,
        state = state,
        approvedAt = approvedAt,
        rejectedAt = null,
        rejectionReason = null,
        originalTrashedAt = null,
    )

    /** Remove everything this run wrote, whether or not the test passed. */
    fun cleanUp(context: Context, uris: Collection<Uri>) {
        for (uri in uris) {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    private fun randomName(): String = "sift-test-${UUID.randomUUID()}.jpg"
}
