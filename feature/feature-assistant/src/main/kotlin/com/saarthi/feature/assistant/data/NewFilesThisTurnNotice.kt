package com.saarthi.feature.assistant.data

/** Prompt line so a new attach is not answered from the previous file's recap. */
internal fun newFilesThisTurnNotice(shortNames: List<String>): String {
    if (shortNames.isEmpty()) return ""
    return "New files this turn: " + shortNames.joinToString("; ") +
        ". Answer from these files; do not reuse answers about earlier documents.\n\n"
}
