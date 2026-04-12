package com.freeconductor.ui

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.*
import javafx.scene.layout.*

class OverviewView(
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit,
    private val onNavigate: (String) -> Unit
) {
    val root: BorderPane = BorderPane()

    init {
        val header = HBox().apply {
            padding = Insets(16.0, 16.0, 4.0, 16.0)
            children.add(Label(cluster.name).apply { styleClass.add("view-title") })
        }
        val progress = ProgressIndicator().apply { maxWidth = 40.0; maxHeight = 40.0 }
        root.top = header
        root.center = StackPane(Label("Loading overview…"), progress)
        loadData()
    }

    private fun loadData() {
        setStatus("Loading cluster overview…")
        Thread {
            try {
                val stats = adminService.getClusterStats()
                Platform.runLater {
                    root.center = buildDashboard(stats)
                    setStatus("Connected to ${cluster.name}")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    root.center = Label("Failed to load overview: ${e.message}")
                    setStatus("Error: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun buildDashboard(s: KafkaAdminService.ClusterStats): ScrollPane {
        val content = VBox(12.0).apply { padding = Insets(8.0, 16.0, 16.0, 16.0) }

        content.children.addAll(
            buildSection("BROKERS", listOf(
                Stat(s.brokerCount.toString(), "Brokers"),
                Stat(if (s.controllerId >= 0) "Yes" else "-", "Controller"),
                Stat(s.clusterId.take(12), "Cluster ID"),
                Stat(s.defaultReplicationFactor.toString(), "Default RF"),
                Stat("n/a", "Version"),
                Stat("n/a", "Similar Config")
            ), "brokers"),

            buildSection("TOPICS", listOf(
                Stat(s.topicCount.toString(), "Topics"),
                Stat(s.partitionCount.toString(), "Partitions"),
                Stat(s.urpCount.toString(),         "URP",        alert = s.urpCount > 0),
                Stat(s.noLeaderCount.toString(),     "No Leader",  alert = s.noLeaderCount > 0),
                Stat(s.underMinIsrCount.toString(),  "< Min ISR",  alert = s.underMinIsrCount > 0)
            ), "topics"),

            buildSection("CONSUMERS", listOf(
                Stat(s.activeGroups.toString(),      "Active"),
                Stat(s.emptyGroups.toString(),       "Empty"),
                Stat(s.rebalancingGroups.toString(), "Rebalancing", alert = s.rebalancingGroups > 0),
                Stat(s.deadGroups.toString(),        "Dead",        alert = s.deadGroups > 0)
            ), "consumergroups"),

            buildSection("KAFKA STREAMS", listOf(
                Stat(s.streamsAppCount.toString(), "Apps"),
                Stat("n/a", "Sources"),
                Stat("n/a", "Sinks"),
                Stat("n/a", "Internals"),
                Stat("n/a", "Stores")
            ), "streams"),

            buildSection("SECURITY", listOf(
                Stat(if (s.aclsEnabled) "Yes" else "No", "ACLs Enabled"),
                Stat(s.aclCount.toString(), "ACLs"),
                Stat(s.aclUserCount.toString(), "Users"),
                Stat(s.aclTopicCount.toString(), "Topics"),
                Stat(s.aclGroupCount.toString(), "Consumer Groups")
            ), "security")
        )

        return ScrollPane(content).apply {
            isFitToWidth = true
            styleClass.add("edge-to-edge")
        }
    }

    private data class Stat(val value: String, val label: String, val alert: Boolean = false)

    private fun buildSection(title: String, stats: List<Stat>, navTarget: String): VBox {
        val titleLabel = Label(title).apply {
            styleClass.add("overview-section-title")
            cursor = Cursor.HAND
            setOnMouseClicked { onNavigate(navTarget) }
        }
        val row = HBox(1.0).apply {
            stats.forEach { stat ->
                val card = VBox(2.0).apply {
                    alignment = Pos.CENTER
                    padding = Insets(14.0, 20.0, 14.0, 20.0)
                    styleClass.add("stat-card")
                    children.addAll(
                        Label(stat.value).apply {
                            styleClass.add(if (stat.alert) "stat-value-alert" else "stat-value")
                        },
                        Label(stat.label).apply { styleClass.add("stat-label") }
                    )
                }
                HBox.setHgrow(card, Priority.ALWAYS)
                children.add(card)
            }
        }
        return VBox(6.0, titleLabel, row).apply { styleClass.add("overview-section") }
    }
}
