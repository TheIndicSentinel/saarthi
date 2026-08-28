package com.saarthi.feature.assistant.data

/**
 * Phase 3.2 — shared amount normalization for tabular QA and post-gen audit.
 * Handles ₹/Rs/INR prefixes, Indian grouping commas, and crore/lakh units.
 */

internal fun stripIndianAmountGrouping(raw: String): String =
    raw.replace(",", "").trim()

/** Digits-only normalization for amount comparison (drops grouping commas). */
internal fun normalizeIndianAmountDigits(raw: String): String =
    stripIndianAmountGrouping(raw)

private val CRORE_AMOUNT_RX = Regex(
    "(?i)(?:₹|rs\\.?|inr)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(crore|crores)",
)
private val LAKH_AMOUNT_RX = Regex(
    "(?i)(?:₹|rs\\.?|inr)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*(lakh|lakhs|lacs)",
)
private val PREFIXED_RS_AMOUNT_RX = Regex(
    "(?i)(?:₹|rs\\.?|inr)\\s*([\\d,]+(?:\\.\\d+)?)",
)

/** Comparable monetary signatures extracted from text (crore/lakh/rs prefixes). */
internal fun extractMonetarySignatures(text: String): Set<String> {
    val sigs = LinkedHashSet<String>()
    CRORE_AMOUNT_RX.findAll(text).forEach { match ->
        val num = normalizeIndianAmountDigits(match.groupValues[1])
        if (num.isNotEmpty()) sigs.add("${num}crore")
    }
    LAKH_AMOUNT_RX.findAll(text).forEach { match ->
        val num = normalizeIndianAmountDigits(match.groupValues[1])
        if (num.isNotEmpty()) sigs.add("${num}lakh")
    }
    PREFIXED_RS_AMOUNT_RX.findAll(text).forEach { match ->
        val num = normalizeIndianAmountDigits(match.groupValues[1])
        if (num.isNotEmpty()) sigs.add("rs$num")
    }
    return sigs
}

internal fun corpusContainsMonetarySignature(signature: String, corpus: String): Boolean {
    val lower = corpus.lowercase().replace(",", "")
    return when {
        signature.endsWith("crore") -> {
            val num = signature.removeSuffix("crore")
            lower.contains(num) && lower.contains("crore")
        }
        signature.endsWith("lakh") -> {
            val num = signature.removeSuffix("lakh")
            lower.contains(num) && (lower.contains("lakh") || lower.contains("lac"))
        }
        signature.startsWith("rs") -> lower.contains(signature.removePrefix("rs"))
        else -> lower.contains(signature)
    }
}
