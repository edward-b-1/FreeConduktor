package com.freeconductor.service

import com.freeconductor.model.ClusterConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.Properties

class KafkaProducerService(
    private val clusterConfig: ClusterConfig,
    val compression: String = "none",
    val acks: String = "all",
    val idempotent: Boolean = false
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(KafkaProducerService::class.java)
    private val producer: KafkaProducer<ByteArray?, ByteArray?> = createProducer()

    private fun createProducer(): KafkaProducer<ByteArray?, ByteArray?> {
        val props = Properties()
        props[ProducerConfig.BOOTSTRAP_SERVERS_CONFIG] = clusterConfig.bootstrapServers
        props[ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java.name
        props[ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG] = ByteArraySerializer::class.java.name
        props[ProducerConfig.ACKS_CONFIG] = when (acks) {
            "none"   -> "0"
            "leader" -> "1"
            else     -> "all"
        }
        props[ProducerConfig.RETRIES_CONFIG] = 3
        if (compression != "none") props[ProducerConfig.COMPRESSION_TYPE_CONFIG] = compression
        if (idempotent)            props[ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG] = true
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
        return KafkaProducer(props)
    }

    fun send(
        topic: String,
        key: String?,
        value: String,
        keyFormat: String = "STRING",
        valueFormat: String = "STRING",
        partition: Int? = null,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, Long> {
        val keyBytes = key?.let { serializeValue(it, keyFormat) }
        val valueBytes = serializeValue(value, valueFormat)

        val record = if (partition != null) {
            ProducerRecord(topic, partition, keyBytes, valueBytes)
        } else {
            ProducerRecord(topic, keyBytes, valueBytes)
        }

        for ((headerKey, headerValue) in headers) {
            record.headers().add(RecordHeader(headerKey, headerValue.toByteArray()))
        }

        val metadata = producer.send(record).get()
        logger.info("Sent message to {}-{} at offset {}", topic, metadata.partition(), metadata.offset())
        return Pair(metadata.partition(), metadata.offset())
    }

    private fun serializeValue(value: String, format: String): ByteArray {
        return when (format.uppercase()) {
            "INTEGER", "INT"         -> ByteBuffer.allocate(4).putInt(value.trim().toInt()).array()
            "LONG"                   -> ByteBuffer.allocate(8).putLong(value.trim().toLong()).array()
            "FLOAT"                  -> ByteBuffer.allocate(4).putFloat(value.trim().toFloat()).array()
            "DOUBLE"                 -> ByteBuffer.allocate(8).putDouble(value.trim().toDouble()).array()
            "BYTES (BASE64)", "BASE64" -> java.util.Base64.getDecoder().decode(value.trim())
            else                     -> value.toByteArray(Charsets.UTF_8)
        }
    }

    override fun close() {
        try {
            producer.flush()
            producer.close()
        } catch (e: Exception) {
            logger.warn("Error closing producer", e)
        }
    }
}
