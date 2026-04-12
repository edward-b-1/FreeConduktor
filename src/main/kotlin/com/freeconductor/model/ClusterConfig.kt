package com.freeconductor.model

import java.util.UUID

data class ClusterConfig(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var bootstrapServers: String = "localhost:9092",
    var securityProtocol: String = "PLAINTEXT",
    var saslMechanism: String? = null,
    var saslUsername: String? = null,
    var saslPassword: String? = null,
    var sslTruststorePath: String? = null,
    var sslTruststorePassword: String? = null,
    var schemaRegistryUrl: String? = null,
    var schemaRegistryUsername: String? = null,
    var schemaRegistryPassword: String? = null,
    var kafkaConnectUrl: String? = null,
    var color: String? = null
) {
    override fun toString(): String = name
}
