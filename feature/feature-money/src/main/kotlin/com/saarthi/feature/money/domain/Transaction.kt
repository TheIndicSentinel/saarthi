package com.saarthi.feature.money.domain

data class Transaction(
    val id: Long = 0,
    val amount: Double,
    val category: String,
    val description: String,
    val rawSms: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isDebit: Boolean,
)

// Categories for AI classification
object TransactionCategory {
    const val FOOD = "Food"
    const val TRAVEL = "Travel"
    const val BILLS = "Bills"
    const val SHOPPING = "Shopping"
    const val HEALTH = "Health"
    const val INCOME = "Income"
    const val OTHER = "Other"
}
