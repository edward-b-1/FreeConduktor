package com.freeconductor.model

data class TopicInfo(
    val name: String,
    val partitionCount: Int,
    val replicationFactor: Int,
    val messageCount: Long = -1L,
    val isInternal: Boolean = false,
    val isFavourite: Boolean = false,
    val urpCount: Int = 0,
    val noLeaderCount: Int = 0,
    val logSize: Long = -1L,       // bytes; -1 = not yet loaded
    val consumerCount: Int = -1,   // active consumer groups; -1 = not yet loaded
    val lastWriteTime: Long? = null, // epoch ms of latest message; null = not loaded / empty
    val spread: Int? = null          // % of brokers holding ≥1 replica; null = not loaded
)

data class PartitionInfo(
    val topicName: String,
    val partition: Int,
    val leader: Int,
    val replicas: List<Int>,
    val isr: List<Int>,
    val earliestOffset: Long,
    val latestOffset: Long,
    val logSize: Long = -1L   // bytes on leader replica; -1 = not loaded
) {
    val messageCount: Long get() = latestOffset - earliestOffset
}

data class TopicConfig(
    val name: String,
    val value: String?,
    val isDefault: Boolean = false,
    val isSensitive: Boolean = false,
    val defaultValue: String? = null,
    val overrideSource: String? = null
)
