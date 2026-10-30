package dev.sift.samples

import dev.sift.imaging.ColorSpaces
import dev.sift.imaging.FloatImage
import dev.sift.imaging.Pipeline
import dev.sift.model.DerivedParams
import dev.sift.model.FrameAnalysis
import dev.sift.model.GateReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.sqrt

/**
 * Grades every `*.jpg` in an input directory with the unmodified [Pipeline] and
 * writes, per photo, the pipeline's own JPEG plus a JSON record of what it
 * decided and how far the pixels moved.
 *
 * This is the app's call, minus the Android half: `GradeWorker` passes the same
 * [Pipeline.Request] defaults, `ExportPreset.MASTER`, and `FaceDetector.None`
 * (`ModelPolicy.faceDetector` ships as `None`), so routing here matches the
 * device. Decoding is `ImageIO` rather than `BitmapFactory`; both hand over
 * gamma-encoded sRGB, and EXIF orientation is irrelevant to these inputs.
 *
 * Usage: `gradle :tools:samples:run --args="<in-dir> <out-dir>"`. Normally
 * driven by `make_assets.py`, which owns the inputs and composes the assets.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: grade-samples <in-dir> <out-dir>" }
    val inDir = File(args[0])
    val outDir = File(args[1]).apply { mkdirs() }
    val json = Json { prettyPrint = true; encodeDefaults = true }

    val inputs = inDir.listFiles { f -> f.extension.equals("jpg", ignoreCase = true) }
        ?.sortedBy { it.name }
        .orEmpty()
    require(inputs.isNotEmpty()) { "no .jpg files in $inDir" }

    for (file in inputs) {
        val decoded = ImageIO.read(file) ?: error("ImageIO could not decode $file")
        val w = decoded.width
        val h = decoded.height
        val argb = decoded.getRGB(0, 0, w, h, null, 0, w)
        val before = ByteArray(w * h * 3).also { buf ->
            argb.forEachIndexed { i, p ->
                buf[i * 3] = (p ushr 16).toByte()
                buf[i * 3 + 1] = (p ushr 8).toByte()
                buf[i * 3 + 2] = p.toByte()
            }
        }

        val result = Pipeline.process(
            Pipeline.Request(
                source = Pipeline.SourceFrame(FloatImage.fromArgb(w, h, argb)),
                // Fixed seed: rerunning the script reproduces the committed bytes.
                ditherSeed = 0L,
            ),
        )
        check(result.width == w && result.height == h) {
            "${file.name}: pipeline resized ${w}x$h to ${result.width}x${result.height}"
        }

        val stem = file.nameWithoutExtension
        File(outDir, "$stem.graded.jpg").writeBytes(result.jpeg)
        val record = Record(
            source = file.name,
            width = w,
            height = h,
            profile = result.profile.name,
            contentClass = result.contentClass.name,
            fellBackToOriginal = result.fellBackToOriginal,
            processingMs = result.processingMs,
            shift = measureShift(before, result.rgb),
            analysisBefore = result.analysisBefore,
            derived = result.derived,
            gates = result.gates,
        )
        File(outDir, "$stem.json").writeText(json.encodeToString(record))
        println(
            "%-52s %-8s dE76 mean %5.2f  dL* %+6.2f  da* %+6.2f  db* %+6.2f%s".format(
                file.name, record.profile, record.shift.meanDeltaE76,
                record.shift.meanDeltaL, record.shift.meanDeltaA, record.shift.meanDeltaB,
                if (record.fellBackToOriginal) "  (fell back)" else "",
            ),
        )
    }
}

@Serializable
data class Record(
    val source: String,
    val width: Int,
    val height: Int,
    val profile: String,
    val contentClass: String,
    val fellBackToOriginal: Boolean,
    val processingMs: Long,
    val shift: Shift,
    val analysisBefore: FrameAnalysis,
    val derived: DerivedParams,
    val gates: GateReport,
)

/**
 * Whole-frame movement between the decoded source and the pipeline's 8-bit
 * output, both read as sRGB and converted with the pipeline's own
 * [ColorSpaces.srgb8ToLab]. Means are over every pixel, unweighted.
 */
@Serializable
data class Shift(
    val meanDeltaL: Double,
    val meanDeltaA: Double,
    val meanDeltaB: Double,
    val meanDeltaE76: Double,
    val p95DeltaE76: Double,
    val beforeMeanLab: List<Double>,
    val afterMeanLab: List<Double>,
)

private fun measureShift(before: ByteArray, after: ByteArray): Shift {
    require(before.size == after.size)
    val n = before.size / 3
    val sumB = DoubleArray(3)
    val sumA = DoubleArray(3)
    val de = FloatArray(n)
    for (i in 0 until n) {
        val lb = lab(before, i)
        val la = lab(after, i)
        var sq = 0.0
        for (c in 0..2) {
            sumB[c] += lb[c]
            sumA[c] += la[c]
            val d = (la[c] - lb[c]).toDouble()
            sq += d * d
        }
        de[i] = sqrt(sq).toFloat()
    }
    de.sort()
    val mb = sumB.map { it / n }
    val ma = sumA.map { it / n }
    return Shift(
        meanDeltaL = ma[0] - mb[0],
        meanDeltaA = ma[1] - mb[1],
        meanDeltaB = ma[2] - mb[2],
        meanDeltaE76 = de.sumOf { it.toDouble() } / n,
        p95DeltaE76 = de[(n * 0.95).toInt().coerceAtMost(n - 1)].toDouble(),
        beforeMeanLab = mb,
        afterMeanLab = ma,
    )
}

private fun lab(rgb: ByteArray, i: Int): FloatArray = ColorSpaces.srgb8ToLab(
    rgb[i * 3].toInt() and 0xFF,
    rgb[i * 3 + 1].toInt() and 0xFF,
    rgb[i * 3 + 2].toInt() and 0xFF,
)

