package com.saarthi.feature.assistant.data

import com.saarthi.core.i18n.CitationDisplayLabels
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.i18n.citationDisplayLabels

/** Prompt line so a new attach is not answered from the previous file's recap. */
internal fun newFilesThisTurnNotice(
    shortNames: List<String>,
    labels: CitationDisplayLabels = SupportedLanguage.ENGLISH.citationDisplayLabels(),
): String {
    if (shortNames.isEmpty()) return ""
    return labels.newFilesThisTurnPrefix + shortNames.joinToString("; ") + labels.newFilesThisTurnSuffix
}
