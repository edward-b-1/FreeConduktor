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
    FLOAT,
    DOUBLE,
    JSON,
    BASE64,
    AVRO_EMBEDDED,
    NONE
}

enum class ConsumeFrom {
    EARLIEST,
    LATEST,
    SPECIFIC_OFFSET,
    SPECIFIC_DATETIME,
    CONSUMER_GROUP
}

enum class ConsumeLimit {
    NONE,
    RECORD_COUNT,
    SPECIFIC_DATE,
    MAX_BYTES,
    PER_PARTITION_RECORD_COUNT,
    PER_PARTITION_MAX_BYTES
}

data class ConsumeSettings(
    val topic: String,
    val from: ConsumeFrom = ConsumeFrom.LATEST,
    val limit: ConsumeLimit = ConsumeLimit.NONE,
    val limitValue: Long? = null,
    val keyDeserializer: Deserializer = Deserializer.STRING,
    val valueDeserializer: Deserializer = Deserializer.JSON,
    val specificOffset: Long? = null,
    val specificTimestamp: Long? = null,
    val consumerGroup: String? = null,
    val partitionFilter: Int? = null
)
