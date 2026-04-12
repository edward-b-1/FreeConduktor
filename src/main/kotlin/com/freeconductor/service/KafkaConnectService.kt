package com.freeconductor.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.ConnectorInfo
import com.freeconductor.model.ConnectorTask
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class KafkaConnectService(private val clusterConfig: ClusterConfig) {
    private val logger = LoggerFactory.getLogger(KafkaConnectService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val baseUrl: String = clusterConfig.kafkaConnectUrl?.trimEnd('/') ?: "http://localhost:8083"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json".toMediaType()

    private fun buildRequest(url: String): Request.Builder {
        return Request.Builder()
            .url("$baseUrl$url")
            .header("Accept", "application/json")
    }

    private fun get(url: String): JsonNode {
        val request = buildRequest(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("Empty response")
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $body")
        return mapper.readTree(body)
    }

    private fun post(url: String, payload: String): JsonNode {
        val body = payload.toRequestBody(JSON_TYPE)
        val request = buildRequest(url).post(body).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $responseBody")
        return mapper.readTree(responseBody)
    }

    private fun put(url: String, payload: String): JsonNode {
        val body = payload.toRequestBody(JSON_TYPE)
        val request = buildRequest(url).put(body).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: $responseBody")
        return mapper.readTree(responseBody)
    }

    private fun delete(url: String) {
        val request = buildRequest(url).delete().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw RuntimeException("HTTP ${response.code}: $body")
        }
    }

    fun listConnectors(): List<ConnectorInfo> {
        val names = get("/connectors?expand=status&expand=info")
        val connectors = mutableListOf<ConnectorInfo>()

        if (names.isObject) {
            for (name in names.fieldNames()) {
                try {
                    val status = names[name]["status"]
                    val info = names[name]["info"]
                    val connectorState = status?.get("connector")?.get("state")?.asText() ?: "UNKNOWN"
                    val connectorType = info?.get("type")?.asText() ?: "unknown"
                    val tasks = parseTasks(status?.get("tasks"), name)
                    val config = parseConfig(info?.get("config"))
                    connectors.add(ConnectorInfo(name, connectorState, connectorType, tasks, config))
                } catch (e: Exception) {
                    connectors.add(ConnectorInfo(name))
                }
            }
        } else if (names.isArray) {
            // Fallback: names is a plain array
            for (node in names) {
                val name = node.asText()
                try {
                    connectors.add(getConnector(name))
                } catch (e: Exception) {
                    connectors.add(ConnectorInfo(name))
                }
            }
        }

        return connectors
    }

    private fun parseTasks(tasksNode: JsonNode?, connectorName: String): List<ConnectorTask> {
        if (tasksNode == null || !tasksNode.isArray) return emptyList()
        return tasksNode.mapIndexed { _, taskNode ->
            ConnectorTask(
                taskId = taskNode["id"]?.asInt() ?: 0,
                state = taskNode["state"]?.asText() ?: "UNKNOWN",
                workerId = taskNode["worker_id"]?.asText(),
                trace = taskNode["trace"]?.asText()
            )
        }
    }

    private fun parseConfig(configNode: JsonNode?): Map<String, String> {
        if (configNode == null || !configNode.isObject) return emptyMap()
        val result = mutableMapOf<String, String>()
        configNode.fieldNames().forEach { key ->
            result[key] = configNode[key].asText()
        }
        return result
    }

    fun getConnector(name: String): ConnectorInfo {
        val status = get("/connectors/$name/status")
        val info = get("/connectors/$name")
        val connectorState = status["connector"]?.get("state")?.asText() ?: "UNKNOWN"
        val connectorType = info["type"]?.asText() ?: "unknown"
        val tasks = parseTasks(status["tasks"], name)
        val config = parseConfig(info["config"])
        return ConnectorInfo(name, connectorState, connectorType, tasks, config)
    }

    fun createConnector(name: String, config: Map<String, String>): ConnectorInfo {
        val payload = mapper.writeValueAsString(mapOf("name" to name, "config" to config))
        val result = post("/connectors", payload)
        return ConnectorInfo(
            name = result["name"]?.asText() ?: name,
            config = parseConfig(result["config"])
        )
    }

    fun updateConnectorConfig(name: String, config: Map<String, String>): ConnectorInfo {
        val payload = mapper.writeValueAsString(config)
        val result = put("/connectors/$name/config", payload)
        return ConnectorInfo(
            name = name,
            config = parseConfig(result)
        )
    }

    fun pauseConnector(name: String) {
        put("/connectors/$name/pause", "{}")
    }

    fun resumeConnector(name: String) {
        put("/connectors/$name/resume", "{}")
    }

    fun restartConnector(name: String) {
        val body = "{}".toRequestBody(JSON_TYPE)
        val request = buildRequest("/connectors/$name/restart").post(body).build()
        client.newCall(request).execute()
    }

    fun deleteConnector(name: String) {
        delete("/connectors/$name")
    }

    fun getConnectorConfig(name: String): Map<String, String> {
        val result = get("/connectors/$name/config")
        return parseConfig(result)
    }
}
