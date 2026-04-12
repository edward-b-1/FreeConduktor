package com.freeconductor.ui.consume

import com.freeconductor.model.*
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.service.KafkaConsumerService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MessageBrowserView(
    private val topicName: String,
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit
) {
    val root: SplitPane = SplitPane()

    // Left panel controls
    private val topicField = TextField(topicName).apply { promptText = "topic-name" }
    private val keyDeserBox = ComboBox<Deserializer>().apply {
        items.addAll(Deserializer.values()); value = Deserializer.STRING; maxWidth = Double.MAX_VALUE
    }
    private val valueDeserBox = ComboBox<Deserializer>().apply {
        items.addAll(Deserializer.values()); value = Deserializer.JSON; maxWidth = Double.MAX_VALUE
    }
    private val fromGroup = ToggleGroup()
    private val fromLatest   = RadioButton("Latest (new messages)").apply { toggleGroup = fromGroup; isSelected = true; userData = ConsumeFrom.LATEST }
    private val fromEarliest = RadioButton("Earliest").apply { toggleGroup = fromGroup; userData = ConsumeFrom.EARLIEST }
    private val fromOffset   = RadioButton("Specific offset").apply { toggleGroup = fromGroup; userData = ConsumeFrom.SPECIFIC_OFFSET }
    private val fromDatetime = RadioButton("Specific datetime").apply { toggleGroup = fromGroup; userData = ConsumeFrom.SPECIFIC_DATETIME }
    private val fromGroup_   = RadioButton("Consumer group").apply { toggleGroup = fromGroup; userData = ConsumeFrom.CONSUMER_GROUP }
    private val specificOffsetField = TextField().apply { promptText = "0"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val specificDateField   = TextField().apply { promptText = "2024-01-15 10:30:00"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val consumerGroupField  = TextField().apply { promptText = "my-group"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val maxMessagesField = TextField("500").apply { maxWidth = Double.MAX_VALUE }
    private val filterField = TextField().apply { promptText = "Filter key or value…"; maxWidth = Double.MAX_VALUE }
    private val startBtn = Button("▶  Start Consuming").apply { styleClass.add("accent"); maxWidth = Double.MAX_VALUE }
    private val stopBtn  = Button("⏹  Stop").apply { styleClass.add("danger"); maxWidth = Double.MAX_VALUE; isDisable = true }

    // Right panel
    private val messageItems = FXCollections.observableArrayList<MessageRecord>()
    private val allMessages  = FXCollections.observableArrayList<MessageRecord>()
    private val messageTable = TableView(messageItems)
    private val messageDetail = TextArea().apply {
        isEditable = false; isWrapText = true; prefHeight = 180.0
        promptText = "Select a message to see details"
        styleClass.add("code-area")
    }
    private val progressIndicator = ProgressIndicator().apply { maxWidth = 28.0; maxHeight = 28.0; isVisible = false }
    private val statusLabel = Label("Ready")
    private val countLabel  = Label("")

    private var consumerService: KafkaConsumerService? = null
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    init {
        wireEvents()
        setupMessageTable()
        root.items.addAll(buildLeftPanel(), buildRightPanel())
        root.setDividerPositions(0.27)
        root.orientation = Orientation.HORIZONTAL
    }

    private fun buildLeftPanel(): ScrollPane {
        fun sectionLabel(text: String) = Label(text).apply { styleClass.add("config-section-label") }
        fun row(vararg nodes: javafx.scene.Node) = HBox(4.0, *nodes).apply { alignment = Pos.CENTER_LEFT }

        val content = VBox(8.0).apply {
            padding = Insets(12.0)

            children.addAll(
                sectionLabel("TOPIC"),
                topicField,

                Separator(),
                sectionLabel("FORMAT"),
                row(Label("Key:").apply { minWidth = 44.0 }, keyDeserBox).also { HBox.setHgrow(keyDeserBox, Priority.ALWAYS) },
                row(Label("Value:").apply { minWidth = 44.0 }, valueDeserBox).also { HBox.setHgrow(valueDeserBox, Priority.ALWAYS) },

                Separator(),
                sectionLabel("START FROM"),
                fromLatest,
                fromEarliest,
                fromOffset,
                specificOffsetField,
                fromDatetime,
                specificDateField,
                fromGroup_,
                consumerGroupField,

                Separator(),
                sectionLabel("MAX MESSAGES"),
                maxMessagesField,

                Separator(),
                sectionLabel("FILTER"),
                filterField,

                Region().apply { VBox.setVgrow(this, Priority.ALWAYS) },
                startBtn,
                stopBtn
            )
        }

        return ScrollPane(content).apply {
            isFitToWidth = true
            vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            style = "-fx-background-color: -color-bg-subtle; -fx-border-color: -color-border-default; -fx-border-width: 0 1 0 0;"
            prefWidth = 260.0
        }
    }

    private fun buildRightPanel(): VBox {
        val toolbar = HBox(8.0).apply {
            padding = Insets(6.0, 8.0, 6.0, 8.0)
            alignment = Pos.CENTER_LEFT
            styleClass.add("message-toolbar")
            children.addAll(
                progressIndicator,
                statusLabel,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                countLabel,
                Button("Clear").apply { setOnAction { allMessages.clear(); messageItems.clear(); messageDetail.clear(); countLabel.text = "" } }
            )
        }

        val tableContainer = StackPane(messageTable)
        VBox.setVgrow(tableContainer, Priority.ALWAYS)

        val splitPane = SplitPane().apply {
            orientation = Orientation.VERTICAL
            items.addAll(tableContainer, messageDetail)
            setDividerPositions(0.72)
        }
        VBox.setVgrow(splitPane, Priority.ALWAYS)

        return VBox(toolbar, splitPane)
    }

    private fun setupMessageTable() {
        val timestampCol = TableColumn<MessageRecord, String>("Timestamp").apply {
            setCellValueFactory { SimpleStringProperty(formatter.format(Instant.ofEpochMilli(it.value.timestamp))) }
            prefWidth = 170.0
        }
        val partCol = TableColumn<MessageRecord, String>("Part").apply {
            setCellValueFactory { SimpleStringProperty(it.value.partition.toString()) }
            prefWidth = 50.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val offsetCol = TableColumn<MessageRecord, String>("Offset").apply {
            setCellValueFactory { SimpleStringProperty(it.value.offset.toString()) }
            prefWidth = 80.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val keyCol = TableColumn<MessageRecord, String>("Key").apply {
            setCellValueFactory { SimpleStringProperty(it.value.key ?: "(null)") }
            prefWidth = 130.0
        }
        val valueCol = TableColumn<MessageRecord, String>("Value").apply {
            setCellValueFactory {
                val v = it.value.value ?: "(null)"
                val preview = v.replace('\n', ' ').replace('\r', ' ')
                SimpleStringProperty(if (preview.length > 120) preview.substring(0, 120) + "…" else preview)
            }
            prefWidth = 320.0
        }
        messageTable.columns.addAll(timestampCol, partCol, offsetCol, keyCol, valueCol)
        messageTable.placeholder = Label("No messages. Configure settings and click Start Consuming.")
        messageTable.selectionModel.selectionMode = SelectionMode.SINGLE
        VBox.setVgrow(messageTable, Priority.ALWAYS)
        messageTable.selectionModel.selectedItemProperty().addListener { _, _, msg ->
            if (msg != null) showDetail(msg)
        }
    }

    private fun wireEvents() {
        fromGroup.selectedToggleProperty().addListener { _, _, toggle ->
            specificOffsetField.isDisable = toggle?.userData != ConsumeFrom.SPECIFIC_OFFSET
            specificDateField.isDisable   = toggle?.userData != ConsumeFrom.SPECIFIC_DATETIME
            consumerGroupField.isDisable  = toggle?.userData != ConsumeFrom.CONSUMER_GROUP
        }
        filterField.textProperty().addListener { _, _, text -> applyFilter(text) }
        startBtn.setOnAction { startConsuming() }
        stopBtn.setOnAction  { stopConsuming() }
    }

    private fun currentSettings(): ConsumeSettings {
        val from = fromGroup.selectedToggle?.userData as? ConsumeFrom ?: ConsumeFrom.LATEST
        val timestamp = if (from == ConsumeFrom.SPECIFIC_DATETIME) {
            try {
                val dt = LocalDateTime.parse(specificDateField.text.trim().replace(" ", "T"))
                dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        } else null

        return ConsumeSettings(
            topic = topicField.text.trim(),
            from = from,
            maxMessages = maxMessagesField.text.trim().toIntOrNull() ?: 500,
            keyDeserializer = keyDeserBox.value,
            valueDeserializer = valueDeserBox.value,
            specificOffset = specificOffsetField.text.trim().toLongOrNull(),
            specificTimestamp = timestamp,
            consumerGroup = consumerGroupField.text.trim().takeIf { it.isNotBlank() }
        )
    }

    private fun startConsuming() {
        val settings = currentSettings()
        if (settings.topic.isBlank()) { statusLabel.text = "Enter a topic name"; return }

        allMessages.clear(); messageItems.clear(); messageDetail.clear()
        progressIndicator.isVisible = true
        startBtn.isDisable = true; stopBtn.isDisable = false
        statusLabel.text = "Consuming from ${settings.topic}…"
        countLabel.text = ""

        val svc = KafkaConsumerService(cluster)
        consumerService = svc

        Thread {
            svc.consume(
                settings = settings,
                onMessage = { msg ->
                    Platform.runLater {
                        allMessages.add(msg)
                        applyFilter(filterField.text)
                        countLabel.text = "${allMessages.size} messages"
                    }
                },
                onComplete = {
                    Platform.runLater {
                        progressIndicator.isVisible = false
                        startBtn.isDisable = false; stopBtn.isDisable = true
                        statusLabel.text = "Done — ${allMessages.size} messages"
                        setStatus("Consumed ${allMessages.size} messages from ${settings.topic}")
                    }
                },
                onError = { e ->
                    Platform.runLater {
                        progressIndicator.isVisible = false
                        startBtn.isDisable = false; stopBtn.isDisable = true
                        statusLabel.text = "Error: ${e.message}"
                    }
                }
            )
        }.also { it.isDaemon = true }.start()
    }

    fun stopConsuming() {
        consumerService?.stopConsuming()
        stopBtn.isDisable = true; startBtn.isDisable = false
        progressIndicator.isVisible = false
        statusLabel.text = "Stopped"
    }

    private fun applyFilter(text: String) {
        if (text.isBlank()) {
            messageItems.setAll(allMessages)
        } else {
            messageItems.setAll(allMessages.filter {
                it.key?.contains(text, ignoreCase = true) == true ||
                it.value?.contains(text, ignoreCase = true) == true
            })
        }
        countLabel.text = if (text.isBlank()) "${allMessages.size} messages"
                          else "${messageItems.size} / ${allMessages.size} messages"
    }

    private fun showDetail(msg: MessageRecord) {
        messageDetail.text = buildString {
            appendLine("Topic:     ${msg.topic}")
            appendLine("Partition: ${msg.partition}    Offset: ${msg.offset}")
            appendLine("Timestamp: ${formatter.format(Instant.ofEpochMilli(msg.timestamp))}")
            appendLine("Key size:  ${msg.keySize} bytes    Value size: ${msg.valueSize} bytes")
            if (msg.headers.isNotEmpty()) { appendLine(); appendLine("--- Headers ---"); msg.headers.forEach { (k, v) -> appendLine("$k: $v") } }
            appendLine(); appendLine("--- Key ---"); appendLine(msg.key ?: "(null)")
            appendLine(); appendLine("--- Value ---"); appendLine(msg.value ?: "(null)")
        }
    }
}
