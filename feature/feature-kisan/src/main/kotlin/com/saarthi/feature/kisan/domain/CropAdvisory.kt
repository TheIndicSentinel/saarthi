package com.saarthi.feature.kisan.domain

data class CropAdvisory(
    val cropName: String,
    val season: String,
    val advisory: String,
    val source: String = "Offline DB",
)

// Domain model — Kisan Saathi pack provides offline agricultural guidance
// backed by NCERT + Krishi Vigyan Kendra chunked into local vector store
