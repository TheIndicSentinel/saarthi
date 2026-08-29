package com.saarthi.app.navigation

/**
 * About screen version line. [versionName] is Play's user-facing version
 * (e.g. 1.0.39); [versionCode] is the integer the store increments.
 * Never hardcode these — the old "v 1.4.0 · build 187" drifted from the APK.
 */
fun aboutVersionLabel(versionName: String?, versionCode: Long): String {
    val name = versionName?.trim().orEmpty().ifBlank { "?" }
    return "v $name · $versionCode"
}
