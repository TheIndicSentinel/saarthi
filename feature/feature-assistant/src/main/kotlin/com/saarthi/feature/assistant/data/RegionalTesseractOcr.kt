package com.saarthi.feature.assistant.data

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * R4 follow-up — offline OCR for scripts ML Kit does not bundle (Bengali, Tamil,
 * Telugu, Kannada, Gujarati, Punjabi, Odia).
 *
 * traineddata files live in assets/tessdata/ and are copied once to app storage.
 */
@Singleton
class RegionalTesseractOcr @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val initLock = Any()

    @Volatile
    private var dataPath: String? = null

    suspend fun recognize(bitmap: Bitmap, languages: String): String = withContext(Dispatchers.Default) {
        if (!ensureDataReady()) return@withContext ""
        // Tesseract runtime and memory scale with pixel count. A 4096px PDF
        // render can take many seconds per page on a low-end device; cap the
        // longest side so a single page can't stall the whole extract.
        val scaled = downscaleForTesseract(bitmap)
        var api: TessBaseAPI? = null
        try {
            withTimeout(TESSERACT_PAGE_TIMEOUT_MS) {
                val tess = TessBaseAPI()
                api = tess
                if (!tess.init(requireNotNull(dataPath), languages)) {
                    Timber.w("Regional Tesseract init failed for langs=$languages")
                    return@withTimeout ""
                }
                tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                tess.setImage(scaled)
                tess.getUTF8Text()?.trim().orEmpty()
            }
        } catch (e: TimeoutCancellationException) {
            runCatching { api?.stop() }
            Timber.w("Regional Tesseract timed out after ${TESSERACT_PAGE_TIMEOUT_MS}ms langs=$languages")
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Regional Tesseract OCR failed")
            ""
        } finally {
            runCatching { api?.recycle() }
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun downscaleForTesseract(bitmap: Bitmap): Bitmap {
        val factor = tesseractDownscaleFactor(bitmap.width, bitmap.height, MAX_TESSERACT_DIM)
        if (factor >= 1f) return bitmap
        val w = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val h = (bitmap.height * factor).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(bitmap, w, h, true) }
            .getOrDefault(bitmap)
    }

    private fun ensureDataReady(): Boolean = synchronized(initLock) {
        if (dataPath != null) return true

        val assetNames = context.assets.list("tessdata").orEmpty()
            .filter { it.endsWith(".traineddata") }
        if (assetNames.isEmpty()) {
            Timber.w("Regional OCR: no tessdata in assets")
            return false
        }

        val root = File(context.filesDir, "tesseract")
        val tessDir = File(root, "tessdata")
        if (!tessDir.exists() && !tessDir.mkdirs()) {
            Timber.w("Regional OCR: could not create tessdata dir")
            return false
        }

        for (name in assetNames) {
            val dest = File(tessDir, name)
            val expected = assetLength("tessdata/$name")
            // Re-copy when missing, truncated, or smaller than the pinned
            // minimum (a half-written prior copy). Length match against the
            // asset is the copy-integrity check.
            val copyOk = dest.exists() &&
                isPlausibleTessdataSize(name, dest.length()) &&
                (expected <= 0L || dest.length() == expected)
            if (copyOk) continue

            val tmp = File(tessDir, "$name.tmp")
            runCatching {
                context.assets.open("tessdata/$name").use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                }
            }.onFailure { Timber.w(it, "Regional OCR: failed to copy $name") }
            if (tmp.exists()) tmp.delete()
            if (dest.exists() && !isPlausibleTessdataSize(name, dest.length())) {
                Timber.w("Regional OCR: $name looks truncated (${dest.length()} bytes) — skipping")
                dest.delete()
            }
        }

        val anyValid = tessDir.listFiles()?.any {
            it.name.endsWith(".traineddata") && isPlausibleTessdataSize(it.name, it.length())
        } == true
        if (!anyValid) {
            Timber.w("Regional OCR: no valid tessdata files after copy")
            return false
        }
        dataPath = root.absolutePath
        true
    }

    private fun assetLength(path: String): Long = runCatching {
        context.assets.open(path).use { input ->
            var total = 0L
            val buf = ByteArray(64 * 1024)
            var n = input.read(buf)
            while (n >= 0) { total += n; n = input.read(buf) }
            total
        }
    }.getOrDefault(-1L)

    companion object {
        private const val MAX_TESSERACT_DIM = 2560
        internal const val TESSERACT_PAGE_TIMEOUT_MS = 15_000L
    }
}

/**
 * Minimum plausible byte sizes for bundled tessdata_fast files. Used as a
 * checksum-lite: a truncated copy or failed download is rejected so we fall
 * back to ML Kit instead of feeding Tesseract a corrupt pack. Values are ~80%
 * of the tessdata_fast sizes shipped with this branch.
 */
internal val TESSDATA_MIN_BYTES = mapOf(
    "ben.traineddata" to 700_000L,
    "guj.traineddata" to 1_100_000L,
    "kan.traineddata" to 2_800_000L,
    "ori.traineddata" to 1_100_000L,
    "pan.traineddata" to 400_000L,
    "tam.traineddata" to 2_500_000L,
    "tel.traineddata" to 2_200_000L,
)

internal fun isPlausibleTessdataSize(fileName: String, bytes: Long): Boolean {
    val min = TESSDATA_MIN_BYTES[fileName] ?: 100_000L
    return bytes >= min
}

/**
 * Scale factor (≤ 1.0) to bring a bitmap's longest side down to [maxDim] for
 * Tesseract. Returns 1.0 when the image already fits or dimensions are
 * invalid. Pure so the sizing policy is unit-testable without a real Bitmap.
 */
internal fun tesseractDownscaleFactor(width: Int, height: Int, maxDim: Int): Float {
    val longest = maxOf(width, height)
    if (longest <= 0 || longest <= maxDim) return 1f
    return maxDim.toFloat() / longest
}
