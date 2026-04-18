package com.saarthi.feature.money.domain

import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.PackType
import javax.inject.Inject

class SmsParser @Inject constructor(
    private val inferenceEngine: InferenceEngine,
) {
    // Regex to detect transaction SMS patterns (Indian banks)
    private val amountRegex = Regex("""(?:INR|Rs\.?|₹)\s?([\d,]+\.?\d*)""", RegexOption.IGNORE_CASE)
    private val debitKeywords = listOf("debited", "spent", "paid", "withdrawn", "dr")
    private val creditKeywords = listOf("credited", "received", "cr", "deposited")

    fun isTransactionSms(body: String): Boolean =
        amountRegex.containsMatchIn(body) &&
                (debitKeywords.any { body.lowercase().contains(it) } ||
                        creditKeywords.any { body.lowercase().contains(it) })

    fun extractAmount(body: String): Double? =
        amountRegex.find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()

    fun isDebit(body: String): Boolean =
        debitKeywords.any { body.lowercase().contains(it) }

    // AI categorization via Gemma MONEY pack
    suspend fun categorize(smsBody: String): String {
        val prompt = """Categorize this SMS into ONE of these categories: Food, Travel, Bills, Shopping, Health, Income, Other.
SMS: "$smsBody"
Reply with ONLY the category name."""
        return inferenceEngine.generate(prompt, PackType.MONEY).trim()
    }
}
