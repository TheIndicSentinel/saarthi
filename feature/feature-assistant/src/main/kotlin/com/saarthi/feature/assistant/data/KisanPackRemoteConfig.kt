package com.saarthi.feature.assistant.data

/**
 * App-supplied remote-pack settings (manifest URL + signing key + app
 * version). Lives as an interface so [PackUpdateChecker] can live in
 * feature-assistant without depending on the app module's BuildConfig.
 */
interface KisanPackRemoteConfig {
    val manifestUrl: String
    val publicKeyB64: String
    val appVersionCode: Int
}
