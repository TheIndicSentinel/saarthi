package com.saarthi.app.packs

import com.saarthi.app.BuildConfig
import com.saarthi.feature.assistant.data.KisanPackRemoteConfig
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KisanPackRemoteConfigImpl @Inject constructor() : KisanPackRemoteConfig {
    override val manifestUrl: String = BuildConfig.KISAN_PACK_MANIFEST_URL
    override val publicKeyB64: String = BuildConfig.KISAN_PACK_PUBLIC_KEY
    override val appVersionCode: Int = BuildConfig.VERSION_CODE
}

@Module
@InstallIn(SingletonComponent::class)
abstract class KisanPackRemoteModule {
    @Binds
    @Singleton
    abstract fun bindRemoteConfig(impl: KisanPackRemoteConfigImpl): KisanPackRemoteConfig
}
