package dev.sift.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.sift.model.ContentClass
import dev.sift.model.GradeProfile
import dev.sift.model.JobState
import dev.sift.model.LifecycleState
import dev.sift.model.RejectionReason
import dev.sift.model.Verdict

/**
 * Enum converters.
 *
 * Stored as names, not ordinals. Ordinals would make reordering an enum a silent
 * data corruption — `LifecycleState` in particular, where a shifted ordinal
 * could turn `REJECTED` into `APPROVED` and hand an asset to the deletion batch.
 * Unknown names decode to a safe value rather than throwing, so a downgrade
 * cannot make the library unreadable.
 */
class Converters {
    @TypeConverter fun verdictToString(v: Verdict?): String? = v?.name

    @TypeConverter
    fun stringToVerdict(v: String?): Verdict? =
        v?.let { runCatching { Verdict.valueOf(it) }.getOrNull() }

    @TypeConverter fun lifecycleToString(v: LifecycleState?): String? = v?.name

    @TypeConverter
    fun stringToLifecycle(v: String?): LifecycleState? = v?.let {
        runCatching { LifecycleState.valueOf(it) }.getOrDefault(LifecycleState.UNTRIAGED)
    }

    @TypeConverter fun jobStateToString(v: JobState?): String? = v?.name

    @TypeConverter
    fun stringToJobState(v: String?): JobState? = v?.let {
        runCatching { JobState.valueOf(it) }.getOrDefault(JobState.FAILED)
    }

    @TypeConverter fun profileToString(v: GradeProfile?): String? = v?.name

    @TypeConverter
    fun stringToProfile(v: String?): GradeProfile? = v?.let {
        runCatching { GradeProfile.valueOf(it) }.getOrDefault(GradeProfile.NONE)
    }

    @TypeConverter fun contentClassToString(v: ContentClass?): String? = v?.name

    @TypeConverter
    fun stringToContentClass(v: String?): ContentClass? =
        v?.let { runCatching { ContentClass.valueOf(it) }.getOrNull() }

    @TypeConverter fun rejectionToString(v: RejectionReason?): String? = v?.name

    @TypeConverter
    fun stringToRejection(v: String?): RejectionReason? =
        v?.let { runCatching { RejectionReason.valueOf(it) }.getOrNull() }
}

/**
 * §4.2 — Room with explicit migrations from schema v1, schemas exported to
 * `core/data/schemas/` and committed.
 *
 * `fallbackToDestructiveMigration` is deliberately never called. This database
 * is the only record of which originals have been trashed and which grades fell
 * back; dropping it on a schema change would strand assets mid-lifecycle with no
 * way to tell which (§14.10).
 */
@Database(
    entities = [
        MediaAsset::class,
        TriageDecision::class,
        EditJob::class,
        LifecycleEvent::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SiftDatabase : RoomDatabase() {
    abstract fun mediaAssets(): MediaAssetDao
    abstract fun triageDecisions(): TriageDecisionDao
    abstract fun editJobs(): EditJobDao
    abstract fun lifecycleEvents(): LifecycleEventDao

    companion object {
        const val NAME = "sift.db"

        /**
         * v1 → v2: the §9.5 regrade overrides.
         *
         * Explicit rather than destructive (§4.2). This database is the only
         * record of which originals have already been trashed and which grades
         * fell back; recreating it would strand assets mid-lifecycle with no way
         * to tell which, so every schema change gets a real migration.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_assets ADD COLUMN pendingProfile TEXT")
                db.execSQL("ALTER TABLE media_assets ADD COLUMN pendingStrengthScale REAL")
            }
        }

        /**
         * v2 → v3: `edit_jobs.createdAt`.
         *
         * "The latest job for this asset" was resolved by ordering on
         * `processingMs` — the grade's *duration* — so it returned the slowest
         * job rather than the newest. `ApprovalGuard` reads that job's
         * `approvedAt` as §9.3 invariant 5, which made a stale approval able to
         * stand in for consent to a grade the user had never seen.
         *
         * Existing rows migrate in with 0 and are then ordered by `rowid`,
         * which for an append-only table is insertion order — the right answer
         * for history written before the column existed.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE edit_jobs ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
