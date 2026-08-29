package com.saarthi.core.inference.engine

/**
 * Keep the KV-cache token window stable across in-process engine reloads.
 *
 * [calculateEffectiveMaxTokens] uses live RAM headroom, which swings after a
 * generate + 120s background release. That produced 4096↔2048 flaps on the
 * same FLAGSHIP phone (prompt budget and system-prompt path changing mid-day).
 * Pin the first successful window for this model until process death, unless
 * crash recovery calculates a *lower* floor.
 */
fun stabilizeEffectiveMaxTokens(
    newlyCalculated: Int,
    pinnedForThisModel: Int,
    sameModelAsPin: Boolean,
    cpuCrashCount: Int,
): Int {
    if (!sameModelAsPin || pinnedForThisModel <= 0) return newlyCalculated
    if (cpuCrashCount >= 1) return newlyCalculated
    return pinnedForThisModel
}
