package com.freeconductor.ui.consumergroups

import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.ConsumerGroupInfo
import com.freeconductor.model.ConsumerGroupPartitionInfo
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon
import org.apache.kafka.common.TopicPartition

class ConsumerGroupDetailView(
    private val group: ConsumerGroupInfo,
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit
) {
    val root: BorderPane = BorderPane()
    private val partitionItems = FXCollections.observableArrayList<ConsumerGroupPartitionInfo>()
    private val partitionTable = TableView(partitionItems)
    private lateinit var coordinatorValueLbl: Label

    init {
        setupUI()
        loadDetails()
    }

    private fun setupUI() {
        // ── Header ────────────────────────────────────────────────────────
        val title = Label("CONSUMER GROUP ${group.groupId}").apply {
            styleClass.add("view-title")
            style = "-fx-font-size: 20px;"
            isWrapText = true
        }
        val header = HBox(title).apply {
            padding = Insets(12.0, 16.0, 8.0, 16.0)
            alignment = Pos.CENTER_LEFT
        }

        fun infoRow(label: String, value: String) = HBox(6.0).apply {
            padding = Insets(2.0, 16.0, 2.0, 16.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("$label:").apply { style = "-fx-font-weight: bold;" },
                Label(value)
            )
        }

        val infoBox = VBox(2.0).apply {
            padding = Insets(0.0, 0.0, 10.0, 0.0)
            children.addAll(
                infoRow("Coordinator",        "—"),   // updated after load
                infoRow("State",              group.state),
                infoRow("Assigned Topics",    "—"),   // updated after load
                infoRow("Assigned Partitions","—")    // updated after load
            )
        }
        // Keep references to the value labels so we can update them after load
        coordinatorValueLbl     = (infoBox.children[0] as HBox).children[1] as Label
        val stateValueLbl       = (infoBox.children[1] as HBox).children[1] as Label
        val topicsValueLbl      = (infoBox.children[2] as HBox).children[1] as Label
        val partitionsValueLbl  = (infoBox.children[3] as HBox).children[1] as Label

        root.top = VBox(header, infoBox).apply {
            style = "-fx-border-color: -color-border-default; -fx-border-width: 0 0 1 0;"
        }

        // ── Toolbar ───────────────────────────────────────────────────────
        val resetBtn = Button("Reset Offsets").apply {
            setOnAction { resetOffsets() }
        }
        val deleteBtn = Button("Delete Group").apply {
            styleClass.add("danger")
            setOnAction { deleteGroup() }
        }
        val refreshBtn = Button("Refresh", FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 12 }).apply {
            styleClass.add("accent")
            setOnAction { loadDetails() }
        }
        val toolbar = HBox(8.0, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            resetBtn, deleteBtn, refreshBtn).apply {
            padding = Insets(8.0, 12.0, 8.0, 12.0)
            alignment = Pos.CENTER_LEFT
        }

        // ── Partition table ───────────────────────────────────────────────
        val topicCol = TableColumn<ConsumerGroupPartitionInfo, String>("Topic").apply {
            setCellValueFactory { SimpleStringProperty(it.value.topic) }
            prefWidth = 240.0
        }
        val partitionCol = TableColumn<ConsumerGroupPartitionInfo, String>("Partition").apply {
            setCellValueFactory { SimpleStringProperty(it.value.partition.toString()) }
            prefWidth = 80.0
        }
        val offsetCol = TableColumn<ConsumerGroupPartitionInfo, String>("Current Offset").apply {
            setCellValueFactory { SimpleStringProperty(it.value.currentOffset.toString()) }
            prefWidth = 130.0
        }
        val endOffsetCol = TableColumn<ConsumerGroupPartitionInfo, String>("Log End Offset").apply {
            setCellValueFactory { SimpleStringProperty(it.value.logEndOffset.toString()) }
            prefWidth = 130.0
        }
        val lagCol = TableColumn<ConsumerGroupPartitionInfo, String>("Lag").apply {
            setCellValueFactory { SimpleStringProperty(it.value.lag.toString()) }
            prefWidth = 80.0
        }
        val memberCol = TableColumn<ConsumerGroupPartitionInfo, String>("Client ID").apply {
            setCellValueFactory { SimpleStringProperty(it.value.clientId ?: it.value.memberId ?: "") }
            prefWidth = 180.0
        }
        val hostCol = TableColumn<ConsumerGroupPartitionInfo, String>("Host").apply {
            setCellValueFactory { SimpleStringProperty(it.value.host ?: "") }
            prefWidth = 130.0
        }
        partitionTable.columns.addAll(topicCol, partitionCol, offsetCol, endOffsetCol, lagCol, memberCol, hostCol)
        partitionTable.placeholder = Label("Loading…")
        partitionTable.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY

        val tableContainer = BorderPane().apply {
            top = toolbar
            center = partitionTable
        }
        root.center = tableContainer

        // Callback to update info labels once data is loaded
        partitionItems.addListener(javafx.collections.ListChangeListener {
            val topics = partitionItems.map { it.topic }.toSet().size
            val parts  = partitionItems.size
            topicsValueLbl.text     = topics.toString()
            partitionsValueLbl.text = parts.toString()
            stateValueLbl.text      = group.state
        })
    }

    private fun loadDetails() {
        setStatus("Loading details for ${group.groupId}…")
        partitionTable.placeholder = Label("Loading…")
        Thread {
            try {
                val coordinator = adminService.getConsumerGroupCoordinator(group.groupId)
                val details = adminService.describeConsumerGroup(group.groupId)
                Platform.runLater {
                    coordinatorValueLbl.text = coordinator
                    partitionItems.setAll(details)
                    partitionTable.placeholder = Label("No partition data")
                    setStatus("Loaded ${details.size} partitions for ${group.groupId}")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    partitionTable.placeholder = Label("Failed to load: ${e.message}")
                    setStatus("Failed to load group details: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun resetOffsets() {
        val dialog = ResetOffsetsDialog(group.groupId)
        dialog.showAndWait().ifPresent { request ->
            val confirm = Alert(Alert.AlertType.CONFIRMATION).apply {
                title = "Confirm Reset"
                headerText = "Reset offsets for '${group.groupId}'?"
                contentText = "This will change the committed offsets. The consumer group must be stopped."
            }
            if (confirm.showAndWait().orElse(null) != ButtonType.OK) return@ifPresent
            setStatus("Resetting offsets for ${group.groupId}…")
            Thread {
                try {
                    val partitions = adminService.describeConsumerGroup(group.groupId)
                    val filtered = if (request.topic != null) partitions.filter { it.topic == request.topic } else partitions
                    val offsetMap = mutableMapOf<TopicPartition, Long>()
                    when (request.strategy) {
                        com.freeconductor.model.ConsumeFrom.EARLIEST -> filtered.forEach { p ->
                            val tp = TopicPartition(p.topic, p.partition)
                            offsetMap[tp] = adminService.describeTopicPartitions(p.topic).firstOrNull { it.partition == p.partition }?.earliestOffset ?: 0L
                        }
                        com.freeconductor.model.ConsumeFrom.LATEST -> filtered.forEach { p ->
                            offsetMap[TopicPartition(p.topic, p.partition)] = p.logEndOffset
                        }
                        com.freeconductor.model.ConsumeFrom.SPECIFIC_OFFSET -> {
                            val offset = request.specificOffset ?: 0L
                            filtered.forEach { p -> offsetMap[TopicPartition(p.topic, p.partition)] = offset }
                        }
                        else -> {}
                    }
                    adminService.resetConsumerGroupOffsets(group.groupId, offsetMap)
                    Platform.runLater { setStatus("Offsets reset for ${group.groupId}"); loadDetails() }
                } catch (e: Exception) {
                    Platform.runLater {
                        setStatus("Failed to reset offsets: ${e.message}")
                        Alert(Alert.AlertType.ERROR).apply { title = "Reset Failed"; contentText = e.message; showAndWait() }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun deleteGroup() {
        Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "Delete Consumer Group"
            headerText = "Delete consumer group '${group.groupId}'?"
            contentText = "The group must have no active members."
        }.showAndWait().ifPresent {
            if (it != ButtonType.OK) return@ifPresent
            setStatus("Deleting consumer group ${group.groupId}…")
            Thread {
                try {
                    adminService.deleteConsumerGroup(group.groupId)
                    Platform.runLater { setStatus("Deleted consumer group ${group.groupId}") }
                } catch (e: Exception) {
                    Platform.runLater {
                        setStatus("Failed to delete group: ${e.message}")
                        Alert(Alert.AlertType.ERROR).apply { title = "Delete Failed"; contentText = e.message; showAndWait() }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }
}
