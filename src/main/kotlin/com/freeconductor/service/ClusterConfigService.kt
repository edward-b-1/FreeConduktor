package com.freeconductor.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.freeconductor.model.ClusterConfig
import org.slf4j.LoggerFactory
import java.io.File

class ClusterConfigService {
    private val logger = LoggerFactory.getLogger(ClusterConfigService::class.java)
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val configDir = File(System.getProperty("user.home"), ".freeconductor")
    private val configFile = File(configDir, "clusters.json")

    init {
        configDir.mkdirs()
    }

    fun loadClusters(): MutableList<ClusterConfig> {
        return try {
            if (configFile.exists()) {
                mapper.readValue<MutableList<ClusterConfig>>(configFile)
            } else {
                mutableListOf()
            }
        } catch (e: Exception) {
            logger.error("Failed to load cluster configs", e)
            mutableListOf()
        }
    }

    fun saveClusters(clusters: List<ClusterConfig>) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(configFile, clusters)
        } catch (e: Exception) {
            logger.error("Failed to save cluster configs", e)
            throw e
        }
    }

    fun addCluster(cluster: ClusterConfig) {
        val clusters = loadClusters()
        clusters.add(cluster)
        saveClusters(clusters)
    }

    fun updateCluster(cluster: ClusterConfig) {
        val clusters = loadClusters()
        val index = clusters.indexOfFirst { it.id == cluster.id }
        if (index >= 0) {
            clusters[index] = cluster
            saveClusters(clusters)
        }
    }

    fun deleteCluster(clusterId: String) {
        val clusters = loadClusters()
        clusters.removeIf { it.id == clusterId }
        saveClusters(clusters)
    }
}
