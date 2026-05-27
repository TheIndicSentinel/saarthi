package com.saarthi.core.i18n

/**
 * Lightweight, fully-offline detector for the user's Indian state from free
 * text. Used by the Kisan pack to layer STATE-specific advice on top of the
 * CENTRAL baseline (Center → State hierarchy). Deterministic — no model
 * involved, no network.
 *
 * Matches Latin names + common transliterations, plus a few Devanagari forms
 * for the largest farming states. More scripts/aliases can be added later
 * without touching callers.
 */
object IndianStates {

    // Canonical display name → lower-cased match aliases (matched whole-word).
    private val aliases: Map<String, List<String>> = mapOf(
        "Andhra Pradesh" to listOf("andhra pradesh", "andhra"),
        "Arunachal Pradesh" to listOf("arunachal pradesh", "arunachal"),
        "Assam" to listOf("assam", "असम"),
        "Bihar" to listOf("bihar", "बिहार"),
        "Chhattisgarh" to listOf("chhattisgarh", "chattisgarh", "छत्तीसगढ़"),
        "Goa" to listOf("goa"),
        "Gujarat" to listOf("gujarat", "गुजरात"),
        "Haryana" to listOf("haryana", "हरियाणा"),
        "Himachal Pradesh" to listOf("himachal pradesh", "himachal"),
        "Jharkhand" to listOf("jharkhand", "झारखंड"),
        "Karnataka" to listOf("karnataka", "कर्नाटक"),
        "Kerala" to listOf("kerala", "केरल"),
        "Madhya Pradesh" to listOf("madhya pradesh", "madhyapradesh", "मध्य प्रदेश"),
        "Maharashtra" to listOf("maharashtra", "महाराष्ट्र"),
        "Manipur" to listOf("manipur"),
        "Meghalaya" to listOf("meghalaya"),
        "Mizoram" to listOf("mizoram"),
        "Nagaland" to listOf("nagaland"),
        "Odisha" to listOf("odisha", "orissa", "ओडिशा"),
        "Punjab" to listOf("punjab", "ਪੰਜਾਬ", "पंजाब"),
        "Rajasthan" to listOf("rajasthan", "राजस्थान"),
        "Sikkim" to listOf("sikkim"),
        "Tamil Nadu" to listOf("tamil nadu", "tamilnadu", "தமிழ்நாடு"),
        "Telangana" to listOf("telangana", "తెలంగాణ", "तेलंगाना"),
        "Tripura" to listOf("tripura"),
        "Uttar Pradesh" to listOf("uttar pradesh", "uttarpradesh", "उत्तर प्रदेश"),
        "Uttarakhand" to listOf("uttarakhand", "uttaranchal"),
        "West Bengal" to listOf("west bengal", "bengal", "पश्चिम बंगाल"),
        "Jammu and Kashmir" to listOf("jammu and kashmir", "jammu & kashmir", "j&k", "kashmir", "jammu"),
        "Delhi" to listOf("delhi", "दिल्ली"),
        "Puducherry" to listOf("puducherry", "pondicherry"),
    )

    // Aliases longest-first so "andhra pradesh" wins over "andhra", etc.
    private val byAlias: List<Pair<String, String>> = aliases
        .flatMap { (canon, al) -> al.map { it to canon } }
        .sortedByDescending { it.first.length }

    private val canonLower: Map<String, String> = aliases.keys.associateBy { it.lowercase() }

    /** All canonical state/UT display names, alphabetical — for the picker UI. */
    val all: List<String> = aliases.keys.sorted()

    /** Sentinel returned by [statePrefixOf] for the combined NE-states overlay. */
    const val NORTH_EAST = "North East"

    private val northEastStates = setOf(
        "Arunachal Pradesh", "Manipur", "Meghalaya", "Mizoram", "Nagaland", "Tripura", "Sikkim",
    )

    /** True if [state] is one of the north-eastern states covered by the combined overlay. */
    fun isNorthEast(state: String): Boolean = northEastStates.any { it.equals(state, ignoreCase = true) }

    /** Detect a state mentioned anywhere in [text]; null if none. */
    fun detect(text: String): String? {
        if (text.isBlank()) return null
        val hay = text.lowercase()
        for ((alias, canon) in byAlias) {
            val re = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(alias) + "(?![\\p{L}\\p{N}])")
            if (re.containsMatchIn(hay)) return canon
        }
        return null
    }

    /**
     * If [docName] is a STATE-OVERLAY entry — its heading starts with a state
     * name before " —" / " -" / ":" — return that state; otherwise null. Lets
     * the Kisan pack keep state add-ons separate from the central baseline and
     * filter them by the user's state.
     */
    fun statePrefixOf(docName: String): String? {
        val prefix = docName
            .substringBefore(" —")
            .substringBefore(" -")
            .substringBefore(":")
            .trim()
            .lowercase()
        if (prefix in setOf("north east states", "north-east states", "northeast states", "north east")) {
            return NORTH_EAST
        }
        return canonLower[prefix]
    }
}
