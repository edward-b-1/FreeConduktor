package com.freeconductor.service

import com.freeconductor.model.*
import com.freeconductor.model.QuotaInfo
import com.freeconductor.model.StreamsAppInfo
import org.apache.kafka.clients.admin.*
import org.apache.kafka.clients.admin.AlterConfigOp
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.acl.*
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.quota.ClientQuotaAlteration
import org.apache.kafka.common.quota.ClientQuotaEntity
import org.apache.kafka.common.quota.ClientQuotaFilter
import org.apache.kafka.common.resource.PatternType
import org.apache.kafka.common.resource.ResourcePattern
import org.apache.kafka.common.resource.ResourcePatternFilter
import org.apache.kafka.common.resource.ResourceType
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.TimeUnit

data class BrokerTopicInfo(
    val defaults: Map<String, String>,
    val deprecatedKeys: Set<String>,
    val docs: Map<String, String>
)

class KafkaAdminService(private val clusterConfig: com.freeconductor.model.ClusterConfig) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(KafkaAdminService::class.java)
    private val adminClient: AdminClient = createAdminClient()

    private fun createAdminClient(): AdminClient {
        val props = Properties()
        props["bootstrap.servers"] = clusterConfig.bootstrapServers
        props["security.protocol"] = clusterConfig.securityProtocol
        props["request.timeout.ms"] = "10000"
        props["default.api.timeout.ms"] = "15000"

        when (clusterConfig.securityProtocol) {
            "SASL_PLAINTEXT", "SASL_SSL" -> {
                clusterConfig.saslMechanism?.let { props["sasl.mechanism"] = it }
                val username = clusterConfig.saslUsername
                val password = clusterConfig.saslPassword
                if (username != null && password != null) {
                    props["sasl.jaas.config"] =
                        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$username\" password=\"$password\";"
                }
            }
            "SSL", "SASL_SSL" -> {
                clusterConfig.sslTruststorePath?.let { props["ssl.truststore.location"] = it }
                clusterConfig.sslTruststorePassword?.let { props["ssl.truststore.password"] = it }
            }
        }
        return AdminClient.create(props)
    }

    fun listTopics(): List<TopicInfo> {
        val listResult = adminClient.listTopics(ListTopicsOptions().listInternal(true))
        val topicNames = listResult.names().get(15, TimeUnit.SECONDS)

        val describeResult = adminClient.describeTopics(topicNames)
        val descriptions = describeResult.allTopicNames().get(15, TimeUnit.SECONDS)

        val brokerCount = adminClient.describeCluster().nodes().get(15, TimeUnit.SECONDS).size.coerceAtLeast(1)

        return descriptions.values.map { desc ->
            var urp = 0; var noLeader = 0
            desc.partitions().forEach { p ->
                if (p.leader() == null || p.leader()!!.id() == -1) noLeader++
                if (p.isr().size < p.replicas().size) urp++
            }
            val uniqueBrokers = desc.partitions().flatMap { p -> p.replicas().map { it.id() } }.toSet().size
            TopicInfo(
                name = desc.name(),
                partitionCount = desc.partitions().size,
                replicationFactor = desc.partitions().firstOrNull()?.replicas()?.size ?: 0,
                isInternal = desc.isInternal,
                urpCount = urp,
                noLeaderCount = noLeader,
                spread = (uniqueBrokers * 100) / brokerCount
            )
        }.sortedBy { it.name }
    }

    fun getTopicLastWriteTimes(topicNames: Collection<String>): Map<String, Long> {
        if (topicNames.isEmpty()) return emptyMap()
        val descriptions = adminClient.describeTopics(topicNames.toList()).allTopicNames().get(15, TimeUnit.SECONDS)
        val allPartitions = descriptions.values.flatMap { desc ->
            desc.partitions().map { TopicPartition(desc.name(), it.partition()) }
        }
        if (allPartitions.isEmpty()) return emptyMap()

        val latestOffsets = adminClient.listOffsets(allPartitions.associateWith { OffsetSpec.latest() })
            .all().get(15, TimeUnit.SECONDS)
        val nonEmpty = allPartitions.filter { (latestOffsets[it]?.offset() ?: 0L) > 0L }
        if (nonEmpty.isEmpty()) return emptyMap()

        val maxTsResult = adminClient.listOffsets(nonEmpty.associateWith { OffsetSpec.maxTimestamp() })
            .all().get(15, TimeUnit.SECONDS)

        val result = mutableMapOf<String, Long>()
        for ((tp, info) in maxTsResult) {
            val ts = info.timestamp()
            if (ts > 0L) {
                val prev = result[tp.topic()]
                if (prev == null || ts > prev) result[tp.topic()] = ts
            }
        }
        return result
    }

    fun createTopic(name: String, partitions: Int, replicationFactor: Short, configs: Map<String, String> = emptyMap()) {
        val newTopic = NewTopic(name, partitions, replicationFactor).configs(configs)
        adminClient.createTopics(listOf(newTopic)).all().get(15, TimeUnit.SECONDS)
    }

    fun deleteTopic(name: String) {
        adminClient.deleteTopics(listOf(name)).all().get(15, TimeUnit.SECONDS)
    }

    fun describeTopicPartitions(topicName: String): List<PartitionInfo> {
        val describeResult = adminClient.describeTopics(listOf(topicName))
        val desc = describeResult.allTopicNames().get(15, TimeUnit.SECONDS)[topicName]
            ?: throw IllegalArgumentException("Topic not found: $topicName")

        val partitions = desc.partitions()
        val topicPartitions = partitions.map { TopicPartition(topicName, it.partition()) }

        val beginOffsets = adminClient.listOffsets(
            topicPartitions.associateWith { OffsetSpec.earliest() }
        ).all().get(15, TimeUnit.SECONDS)

        val endOffsets = adminClient.listOffsets(
            topicPartitions.associateWith { OffsetSpec.latest() }
        ).all().get(15, TimeUnit.SECONDS)

        // Fetch log sizes from each partition's leader broker
        val leaderBrokerIds = partitions.mapNotNull { it.leader()?.id() }.filter { it >= 0 }.toSet()
        val partitionSizes = mutableMapOf<Int, Long>()
        try {
            val logDirs = adminClient.describeLogDirs(leaderBrokerIds).allDescriptions().get(15, TimeUnit.SECONDS)
            for ((brokerId, brokerDirs) in logDirs) {
                for (dirInfo in brokerDirs.values) {
                    for ((tp, replicaInfo) in dirInfo.replicaInfos()) {
                        if (tp.topic() == topicName) {
                            val partitionLeaderId = partitions.find { it.partition() == tp.partition() }?.leader()?.id()
                            if (partitionLeaderId == brokerId) {
                                partitionSizes[tp.partition()] = replicaInfo.size()
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { /* log sizes unavailable; leave -1 */ }

        return partitions.map { partition ->
            val tp = TopicPartition(topicName, partition.partition())
            PartitionInfo(
                topicName = topicName,
                partition = partition.partition(),
                leader = partition.leader()?.id() ?: -1,
                replicas = partition.replicas().map { it.id() },
                isr = partition.isr().map { it.id() },
                earliestOffset = beginOffsets[tp]?.offset() ?: 0L,
                latestOffset = endOffsets[tp]?.offset() ?: 0L,
                logSize = partitionSizes[partition.partition()] ?: -1L
            )
        }
    }

    fun getTopicConfigs(topicName: String): List<TopicConfig> {
        val resource = ConfigResource(ConfigResource.Type.TOPIC, topicName)
        val options = org.apache.kafka.clients.admin.DescribeConfigsOptions().includeSynonyms(true)
        val result = adminClient.describeConfigs(listOf(resource), options)
        val config = result.all().get(15, TimeUnit.SECONDS)[resource] ?: return emptyList()

        return config.entries().map { entry ->
            // Synonyms are ordered highest→lowest priority; the last entry is the most-default value.
            // Some configs inherit their default from STATIC_BROKER_CONFIG rather than DEFAULT_CONFIG,
            // so we take the last synonym rather than filtering for a specific source.
            val defaultVal = entry.synonyms().lastOrNull()?.value()
            val overrideSource = when (entry.source()) {
                ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG          -> "TOPIC"
                ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG         -> "BROKER"
                ConfigEntry.ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG -> "CLUSTER"
                ConfigEntry.ConfigSource.STATIC_BROKER_CONFIG          -> "STATIC"
                else                                                    -> null
            }
            TopicConfig(
                name           = entry.name(),
                value          = entry.value(),
                isDefault      = entry.isDefault,
                isSensitive    = entry.isSensitive,
                defaultValue   = defaultVal,
                overrideSource = overrideSource
            )
        }.sortedBy { it.name }
    }

    fun listConsumerGroups(): List<ConsumerGroupInfo> {
        val groups = adminClient.listConsumerGroups().all().get(15, TimeUnit.SECONDS)
        val groupIds = groups.map { it.groupId() }
        if (groupIds.isEmpty()) return emptyList()

        val described = adminClient.describeConsumerGroups(groupIds).all().get(15, TimeUnit.SECONDS)

        // Fan-out committed-offset requests in parallel for all groups
        val offsetFutures = groupIds.associateWith { gid ->
            adminClient.listConsumerGroupOffsets(gid).partitionsToOffsetAndMetadata()
        }
        val groupOffsets = offsetFutures.mapValues { (_, future) ->
            try { future.get(15, TimeUnit.SECONDS).mapValues { it.value.offset() } }
            catch (_: Exception) { emptyMap() }
        }

        // Batch log-end-offset lookup for all partitions across all groups in one request
        val allTPs = groupOffsets.values.flatMap { it.keys }.toSet()
        val endOffsets = if (allTPs.isNotEmpty()) {
            try {
                adminClient.listOffsets(allTPs.associateWith { OffsetSpec.latest() })
                    .all().get(15, TimeUnit.SECONDS)
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        return described.values.map { desc ->
            val offsets = groupOffsets[desc.groupId()] ?: emptyMap()
            val lag = offsets.entries.sumOf { (tp, committed) ->
                maxOf(0L, (endOffsets[tp]?.offset() ?: 0L) - committed)
            }
            ConsumerGroupInfo(
                groupId        = desc.groupId(),
                state          = desc.state().toString(),
                members        = desc.members().size,
                totalLag       = lag,
                topicCount     = offsets.keys.map { it.topic() }.toSet().size,
                partitionCount = offsets.keys.size
            )
        }.sortedBy { it.groupId }
    }

    fun getConsumerGroupCoordinator(groupId: String): String {
        val desc = adminClient.describeConsumerGroups(listOf(groupId))
            .all().get(15, TimeUnit.SECONDS)[groupId] ?: return "—"
        val node = desc.coordinator()
        return "${node.id()} (${node.host()}:${node.port()})"
    }

    fun describeConsumerGroup(groupId: String): List<ConsumerGroupPartitionInfo> {
        val described = adminClient.describeConsumerGroups(listOf(groupId)).all().get(15, TimeUnit.SECONDS)
        val desc = described[groupId] ?: return emptyList()

        val assignedPartitions = mutableMapOf<TopicPartition, Triple<String?, String?, String?>>()
        for (member in desc.members()) {
            for (tp in member.assignment().topicPartitions()) {
                assignedPartitions[tp] = Triple(null, member.clientId(), member.host())
            }
        }

        val offsetResult = adminClient.listConsumerGroupOffsets(groupId)
            .partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS)

        val allPartitions = offsetResult.keys.toSet() + assignedPartitions.keys
        if (allPartitions.isEmpty()) return emptyList()

        val endOffsets = adminClient.listOffsets(
            allPartitions.associateWith { OffsetSpec.latest() }
        ).all().get(15, TimeUnit.SECONDS)

        return allPartitions.map { tp ->
            val committed = offsetResult[tp]?.offset() ?: -1L
            val endOffset = endOffsets[tp]?.offset() ?: 0L
            val lag = if (committed >= 0) maxOf(0L, endOffset - committed) else endOffset
            val memberInfo = assignedPartitions[tp]
            ConsumerGroupPartitionInfo(
                groupId = groupId,
                topic = tp.topic(),
                partition = tp.partition(),
                currentOffset = committed,
                logEndOffset = endOffset,
                lag = lag,
                memberId = memberInfo?.first,
                clientId = memberInfo?.second,
                host = memberInfo?.third
            )
        }.sortedWith(compareBy({ it.topic }, { it.partition }))
    }

    fun resetConsumerGroupOffsets(
        groupId: String,
        offsets: Map<TopicPartition, Long>
    ) {
        val offsetMap = offsets.mapValues { (_, offset) ->
            org.apache.kafka.clients.consumer.OffsetAndMetadata(offset)
        }
        adminClient.alterConsumerGroupOffsets(groupId, offsetMap).all().get(15, TimeUnit.SECONDS)
    }

    fun deleteConsumerGroup(groupId: String) {
        adminClient.deleteConsumerGroups(listOf(groupId)).all().get(15, TimeUnit.SECONDS)
    }

    fun listBrokers(): List<BrokerInfo> {
        val clusterDesc = adminClient.describeCluster()
        val nodes = clusterDesc.nodes().get(15, TimeUnit.SECONDS)
        val controllerId = clusterDesc.controller().get(15, TimeUnit.SECONDS)?.id() ?: -1

        return nodes.map { node ->
            BrokerInfo(
                id = node.id(),
                host = node.host(),
                port = node.port(),
                rack = node.rack(),
                isController = node.id() == controllerId
            )
        }.sortedBy { it.id }
    }

    fun listBrokersDetailed(): Pair<com.freeconductor.model.BrokerClusterInfo, List<BrokerInfo>> {
        val clusterDesc = adminClient.describeCluster()
        val nodes        = clusterDesc.nodes().get(15, TimeUnit.SECONDS)
        val controllerId = clusterDesc.controller().get(15, TimeUnit.SECONDS)?.id() ?: -1
        val clusterId    = clusterDesc.clusterId().get(15, TimeUnit.SECONDS) ?: "-"

        // Per-broker partition and leader counts from topic metadata
        val partitionCounts = mutableMapOf<Int, Int>()
        val leaderCounts    = mutableMapOf<Int, Int>()
        try {
            val topicNames   = adminClient.listTopics(ListTopicsOptions().listInternal(true)).names().get(15, TimeUnit.SECONDS)
            val descriptions = adminClient.describeTopics(topicNames).allTopicNames().get(15, TimeUnit.SECONDS)
            for (desc in descriptions.values) {
                for (p in desc.partitions()) {
                    p.replicas().forEach { r -> partitionCounts.merge(r.id(), 1, Int::plus) }
                    val lid = p.leader()?.id() ?: -1
                    if (lid >= 0) leaderCounts.merge(lid, 1, Int::plus)
                }
            }
        } catch (_: Exception) {}

        // Per-broker log sizes from log dirs
        val logSizes = mutableMapOf<Int, Long>()
        try {
            val brokerIds = nodes.map { it.id() }
            val logDirs   = adminClient.describeLogDirs(brokerIds).allDescriptions().get(15, TimeUnit.SECONDS)
            for ((brokerId, brokerDirs) in logDirs)
                logSizes[brokerId] = brokerDirs.values.sumOf { dir -> dir.replicaInfos().values.sumOf { it.size() } }
        } catch (_: Exception) {}

        // Protocol version + similar-config check from broker configs
        // Keys that legitimately differ between brokers and should be excluded from the comparison
        val perBrokerKeys = setOf("broker.id", "advertised.listeners", "listeners",
            "advertised.host.name", "host.name", "log.dirs", "log.dir")

        var protocolVersion = "n/a"
        var similarConfig   = true
        try {
            val resources = nodes.map { ConfigResource(ConfigResource.Type.BROKER, it.id().toString()) }
            val allConfigs = adminClient.describeConfigs(resources).all().get(10, TimeUnit.SECONDS)

            // Protocol version from first broker
            val firstCfg = allConfigs[resources.first()]
            protocolVersion = firstCfg?.get("inter.broker.protocol.version")?.value()
                ?: firstCfg?.get("log.message.format.version")?.value()
                ?: "n/a"

            // Similar config: all brokers must agree on every non-per-broker key
            if (nodes.size > 1) {
                val reference = allConfigs[resources.first()]
                    ?.entries()?.filter { it.name() !in perBrokerKeys }
                    ?.associate { it.name() to it.value() }
                    ?: emptyMap()
                similarConfig = resources.drop(1).all { resource ->
                    allConfigs[resource]
                        ?.entries()?.filter { it.name() !in perBrokerKeys }
                        ?.associate { it.name() to it.value() } == reference
                }
            }
        } catch (_: Exception) { similarConfig = false }

        val clusterInfo = com.freeconductor.model.BrokerClusterInfo(
            clusterId       = clusterId,
            brokerCount     = nodes.size,
            controllerId    = controllerId,
            protocolVersion = protocolVersion,
            similarConfig   = similarConfig
        )
        val brokers = nodes.map { node ->
            BrokerInfo(
                id             = node.id(),
                host           = node.host(),
                port           = node.port(),
                rack           = node.rack(),
                isController   = node.id() == controllerId,
                partitionCount = partitionCounts[node.id()] ?: 0,
                leaderCount    = leaderCounts[node.id()] ?: 0,
                logSize        = logSizes[node.id()] ?: -1L
            )
        }.sortedBy { it.id }
        return Pair(clusterInfo, brokers)
    }

    fun getBrokerConfig(brokerId: Int): List<com.freeconductor.model.BrokerConfigEntry> {
        val resource = ConfigResource(ConfigResource.Type.BROKER, brokerId.toString())
        val options = DescribeConfigsOptions().includeSynonyms(true)
        val configs = adminClient.describeConfigs(listOf(resource), options).all().get(10, TimeUnit.SECONDS)
        return configs[resource]?.entries()?.map { entry ->
            val overrideSource = when (entry.source()) {
                ConfigEntry.ConfigSource.STATIC_BROKER_CONFIG          -> "STATIC"
                ConfigEntry.ConfigSource.DYNAMIC_BROKER_CONFIG         -> "BROKER"
                ConfigEntry.ConfigSource.DYNAMIC_DEFAULT_BROKER_CONFIG -> "CLUSTER"
                ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG          -> "TOPIC"
                else                                                    -> null
            }
            val defaultValue = entry.synonyms()
                .lastOrNull { it.source() == ConfigEntry.ConfigSource.DEFAULT_CONFIG }
                ?.value()
                ?: entry.synonyms().lastOrNull()?.value()
            com.freeconductor.model.BrokerConfigEntry(
                name         = entry.name(),
                value        = entry.value() ?: "",
                isReadOnly   = entry.isReadOnly,
                isDefault    = entry.source() == ConfigEntry.ConfigSource.DEFAULT_CONFIG,
                overrideSource = overrideSource,
                defaultValue = defaultValue
            )
        }?.sortedBy { it.name } ?: emptyList()
    }

    fun alterBrokerConfig(brokerId: Int, propertyName: String, newValue: String?, clusterWide: Boolean) {
        val resourceId = if (clusterWide) "" else brokerId.toString()
        val resource = ConfigResource(ConfigResource.Type.BROKER, resourceId)
        val op = if (newValue != null)
            AlterConfigOp(ConfigEntry(propertyName, newValue), AlterConfigOp.OpType.SET)
        else
            AlterConfigOp(ConfigEntry(propertyName, ""), AlterConfigOp.OpType.DELETE)
        adminClient.incrementalAlterConfigs(mapOf(resource to listOf(op))).all().get(10, TimeUnit.SECONDS)
    }

    fun listAcls(): List<AclInfo> {
        val filter = AclBindingFilter(ResourcePatternFilter.ANY, AccessControlEntryFilter.ANY)
        val acls = adminClient.describeAcls(filter).values().get(15, TimeUnit.SECONDS)

        return acls.map { binding ->
            AclInfo(
                resourceType = binding.pattern().resourceType().toString(),
                resourceName = binding.pattern().name(),
                principal = binding.entry().principal(),
                host = binding.entry().host(),
                operation = binding.entry().operation().toString(),
                permissionType = binding.entry().permissionType().toString(),
                patternType = binding.pattern().patternType().toString()
            )
        }
    }

    fun createAcl(aclInfo: AclInfo) {
        val resourceType = ResourceType.valueOf(aclInfo.resourceType)
        val patternType = PatternType.valueOf(aclInfo.patternType)
        val operation = AclOperation.valueOf(aclInfo.operation)
        val permissionType = AclPermissionType.valueOf(aclInfo.permissionType)

        val pattern = ResourcePattern(resourceType, aclInfo.resourceName, patternType)
        val entry = AccessControlEntry(aclInfo.principal, aclInfo.host, operation, permissionType)
        val binding = AclBinding(pattern, entry)

        adminClient.createAcls(listOf(binding)).all().get(15, TimeUnit.SECONDS)
    }

    fun deleteAcl(aclInfo: AclInfo) {
        val resourceType = ResourceType.valueOf(aclInfo.resourceType)
        val patternType = PatternType.valueOf(aclInfo.patternType)
        val operation = AclOperation.valueOf(aclInfo.operation)
        val permissionType = AclPermissionType.valueOf(aclInfo.permissionType)

        val patternFilter = ResourcePatternFilter(resourceType, aclInfo.resourceName, patternType)
        val entryFilter = AccessControlEntryFilter(aclInfo.principal, aclInfo.host, operation, permissionType)
        val bindingFilter = AclBindingFilter(patternFilter, entryFilter)

        adminClient.deleteAcls(listOf(bindingFilter)).all().get(15, TimeUnit.SECONDS)
    }

    fun getBrokerCount(): Int =
        adminClient.describeCluster().nodes().get(15, TimeUnit.SECONDS).size.coerceAtLeast(1)

    fun getBrokerTopicDefaults(): Map<String, String> = getBrokerTopicInfo().defaults

    fun getBrokerTopicInfo(): BrokerTopicInfo {
        val nodes = adminClient.describeCluster().nodes().get(15, TimeUnit.SECONDS)
        if (nodes.isEmpty()) return BrokerTopicInfo(emptyMap(), emptySet(), emptyMap())
        val resource = ConfigResource(ConfigResource.Type.BROKER, nodes.first().id().toString())
        val config = adminClient.describeConfigs(listOf(resource)).all()
            .get(15, TimeUnit.SECONDS)[resource]
            ?: return BrokerTopicInfo(emptyMap(), emptySet(), emptyMap())
        val defaults = TOPIC_CONFIG_KEYS.mapNotNull { key ->
            config.get(key)?.value()?.let { key to it }
        }.toMap()
        val deprecated = TOPIC_CONFIG_KEYS.filter { key ->
            config.get(key)?.documentation()?.startsWith("[DEPRECATED]") == true
        }.toSet()
        val docs = TOPIC_CONFIG_KEYS.mapNotNull { key ->
            config.get(key)?.documentation()?.takeIf { it.isNotBlank() }?.let { key to it }
        }.toMap()
        return BrokerTopicInfo(defaults, deprecated, docs)
    }

    companion object {
        val TOPIC_CONFIG_KEYS = listOf(
            "min.insync.replicas", "retention.bytes", "retention.ms",
            "compression.type", "delete.retention.ms", "file.delete.delay.ms",
            "flush.messages", "flush.ms", "index.interval.bytes",
            "local.retention.bytes", "local.retention.ms", "max.compaction.lag.ms",
            "max.message.bytes", "message.downconversion.enable", "message.format.version",
            "message.timestamp.difference.max.ms", "message.timestamp.type",
            "min.cleanable.dirty.ratio", "min.compaction.lag.ms",
            "preallocate", "remote.storage.enable",
            "segment.bytes", "segment.index.bytes", "segment.jitter.ms", "segment.ms",
            "unclean.leader.election.enable",
            "leader.replication.throttled.replicas", "follower.replication.throttled.replicas"
        )
    }

    fun getClusterInfo(): String {
        val cluster = adminClient.describeCluster()
        val clusterId = cluster.clusterId().get(15, TimeUnit.SECONDS)
        return clusterId ?: "unknown"
    }

    data class ClusterStats(
        // Brokers
        val brokerCount: Int,
        val controllerId: Int,
        val clusterId: String,
        val defaultReplicationFactor: Int,
        // Topics
        val topicCount: Int,
        val partitionCount: Int,
        val urpCount: Int,
        val noLeaderCount: Int,
        val underMinIsrCount: Int,
        // Consumer groups
        val groupCount: Int,
        val activeGroups: Int,
        val emptyGroups: Int,
        val rebalancingGroups: Int,
        val deadGroups: Int,
        // Kafka Streams
        val streamsAppCount: Int,
        // Security
        val aclsEnabled: Boolean,
        val aclCount: Int,
        val aclUserCount: Int,
        val aclTopicCount: Int,
        val aclGroupCount: Int
    )

    fun getClusterStats(): ClusterStats {
        // ── Brokers ──────────────────────────────────────────────────────
        val clusterDesc = adminClient.describeCluster()
        val brokers = clusterDesc.nodes().get(15, TimeUnit.SECONDS)
        val controller = clusterDesc.controller().get(15, TimeUnit.SECONDS)
        val clusterId = clusterDesc.clusterId().get(15, TimeUnit.SECONDS) ?: "-"

        // ── Topics + health metrics ───────────────────────────────────────
        // Include internal topics so counts match the broker's true state
        val topicNames = adminClient.listTopics(ListTopicsOptions().listInternal(true))
            .names().get(15, TimeUnit.SECONDS)
        val descriptions = adminClient.describeTopics(topicNames).allTopicNames().get(15, TimeUnit.SECONDS)

        // Fetch broker config for min.insync.replicas and default.replication.factor
        val (minInSyncReplicas, defaultReplicationFactor) = try {
            val resource = ConfigResource(ConfigResource.Type.BROKER, brokers.first().id().toString())
            val cfg = adminClient.describeConfigs(listOf(resource)).all().get(10, TimeUnit.SECONDS)[resource]
            val minIsr = cfg?.get("min.insync.replicas")?.value()?.toIntOrNull() ?: 1
            val defaultRF = cfg?.get("default.replication.factor")?.value()?.toIntOrNull() ?: 1
            Pair(minIsr, defaultRF)
        } catch (_: Exception) { Pair(1, 1) }

        var partitionCount = 0; var urpCount = 0; var noLeaderCount = 0; var underMinIsrCount = 0
        for (desc in descriptions.values) {
            for (p in desc.partitions()) {
                partitionCount++
                val rf = p.replicas().size
                val isr = p.isr().size
                if (p.leader() == null || p.leader()!!.id() == -1) noLeaderCount++
                if (isr < rf) urpCount++
                if (isr < minInSyncReplicas) underMinIsrCount++
            }
        }

        // ── Consumer groups by state ──────────────────────────────────────
        val groups = adminClient.listConsumerGroups().all().get(15, TimeUnit.SECONDS)
        var activeGroups = 0; var emptyGroups = 0; var rebalancingGroups = 0; var deadGroups = 0
        for (g in groups) {
            when (g.state().orElse(null)?.name?.uppercase()) {
                "STABLE"                               -> activeGroups++
                "EMPTY"                                -> emptyGroups++
                "PREPARING_REBALANCE",
                "COMPLETING_REBALANCE"                 -> rebalancingGroups++
                "DEAD"                                 -> deadGroups++
            }
        }

        // ── Kafka Streams detection ───────────────────────────────────────
        val groupIds = groups.map { it.groupId() }.toSet()
        val streamsAppCount = groupIds.count { gid ->
            topicNames.any { t ->
                t.startsWith("$gid-") && (t.endsWith("-changelog") || t.endsWith("-repartition"))
            }
        }

        // ── Security ─────────────────────────────────────────────────────
        var aclsEnabled = false; var aclCount = 0
        var aclUserCount = 0; var aclTopicCount = 0; var aclGroupCount = 0
        try {
            val acls = adminClient.describeAcls(
                AclBindingFilter(ResourcePatternFilter.ANY, AccessControlEntryFilter.ANY)
            ).values().get(10, TimeUnit.SECONDS)
            aclsEnabled = true; aclCount = acls.size
            aclUserCount  = acls.map { it.entry().principal() }
                .filter { it.startsWith("User:") }.toSet().size
            aclTopicCount = acls.filter { it.pattern().resourceType().toString() == "TOPIC" }
                .map { it.pattern().name() }.toSet().size
            aclGroupCount = acls.filter { it.pattern().resourceType().toString() == "GROUP" }
                .map { it.pattern().name() }.toSet().size
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (!msg.contains("disabled") && !msg.contains("not supported")) aclsEnabled = true
        }

        return ClusterStats(
            brokerCount = brokers.size, controllerId = controller?.id() ?: -1,
            clusterId = clusterId, defaultReplicationFactor = defaultReplicationFactor,
            topicCount = topicNames.size, partitionCount = partitionCount,
            urpCount = urpCount, noLeaderCount = noLeaderCount, underMinIsrCount = underMinIsrCount,
            groupCount = groups.size,
            activeGroups = activeGroups, emptyGroups = emptyGroups,
            rebalancingGroups = rebalancingGroups, deadGroups = deadGroups,
            streamsAppCount = streamsAppCount,
            aclsEnabled = aclsEnabled, aclCount = aclCount,
            aclUserCount = aclUserCount, aclTopicCount = aclTopicCount, aclGroupCount = aclGroupCount
        )
    }

    fun getTopicMessageCounts(topicNames: List<String>): Map<String, Long> {
        if (topicNames.isEmpty()) return emptyMap()
        val descriptions = adminClient.describeTopics(topicNames).allTopicNames().get(15, TimeUnit.SECONDS)
        val allPartitions = descriptions.values.flatMap { desc ->
            desc.partitions().map { TopicPartition(desc.name(), it.partition()) }
        }
        if (allPartitions.isEmpty()) return emptyMap()
        val earliest = adminClient.listOffsets(allPartitions.associateWith { OffsetSpec.earliest() })
            .all().get(15, TimeUnit.SECONDS)
        val latest = adminClient.listOffsets(allPartitions.associateWith { OffsetSpec.latest() })
            .all().get(15, TimeUnit.SECONDS)
        return descriptions.keys.associateWith { topic ->
            descriptions[topic]!!.partitions().sumOf { p ->
                val tp = TopicPartition(topic, p.partition())
                val lo = latest[tp]?.offset() ?: 0L
                val ea = earliest[tp]?.offset() ?: 0L
                maxOf(0L, lo - ea)
            }
        }
    }

    fun getTopicConsumerGroups(topicName: String): List<com.freeconductor.model.TopicConsumerGroupInfo> {
        val allGroups = adminClient.listConsumerGroups().all().get(15, TimeUnit.SECONDS)
        if (allGroups.isEmpty()) return emptyList()

        // Fan-out offset requests for all groups simultaneously
        val offsetFutures = allGroups.associate { g ->
            g.groupId() to adminClient.listConsumerGroupOffsets(g.groupId()).partitionsToOffsetAndMetadata()
        }

        // Filter to groups that have committed offsets for this topic
        val relevantGroups = mutableMapOf<String, Map<TopicPartition, Long>>()
        for ((groupId, future) in offsetFutures) {
            val offsets = try { future.get(15, TimeUnit.SECONDS) } catch (_: Exception) { continue }
            val topicOffsets = offsets.filterKeys { it.topic() == topicName }
            if (topicOffsets.isNotEmpty())
                relevantGroups[groupId] = topicOffsets.mapValues { it.value.offset() }
        }
        if (relevantGroups.isEmpty()) return emptyList()

        // Log-end offsets for lag calculation
        val allTPs = relevantGroups.values.flatMap { it.keys }.toSet()
        val endOffsets = adminClient.listOffsets(allTPs.associateWith { OffsetSpec.latest() })
            .all().get(15, TimeUnit.SECONDS)

        // Group states
        val described = adminClient.describeConsumerGroups(relevantGroups.keys.toList())
            .all().get(15, TimeUnit.SECONDS)

        return relevantGroups.map { (groupId, committed) ->
            val lag = committed.entries.sumOf { (tp, offset) ->
                maxOf(0L, (endOffsets[tp]?.offset() ?: 0L) - offset)
            }
            val stateRaw = described[groupId]?.state()?.toString()?.uppercase() ?: ""
            val state = when (stateRaw) {
                "STABLE"                                      -> "Active"
                "EMPTY"                                       -> "Empty"
                "PREPARING_REBALANCE", "COMPLETING_REBALANCE" -> "Rebalancing"
                "DEAD"                                        -> "Dead"
                else                                          -> stateRaw.ifEmpty { "Unknown" }
            }
            com.freeconductor.model.TopicConsumerGroupInfo(groupId, state, lag)
        }.sortedBy { it.groupId }
    }

    fun getTopicLogSizes(topicNames: Collection<String>): Map<String, Long> {
        if (topicNames.isEmpty()) return emptyMap()
        val topicSet = topicNames.toSet()
        val brokerIds = adminClient.describeCluster().nodes().get(15, TimeUnit.SECONDS).map { it.id() }
        val logDirs = adminClient.describeLogDirs(brokerIds).allDescriptions().get(15, TimeUnit.SECONDS)
        val sizes = mutableMapOf<String, Long>()
        for (brokerDirs in logDirs.values)
            for (dirInfo in brokerDirs.values)
                for ((tp, replicaInfo) in dirInfo.replicaInfos())
                    if (tp.topic() in topicSet) sizes[tp.topic()] = (sizes[tp.topic()] ?: 0L) + replicaInfo.size()
        return sizes
    }

    fun getTopicConsumerCounts(topicNames: Collection<String>): Map<String, Int> {
        if (topicNames.isEmpty()) return emptyMap()
        val topicSet = topicNames.toSet()
        val groupIds = adminClient.listConsumerGroups().all().get(15, TimeUnit.SECONDS).map { it.groupId() }
        if (groupIds.isEmpty()) return emptyMap()
        val described = adminClient.describeConsumerGroups(groupIds).all().get(15, TimeUnit.SECONDS)
        val counts = mutableMapOf<String, Int>()
        for (desc in described.values) {
            val subscribedTopics = desc.members().flatMap { it.assignment().topicPartitions() }.map { it.topic() }.toSet()
            for (topic in subscribedTopics.intersect(topicSet)) counts[topic] = (counts[topic] ?: 0) + 1
        }
        return counts
    }

    fun listQuotas(): List<QuotaInfo> {
        val filter = ClientQuotaFilter.all()
        val result = adminClient.describeClientQuotas(filter).entities().get(15, TimeUnit.SECONDS)
        return result.entries.map { (entity, quotas) ->
            val parts = entity.entries().entries.joinToString(", ") { (type, name) ->
                "${type.replaceFirstChar { it.uppercase() }}: ${name ?: "<default>"}"
            }
            QuotaInfo(
                entityDescription = parts.ifBlank { "<default>" },
                entityEntries = entity.entries(),
                producerByteRate = quotas["producer_byte_rate"],
                consumerByteRate = quotas["consumer_byte_rate"],
                requestPercentage = quotas["request_percentage"]
            )
        }
    }

    fun createQuota(entityType: String, entityName: String?, quotaKey: String, quotaValue: Double) {
        val entityMap = java.util.HashMap<String, String?>()
        entityMap[entityType] = entityName
        val entity = ClientQuotaEntity(entityMap)
        val op = ClientQuotaAlteration.Op(quotaKey, quotaValue)
        adminClient.alterClientQuotas(listOf(ClientQuotaAlteration(entity, listOf(op)))).all().get(15, TimeUnit.SECONDS)
    }

    fun deleteQuotaEntity(quota: QuotaInfo) {
        val keysToRemove = buildList {
            if (quota.producerByteRate != null) add("producer_byte_rate")
            if (quota.consumerByteRate != null) add("consumer_byte_rate")
            if (quota.requestPercentage != null) add("request_percentage")
        }
        val entity = ClientQuotaEntity(quota.entityEntries)
        val ops = keysToRemove.map { ClientQuotaAlteration.Op(it, null) }
        adminClient.alterClientQuotas(listOf(ClientQuotaAlteration(entity, ops))).all().get(15, TimeUnit.SECONDS)
    }

    fun detectStreamsApplications(): List<StreamsAppInfo> {
        val allTopics = adminClient.listTopics(ListTopicsOptions().listInternal(true))
            .names().get(15, TimeUnit.SECONDS)
        val groups = adminClient.listConsumerGroups().all().get(15, TimeUnit.SECONDS)
        val groupIds = groups.map { it.groupId() }
        if (groupIds.isEmpty()) return emptyList()
        val described = adminClient.describeConsumerGroups(groupIds).all().get(15, TimeUnit.SECONDS)
        return described.values.mapNotNull { desc ->
            val groupId = desc.groupId()
            val internalTopics = allTopics.filter { topic ->
                topic.startsWith("$groupId-") && (
                    topic.endsWith("-changelog") || topic.endsWith("-repartition") ||
                    topic.contains("-KSTREAM-") || topic.contains("-KTABLE-")
                )
            }.sorted()
            if (internalTopics.isNotEmpty()) {
                StreamsAppInfo(
                    applicationId = groupId,
                    state = desc.state().toString(),
                    memberCount = desc.members().size,
                    internalTopics = internalTopics
                )
            } else null
        }
    }

    override fun close() {
        try {
            adminClient.close()
        } catch (e: Exception) {
            logger.warn("Error closing admin client", e)
        }
    }
}
