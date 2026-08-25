package com.saarthi.feature.assistant.data

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
        val api = TessBaseAPI()
        try {
            if (!api.init(requireNotNull(dataPath), languages)) {
                Timber.w("Regional Tesseract init failed for langs=$languages")
                return@withContext ""
            }
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(bitmap)
            api.getUTF8Text()?.trim().orEmpty()
        } catch (e: Exception) {
            Timber.w(e, "Regional Tesseract OCR failed")
            ""
        } finally {
            api.recycle()
        }
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
            if (dest.exists() && dest.length() > 50_000L) continue
            runCatching {
                context.assets.open("tessdata/$name").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }.onFailure { Timber.w(it, "Regional OCR: failed to copy $name") }
        }

        dataPath = root.absolutePath
        true
    }
}
