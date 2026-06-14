package com.freeconductor.ui.topics

import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.TopicInfo
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.ui.consume.ConsumerWindow
import com.freeconductor.ui.produce.ProducerDialog
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.fontawesome5.FontAwesomeRegular
import org.kordamp.ikonli.javafx.FontIcon

class TopicsView(
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit,
    private val onTopicSelected: (TopicInfo) -> Unit = {}
) {
    val root: BorderPane = BorderPane()
    private val topicItems = FXCollections.observableArrayList<TopicInfo>()
    private val filteredTopics = FilteredList(topicItems)
    private val topicTable = TableView(filteredTopics)
    private val progressIndicator = ProgressIndicator()
    private var showInternalTopics = false
    private var brokerCount = 1

    // Stat box labels
    private val statTopicsValue     = Label("—")
    private val statPartitionsValue = Label("—")
    private val statUrpValue        = Label("—")
    private val statNoLeaderValue   = Label("—")
    private val statMinIsrValue     = Label("—")

    init { setupUI() }

    private fun setupUI() {
        setupTable()
        progressIndicator.apply { isVisible = false; maxWidth = 40.0; maxHeight = 40.0 }
        root.top = buildToolbar()
        root.center = StackPane(topicTable, progressIndicator).apply {
            padding = Insets(10.0, 0.0, 0.0, 0.0)
        }
        VBox.setVgrow(topicTable, Priority.ALWAYS)
    }

    // ── Toolbar ───────────────────────────────────────────────────────────

    private fun buildToolbar(): VBox {
        val searchField = TextField().apply {
            promptText = "Filter topics…"
            prefWidth = 200.0
        }
        searchField.textProperty().addListener { _, _, v ->
            filteredTopics.setPredicate { v.isBlank() || it.name.contains(v, ignoreCase = true) }
        }
        val showInternalCheck = CheckBox("Show internal").apply {
            setOnAction { showInternalTopics = isSelected; refresh() }
        }
        val createButton = Button("CREATE", FontIcon(FontAwesomeSolid.PLUS).also { it.iconSize = 12 }).apply {
            styleClass.addAll("accent", "toolbar-action-btn")
            setOnAction { showCreateTopicDialog() }
        }
        val deleteButton = Button("Delete", FontIcon(FontAwesomeRegular.TRASH_ALT).also { it.iconSize = 13 }).apply {
            styleClass.add("danger")
            setOnAction { deleteSelectedTopic() }
        }
        val refreshButton = Button("Refresh", FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 12 }).apply { setOnAction { refresh() } }

        // Title row
        val titleRow = HBox(8.0).apply {
            padding = Insets(10.0, 16.0, 8.0, 16.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("TOPICS").apply { styleClass.add("view-title") },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                createButton
            )
        }

        // Stats bar
        listOf(statTopicsValue, statPartitionsValue).forEach { it.styleClass.add("topics-stat-value") }
        listOf(statUrpValue, statNoLeaderValue, statMinIsrValue).forEach {
            it.styleClass.addAll("topics-stat-value", "topics-stat-ok")
        }
        val statsBar = HBox().apply {
            styleClass.add("topics-stats-bar")
            children.addAll(
                buildStatBox(statTopicsValue,     "Topics"),
                buildStatBox(statPartitionsValue, "Partitions"),
                buildStatBox(statUrpValue,        "URP"),
                buildStatBox(statNoLeaderValue,   "No Leader"),
                buildStatBox(statMinIsrValue,     "< Min ISR")
            )
        }

        // Filter / action row
        val actionRow = HBox(8.0).apply {
            padding = Insets(16.0, 16.0, 6.0, 16.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(
                searchField, showInternalCheck,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                deleteButton, refreshButton
            )
        }

        topicItems.addListener(javafx.collections.ListChangeListener { updateStats() })
        return VBox(titleRow, statsBar, actionRow)
    }

    private fun buildStatBox(valueLabel: Label, description: String) =
        VBox(2.0, valueLabel, Label(description).apply { styleClass.add("topics-stat-label") }).apply {
            styleClass.add("topics-stat-box")
            alignment = Pos.CENTER
            HBox.setHgrow(this, Priority.ALWAYS)
            maxWidth = Double.MAX_VALUE
        }

    private fun updateStats() {
        val topics   = topicItems.toList()
        val urp      = topics.sumOf { it.urpCount }
        val noLeader = topics.sumOf { it.noLeaderCount }
        statTopicsValue.text     = topics.size.toString()
        statPartitionsValue.text = topics.sumOf { it.partitionCount }.toString()
        statUrpValue.text        = urp.toString()
        statNoLeaderValue.text   = noLeader.toString()
        statMinIsrValue.text     = "0"
        fun setAlert(lbl: Label, alert: Boolean) {
            lbl.styleClass.removeAll("topics-stat-ok", "topics-stat-alert")
            lbl.styleClass.add(if (alert) "topics-stat-alert" else "topics-stat-ok")
        }
        setAlert(statUrpValue,      urp > 0)
        setAlert(statNoLeaderValue, noLeader > 0)
    }

    // ── Table ─────────────────────────────────────────────────────────────

    private fun setupTable() {
        // Consume icon column (leftmost)
        val consumeCol = TableColumn<TopicInfo, TopicInfo>("").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            setCellFactory {
                object : TableCell<TopicInfo, TopicInfo>() {
                    private val btn = Button(null, FontIcon(FontAwesomeSolid.SEARCH).also { it.iconSize = 12 }).apply { styleClass.addAll("row-icon-btn", "row-consume-btn") }
                    override fun updateItem(item: TopicInfo?, empty: Boolean) {
                        super.updateItem(item, empty); graphic = if (empty || item == null) null
                        else btn.also { it.setOnAction { _ -> ConsumerWindow(item.name, cluster, adminService, setStatus) } }
                    }
                }
            }
            prefWidth = 36.0; minWidth = 36.0; maxWidth = 36.0; isResizable = false
            style = "-fx-alignment: CENTER;"
        }

        // Topic name as Hyperlink
        val nameCol = TableColumn<TopicInfo, TopicInfo>("Topic Name").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            setCellFactory {
                object : TableCell<TopicInfo, TopicInfo>() {
                    private val link = Hyperlink().apply { styleClass.add("topic-name-link") }
                    override fun updateItem(item: TopicInfo?, empty: Boolean) {
                        super.updateItem(item, empty); graphic = if (empty || item == null) null
                        else link.also { it.text = item.name; it.setOnAction { _ -> onTopicSelected(item) } }
                    }
                }
            }
            prefWidth = 340.0
        }

        val rfCol = TableColumn<TopicInfo, String>("RF").apply {
            setCellValueFactory { SimpleStringProperty(it.value.replicationFactor.toString()) }
            prefWidth = 40.0; style = "-fx-alignment: CENTER;"
        }
        val partCol = TableColumn<TopicInfo, String>("Partitions").apply {
            setCellValueFactory { SimpleStringProperty(it.value.partitionCount.toString()) }
            prefWidth = 80.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val countCol = TableColumn<TopicInfo, String>("Count").apply {
            setCellValueFactory {
                val c = it.value.messageCount
                SimpleStringProperty(if (c < 0) "—" else "%,d".format(c))
            }
            prefWidth = 100.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val sizeCol = TableColumn<TopicInfo, String>("Size").apply {
            setCellValueFactory { SimpleStringProperty(formatSize(it.value.logSize)) }
            prefWidth = 90.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val consumersCol = TableColumn<TopicInfo, String>("Consumers").apply {
            setCellValueFactory {
                val c = it.value.consumerCount
                SimpleStringProperty(if (c < 0) "—" else c.toString())
            }
            prefWidth = 80.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val lastWriteCol = TableColumn<TopicInfo, TopicInfo>("Last Write").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            setCellFactory {
                object : TableCell<TopicInfo, TopicInfo>() {
                    override fun updateItem(item: TopicInfo?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; style = ""; return }
                        val ts = item.lastWriteTime
                        if (ts == null) { text = "—"; style = "-fx-alignment: CENTER-RIGHT;"; return }
                        val ageMs = System.currentTimeMillis() - ts
                        val (label, color) = when {
                            ageMs <  60_000L              -> "now"  to "-color-danger-fg"
                            ageMs <  3_600_000L           -> "${ageMs / 60_000L}m ago" to null
                            ageMs <  86_400_000L          -> "${ageMs / 3_600_000L}h ago" to null
                            else                          -> "${ageMs / 86_400_000L}d ago" to null
                        }
                        text = label
                        style = "-fx-alignment: CENTER-RIGHT;" +
                                if (color != null) " -fx-text-fill: $color;" else ""
                    }
                }
            }
            prefWidth = 90.0
        }
        val spreadCol = TableColumn<TopicInfo, String>("Spread").apply {
            setCellValueFactory {
                val s = it.value.spread
                SimpleStringProperty(if (s == null) "—" else "$s%")
            }
            prefWidth = 65.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val internalCol = TableColumn<TopicInfo, String>("Internal").apply {
            setCellValueFactory { SimpleStringProperty(if (it.value.isInternal) "Yes" else "") }
            prefWidth = 70.0; style = "-fx-alignment: CENTER;"
        }

        // Gear / settings icon column (rightmost)
        val gearCol = TableColumn<TopicInfo, TopicInfo>("").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            setCellFactory {
                object : TableCell<TopicInfo, TopicInfo>() {
                    private val btn = Button(null, FontIcon(FontAwesomeSolid.COG).also { it.iconSize = 12 }).apply { styleClass.add("row-icon-btn") }
                    override fun updateItem(item: TopicInfo?, empty: Boolean) {
                        super.updateItem(item, empty); graphic = if (empty || item == null) null
                        else btn.also { b ->
                            b.setOnAction { _ ->
                                val menu = ContextMenu(
                                    MenuItem("View Details").apply { setOnAction { onTopicSelected(item) } },
                                    SeparatorMenuItem(),
                                    MenuItem("Consume").apply { setOnAction { ConsumerWindow(item.name, cluster, adminService, setStatus) } },
                                    MenuItem("Produce").apply { setOnAction { ProducerDialog(cluster, item.name, adminService = adminService).show() } },
                                    SeparatorMenuItem(),
                                    MenuItem("Delete…").apply {
                                        setOnAction {
                                            val ok = Alert(Alert.AlertType.CONFIRMATION).also {
                                                it.title = "Delete Topic"
                                                it.headerText = "Delete '${item.name}'?"
                                                it.contentText = "This is irreversible."
                                            }.showAndWait().orElse(ButtonType.CANCEL)
                                            if (ok == ButtonType.OK) {
                                                Thread {
                                                    try { adminService.deleteTopic(item.name); Platform.runLater { setStatus("Deleted '${item.name}'"); refresh() } }
                                                    catch (e: Exception) { Platform.runLater { Alert(Alert.AlertType.ERROR).also { a -> a.contentText = e.message }.showAndWait() } }
                                                }.also { it.isDaemon = true }.start()
                                            }
                                        }
                                    }
                                )
                                menu.show(b, javafx.geometry.Side.BOTTOM, 0.0, 4.0)
                            }
                        }
                    }
                }
            }
            prefWidth = 36.0; minWidth = 36.0; maxWidth = 36.0; isResizable = false
            style = "-fx-alignment: CENTER;"
        }

        topicTable.columns.addAll(consumeCol, nameCol, rfCol, partCol, countCol, sizeCol, consumersCol, lastWriteCol, spreadCol, internalCol, gearCol)
        topicTable.placeholder = Label("No topics found")
        topicTable.selectionModel.selectionMode = SelectionMode.SINGLE
        topicTable.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        VBox.setVgrow(topicTable, Priority.ALWAYS)

        // Double-click row still works as fallback
        topicTable.setOnMouseClicked { e ->
            if (e.clickCount == 2) topicTable.selectionModel.selectedItem?.let { onTopicSelected(it) }
        }
    }

    // ── Create / Delete dialogs ───────────────────────────────────────────

    private fun showCreateTopicDialog() {
        val dialog = CreateTopicDialog(brokerCount, adminService)
        dialog.showAndWait().ifPresent { request ->
            if (request.name.isBlank()) {
                Alert(Alert.AlertType.WARNING).apply { title = "Validation"; contentText = "Topic name cannot be empty."; showAndWait() }
                return@ifPresent
            }
            setStatus("Creating topic '${request.name}'…")
            Thread {
                try {
                    adminService.createTopic(request.name, request.partitions, request.replicationFactor, request.configs)
                    Platform.runLater { setStatus("Topic '${request.name}' created"); refresh() }
                } catch (e: Exception) {
                    Platform.runLater {
                        setStatus("Failed to create topic: ${e.message}")
                        Alert(Alert.AlertType.ERROR).apply { title = "Create Failed"; contentText = e.message; showAndWait() }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun deleteSelectedTopic() {
        val selected = topicTable.selectionModel.selectedItem ?: run {
            Alert(Alert.AlertType.WARNING).apply { title = "No Selection"; contentText = "Select a topic to delete."; showAndWait() }
            return
        }
        val confirm = Alert(Alert.AlertType.CONFIRMATION)
        confirm.title = "Delete Topic"
        confirm.headerText = "Delete '${selected.name}'?"
        confirm.contentText = "This is irreversible. All data will be lost."
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return
        Thread {
            try {
                adminService.deleteTopic(selected.name)
                Platform.runLater { setStatus("Deleted '${selected.name}'"); refresh() }
            } catch (e: Exception) {
                Platform.runLater { Alert(Alert.AlertType.ERROR).apply { title = "Delete Failed"; contentText = e.message; showAndWait() } }
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    fun refresh() {
        progressIndicator.isVisible = true
        setStatus("Loading topics…")
        Thread {
            try {
                val topics = adminService.listTopics().filter { showInternalTopics || !it.isInternal }
                brokerCount = adminService.getBrokerCount()
                Platform.runLater {
                    topicItems.setAll(topics)
                    progressIndicator.isVisible = false
                    setStatus("Loaded ${topics.size} topics")
                }

                val nonInternal = topics.filter { !it.isInternal }.map { it.name }
                if (nonInternal.isEmpty()) return@Thread

                // Message counts
                val counts = adminService.getTopicMessageCounts(nonInternal)
                Platform.runLater {
                    topicItems.setAll(topics.map { t -> if (counts.containsKey(t.name)) t.copy(messageCount = counts[t.name]!!) else t })
                }

                // Log sizes
                try {
                    val sizes = adminService.getTopicLogSizes(nonInternal)
                    Platform.runLater {
                        topicItems.setAll(topicItems.map { t -> if (sizes.containsKey(t.name)) t.copy(logSize = sizes[t.name]!!) else t })
                    }
                } catch (_: Exception) { /* log sizes optional */ }

                // Consumer counts
                try {
                    val consumers = adminService.getTopicConsumerCounts(nonInternal)
                    Platform.runLater {
                        topicItems.setAll(topicItems.map { t -> t.copy(consumerCount = consumers[t.name] ?: 0) })
                    }
                } catch (_: Exception) { /* consumer counts optional */ }

                // Last write times
                try {
                    val lastWrites = adminService.getTopicLastWriteTimes(nonInternal)
                    Platform.runLater {
                        topicItems.setAll(topicItems.map { t ->
                            if (lastWrites.containsKey(t.name)) t.copy(lastWriteTime = lastWrites[t.name]) else t
                        })
                    }
                } catch (_: Exception) { /* last write times optional */ }

            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to load topics: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 0    -> "—"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / 1048576.0)} MB"
            else -> "${"%.1f".format(bytes / 1073741824.0)} GB"
        }
    }
}
