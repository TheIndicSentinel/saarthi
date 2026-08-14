package com.saarthi.core.inference

import com.saarthi.core.common.CrashReporter

/**
 * [CrashReporter] implementation: writes everything to the on-device
 * `saarthi_debug.log` via [DebugLogger], never off the phone.
 *
 * Point 6 fix: this used to live in core-common as `LocalCrashReporter`,
 * reaching [DebugLogger] via `Class.forName(...)` + reflective `getMethod`
 * because core-common can't depend on core-inference (DebugLogger's module)
 * without a circular Gradle dependency. That reflection was silently
 * fragile: if [DebugLogger.log]'s signature ever changed, `getMethod` would
 * throw, the surrounding `runCatching` would swallow it, and every
 * [recordException]/[log]/[setUserId]/[setKey] call would silently become a
 * no-op — no compile error, no runtime error, just this app's only
 * observability layer (no Firebase/Crashlytics) going dark with zero signal.
 *
 * Living here instead — where [DebugLogger] is a real, compile-time-checked
 * dependency — turns that same signature change into a build failure at the
 * point of the rename, not a silent gap discovered only when a crash log
 * was needed and wasn't there.
 */
class LocalCrashReporter : CrashReporter {

    override fun recordException(throwable: Throwable, tags: Map<String, String>) {
        DebugLogger.log("CRASH", "non-fatal: ${throwable.javaClass.name}: ${throwable.message}")
        // Point 9: tag values may be user- or device-correlating — lengths only.
        tags.forEach { (k, v) ->
            DebugLogger.log("CRASH", "  ${LogPrivacy.keyLen(k)} ${LogPrivacy.valueLen(v)}")
        }
        throwable.stackTrace.take(8).forEach { frame ->
            DebugLogger.log("CRASH", "  at $frame")
        }
    }

    override fun log(tag: String, message: String) = DebugLogger.log(tag, message)

    override fun setUserId(id: String) =
        DebugLogger.log("CRASH", "userId set (${LogPrivacy.valueLen(id)})")

    override fun setKey(key: String, value: String) =
        DebugLogger.log("CRASH", "${LogPrivacy.keyLen(key)} ${LogPrivacy.valueLen(value)}")
}
