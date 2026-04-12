package com.freeconductor.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.freeconductor.model.*
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.time.Duration
import java.util.*
import java.util.Base64

class KafkaConsumerService(private val clusterConfig: com.freeconductor.model.ClusterConfig) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(KafkaConsumerService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    @Volatile private var running = false
    private var consumer: KafkaConsumer<ByteArray?, ByteArray?>? = null

    private fun createConsumer(groupId: String? = null): KafkaConsumer<ByteArray?, ByteArray?> {
        val props = Properties()
        props[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = clusterConfig.bootstrapServers
        props[ConsumerConfig.GROUP_ID_CONFIG] = groupId ?: "freeconductor-consumer-${UUID.randomUUID()}"
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java.name
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java.name
        props[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = "false"
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        props[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = "500"
        props["security.protocol"] = clusterConfig.securityProtocol

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
        return KafkaConsumer(props)
    }

    fun consume(
        settings: ConsumeSettings,
        onMessage: (MessageRecord) -> Unit,
        onComplete: () -> Unit,
        onError: (Exception) -> Unit
    ) {
        running = true
        val c = createConsumer(settings.consumerGroup)
        consumer = c

        try {
            val partitions = c.partitionsFor(settings.topic)
                ?.map { TopicPartition(settings.topic, it.partition()) }
                ?: emptyList()

            val filtered = if (settings.partitionFilter != null) {
                partitions.filter { it.partition() == settings.partitionFilter }
            } else {
                partitions
            }

            if (filtered.isEmpty()) {
                onComplete()
                return
            }

            c.assign(filtered)

            when (settings.from) {
                ConsumeFrom.EARLIEST -> c.seekToBeginning(filtered)
                ConsumeFrom.LATEST -> c.seekToEnd(filtered)
                ConsumeFrom.SPECIFIC_OFFSET -> {
                    val offset = settings.specificOffset ?: 0L
                    filtered.forEach { c.seek(it, offset) }
                }
                ConsumeFrom.SPECIFIC_DATETIME -> {
                    val ts = settings.specificTimestamp ?: System.currentTimeMillis()
                    val timestamps = filtered.associateWith { ts }
                    val result = c.offsetsForTimes(timestamps)
                    result.forEach { (tp, offsetAndTimestamp) ->
                        if (offsetAndTimestamp != null) {
                            c.seek(tp, offsetAndTimestamp.offset())
                        } else {
                            c.seekToEnd(listOf(tp))
                        }
                    }
                }
                ConsumeFrom.CONSUMER_GROUP -> {
                    // Use existing group offsets (don't seek)
                }
            }

            var count = 0
            while (running && count < settings.maxMessages) {
                val records = c.poll(Duration.ofMillis(1000))
                if (records.isEmpty && settings.from != ConsumeFrom.CONSUMER_GROUP) {
                    break
                }
                for (record in records) {
                    if (!running) break
                    val msg = convertRecord(record, settings.keyDeserializer, settings.valueDeserializer)
                    onMessage(msg)
                    count++
                    if (count >= settings.maxMessages) break
                }
            }
            onComplete()
        } catch (e: Exception) {
            if (running) {
                logger.error("Error consuming messages", e)
                onError(e)
            }
        } finally {
            running = false
            try { c.close() } catch (_: Exception) {}
            consumer = null
        }
    }

    fun stopConsuming() {
        running = false
        consumer?.wakeup()
    }

    private fun convertRecord(
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        keyDeserializer: Deserializer,
        valueDeserializer: Deserializer
    ): MessageRecord {
        val key = record.key()?.let { deserialize(it, keyDeserializer) }
        val value = record.value()?.let { deserialize(it, valueDeserializer) }
        val headers = record.headers().associate { header ->
            header.key() to (header.value()?.let { String(it) } ?: "")
        }
        return MessageRecord(
            topic = record.topic(),
            partition = record.partition(),
            offset = record.offset(),
            timestamp = record.timestamp(),
            key = key,
            value = value,
            headers = headers,
            keySize = record.key()?.size ?: 0,
            valueSize = record.value()?.size ?: 0
        )
    }

    private fun deserialize(bytes: ByteArray, deserializer: Deserializer): String {
        return try {
            when (deserializer) {
                Deserializer.STRING -> String(bytes, Charsets.UTF_8)
                Deserializer.INTEGER -> {
                    if (bytes.size == 4) ByteBuffer.wrap(bytes).int.toString()
                    else String(bytes, Charsets.UTF_8)
                }
                Deserializer.LONG -> {
                    if (bytes.size == 8) ByteBuffer.wrap(bytes).long.toString()
                    else String(bytes, Charsets.UTF_8)
                }
                Deserializer.DOUBLE -> {
                    if (bytes.size == 8) ByteBuffer.wrap(bytes).double.toString()
                    else String(bytes, Charsets.UTF_8)
                }
                Deserializer.JSON -> {
                    val str = String(bytes, Charsets.UTF_8)
                    try {
                        val node = mapper.readTree(str)
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
                    } catch (_: Exception) {
                        str
                    }
                }
                Deserializer.BASE64 -> Base64.getEncoder().encodeToString(bytes)
            }
        } catch (e: Exception) {
            Base64.getEncoder().encodeToString(bytes)
        }
    }

    override fun close() {
        stopConsuming()
    }
}
