package com.saarthi.feature.assistant.data

/**
 * Phase 6.1 — on-device dense retrieval spike flag.
 *
 * Production RAG stays BM25 + feature rerank. Revisit only when
 * [DenseRetrievalEvalGate] shows systematic paraphrase misses after lexicon
 * retries and ship eval is green — and device RAM/latency budget allows a
 * second on-device encoder (MiniLM-class ONNX or tiny sidecar).
 */
internal const val DENSE_RETRIEVAL_SPIKE_ENABLED = false

internal fun denseRetrievalSpikeEnabled(): Boolean = DENSE_RETRIEVAL_SPIKE_ENABLED
