plugins {
    id("saarthi.android.feature")
}

android { namespace = "com.saarthi.feature.assistant" }

// ── sherpa-onnx AAR auto-download ────────────────────────────────────────────
// The AAR is NOT committed to git (54 MB binary). It is fetched from
// sherpa-onnx releases on first build. CI also downloads it explicitly in the
// workflow before Gradle runs (build_apk.yml) for reliability.
private val sherpaVersion = "1.13.2"
private val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
private val sherpaUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"

// Download task — configuration-cache-compatible by capturing only primitives.
abstract class DownloadSherpaOnnxTask : DefaultTask() {
    @get:org.gradle.api.tasks.Input  val version = "1.13.2"
    @get:org.gradle.api.tasks.OutputFile val aarFile = project.file("libs/sherpa-onnx-$version.aar")

    @org.gradle.api.tasks.TaskAction
    fun download() {
        if (aarFile.exists()) return
        aarFile.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
        logger.lifecycle("Downloading sherpa-onnx $version AAR (~54 MB)…")
        val exitCode = ProcessBuilder("curl", "-L", "-o", aarFile.absolutePath, url)
            .redirectErrorStream(true)
            .start()
            .waitFor()
        check(exitCode == 0 && aarFile.exists()) {
            "Failed to download sherpa-onnx AAR (exit=$exitCode). Run manually: curl -L -o ${aarFile.absolutePath} $url"
        }
        logger.lifecycle("sherpa-onnx AAR ready (${aarFile.length() / 1_048_576} MB)")
    }
}

tasks.register<DownloadSherpaOnnxTask>("downloadSherpaOnnx")

tasks.configureEach {
    if (name.startsWith("compile") || name.startsWith("check") ||
        name.startsWith("merge") || name.startsWith("process") ||
        name == "preBuild"
    ) {
        dependsOn("downloadSherpaOnnx")
    }
}

dependencies {
    implementation(project(":core:core-inference"))
    implementation(project(":core:core-rag"))
    implementation(libs.coil.compose)
    implementation(libs.accompanist.permissions)
    implementation(libs.mlkit.text.recognition)
    implementation(files("libs/sherpa-onnx-$sherpaVersion.aar"))
    implementation(libs.commons.compress)
}
