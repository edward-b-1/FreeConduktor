package com.freeconductor.ui.topics

import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.PartitionInfo
import com.freeconductor.model.TopicConfig
import com.freeconductor.model.TopicConsumerGroupInfo
import com.freeconductor.model.TopicInfo
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.ui.consume.ConsumerWindow
import com.freeconductor.ui.produce.ProducerDialog
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.fontawesome5.FontAwesomeRegular
import org.kordamp.ikonli.javafx.FontIcon
import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.shape.Rectangle
import javafx.util.Duration

class TopicDetailView(
    private val topic: TopicInfo,
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit,
    private val onBack: () -> Unit = {}
) {
    val root: BorderPane = BorderPane()

    private val partitionItems = FXCollections.observableArrayList<PartitionInfo>()
    private val configItems = FXCollections.observableArrayList<TopicConfig>()
    private val consumerGroupItems = FXCollections.observableArrayList<TopicConsumerGroupInfo>()
    private val progressIndicator = ProgressIndicator()
    private val statsBar = HBox()

    init {
        setupUI()
        loadData()
    }

    /**
     * Builds a clipped Pane containing [text] that animates like a marquee
     * when the text is wider than the available space.
     */
    private fun buildMarqueeTitle(text: String): Region {
        val label = Label(text).apply {
            styleClass.addAll("view-title", "section-title-blue")
            style = "-fx-font-size: 24px;"
        }

        val container = Pane(label).apply {
            HBox.setHgrow(this, Priority.ALWAYS)
            minWidth = 0.0
            // Clip content to container bounds
            val clip = Rectangle()
            clip.widthProperty().bind(widthProperty())
            clip.heightProperty().bind(heightProperty())
            setClip(clip)
        }

        // Vertically centre the label inside the container
        label.layoutYProperty().bind(
            container.heightProperty().subtract(label.heightProperty()).divide(2.0)
        )

        var marquee: Timeline? = null

        fun startMarquee() {
            marquee?.stop()
            marquee = null

            val labelW    = label.prefWidth(-1.0)
            val containerW = container.width
            if (containerW <= 0 || labelW <= containerW) {
                label.translateX = 0.0
                return
            }

            // Only scroll far enough to reveal the right edge of the text
            val scrollDist = labelW - containerW
            val pause = 1.5                        // seconds to hold at each end
            val scrollSecs = scrollDist / 80.0     // 80 px/sec

            // One cycle: pause → scroll → pause → instant reset → repeat
            marquee = Timeline(
                KeyFrame(Duration.ZERO,
                    KeyValue(label.translateXProperty(), 0.0, Interpolator.LINEAR)),
                KeyFrame(Duration.seconds(pause),
                    KeyValue(label.translateXProperty(), 0.0, Interpolator.LINEAR)),
                KeyFrame(Duration.seconds(pause + scrollSecs),
                    KeyValue(label.translateXProperty(), -scrollDist, Interpolator.LINEAR)),
                KeyFrame(Duration.seconds(pause + scrollSecs + pause),
                    KeyValue(label.translateXProperty(), -scrollDist, Interpolator.LINEAR)),
                // Near-instant snap back to start
                KeyFrame(Duration.seconds(pause + scrollSecs + pause + 0.05),
                    KeyValue(label.translateXProperty(), 0.0, Interpolator.DISCRETE))
            ).apply {
                cycleCount = Timeline.INDEFINITE
                play()
            }
        }

        container.widthProperty().addListener { _, old, new -> if (old != new) startMarquee() }
        label.layoutBoundsProperty().addListener { _, _, _ -> startMarquee() }

        return container
    }

    private fun setupUI() {
        val consumeBtn = Button("Consume").apply {
            styleClass.add("accent")
            minWidth = Button.USE_PREF_SIZE
            setOnAction { ConsumerWindow(topic.name, cluster, adminService, setStatus) }
        }
        val produceBtn = Button("Produce").apply {
            minWidth = Button.USE_PREF_SIZE
            styleClass.add("success")
            setOnAction { ProducerDialog(cluster, topic.name, adminService = adminService).show() }
        }
        val deleteBtn = Button("Delete", FontIcon(FontAwesomeRegular.TRASH_ALT).also { it.iconSize = 13 }).apply {
            styleClass.add("danger")
            minWidth = Button.USE_PREF_SIZE
            setOnAction {
                val ok = Alert(Alert.AlertType.CONFIRMATION).also {
                    it.title = "Delete Topic"
                    it.headerText = "Delete '${topic.name}'?"
                    it.contentText = "This is irreversible. All data will be lost."
                }.showAndWait().orElse(ButtonType.CANCEL)
                if (ok == ButtonType.OK) {
                    Thread {
                        try {
                            adminService.deleteTopic(topic.name)
                            Platform.runLater { setStatus("Deleted '${topic.name}'"); onBack() }
                        } catch (e: Exception) {
                            Platform.runLater { Alert(Alert.AlertType.ERROR).apply { contentText = e.message; showAndWait() } }
                        }
                    }.also { it.isDaemon = true }.start()
                }
            }
        }

        val header = HBox(8.0).apply {
            padding = Insets(10.0, 16.0, 10.0, 16.0)
            alignment = Pos.CENTER_LEFT
            styleClass.add("topic-detail-header")
            children.addAll(
                Label("TOPIC").apply {
                    styleClass.add("view-title")
                    style = "-fx-font-size: 24px;"
                    minWidth = Label.USE_PREF_SIZE
                    padding = Insets(0.0, 4.0, 0.0, 12.0)
                },
                buildMarqueeTitle(topic.name),
                consumeBtn, produceBtn, deleteBtn,
                Button("Refresh", FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 12 }).apply { minWidth = Button.USE_PREF_SIZE; setOnAction { loadData() } }
            )
        }

        statsBar.apply {
            styleClass.add("topics-stats-bar")
            padding = Insets(0.0)
            // Placeholder until data loads
            children.add(Label("Loading…").apply {
                styleClass.add("topics-stat-label")
                padding = Insets(10.0, 16.0, 10.0, 16.0)
            })
        }

        val topSection = VBox(header, statsBar)

        val tabPane = TabPane()
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        tabPane.tabs.addAll(
            buildConsumerGroupsTab(),
            buildPartitionsTab(),
            buildConfigTab()
        )

        progressIndicator.isVisible = false
        progressIndicator.maxWidth = 40.0; progressIndicator.maxHeight = 40.0

        val center = StackPane(tabPane, progressIndicator)
        root.top = topSection
        root.center = center
    }

    // ── Stats bar ─────────────────────────────────────────────────────────────

    private fun refreshStatsBar(partitions: List<PartitionInfo>, configs: List<TopicConfig>) {
        val totalMessages = partitions.sumOf { it.messageCount }
        val partitionCount = partitions.size
        val noLeaderCount = partitions.count { it.leader < 0 }
        val rf = partitions.firstOrNull()?.replicas?.size ?: 0
        val minIsrConfig = configs.find { it.name == "min.insync.replicas" }?.value?.toIntOrNull() ?: 1
        val underMinIsr = partitions.count { it.isr.size < minIsrConfig }
        val cleanupPolicy = configs.find { it.name == "cleanup.policy" }?.value ?: "delete"
        val isCompacted = cleanupPolicy.contains("compact")

        // Partition leader status
        val partitionLabel = if (noLeaderCount == 0) "All have a leader" else "$noLeaderCount without leader"
        val partitionAlert = noLeaderCount > 0

        // ISR status
        val urpCount = partitions.count { it.isr.size < it.replicas.size }
        val isrLabel = if (urpCount == 0) "All ISRs at $rf" else "$urpCount under-replicated"
        val isrAlert = urpCount > 0

        // Min ISR status
        val minIsrLabel = if (underMinIsr == 0) "All ISRs ≥ $minIsrConfig" else "$underMinIsr below min ISR"
        val minIsrAlert = underMinIsr > 0

        val cells = listOf(
            statCell("Count",              formatCount(totalMessages), null,       false),
            statCell("Partitions",         partitionCount.toString(),  partitionLabel, partitionAlert),
            statCell("Replication Factor", rf.toString(),              isrLabel,   isrAlert),
            statCell("Min ISR",            minIsrConfig.toString(),    minIsrLabel, minIsrAlert),
            statCell("Compacted",          if (isCompacted) "Yes" else "No", null, false)
        )

        statsBar.children.setAll(cells)
    }

    private fun statCell(title: String, value: String, subtitle: String?, alert: Boolean): VBox {
        val valueLabel = Label(value).apply {
            styleClass.addAll("topics-stat-value",
                if (alert) "topics-stat-alert" else "topics-stat-ok")
        }
        val titleLabel = Label(title).apply { styleClass.add("topics-stat-label") }
        val box = VBox(2.0).apply {
            styleClass.add("topics-stat-box")
            alignment = Pos.CENTER
            HBox.setHgrow(this, Priority.ALWAYS)
            maxWidth = Double.MAX_VALUE
            children.addAll(valueLabel, titleLabel)
            if (subtitle != null) {
                children.add(Label(subtitle).apply {
                    styleClass.add("topics-stat-label")
                    style = if (alert) "-fx-text-fill: -color-danger-fg;" else ""
                })
            }
        }
        return box
    }

    private fun formatCount(n: Long): String = when {
        n >= 1_000_000_000L -> "%.1fB".format(n / 1_000_000_000.0)
        n >= 1_000_000L     -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000L         -> "%.1fK".format(n / 1_000.0)
        else                -> n.toString()
    }

    // ── Consumer Groups tab ───────────────────────────────────────────────────

    private fun buildConsumerGroupsTab(): Tab {
        val table = TableView(consumerGroupItems)
        val groupCol = TableColumn<TopicConsumerGroupInfo, String>("Group ID").apply {
            setCellValueFactory { SimpleStringProperty(it.value.groupId) }
            prefWidth = 400.0
        }
        val stateCol = TableColumn<TopicConsumerGroupInfo, String>("State").apply {
            setCellValueFactory { SimpleStringProperty(it.value.state) }
            prefWidth = 120.0
        }
        val lagCol = TableColumn<TopicConsumerGroupInfo, String>("Lag").apply {
            setCellValueFactory { SimpleStringProperty(it.value.lag.toString()) }
            prefWidth = 120.0
        }
        table.columns.addAll(groupCol, stateCol, lagCol)
        table.placeholder = Label("No consumer groups")
        VBox.setVgrow(table, Priority.ALWAYS)
        return Tab("Consumer Groups", table)
    }

    // ── Partitions tab ────────────────────────────────────────────────────────

    private fun buildPartitionsTab(): Tab {
        val partitionTable = TableView(partitionItems)
        val partCol = TableColumn<PartitionInfo, String>("Partition").apply {
            setCellValueFactory { SimpleStringProperty(it.value.partition.toString()) }
            prefWidth = 80.0
        }
        val leaderCol = TableColumn<PartitionInfo, String>("Leader").apply {
            setCellValueFactory { SimpleStringProperty(it.value.leader.toString()) }
            prefWidth = 80.0
        }
        val replicasCol = TableColumn<PartitionInfo, String>("Replicas").apply {
            setCellValueFactory { SimpleStringProperty(it.value.replicas.joinToString(", ")) }
            prefWidth = 120.0
        }
        val isrCol = TableColumn<PartitionInfo, String>("ISR").apply {
            setCellValueFactory { SimpleStringProperty(it.value.isr.joinToString(", ")) }
            prefWidth = 120.0
        }
        val earliestCol = TableColumn<PartitionInfo, String>("Earliest Offset").apply {
            setCellValueFactory { SimpleStringProperty(it.value.earliestOffset.toString()) }
            prefWidth = 120.0
        }
        val latestCol = TableColumn<PartitionInfo, String>("Latest Offset").apply {
            setCellValueFactory { SimpleStringProperty(it.value.latestOffset.toString()) }
            prefWidth = 120.0
        }
        val msgCountCol = TableColumn<PartitionInfo, String>("Message Count").apply {
            setCellValueFactory { SimpleStringProperty(it.value.messageCount.toString()) }
            prefWidth = 120.0
        }
        val sizeCol = TableColumn<PartitionInfo, String>("Size").apply {
            setCellValueFactory { SimpleStringProperty(formatBytes(it.value.logSize)) }
            prefWidth = 100.0
        }

        partitionTable.columns.addAll(partCol, leaderCol, replicasCol, isrCol, earliestCol, latestCol, msgCountCol, sizeCol)
        partitionTable.placeholder = Label("No partitions")
        VBox.setVgrow(partitionTable, Priority.ALWAYS)

        return Tab("Partitions", partitionTable)
    }

    // ── Config tab ────────────────────────────────────────────────────────────

    private fun buildConfigTab(): Tab {
        val configTable = TableView(configItems)

        val nameCol = TableColumn<TopicConfig, String>("Name").apply {
            setCellValueFactory { SimpleStringProperty(it.value.name) }
            prefWidth = 280.0
            setCellFactory {
                object : TableCell<TopicConfig, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; text = null; return }
                        val entry = tableView.items[index]
                        val sourceDesc = when (entry.overrideSource) {
                            "TOPIC"   -> "Topic — override set specifically for this topic"
                            "BROKER"  -> "Broker — inherited from this broker's dynamic config"
                            "CLUSTER" -> "Cluster — inherited from cluster-wide dynamic default"
                            "STATIC"  -> "Static — inherited from server.properties"
                            else      -> "Default (Kafka built-in)"
                        }
                        text = null
                        graphic = Label(item).apply {
                            styleClass.add("broker-config-name")
                            tooltip = Tooltip("$item\nSource: $sourceDesc")
                        }
                    }
                }
            }
        }

        val valueCol = TableColumn<TopicConfig, String>("Value").apply {
            setCellValueFactory { SimpleStringProperty(if (it.value.isSensitive) "***" else it.value.value ?: "") }
            prefWidth = 300.0
            setCellFactory {
                object : TableCell<TopicConfig, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; text = null; return }
                        val entry = tableView.items[index]
                        val badge = when (entry.overrideSource) {
                            "TOPIC"   -> Label("T").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-topic")
                                tooltip = Tooltip("Topic — override set specifically for this topic")
                            }
                            "BROKER"  -> Label("B").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-broker")
                                tooltip = Tooltip("Broker — inherited from this broker's dynamic config")
                            }
                            "CLUSTER" -> Label("C").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-cluster")
                                tooltip = Tooltip("Cluster — inherited from cluster-wide dynamic default")
                            }
                            "STATIC"  -> Label("S").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-static")
                                tooltip = Tooltip("Static — inherited from server.properties")
                            }
                            else -> null
                        }
                        val lbl = Label(item).apply {
                            if (item.isEmpty()) style = "-fx-text-fill: -color-fg-muted;"
                            tooltip = Tooltip(item)
                        }
                        graphic = if (badge != null)
                            HBox(6.0, badge, lbl).apply { alignment = Pos.CENTER_LEFT }
                        else lbl
                        text = null
                    }
                }
            }
        }

        val defaultCol = TableColumn<TopicConfig, String>("Default").apply {
            setCellValueFactory { SimpleStringProperty(if (it.value.isDefault) "Yes" else "No") }
            prefWidth = 80.0
        }

        val defaultValueCol = TableColumn<TopicConfig, String>("Default Value").apply {
            setCellValueFactory { SimpleStringProperty(it.value.defaultValue ?: "") }
            prefWidth = 250.0
        }

        configTable.columns.addAll(nameCol, valueCol, defaultCol, defaultValueCol)
        configTable.placeholder = Label("No configuration")
        VBox.setVgrow(configTable, Priority.ALWAYS)

        return Tab("Configuration", configTable)
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadData() {
        progressIndicator.isVisible = true
        setStatus("Loading topic details for ${topic.name}...")

        Thread {
            try {
                val partitions = adminService.describeTopicPartitions(topic.name)
                val configs = adminService.getTopicConfigs(topic.name)
                val consumerGroups = adminService.getTopicConsumerGroups(topic.name)
                Platform.runLater {
                    partitionItems.setAll(partitions)
                    configItems.setAll(configs)
                    consumerGroupItems.setAll(consumerGroups)
                    refreshStatsBar(partitions, configs)
                    progressIndicator.isVisible = false
                    setStatus("Loaded details for ${topic.name}")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to load topic details: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024L         -> "%.1f kB".format(bytes / 1_024.0)
            else                    -> "$bytes B"
        }
    }
}
