package com.freeconductor.model

data class TopicConsumerGroupInfo(
    val groupId: String,
    val state: String,
    val lag: Long
)

data class ConsumerGroupInfo(
    val groupId: String,
    val state: String,
    val members: Int = 0,
    val totalLag: Long = 0L,
    val topicCount: Int = 0,
    val partitionCount: Int = 0
)

data class ConsumerGroupPartitionInfo(
    val groupId: String,
    val topic: String,
    val partition: Int,
    val currentOffset: Long,
    val logEndOffset: Long,
    val lag: Long,
    val memberId: String? = null,
    val clientId: String? = null,
    val host: String? = null
)

data class BrokerInfo(
    val id: Int,
    val host: String,
    val port: Int,
    val rack: String? = null,
    val isController: Boolean = false,
    val partitionCount: Int = 0,
    val leaderCount: Int = 0,
    val logSize: Long = -1L
)

data class BrokerClusterInfo(
    val clusterId: String,
    val brokerCount: Int,
    val controllerId: Int,
    val protocolVersion: String,
    val similarConfig: Boolean
)

data class AclInfo(
    val resourceType: String,
    val resourceName: String,
    val principal: String,
    val host: String,
    val operation: String,
    val permissionType: String,
    val patternType: String = "LITERAL"
)
