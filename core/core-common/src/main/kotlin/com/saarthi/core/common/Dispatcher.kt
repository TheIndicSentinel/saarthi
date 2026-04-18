package com.saarthi.core.common

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val saarthiDispatcher: SaarthiDispatchers)

enum class SaarthiDispatchers {
    Default,
    IO,
    Main
}
