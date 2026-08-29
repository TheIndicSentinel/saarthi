package com.saarthi.feature.assistant.data

import com.saarthi.core.memory.db.RagChunkEntity

/** Session-scoped pointer row — not a user document; stores active-doc URI in [text]. */
internal const val ACTIVE_DOC_POINTER_URI = "__saarthi_active_doc__"

internal const val ACTIVE_DOC_CHUNK_INDEX = -6

internal fun isActiveDocPointerRow(entity: RagChunkEntity): Boolean =
    entity.docUri == ACTIVE_DOC_POINTER_URI && entity.chunkIndex == ACTIVE_DOC_CHUNK_INDEX

internal fun isUserIndexedDocUri(docUri: String): Boolean =
    docUri.isNotEmpty() && docUri != ACTIVE_DOC_POINTER_URI
