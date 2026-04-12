package com.freeconductor.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.SchemaSubject
import com.freeconductor.model.SchemaVersion
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class SchemaRegistryService(private val clusterConfig: ClusterConfig) {
    private val logger = LoggerFactory.getLogger(SchemaRegistryService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val baseUrl: String = clusterConfig.schemaRegistryUrl?.trimEnd('/') ?: "http://localhost:8081"
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/vnd.schemaregistry.v1+json".toMediaType()

    private fun buildRequest(url: String): Request.Builder {
        val builder = Request.Builder().url("$baseUrl$url")
        val username = clusterConfig.schemaRegistryUsername
        val password = clusterConfig.schemaRegistryPassword
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            builder.header("Authorization", Credentials.basic(username, password))
        }
        builder.header("Accept", "application/vnd.schemaregistry.v1+json")
        return builder
    }

    private fun get(url: String): JsonNode {
        val request = buildRequest(url).get().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("Empty response")
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: $body")
        }
        return mapper.readTree(body)
    }

    private fun post(url: String, payload: String): JsonNode {
        val body = payload.toRequestBody(JSON_MEDIA_TYPE)
        val request = buildRequest(url).post(body).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: $responseBody")
        }
        return mapper.readTree(responseBody)
    }

    private fun delete(url: String): JsonNode {
        val request = buildRequest(url).delete().build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw RuntimeException("Empty response")
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: $body")
        }
        return mapper.readTree(body)
    }

    private fun put(url: String, payload: String): JsonNode {
        val body = payload.toRequestBody(JSON_MEDIA_TYPE)
        val request = buildRequest(url).put(body).build()
        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw RuntimeException("Empty response")
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: $responseBody")
        }
        return mapper.readTree(responseBody)
    }

    fun listSubjects(): List<SchemaSubject> {
        val result = get("/subjects")
        return result.mapNotNull { node ->
            val name = node.asText()
            try {
                val versions = getVersionsList(name)
                SchemaSubject(
                    name = name,
                    versions = versions,
                    latestVersion = versions.lastOrNull() ?: 0
                )
            } catch (e: Exception) {
                logger.warn("Could not get versions for subject $name", e)
                SchemaSubject(name = name)
            }
        }
    }

    fun getVersionsList(subject: String): List<Int> {
        val result = get("/subjects/$subject/versions")
        return result.map { it.asInt() }
    }

    fun getSchemaVersion(subject: String, version: Int): SchemaVersion {
        val result = get("/subjects/$subject/versions/$version")
        return SchemaVersion(
            subject = subject,
            version = result["version"]?.asInt() ?: version,
            schemaId = result["id"]?.asInt() ?: 0,
            schema = result["schema"]?.asText() ?: "",
            schemaType = result["schemaType"]?.asText() ?: "AVRO"
        )
    }

    fun getLatestSchema(subject: String): SchemaVersion {
        val result = get("/subjects/$subject/versions/latest")
        return SchemaVersion(
            subject = subject,
            version = result["version"]?.asInt() ?: 0,
            schemaId = result["id"]?.asInt() ?: 0,
            schema = result["schema"]?.asText() ?: "",
            schemaType = result["schemaType"]?.asText() ?: "AVRO"
        )
    }

    fun registerSchema(subject: String, schema: String, schemaType: String = "AVRO"): Int {
        val payload = buildString {
            append("{\"schema\":")
            append(mapper.writeValueAsString(schema))
            if (schemaType != "AVRO") {
                append(",\"schemaType\":\"$schemaType\"")
            }
            append("}")
        }
        val result = post("/subjects/$subject/versions", payload)
        return result["id"]?.asInt() ?: throw RuntimeException("No schema ID in response")
    }

    fun deleteSubject(subject: String): List<Int> {
        val result = delete("/subjects/$subject")
        return result.map { it.asInt() }
    }

    fun deleteSchemaVersion(subject: String, version: Int) {
        delete("/subjects/$subject/versions/$version")
    }

    fun getCompatibility(subject: String): String {
        return try {
            val result = get("/config/$subject")
            result["compatibilityLevel"]?.asText() ?: "BACKWARD"
        } catch (_: Exception) {
            val result = get("/config")
            result["compatibilityLevel"]?.asText() ?: "BACKWARD"
        }
    }

    fun updateCompatibility(subject: String, compatibility: String) {
        val payload = """{"compatibility":"$compatibility"}"""
        put("/config/$subject", payload)
    }

    fun checkCompatibility(subject: String, schema: String, schemaType: String = "AVRO"): Boolean {
        val payload = buildString {
            append("{\"schema\":")
            append(mapper.writeValueAsString(schema))
            if (schemaType != "AVRO") {
                append(",\"schemaType\":\"$schemaType\"")
            }
            append("}")
        }
        val result = post("/compatibility/subjects/$subject/versions/latest", payload)
        return result["is_compatible"]?.asBoolean() ?: false
    }
}
