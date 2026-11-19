package dev.sift.imaging

import dev.sift.model.ExportPreset
import dev.sift.model.GradeProfile
import dev.sift.model.GradeSettings
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Portfolio evidence generator. This deliberately exercises the production Sift
 * Pipeline on real, licensed photographic input, not image-editor adjustments.
 * Outputs retain the source photo and Sift JPEG separately for audit.
 */
class PortfolioCoverEvidenceTest {
    @Test
    fun gradeActualPhotographs() {
        val inputDir = System.getenv("SIFT_COVER_INPUT")?.let(::File) ?: return
        val outputDir = File(System.getenv("SIFT_COVER_OUTPUT") ?: error("Set SIFT_COVER_OUTPUT"))
        outputDir.mkdirs()
        val files = inputDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            ?.sortedBy { it.name }
            ?: error("Photo directory does not exist")
        require(files.isNotEmpty()) { "No real photographs supplied" }

        for (file in files) {
            val photo = ImageIO.read(file) ?: error("ImageIO cannot decode " + file.name)
            require(photo.width >= 400 && photo.height >= 300)
            val pixels = photo.getRGB(0, 0, photo.width, photo.height, null, 0, photo.width)
            val profile = if ("astronaut" in file.name) GradeProfile.PORTRAIT else GradeProfile.SCENE
            val source = FloatImage.fromArgb(photo.width, photo.height, pixels)
            val grade = Pipeline.process(
                Pipeline.Request(
                    source = Pipeline.SourceFrame(source),
                    settings = GradeSettings.VALIDATED_DEFAULTS.copy(upscale = GradeSettings.UpscaleMode.OFF),
                    preset = ExportPreset.MASTER,
                    forcedProfile = profile,
                    ditherSeed = 20261119L,
                    ownsSource = true,
                ),
            )
            val name = file.nameWithoutExtension
            val before = File(outputDir, "${name}-before.png")
            val after = File(outputDir, "${name}-after.jpg")
            ImageIO.write(photo, "png", before)
            after.writeBytes(grade.jpeg)
            val decoded = ImageIO.read(ByteArrayInputStream(grade.jpeg))
            require(decoded != null && decoded.width == photo.width && decoded.height == photo.height)
            var delta = 0.0
            val afterPixels = decoded.getRGB(0, 0, photo.width, photo.height, null, 0, photo.width)
            for (i in pixels.indices) {
                val a = pixels[i]
                val b = afterPixels[i]
                delta += abs(((a shr 16) and 255) - ((b shr 16) and 255))
                delta += abs(((a shr 8) and 255) - ((b shr 8) and 255))
                delta += abs((a and 255) - (b and 255))
            }
            val meanDelta = delta / (pixels.size * 3.0)
            val line = "${name},${photo.width},${photo.height},${profile},${grade.fellBackToOriginal},${grade.gates.allPassed},${"%.4f".format(java.util.Locale.US, meanDelta)},${grade.processingMs}"
            File(outputDir, "results.csv").appendText(line + "\n")
            println("SIFT_COVER_RESULT $line")
        }
    }
}
