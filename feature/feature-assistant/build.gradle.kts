plugins {
    id("saarthi.android.feature")
}

import java.net.URI
import java.security.MessageDigest

android { namespace = "com.saarthi.feature.assistant" }

tasks.register("downloadRegionalTessdata") {
    group = "saarthi"
    description = "Fetch Tesseract traineddata for Bengali/Tamil/Telugu/etc."
    val outDir = layout.projectDirectory.dir("src/main/assets/tessdata")
    outputs.dir(outDir)
    // Always re-run so a reused local file is SHA-256 checked, not treated as
    // UP-TO-DATE solely because the tessdata directory already exists.
    outputs.upToDateWhen { false }
    doLast {
        val dir = outDir.asFile
        dir.mkdirs()
        val langs = listOf("ben", "tam", "tel", "kan", "guj", "pan", "ori")
        // Pinned SHA-256 of tessdata_fast {lang}.traineddata at
        // tesseract-ocr/tessdata_fast@87416418657359cb625c412a48b6e1d6d41c29bd
        // (main HEAD as of 2024-08-01 README commit; traineddata blobs
        // unchanged in that commit). Hashes computed locally from GitHub raw
        // downloads whose byte sizes match the git tree at that commit.
        // Never fail preBuild — offline machines still compile; regional OCR
        // degrades to ML Kit if a pack is missing or discarded on mismatch.
        val tessdataCommit = "87416418657359cb625c412a48b6e1d6d41c29bd"
        val expectedSha256 = mapOf(
            "ben" to "31163084c279aaebd376216f0c3d5c17ad4b5fee8db49dae79c20000b5de5964",
            "tam" to "d02fbec24be4b07e32e80d0ccfc3b6b67a3c5d61c9d0a7c8532677990912c6ec",
            "tel" to "d10691fddd5b67802e1c12800ebb321d3b8bcd8d24a2ac3ff206f93188c04ab5",
            "kan" to "bd31e6b6ae93271e3bcf5383d306d8eefbb91542937cd6d735a5930c970e61d8",
            "guj" to "fa69658614b4946a9afae8853d67e0689838803dfa3d12c2e35ec53ee6f8df34",
            "pan" to "1ec0907fc3534065ea9ae190c6bb7ec9e5c74fd9d2fa996aaec7407f11ad8131",
            "ori" to "36f3135e61d501a3acfad41f5fe60b8e791274fff4c5375c969fdcca980cdbac",
        )
        val base = "https://github.com/tesseract-ocr/tessdata_fast/raw/$tessdataCommit"
        fun sha256Hex(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun matchesPinnedHash(file: java.io.File, expected: String): Boolean {
            if (!file.exists()) return false
            return try {
                sha256Hex(file).equals(expected, ignoreCase = true)
            } catch (e: Exception) {
                logger.warn("tessdata ${file.name} could not be hashed: ${e.message}")
                false
            }
        }
        langs.forEach { lang ->
            val dest = dir.resolve("$lang.traineddata")
            val expected = expectedSha256.getValue(lang)
            if (matchesPinnedHash(dest, expected)) return@forEach
            if (dest.exists()) {
                logger.warn(
                    "tessdata $lang SHA-256 mismatch (or unreadable); deleting and re-fetching",
                )
                dest.delete()
            }
            try {
                URI("$base/$lang.traineddata").toURL().openStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (!matchesPinnedHash(dest, expected)) {
                    logger.warn(
                        "tessdata $lang download SHA-256 mismatch; discarding " +
                            "(regional OCR for $lang will use ML Kit)",
                    )
                    dest.delete()
                }
            } catch (e: Exception) {
                if (dest.exists()) dest.delete()
                logger.warn(
                    "tessdata $lang unavailable (offline?). Regional OCR for $lang will be skipped. ${e.message}",
                )
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn("downloadRegionalTessdata") }

dependencies {
    implementation(project(":core:core-inference"))
    implementation(project(":core:core-rag"))
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.devanagari)
    implementation(libs.tesseract4android)
    implementation(libs.pdfbox.android)
}
