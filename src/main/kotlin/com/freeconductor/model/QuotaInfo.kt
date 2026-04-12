package com.freeconductor.model

data class QuotaInfo(
    val entityDescription: String,
    val entityEntries: Map<String, String?>,
    val producerByteRate: Double?,
    val consumerByteRate: Double?,
    val requestPercentage: Double?
)
