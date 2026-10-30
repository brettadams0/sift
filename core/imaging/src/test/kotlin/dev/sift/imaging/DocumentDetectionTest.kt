package dev.sift.imaging

import dev.sift.testing.SyntheticFrames
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import javax.imageio.ImageIO
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Regression for the §6.3 document detector routing ordinary photographs to
 * NON_PHOTOGRAPHIC, where they are exported ungraded.
 *
 * The bimodality signal is Otsu's between-class variance over total variance.
 * That ratio is 2/π ≈ 0.64 for *any* normal distribution and 0.75 for a uniform
 * one, so the old 0.55 floor was met by every photograph. With the edge-density
 * floor also met by every photograph, the detector reduced to "mean chroma
 * below 8": a tabby cat, a black-and-white portrait and a dim ISS interior were
 * all treated as documents. Ten of 28 public-domain test photos were misrouted.
 */
class DocumentDetectionTest {

    @Test
    @DisplayName("A synthetic page of ink on paper is still detected as a document")
    fun inkOnPaperIsADocument() {
        val page = SyntheticFrames.toWorkingSpace(SyntheticFrames.document())
        assertTrue(FrameAnalyzer.analyze(page).isLikelyDocument)
    }

    @Test
    @DisplayName("A muted frame with a unimodal tonal spread is not a document")
    fun unimodalLowChromaFrameIsNotADocument() {
        // Grey, normally distributed lightness: the shape the old 0.55 floor
        // could not tell apart from ink on paper.
        val random = Random(7)
        val w = 400
        val h = 300
        val argb = IntArray(w * h) {
            val v = (128 + 40 * gaussian(random)).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val frame = ColorSpaces.toLinear(FloatImage.fromArgb(w, h, argb))
        assertFalse(FrameAnalyzer.analyze(frame).isLikelyDocument)
    }

    /**
     * Real photographs the old detector routed out. The cat leads because no
     * reading of it is a document; outdoor-portrait has the highest bimodality
     * of the 28 sampled photos (0.847) and is the closest to the new floor;
     * the freckles portrait is fully desaturated (mean chroma 0). Provenance
     * and sha256 are in `document-misroute/sources.json`.
     */
    @ParameterizedTest(name = "{0} is not a document")
    @ValueSource(
        strings = [
            "pexels-krysten-merriman-20787",
            "outdoor-portrait",
            "curly-hair-and-freckles-man",
        ],
    )
    fun misroutedPhotographsAreNotDocuments(slug: String) {
        val stream = javaClass.getResourceAsStream("/document-misroute/$slug.jpg")
            ?: error("missing fixture $slug.jpg")
        val decoded = stream.use { ImageIO.read(it) } ?: error("ImageIO could not decode $slug.jpg")
        val argb = decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width)
        val frame = ColorSpaces.toLinear(FloatImage.fromArgb(decoded.width, decoded.height, argb))
        assertFalse(FrameAnalyzer.analyze(frame).isLikelyDocument)
    }

    private fun gaussian(random: Random): Double {
        // Box-Muller; kotlin.random has no nextGaussian.
        val u = 1.0 - random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
    }
}
