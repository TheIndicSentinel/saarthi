package com.saarthi.feature.assistant.data

/**
 * Wave 5 P22 — synthetic DPDPA-style act for end-to-end golden replay (no Room, no LLM).
 */
internal object DpdpaActFixture {
    const val URI = "content://dpdpa-act"
    const val NAME = "Digital Personal Data Protection Act 2023.pdf"

    val doc: GoldenDoc = GoldenDoc(uri = URI, name = NAME, text = buildActText())

    private fun buildActText(): String = buildString {
        append("THE DIGITAL PERSONAL DATA PROTECTION ACT, 2023\n\n")
        append("CHAPTER I\nPRELIMINARY\n")
        append("Short title and commencement provisions for the Act.\n\n")
        append("CHAPTER II\nOBLIGATIONS OF DATA FIDUCIARY\n")
        append("Every Data Fiduciary shall protect personal data with reasonable security safeguards.\n")
        append("Accuracy and completeness of personal data must be maintained at all times.\n\n")
        append("CHAPTER VI\nPROCESSING OF PERSONAL DATA OF CHILDREN\n")
        append("Verifiable parental consent is required before processing a child's personal data.\n")
        append("Tracking and behavioural monitoring of children is restricted unless permitted.\n")
        append("The Board may specify additional safeguards for significant data fiduciaries.\n\n")
        append("CHAPTER VII\nRIGHTS AND DUTIES OF DATA PRINCIPAL\n")
        append("The Data Principal has the right to access information about personal data.\n")
        append("The Data Principal may seek correction and erasure of personal data.\n")
        append("Nomination of another individual to exercise rights in case of death is allowed.\n\n")
        append("CHAPTER VIII\nSPECIAL PROVISIONS\n")
        append("Processing by the State and its instrumentalities for notified purposes is permitted.\n")
        append("Research, archiving and statistical purposes may be exempt when standards are met.\n")
        append("Cross-border transfer may occur to countries notified by the Central Government.\n\n")
        append("CHAPTER IX\nPENALTIES AND ADJUDICATION\n")
        append("33. Penalties\n")
        append("The Board may impose monetary penalties considering nature gravity and repetition.\n")
        append("Factors include type of personal data breach and gain or loss avoided.\n")
        append("PENALTIES AND ADJUDICATION continue with appeal and review procedures.\n\n")
        append("THE SCHEDULE\n")
        append("Monetary penalties\n")
        (1..14).forEach { i ->
            append("Breach category $i — monetary penalty up to ₹${i * 25} crore\n")
        }
    }
}
