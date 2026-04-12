package com.freeconductor.model

import java.time.Instant

data class MessageRecord(
    val topic: String,
    val partition: Int,
    val offset: Long,
    val timestamp: Long,
    val key: String?,
    val value: String?,
    val headers: Map<String, String> = emptyMap(),
    val keySize: Int = 0,
    val valueSize: Int = 0
) {
    val timestampInstant: Instant get() = Instant.ofEpochMilli(timestamp)
}

enum class Deserializer {
    STRING,
    INTEGER,
    LONG,
    DOUBLE,
    JSON,
    BASE64
}

enum class ConsumeFrom {
    EARLIEST,
    LATEST,
    SPECIFIC_OFFSET,
    SPECIFIC_DATETIME,
    CONSUMER_GROUP
}

data class ConsumeSettings(
    val topic: String,
    val from: ConsumeFrom = ConsumeFrom.LATEST,
    val maxMessages: Int = 100,
    val keyDeserializer: Deserializer = Deserializer.STRING,
    val valueDeserializer: Deserializer = Deserializer.JSON,
    val specificOffset: Long? = null,
    val specificTimestamp: Long? = null,
    val consumerGroup: String? = null,
    val partitionFilter: Int? = null
)
