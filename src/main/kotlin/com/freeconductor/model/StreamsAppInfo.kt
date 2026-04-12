package com.freeconductor.model

data class StreamsAppInfo(
    val applicationId: String,
    val state: String,
    val memberCount: Int,
    val internalTopics: List<String>
)
