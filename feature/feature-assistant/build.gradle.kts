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
        val base = "https://github.com/tesseract-ocr/tessdata_fast/raw/main"
        langs.forEach { lang ->
            val dest = dir.resolve("$lang.traineddata")
            if (dest.exists() && dest.length() > 100_000L) return@forEach
            URI("$base/$lang.traineddata").toURL().openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
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
