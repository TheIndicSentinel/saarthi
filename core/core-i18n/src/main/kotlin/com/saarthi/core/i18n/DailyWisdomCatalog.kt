package com.saarthi.core.i18n

import java.time.LocalDate

/**
 * One day's wisdom — a classical Sanskrit subhāṣita with its English
 * gloss. Used both by the Home-screen "Thought of the day" card and by
 * the daily wisdom notification.
 *
 * The set is deliberately small and high-quality (well-known shlokas
 * from Bhagavad-Gita, Upanishads, Mahopanishad, Tirukkural, Hitopadesha,
 * Subhāṣitas) rather than a long churn of generic quotes — the user is
 * expected to encounter the same set across a month, and recognising
 * one from yesterday is the point.
 */
data class DailyWisdom(
    val sanskrit: String,
    val english: String,
)

object DailyWisdomCatalog {

    /**
     * Curated daily wisdom — 28 entries. Cycled deterministically by
     * day-of-year so every device on the same day shows the same thought.
     */
    val entries: List<DailyWisdom> = listOf(
        DailyWisdom("विद्या ददाति विनयम्",            "Knowledge gives humility"),
        DailyWisdom("सत्यमेव जयते",                  "Truth alone triumphs"),
        DailyWisdom("वसुधैव कुटुम्बकम्",              "The world is one family"),
        DailyWisdom("अहिंसा परमो धर्मः",              "Non-violence is the highest virtue"),
        DailyWisdom("योगः कर्मसु कौशलम्",             "Skill in action — that is yoga"),
        DailyWisdom("सर्वे भवन्तु सुखिनः",            "May all beings be happy"),
        DailyWisdom("कर्मण्येवाधिकारस्ते",            "Your right is to action alone"),
        DailyWisdom("उद्यमेन हि सिध्यन्ति कार्याणि",  "Tasks succeed by effort, not by wishes"),
        DailyWisdom("परोपकाराय फलन्ति वृक्षाः",       "Trees bear their fruit for others"),
        DailyWisdom("श्रद्धावान् लभते ज्ञानम्",         "Faith leads one to true knowledge"),
        DailyWisdom("आत्मानं विद्धि",                  "Know thyself"),
        DailyWisdom("मातृदेवो भव",                    "Honour your mother as the divine"),
        DailyWisdom("क्षणशः कणशश्चैव",                "Time and energy — value every drop"),
        DailyWisdom("विद्या विनयेन शोभते",            "Learning shines through humility"),
        DailyWisdom("अतिथिदेवो भव",                  "Treat your guest as divine"),
        DailyWisdom("मन एव मनुष्याणां कारणं बन्धमोक्षयोः", "The mind alone is the cause of bondage and liberation"),
        DailyWisdom("सत्यं वद धर्मं चर",              "Speak truth; practise righteousness"),
        DailyWisdom("अल्पस्य हेतोर्बहु हातुं इच्छन्",   "Do not lose much for a little"),
        DailyWisdom("शान्ति: शान्ति: शान्ति:",         "Peace, peace, peace"),
        DailyWisdom("अप्रत्यक्षं तपः परम्",            "The greatest discipline is the one unseen"),
        DailyWisdom("न हि ज्ञानेन सदृशं पवित्रमिह विद्यते", "Nothing in this world purifies like knowledge"),
        DailyWisdom("असतो मा सद्गमय",                 "From untruth lead me to truth"),
        DailyWisdom("तमसो मा ज्योतिर्गमय",            "From darkness lead me to light"),
        DailyWisdom("मृत्योर्मा अमृतं गमय",            "From death lead me to immortality"),
        DailyWisdom("क्रोधाद्भवति सम्मोहः",            "Anger gives rise to confusion"),
        DailyWisdom("उत्साहो बलवानार्य",              "Enthusiasm is a strength of its own"),
        DailyWisdom("धैर्यं सर्वत्र साधनम्",           "Patience is the means in every field"),
        DailyWisdom("शुभस्य शीघ्रम्",                  "Do the good thing quickly"),
    )

    /**
     * Picks the wisdom for [date] deterministically — every device,
     * every install, sees the same thought on the same calendar day.
     * Uses day-of-year (1..366) so the cycle resets cleanly each year
     * and there is no off-by-one across month boundaries.
     */
    fun forDate(date: LocalDate = LocalDate.now()): DailyWisdom {
        val index = (date.dayOfYear - 1).mod(entries.size)
        return entries[index]
    }
}
