package com.freeconductor.ui.produce

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.service.KafkaProducerService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.layout.*
import javafx.scene.input.MouseEvent
import javafx.stage.Stage
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ProducerDialog(
    private val cluster: ClusterConfig,
    private val initialTopic: String? = null,
    private val initialKey: String? = null,
    private val initialValue: String? = null,
    private val initialHeaders: Map<String, String> = emptyMap(),
    private val adminService: KafkaAdminService? = null
) {
    private val stage = Stage()

    private val formats = listOf("String", "JSON", "Int", "Long", "Float", "Double", "Bytes (Base64)")

    private val topicCombo = ComboBox<String>().apply {
        isEditable = true
        maxWidth = Double.MAX_VALUE
        promptText = "topic-name"
        if (!initialTopic.isNullOrBlank()) value = initialTopic
    }
    private val partitionField = TextField().apply { promptText = "Auto" }

    private val keyFormatBox   = ComboBox<String>().apply { items.addAll(formats); value = "String" }
    private val valueFormatBox = ComboBox<String>().apply { items.addAll(formats); value = "String" }

    private val keyArea = TextArea(initialKey ?: "").apply {
        promptText = "Message key (optional)"
        prefHeight = 90.0; maxHeight = 90.0
        isWrapText = true; styleClass.add("code-area")
    }
    private val valueArea = TextArea(initialValue ?: "").apply {
        promptText = "Message value"
        isWrapText = true; styleClass.add("code-area")
        VBox.setVgrow(this, Priority.ALWAYS)
    }

    private val headersItems = FXCollections.observableArrayList<Pair<String, String>>()
    private val headersTable = TableView(headersItems)

    // ── Flow mode state ───────────────────────────────────────────────────────

    private val modeGroup    = ToggleGroup()
    private val manualBtn    = ToggleButton("manual").apply { toggleGroup = modeGroup; isSelected = true }
    private val timedBtn     = ToggleButton("timed").apply  { toggleGroup = modeGroup }
    private val chunkSpinner = Spinner<Int>(1, 10_000, 1).apply { isEditable = true; prefWidth = 100.0 }
    private val intervalSpinner = Spinner<Int>(0, 3_600_000, 1000).apply { isEditable = true; prefWidth = 110.0 }
    private val jitterSpinner   = Spinner<Int>(0, 3_600_000, 0).apply    { isEditable = true; prefWidth = 110.0 }
    private val elapsedSpinner  = Spinner<Int>(0, Int.MAX_VALUE, 0).apply { isEditable = true; prefWidth = 110.0 }
    private val msgCountSpinner = Spinner<Int>(0, Int.MAX_VALUE, 0).apply { isEditable = true; prefWidth = 110.0 }

    @Volatile private var flowRunning = false

    // ── Output panel model ────────────────────────────────────────────────────

    private sealed class OutputEntry {
        data class Success(
            val timestamp: LocalDateTime,
            val latencyMs: Long,
            val topic: String,
            val partition: Int,
            val offset: Long,
            val key: String?,
            val value: String
        ) : OutputEntry()
        data class Failure(val timestamp: LocalDateTime, val message: String) : OutputEntry()
        data class Detail(val label: String, val text: String) : OutputEntry()
    }

    private val outputRoot = TreeItem<OutputEntry>()
    private val outputTree = TreeView<OutputEntry>(outputRoot).apply {
        isShowRoot = false
        VBox.setVgrow(this, Priority.ALWAYS)
        setCellFactory { OutputEntryCell() }
    }

    private inner class OutputEntryCell : TreeCell<OutputEntry>() {
        private val tsFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        // ── Success node tree (built once, reused) ────────────────────────────
        private val tsLabel      = Label()
        private val latencyLabel = Label()
        private val clockIcon    = FontIcon(FontAwesomeSolid.CLOCK).apply { iconSize = 13 }
        private val topicLabel   = Label()
        private val offsetLabel  = Label()
        private val successBox   = VBox(1.0,
            HBox(6.0, tsLabel, clockIcon, latencyLabel).apply { alignment = Pos.CENTER_LEFT },
            topicLabel, offsetLabel
        ).apply { padding = Insets(3.0, 0.0, 3.0, 0.0) }

        // ── Failure node tree ─────────────────────────────────────────────────
        private val tsFailLabel  = Label()
        private val errorLabel   = Label().apply {
            style = "-fx-text-fill: -color-danger-fg;"; isWrapText = true
        }
        private val failureBox = VBox(1.0, tsFailLabel, errorLabel).apply { padding = Insets(3.0, 0.0, 3.0, 0.0) }

        // ── Detail node tree ──────────────────────────────────────────────────
        private val detailKeyLabel   = Label().apply { style = "-fx-min-width: 36px;" }
        private val detailValueLabel = Label()
        private val detailBox = HBox(6.0, detailKeyLabel, detailValueLabel).apply {
            padding = Insets(1.0, 0.0, 1.0, 0.0); alignment = Pos.CENTER_LEFT
        }

        override fun updateItem(item: OutputEntry?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) { graphic = null; text = null; return }
            text = null
            when (item) {
                is OutputEntry.Success -> {
                    tsLabel.text      = item.timestamp.format(tsFormat)
                    latencyLabel.text = "${item.latencyMs}ms"
                    topicLabel.text   = "${item.topic}-${item.partition}"
                    offsetLabel.text  = "Offset: ${item.offset}"
                    graphic = successBox
                }
                is OutputEntry.Failure -> {
                    tsFailLabel.text = item.timestamp.format(tsFormat)
                    errorLabel.text  = item.message
                    graphic = failureBox
                }
                is OutputEntry.Detail -> {
                    detailKeyLabel.text   = "${item.label}:"
                    detailValueLabel.text = item.text
                    graphic = detailBox
                }
            }
        }
    }

    private val sendBtn = Button("Send").apply { styleClass.add("accent") }

    private var producerService: KafkaProducerService? = null

    init {
        initialHeaders.forEach { (k, v) -> headersItems.add(Pair(k, v)) }

        val root = BorderPane().apply {
            center = buildCenter()
            bottom = buildBottomBar()
        }

        val scene = Scene(root, 900.0, 620.0)
        scene.stylesheets.add(
            ProducerDialog::class.java.getResource("/com/freeconductor/styles.css")!!.toExternalForm()
        )

        stage.title = "Produce to Topic — ${cluster.name}"
        ProducerDialog::class.java
            .getResourceAsStream("/com/freeconductor/icons/free-conduktor-logo-32.png")
            ?.let { stage.icons.add(Image(it)) }
        stage.scene = scene
        stage.isResizable = true
        stage.setOnCloseRequest { stopFlow(); producerService?.close() }

        // Prevent deselecting the active toggle button by clicking it again
        manualBtn.addEventFilter(MouseEvent.MOUSE_PRESSED) { if (manualBtn.isSelected) it.consume() }
        timedBtn.addEventFilter(MouseEvent.MOUSE_PRESSED)  { if (timedBtn.isSelected)  it.consume() }

        timedBtn.selectedProperty().addListener { _, _, timed -> updateSendButton(timed, false) }

        sendBtn.setOnAction {
            when {
                timedBtn.isSelected && flowRunning -> stopFlow()
                timedBtn.isSelected               -> startFlow()
                else                              -> sendMessage()
            }
        }

        loadTopics()
    }

    // ── Center ────────────────────────────────────────────────────────────────

    private fun buildCenter(): SplitPane {
        HBox.setHgrow(topicCombo, Priority.ALWAYS)
        val topicRow = HBox(10.0, Label("Topic:"), topicCombo).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(10.0, 10.0, 6.0, 10.0)
        }

        val tabs = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs.addAll(buildDataTab(), buildFlowTab(), buildHeadersTab(), buildOptionsTab())
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val leftPane = VBox(topicRow, tabs).apply {
            VBox.setVgrow(tabs, Priority.ALWAYS)
        }

        val outputTitle = Label("OUTPUT").apply { styleClass.add("config-section-label") }
        val outputPane = VBox(8.0, outputTitle, outputTree).apply {
            padding = Insets(10.0)
            VBox.setVgrow(outputTree, Priority.ALWAYS)
        }

        return SplitPane(leftPane, outputPane).apply {
            setDividerPositions(0.62)
        }
    }

    // ── Data tab ──────────────────────────────────────────────────────────────

    private fun buildDataTab(): Tab {
        val keyGenerateChk = CheckBox("Generate random data").apply {
            isDisable = true; tooltip = Tooltip("Coming soon")
        }
        val valueGenerateChk = CheckBox("Generate random data").apply {
            isDisable = true; tooltip = Tooltip("Coming soon")
        }

        val keyHeader = HBox(8.0, Label("Format:"), keyFormatBox,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, keyGenerateChk).apply {
            alignment = Pos.CENTER_LEFT
        }
        val valueHeader = HBox(8.0, Label("Format:"), valueFormatBox,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, valueGenerateChk).apply {
            alignment = Pos.CENTER_LEFT
        }

        val content = VBox(6.0).apply {
            padding = Insets(10.0)
            VBox.setVgrow(this, Priority.ALWAYS)
            children.addAll(
                Label("KEY").apply { styleClass.add("config-section-label") },
                keyHeader, keyArea,
                Separator(),
                Label("VALUE").apply { styleClass.add("config-section-label") },
                valueHeader, valueArea
            )
        }
        VBox.setVgrow(valueArea, Priority.ALWAYS)

        return Tab("Data", content)
    }

    // ── Flow tab ──────────────────────────────────────────────────────────────

    private fun buildFlowTab(): Tab {
        fun infoLabel(text: String, tip: String): HBox {
            val icon = FontIcon(FontAwesomeSolid.INFO_CIRCLE).apply { iconSize = 12 }
            Tooltip.install(icon, Tooltip(tip))
            return HBox(5.0, Label(text), icon).apply { alignment = Pos.CENTER_LEFT }
        }

        val modeRow = HBox(8.0, manualBtn, timedBtn).apply { alignment = Pos.CENTER_LEFT }

        val topGrid = GridPane().apply {
            hgap = 12.0; vgap = 10.0
            columnConstraints.addAll(
                ColumnConstraints(210.0, 220.0, 240.0),
                ColumnConstraints().also { it.hgrow = Priority.ALWAYS }
            )
            addRow(0, Label("Group messages into chunks of"), chunkSpinner)
            addRow(1, Label("Producer Mode"), modeRow)
        }

        val timerGrid = GridPane().apply {
            hgap = 12.0; vgap = 8.0
            columnConstraints.addAll(
                ColumnConstraints(160.0, 180.0, 200.0),
                ColumnConstraints().also { it.hgrow = Priority.ALWAYS }
            )
            addRow(0, infoLabel("Interval (ms)", "Delay between each message send"), intervalSpinner)
            addRow(1, infoLabel("Max jitter (ms)", "Random additional delay added to each interval"), jitterSpinner)
        }

        val lifecycleGrid = GridPane().apply {
            hgap = 12.0; vgap = 8.0
            columnConstraints.addAll(
                ColumnConstraints(160.0, 200.0, 220.0),
                ColumnConstraints().also { it.hgrow = Priority.ALWAYS }
            )
            addRow(0, Label("Elapsed time (ms)"), elapsedSpinner)
            addRow(1, Label("Number of message produced"), msgCountSpinner)
        }

        val timedPane = VBox(10.0).apply {
            padding = Insets(12.0, 0.0, 0.0, 0.0)
            children.addAll(
                Label("Timer options").apply { style = "-fx-font-weight: bold;" },
                timerGrid,
                Separator(),
                Label("Lifecycle options").apply { style = "-fx-font-weight: bold;" },
                Label("Shutdown the producer automatically").apply { style = "-fx-text-fill: -color-fg-muted;" },
                lifecycleGrid
            )
            visibleProperty().bind(timedBtn.selectedProperty())
            managedProperty().bind(timedBtn.selectedProperty())
        }

        val content = VBox(10.0).apply {
            padding = Insets(12.0)
            children.addAll(topGrid, Separator(), timedPane)
        }
        return Tab("Flow", content)
    }

    // ── Headers tab ───────────────────────────────────────────────────────────

    private fun buildHeadersTab(): Tab {
        headersTable.placeholder = Label("No headers")
        headersTable.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        headersTable.columns.addAll(
            TableColumn<Pair<String, String>, String>("Key").apply {
                setCellValueFactory { SimpleStringProperty(it.value.first) }
            },
            TableColumn<Pair<String, String>, String>("Value").apply {
                setCellValueFactory { SimpleStringProperty(it.value.second) }
            }
        )
        VBox.setVgrow(headersTable, Priority.ALWAYS)

        val addBtn    = Button("+ Add").apply { setOnAction { addHeader() } }
        val removeBtn = Button("Remove").apply {
            styleClass.add("danger")
            setOnAction { headersTable.selectionModel.selectedItem?.let { headersItems.remove(it) } }
        }
        val toolbar = HBox(8.0, Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, addBtn, removeBtn).apply {
            padding = Insets(0.0, 0.0, 6.0, 0.0)
            alignment = Pos.CENTER_RIGHT
        }

        val content = VBox(0.0, toolbar, headersTable).apply {
            padding = Insets(10.0)
            VBox.setVgrow(headersTable, Priority.ALWAYS)
        }

        return Tab("Headers", content)
    }

    // ── Options tab ───────────────────────────────────────────────────────────

    private fun buildOptionsTab(): Tab {
        val grid = GridPane().apply {
            hgap = 12.0; vgap = 10.0; padding = Insets(12.0)
            columnConstraints.addAll(
                ColumnConstraints(80.0, 100.0, 120.0),
                ColumnConstraints().also { it.hgrow = Priority.ALWAYS }
            )
            addRow(0, Label("Partition:"), partitionField)
            GridPane.setHgrow(partitionField, Priority.ALWAYS)
        }
        return Tab("Options", grid)
    }

    // ── Bottom bar ────────────────────────────────────────────────────────────

    private fun buildBottomBar(): HBox {
        val csvBtn  = Button("Produce from CSV").apply {
            isDisable = true; tooltip = Tooltip("Coming soon")
        }
        val closeBtn = Button("Close").apply { setOnAction { stage.close() } }

        return HBox(8.0).apply {
            padding = Insets(8.0, 12.0, 10.0, 12.0)
            alignment = Pos.CENTER_LEFT
            style = "-fx-border-color: -color-border-default; -fx-border-width: 1 0 0 0;"
            children.addAll(
                csvBtn,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                sendBtn,
                closeBtn
            )
        }
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private fun loadTopics() {
        val svc = adminService ?: return
        Thread {
            try {
                val names = svc.listTopics().map { it.name }.sorted()
                Platform.runLater {
                    topicCombo.items.setAll(names)
                    if (topicCombo.value.isNullOrBlank() && names.isNotEmpty())
                        topicCombo.value = names.first()
                    else if (!initialTopic.isNullOrBlank())
                        topicCombo.value = initialTopic
                }
            } catch (_: Exception) { }
        }.also { it.isDaemon = true }.start()
    }

    private fun updateSendButton(timed: Boolean, running: Boolean) {
        sendBtn.styleClass.removeAll("accent", "danger")
        when {
            timed && running -> { sendBtn.text = "Stop";  sendBtn.styleClass.add("danger") }
            timed            -> { sendBtn.text = "Start"; sendBtn.styleClass.add("accent") }
            else             -> { sendBtn.text = "Send";  sendBtn.styleClass.add("accent") }
        }
    }

    private fun sendMessage() {
        val topic = topicCombo.value?.trim() ?: ""
        if (topic.isBlank()) { appendFailure("Topic name is required"); return }
        val value = valueArea.text
        if (value.isBlank()) { appendFailure("Message value is required"); return }
        val chunk = chunkSpinner.value
        Thread { repeat(chunk) { doSend(topic, value) } }.also { it.isDaemon = true }.start()
    }

    private fun startFlow() {
        val topic = topicCombo.value?.trim() ?: ""
        if (topic.isBlank()) { appendFailure("Topic name is required"); return }
        val value = valueArea.text
        if (value.isBlank()) { appendFailure("Message value is required"); return }

        val delayMs    = intervalSpinner.value.toLong().coerceAtLeast(10L)
        val jitterMs   = jitterSpinner.value.toLong()
        val maxCount   = msgCountSpinner.value.toLong().let { if (it <= 0L) Long.MAX_VALUE else it }
        val maxElapsed = elapsedSpinner.value.toLong()
        val chunk      = chunkSpinner.value

        flowRunning = true
        updateSendButton(timed = true, running = true)

        Thread {
            var sent = 0L
            val startTime = System.currentTimeMillis()
            while (flowRunning && sent < maxCount) {
                if (maxElapsed > 0L && System.currentTimeMillis() - startTime >= maxElapsed) break
                repeat(chunk) { if (flowRunning) doSend(topic, value) }
                sent += chunk
                if (flowRunning && sent < maxCount) {
                    val sleep = delayMs + if (jitterMs > 0L) (Math.random() * jitterMs).toLong() else 0L
                    Thread.sleep(sleep)
                }
            }
            Platform.runLater {
                flowRunning = false
                updateSendButton(timed = true, running = false)
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun stopFlow() {
        flowRunning = false
        updateSendButton(timed = true, running = false)
    }

    private fun doSend(topic: String, value: String) {
        val svc       = producerService ?: KafkaProducerService(cluster).also { producerService = it }
        val headers   = headersItems.associate { it.first to it.second }
        val partition = partitionField.text.trim().toIntOrNull()
        val key       = keyArea.text.takeIf { it.isNotBlank() }
        val startMs   = System.currentTimeMillis()
        try {
            val (actualPartition, offset) = svc.send(
                topic       = topic,
                key         = key,
                value       = value,
                keyFormat   = keyFormatBox.value.uppercase(),
                valueFormat = valueFormatBox.value.uppercase(),
                partition   = partition,
                headers     = headers
            )
            val latencyMs = System.currentTimeMillis() - startMs
            Platform.runLater { appendSuccess(topic, actualPartition, offset, latencyMs, key, value) }
        } catch (e: Exception) {
            Platform.runLater { appendFailure(e.message ?: "Unknown error") }
        }
    }

    private fun appendSuccess(topic: String, partition: Int, offset: Long, latencyMs: Long, key: String?, value: String) {
        val entry = OutputEntry.Success(LocalDateTime.now(), latencyMs, topic, partition, offset, key, value)
        val item = TreeItem<OutputEntry>(entry)
        if (!key.isNullOrBlank()) {
            val truncKey = if (key.length > 120) key.take(120) + "…" else key
            item.children.add(TreeItem(OutputEntry.Detail("Key", truncKey)))
        }
        val truncValue = if (value.length > 120) value.take(120) + "…" else value
        item.children.add(TreeItem(OutputEntry.Detail("Value", truncValue)))
        outputRoot.children.add(0, item)
    }

    private fun appendFailure(message: String) {
        val item = TreeItem<OutputEntry>(OutputEntry.Failure(LocalDateTime.now(), message))
        outputRoot.children.add(0, item)
    }

    private fun addHeader() {
        val keyField   = TextField().apply { promptText = "Header key" }
        val valueField = TextField().apply { promptText = "Header value" }
        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0; padding = Insets(12.0)
            addRow(0, Label("Key:"),   keyField)
            addRow(1, Label("Value:"), valueField)
            GridPane.setHgrow(keyField,   Priority.ALWAYS)
            GridPane.setHgrow(valueField, Priority.ALWAYS)
        }
        val dlg = Dialog<Pair<String, String>>().apply {
            title = "Add Header"
            dialogPane.content = grid
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
            setResultConverter { if (it == ButtonType.OK) Pair(keyField.text.trim(), valueField.text.trim()) else null }
        }
        dlg.showAndWait().ifPresent { if (it.first.isNotBlank()) headersItems.add(it) }
    }

    fun show() {
        stage.show()
        stage.toFront()
    }
}
