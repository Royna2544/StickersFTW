package com.royna.stickersftw.conversion

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Converts a whole corpus and checks every result against the rules WhatsApp
 * would apply, unattended.
 *
 * Conversion output is decided by the device's own decoders, so this is the
 * one part of the pipeline that cannot be checked on a desktop -- and it is
 * where the defects have actually been: frames that never display, a still
 * sticker inside an animated pack, a clip whose dimensions abort a decoder.
 * Each of those was found by importing a pack through the UI and reading the
 * bytes by hand afterwards, which costs about ten minutes a pack and only
 * looks at the pack somebody thought to look at.
 *
 * The corpus is whatever media sits in `cache/corpus`, so the same test grows
 * by pushing files rather than by being edited:
 *
 * ```sh
 * adb shell 'run-as com.royna.ftw mkdir -p cache/corpus'
 * adb push clip.webm /data/local/tmp/ && adb shell \
 *   "cat /data/local/tmp/clip.webm | run-as com.royna.ftw sh -c 'cat > cache/corpus/clip.webm'"
 * ```
 *
 * It is skipped, not failed, when the corpus is empty: an empty corpus means
 * nobody pushed one, which is not a defect in the converter.
 */
@RunWith(AndroidJUnit4::class)
class ConversionSweepInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val corpus = File(context.cacheDir, "corpus")
    private val outputDir = File(context.cacheDir, "corpus-out")

    private data class Report(
        val name: String,
        val type: StickerMediaType,
        val info: WebpInfo?,
        val failure: String?,
        val elapsedMs: Long,
    )

    /** The type the app would infer. Sniffed from magic bytes because a
     * corpus file has whatever name it was given, and Telegram's own
     * Content-Type is not available here. */
    private fun sniff(file: File): StickerMediaType {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        if (read < 4) return StickerMediaType.Unknown
        return when {
            // .tgs is gzipped JSON.
            head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() -> StickerMediaType.AnimatedLottie
            // WebM/Matroska starts with an EBML header.
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> StickerMediaType.Video
            // ISO base media (mp4/mov) carries "ftyp" in the second word, not
            // the first -- reading only the first four bytes classified an mp4
            // as a still image and then reported the converter had failed on it.
            read >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> StickerMediaType.Video
            String(head, 0, 4, Charsets.US_ASCII) == "RIFF" -> StickerMediaType.Static
            else -> StickerMediaType.Static
        }
    }

    @Test
    fun everyConvertedStickerSatisfiesWhatsappsRules() {
        val sources = corpus.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        assumeTrue("no corpus pushed to ${corpus.absolutePath}", sources.isNotEmpty())
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val reports = sources.map { source ->
            val type = sniff(source)
            val output = File(outputDir, "${source.name}.webp")
            val startedAt = System.currentTimeMillis()
            val result = runBlocking {
                StickerConversionPipeline.convertForWhatsapp(context, source, output, type)
            }
            val elapsed = System.currentTimeMillis() - startedAt
            when (result) {
                is StickerConvertResult.Failed ->
                    Report(source.name, type, null, result.reason, elapsed)
                is StickerConvertResult.Success ->
                    Report(source.name, type, WebpProbe.read(output), null, elapsed)
            }
        }

        val problems = reports.flatMap { describeProblems(it) }
        reports.forEach { Log.w(TAG, it.line()) }
        Log.w(TAG, summary(reports, problems.size))

        assertTrue(
            "${problems.size} of ${reports.size} sources produced a sticker WhatsApp would " +
                "reject:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /** The per-sticker half of WhatsApp's contract. Pack-level rules (how many
     * stickers, the tray, all-or-nothing animation) belong to a pack rather
     * than to a conversion, and are checked by WhatsappPackValidator where a
     * real pack exists. */
    private fun describeProblems(report: Report): List<String> {
        if (report.failure != null) return listOf("${report.name}: conversion failed -- ${report.failure}")
        val info = report.info
            ?: return listOf("${report.name}: output is not a readable WebP")

        val problems = mutableListOf<String>()
        if (info.width != SizeBudget.STICKER_PX || info.height != SizeBudget.STICKER_PX) {
            problems += "${report.name}: ${info.width}x${info.height}, must be " +
                "${SizeBudget.STICKER_PX}x${SizeBudget.STICKER_PX}"
        }
        val limit = if (info.frameCount > 1) SizeBudget.ANIMATED_MAX_BYTES else SizeBudget.STATIC_MAX_BYTES
        if (info.byteCount > limit) {
            problems += "${report.name}: ${info.byteCount} bytes, over the $limit budget"
        }
        if (info.frameCount > 1) {
            // Zero is the notorious case, but anything under the floor is
            // equally unshowable and just as invisible in a spot check.
            val tooShort = info.frameDurationsMs.filter { it < SizeBudget.MIN_FRAME_DURATION_MS }
            if (tooShort.isNotEmpty()) {
                problems += "${report.name}: ${tooShort.size} frame(s) under " +
                    "${SizeBudget.MIN_FRAME_DURATION_MS}ms ${tooShort.take(5)}"
            }
            if (info.totalDurationMs > SizeBudget.MAX_TOTAL_DURATION_MS) {
                problems += "${report.name}: runs ${info.totalDurationMs}ms, over " +
                    "${SizeBudget.MAX_TOTAL_DURATION_MS}ms"
            }
        }
        return problems
    }

    private fun Report.line(): String = if (info == null) {
        "$name type=$type FAILED ${failure ?: "unreadable output"} (${elapsedMs}ms)"
    } else {
        "$name type=$type frames=${info.frameCount} bytes=${info.byteCount} " +
            "dur=${info.totalDurationMs}ms dims=${info.width}x${info.height} " +
            "alpha=${info.hasAlpha} (${elapsedMs}ms)"
    }

    /** Printed so a sweep is comparable against the one before it. "Did it
     * convert" is a weak question; the interesting regressions are frames
     * quietly dropped or files quietly growing, which only show up next to
     * an earlier run. */
    private fun summary(reports: List<Report>, problemCount: Int): String {
        val converted = reports.filter { it.info != null }
        val animated = converted.filter { it.info!!.frameCount > 1 }
        return buildString {
            append("SWEEP sources=${reports.size} converted=${converted.size} ")
            append("failed=${reports.count { it.failure != null }} problems=$problemCount ")
            append("animated=${animated.size} ")
            append("frames=${animated.sumOf { it.info!!.frameCount }} ")
            append("deadFrames=${animated.sumOf { r -> r.info!!.frameDurationsMs.count { it < SizeBudget.MIN_FRAME_DURATION_MS } }} ")
            append("bytes=${converted.sumOf { it.info!!.byteCount }} ")
            append("totalMs=${reports.sumOf { it.elapsedMs }}")
        }
    }

    private companion object {
        const val TAG = "ConversionSweep"
    }
}
