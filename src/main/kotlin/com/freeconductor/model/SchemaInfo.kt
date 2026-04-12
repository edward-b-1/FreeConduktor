package com.freeconductor.model

data class SchemaSubject(
    val name: String,
    val versions: List<Int> = emptyList(),
    val latestVersion: Int = 0,
    val schemaType: String = "AVRO"
)

data class SchemaVersion(
    val subject: String,
    val version: Int,
    val schemaId: Int,
    val schema: String,
    val schemaType: String = "AVRO"
)

data class ConnectorInfo(
    val name: String,
    val state: String = "UNKNOWN",
    val type: String = "unknown",
    val tasks: List<ConnectorTask> = emptyList(),
    val config: Map<String, String> = emptyMap()
)

data class ConnectorTask(
    val taskId: Int,
    val state: String,
    val workerId: String? = null,
    val trace: String? = null
)
