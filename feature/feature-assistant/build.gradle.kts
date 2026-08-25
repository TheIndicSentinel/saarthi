plugins {
    id("saarthi.android.feature")
}

import java.net.URI

android { namespace = "com.saarthi.feature.assistant" }

tasks.register("downloadRegionalTessdata") {
    group = "saarthi"
    description = "Fetch Tesseract traineddata for Bengali/Tamil/Telugu/etc."
    val outDir = layout.projectDirectory.dir("src/main/assets/tessdata")
    outputs.dir(outDir)
    doLast {
        val dir = outDir.asFile
        dir.mkdirs()
        val langs = listOf("ben", "tam", "tel", "kan", "guj", "pan", "ori")
        // Checksum-lite: ~80% of tessdata_fast sizes. Skip re-download when
        // the local file is already plausible. Never fail preBuild — an
        // offline machine with existing files (or without them) still compiles;
        // regional OCR degrades to ML Kit if a pack is missing.
        val minBytes = mapOf(
            "ben" to 700_000L,
            "tam" to 2_500_000L,
            "tel" to 2_200_000L,
            "kan" to 2_800_000L,
            "guj" to 1_100_000L,
            "pan" to 400_000L,
            "ori" to 1_100_000L,
        )
        val base = "https://github.com/tesseract-ocr/tessdata_fast/raw/main"
        langs.forEach { lang ->
            val dest = dir.resolve("$lang.traineddata")
            val min = minBytes.getValue(lang)
            if (dest.exists() && dest.length() >= min) return@forEach
            try {
                URI("$base/$lang.traineddata").toURL().openStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (dest.length() < min) {
                    logger.warn(
                        "tessdata $lang download looked truncated (${dest.length()} bytes; need >= $min)",
                    )
                }
            } catch (e: Exception) {
                if (dest.exists() && dest.length() >= min) {
                    logger.warn("tessdata $lang download failed; using existing file. ${e.message}")
                } else {
                    logger.warn(
                        "tessdata $lang unavailable (offline?). Regional OCR for $lang will be skipped. ${e.message}",
                    )
                }
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
