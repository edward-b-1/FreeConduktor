package com.freeconductor.ui.produce

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.service.KafkaProducerService
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.layout.*
import javafx.scene.input.MouseEvent
import com.freeconductor.ui.util.centerOnActiveWindow
import javafx.stage.FileChooser
import javafx.stage.Stage
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.kordamp.ikonli.fontawesome5.FontAwesomeRegular
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
    private val partitionCombo = ComboBox<String>().apply { isEditable = true; promptText = "Auto"; prefWidth = 200.0 }

    // ── Options state ─────────────────────────────────────────────────────────

    private val compressionGroup  = ToggleGroup()
    private val compressionNone   = ToggleButton("none").apply   { toggleGroup = compressionGroup; isSelected = true }
    private val compressionGzip   = ToggleButton("gzip").apply   { toggleGroup = compressionGroup }
    private val compressionSnappy = ToggleButton("snappy").apply { toggleGroup = compressionGroup }
    private val compressionLz4    = ToggleButton("lz4").apply    { toggleGroup = compressionGroup }
    private val compressionZstd   = ToggleButton("zstd").apply   { toggleGroup = compressionGroup }

    private val idempotenceChk = CheckBox()

    private val acksGroup  = ToggleGroup()
    private val acksNone   = ToggleButton("none").apply   { toggleGroup = acksGroup }
    private val acksLeader = ToggleButton("leader").apply { toggleGroup = acksGroup }
    private val acksAll    = ToggleButton("all").apply    { toggleGroup = acksGroup; isSelected = true }

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

    private val headerRows = VBox(4.0)

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
        data class Info(val timestamp: LocalDateTime, val message: String) : OutputEntry()
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

        // ── Info node tree ────────────────────────────────────────────────────
        private val infoLabel = Label().apply { style = "-fx-text-fill: -color-fg-muted;" }
        private val infoBox   = VBox(infoLabel).apply { padding = Insets(3.0, 0.0, 3.0, 0.0) }

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
                is OutputEntry.Info -> {
                    infoLabel.text = item.message
                    graphic = infoBox
                }
            }
        }
    }

    private val sendBtn = Button("Send").apply { styleClass.add("accent") }

    private var producerService: KafkaProducerService? = null

    init {
        initialHeaders.forEach { (k, v) -> headerRows.children.add(buildHeaderRow(k, v)) }

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
        listOf(manualBtn, timedBtn,
               compressionNone, compressionGzip, compressionSnappy, compressionLz4, compressionZstd,
               acksNone, acksLeader, acksAll).forEach { btn ->
            btn.addEventFilter(MouseEvent.MOUSE_PRESSED) { if (btn.isSelected) it.consume() }
        }

        timedBtn.selectedProperty().addListener { _, _, timed -> updateSendButton(timed, false) }

        sendBtn.setOnAction {
            when {
                timedBtn.isSelected && flowRunning -> stopFlow()
                timedBtn.isSelected               -> startFlow()
                else                              -> sendMessage()
            }
        }

        topicCombo.valueProperty().addListener { _, _, topic ->
            if (!topic.isNullOrBlank()) loadPartitions(topic)
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

    private fun buildHeaderRow(key: String = "", value: String = ""): HBox {
        val fieldStyle = "-fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 2 4 2 4;"
        val keyField = TextField(key).apply {
            promptText = "Key"
            style = fieldStyle
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val valueField = TextField(value).apply {
            promptText = "Value"
            style = fieldStyle
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val deleteBtn = Button().apply {
            graphic = FontIcon(FontAwesomeRegular.TRASH_ALT).apply { iconSize = 11 }
            styleClass.add("danger")
        }
        val row = HBox(8.0, keyField, valueField, deleteBtn).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 0.0, 4.0, 0.0)
            style = "-fx-border-color: -color-border-default; -fx-border-width: 0 0 1 0;"
        }
        deleteBtn.setOnAction { headerRows.children.remove(row) }
        return row
    }

    private fun collectHeaders(): Map<String, String> =
        headerRows.children.filterIsInstance<HBox>().mapNotNull { row ->
            val key   = (row.children[0] as TextField).text.trim()
            val value = (row.children[1] as TextField).text.trim()
            if (key.isNotBlank()) key to value else null
        }.toMap()

    private fun buildHeadersTab(): Tab {
        val addBtn = Button("ADD HEADER").apply {
            styleClass.add("accent")
            setOnAction { headerRows.children.add(buildHeaderRow()) }
        }

        val scroll = ScrollPane(headerRows).apply {
            isFitToWidth = true
            style = "-fx-background-color: transparent; -fx-background: transparent;"
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val content = VBox(10.0, addBtn, scroll).apply {
            padding = Insets(10.0)
            VBox.setVgrow(scroll, Priority.ALWAYS)
        }

        return Tab("Headers", content)
    }

    // ── Options tab ───────────────────────────────────────────────────────────

    private fun buildOptionsTab(): Tab {
        val partitionLabel = HBox(5.0,
            Label("Force Partition"),
            FontIcon(FontAwesomeSolid.INFO_CIRCLE).apply {
                iconSize = 12
                Tooltip.install(this, Tooltip("Leave empty to use automatic partitioning"))
            }
        ).apply { alignment = Pos.CENTER_LEFT }

        val compressionBar = HBox(8.0,
            compressionNone, compressionGzip, compressionSnappy, compressionLz4, compressionZstd
        ).apply { alignment = Pos.CENTER_LEFT }

        val acksBar = HBox(8.0, acksNone, acksLeader, acksAll).apply { alignment = Pos.CENTER_LEFT }

        val grid = GridPane().apply {
            hgap = 12.0; vgap = 12.0; padding = Insets(12.0)
            columnConstraints.addAll(
                ColumnConstraints(130.0, 145.0, 160.0),
                ColumnConstraints().also { it.hgrow = Priority.ALWAYS }
            )
            addRow(0, partitionLabel, partitionCombo)
            addRow(1, Label("Compression Type"), compressionBar)
            addRow(2, Label("Idempotence"), idempotenceChk)
            addRow(3, Label("Acks"), acksBar)
        }
        return Tab("Options", grid)
    }

    // ── Bottom bar ────────────────────────────────────────────────────────────

    private fun buildBottomBar(): HBox {
        val csvBtn = Button("Produce from CSV").apply {
            setOnAction { produceFromCsv() }
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

    private fun loadPartitions(topic: String) {
        val svc = adminService ?: return
        Thread {
            try {
                val count = svc.describeTopicPartitions(topic).size
                Platform.runLater {
                    val current = partitionCombo.value
                    partitionCombo.items.setAll((0 until count).map { it.toString() })
                    partitionCombo.value = if (current in partitionCombo.items) current else null
                }
            } catch (_: Exception) { }
        }.also { it.isDaemon = true }.start()
    }

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

    private fun selectedCompression() = (compressionGroup.selectedToggle as? ToggleButton)?.text ?: "none"
    private fun selectedAcks()        = (acksGroup.selectedToggle as? ToggleButton)?.text ?: "all"

    private fun doSend(topic: String, value: String, keyOverride: String? = null) {
        val compression = selectedCompression()
        val acks        = selectedAcks()
        val idempotent  = idempotenceChk.isSelected
        val svc = producerService?.takeIf {
            it.compression == compression && it.acks == acks && it.idempotent == idempotent
        } ?: KafkaProducerService(cluster, compression, acks, idempotent).also {
            producerService?.close()
            producerService = it
        }
        val headers   = collectHeaders()
        val partition = partitionCombo.value?.trim()?.takeIf { it.isNotBlank() }?.toIntOrNull()
        val key       = keyOverride ?: keyArea.text.takeIf { it.isNotBlank() }
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

    private fun appendInfo(message: String) {
        val item = TreeItem<OutputEntry>(OutputEntry.Info(LocalDateTime.now(), message))
        outputRoot.children.add(0, item)
    }

    private fun showCsvInfoDialog(): Boolean {
        fun numberedRow(n: String, text: String): HBox {
            val numLabel = Label("$n.").apply { style = "-fx-font-weight: bold; -fx-min-width: 18px;" }
            return HBox(8.0, numLabel, Label(text).apply { isWrapText = true }).apply {
                HBox.setHgrow(children[1] as Label, Priority.ALWAYS)
            }
        }

        val infoRow = HBox(6.0,
            FontIcon(FontAwesomeSolid.INFO_CIRCLE).apply { iconSize = 13 },
            Label("All the configuration from the current producer settings will apply. Make sure you selected the correct data types.").apply {
                isWrapText = true
                HBox.setHgrow(this, Priority.ALWAYS)
            }
        ).apply { alignment = Pos.TOP_LEFT }

        val content = VBox(10.0).apply {
            padding = Insets(16.0, 20.0, 8.0, 20.0)
            prefWidth = 480.0
            children.addAll(
                Label("Import a CSV file containing the data to produce to the topic."),
                Label("Allowed formats are :"),
                numberedRow("1", "Two columns without header (first is Record key, second is Record value)"),
                numberedRow("2", "A single column without header (for importing record values with null keys)"),
                numberedRow("3", "Columns with at least a 'key' and 'value' named headers. Extra columns will be ignored."),
                Separator(),
                infoRow
            )
        }

        val continueType = ButtonType("Continue", ButtonBar.ButtonData.OK_DONE)
        val topic = topicCombo.value ?: ""
        val dlg = Dialog<ButtonType>().apply {
            initOwner(stage)
            title = "Import CSV to Topic $topic"
            dialogPane.headerText = "Template : Send to $topic"
            dialogPane.content = content
            dialogPane.buttonTypes.addAll(continueType, ButtonType.CANCEL)
        }
        return dlg.showAndWait().orElse(ButtonType.CANCEL) == continueType
    }

    private fun produceFromCsv() {
        val topic = topicCombo.value?.trim() ?: ""
        if (topic.isBlank()) { appendFailure("Topic name is required"); return }

        if (!showCsvInfoDialog()) return

        val chooser = FileChooser().apply {
            title = "Select CSV File"
            extensionFilters.add(FileChooser.ExtensionFilter("CSV Files", "*.csv"))
        }
        val file = chooser.showOpenDialog(stage) ?: return

        Thread {
            try {
                // Peek at first row to detect format
                val firstLine = file.useLines(Charsets.UTF_8) { it.firstOrNull() } ?: return@Thread
                val firstFields = CSVParser.parse(firstLine, CSVFormat.DEFAULT)
                    .records.firstOrNull()?.map { it.trim().lowercase() } ?: return@Thread
                val hasNamedHeaders = firstFields.any { it == "key" || it == "value" }

                Platform.runLater { appendInfo("Producing from ${file.name}…") }

                if (hasNamedHeaders) {
                    // Format 3: first row is a header row with "key" and/or "value" columns
                    val format = CSVFormat.DEFAULT.builder()
                        .setHeader().setSkipHeaderRecord(true)
                        .setIgnoreHeaderCase(true).setTrim(true).build()
                    CSVParser.parse(file, Charsets.UTF_8, format).use { parser ->
                        val headers = parser.headerNames.map { it.lowercase() }
                        if ("value" !in headers) {
                            Platform.runLater { appendFailure("CSV error: no 'value' column found") }
                            return@use
                        }
                        val hasKey = "key" in headers
                        for (record in parser) {
                            val key   = if (hasKey) record.get("key").takeIf { it.isNotBlank() } else null
                            val value = record.get("value")
                            if (value.isBlank()) continue
                            doSend(topic, value, key)
                        }
                    }
                } else {
                    // Format 1 (two columns) or Format 2 (single column): no header, positional
                    val format = CSVFormat.DEFAULT.builder().setTrim(true).build()
                    CSVParser.parse(file, Charsets.UTF_8, format).use { parser ->
                        for (record in parser) {
                            if (record.size() == 0) continue
                            val key   = if (record.size() >= 2) record.get(0).takeIf { it.isNotBlank() } else null
                            val value = if (record.size() >= 2) record.get(1) else record.get(0)
                            if (value.isBlank()) continue
                            doSend(topic, value, key)
                        }
                    }
                }

                Platform.runLater { appendInfo("Done: ${file.name}") }
            } catch (e: Exception) {
                Platform.runLater { appendFailure("CSV error: ${e.message}") }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun show() {
        stage.centerOnActiveWindow()
        stage.show()
        stage.toFront()
    }
}
