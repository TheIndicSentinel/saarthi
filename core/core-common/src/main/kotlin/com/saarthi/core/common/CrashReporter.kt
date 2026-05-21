package com.saarthi.core.common

/**
 * Crash + diagnostic event sink.
 *
 * The default [LocalCrashReporter] forwards everything to the on-device
 * `saarthi_debug.log` (offline-first; no telemetry). To plug in Firebase
 * Crashlytics later:
 *
 *   1. Drop `app/google-services.json` into the project.
 *   2. Apply the `com.google.gms.google-services` and
 *      `com.google.firebase.crashlytics` Gradle plugins on the app module.
 *   3. Add `implementation(platform("com.google.firebase:firebase-bom:33.x"))`
 *      and `implementation("com.google.firebase:firebase-crashlytics-ktx")`.
 *   4. Provide a `CrashlyticsCrashReporter` (see comment below) and bind it
 *      in `core-common`'s Hilt module instead of [LocalCrashReporter].
 *
 * The Crashlytics SDK queues events offline until the device comes online, so
 * adopting it doesn't break Saarthi's offline-first promise.
 */
interface CrashReporter {
    /** Record a non-fatal throwable + optional context tags. */
    fun recordException(throwable: Throwable, tags: Map<String, String> = emptyMap())

    /** Breadcrumb-style log line, kept with the next crash. */
    fun log(tag: String, message: String)

    /** Stable user-scoped identifier (anonymous; not the user's name). */
    fun setUserId(id: String)

    /** Custom key/value attached to every subsequent event. */
    fun setKey(key: String, value: String)
}

/**
 * Default implementation: write everything to the on-device debug log via
 * [com.saarthi.core.inference.DebugLogger] without ever leaving the device.
 *
 * Loaded reflectively to avoid a hard dependency from core-common back into
 * core-inference (DebugLogger lives in core-inference).
 */
class LocalCrashReporter : CrashReporter {

    private val debugLogger = runCatching {
        Class.forName("com.saarthi.core.inference.DebugLogger")
    }.getOrNull()

    private fun logTo(tag: String, message: String) {
        runCatching {
            val m = debugLogger?.getMethod("log", String::class.java, String::class.java)
            m?.invoke(null, tag, message)
        }
    }

    override fun recordException(throwable: Throwable, tags: Map<String, String>) {
        logTo("CRASH", "non-fatal: ${throwable.javaClass.name}: ${throwable.message}")
        tags.forEach { (k, v) -> logTo("CRASH", "  $k=$v") }
        throwable.stackTrace.take(8).forEach { frame ->
            logTo("CRASH", "  at $frame")
        }
    }

    override fun log(tag: String, message: String) = logTo(tag, message)

    override fun setUserId(id: String) = logTo("CRASH", "userId=$id")

    override fun setKey(key: String, value: String) = logTo("CRASH", "$key=$value")
}

/**
 * Reflective Crashlytics reporter — links to Firebase via reflection so this
 * class compiles even when the Firebase deps aren't on the classpath. The
 * factory method [tryCreate] returns null if any required class is missing;
 * the DI module then falls back to [LocalCrashReporter]. Once you drop
 * `app/google-services.json` into the project, the Firebase plugins kick in,
 * the BoM resolves, Firebase auto-initializes, and this implementation
 * starts being used automatically — no other code changes required.
 *
 * Why reflection: a non-reflective `import com.google.firebase.crashlytics.*`
 * here would force `core-common` to depend on the Firebase BoM, defeating the
 * whole point of the optional integration.
 */
class CrashlyticsCrashReporter private constructor(
    private val crashlytics: Any,
) : CrashReporter {
    private val recordException = crashlytics.javaClass.getMethod("recordException", Throwable::class.java)
    private val log = crashlytics.javaClass.getMethod("log", String::class.java)
    private val setUserId = crashlytics.javaClass.getMethod("setUserId", String::class.java)
    private val setCustomKey = crashlytics.javaClass.getMethod("setCustomKey", String::class.java, String::class.java)

    override fun recordException(throwable: Throwable, tags: Map<String, String>) {
        runCatching {
            tags.forEach { (k, v) -> setCustomKey.invoke(crashlytics, k, v) }
            recordException.invoke(crashlytics, throwable)
        }
    }

    override fun log(tag: String, message: String) {
        runCatching { log.invoke(crashlytics, "[$tag] $message") }
    }

    override fun setUserId(id: String) {
        runCatching { setUserId.invoke(crashlytics, id) }
    }

    override fun setKey(key: String, value: String) {
        runCatching { setCustomKey.invoke(crashlytics, key, value) }
    }

    companion object {
        /**
         * Returns a [CrashlyticsCrashReporter] iff the Firebase SDK is on the
         * classpath AND `FirebaseApp.initializeApp` has succeeded (i.e. a
         * `google-services.json` was bundled at build time). Returns null
         * otherwise — the caller should fall back to [LocalCrashReporter].
         */
        fun tryCreate(): CrashReporter? {
            return runCatching {
                val crashlyticsCls = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
                val instance = crashlyticsCls.getMethod("getInstance").invoke(null)
                    ?: return null
                CrashlyticsCrashReporter(instance)
            }.getOrNull()
        }
    }
}
