package com.freeconductor.ui.consume

import com.freeconductor.model.*
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.service.KafkaConsumerService
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.geometry.Side
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MessageBrowserView(
    private val topicName: String,
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit,
    private val setWindowTitle: (String) -> Unit = {}
) {
    // Root is a BorderPane: fixed-width left panel | growing right panel
    val root: BorderPane = BorderPane()

    // ── Topic selector ───────────────────────────────────────────────────────
    private val topicCombo = ComboBox<String>().apply {
        maxWidth = Double.MAX_VALUE
        promptText = "Select a topic"
        if (topicName.isNotBlank()) value = topicName
    }

    // ── Format controls ──────────────────────────────────────────────────────
    private val keyDeserBox = ComboBox<Deserializer>().apply {
        items.addAll(Deserializer.values()); value = Deserializer.STRING; maxWidth = Double.MAX_VALUE
    }
    private val valueDeserBox = ComboBox<Deserializer>().apply {
        items.addAll(Deserializer.values()); value = Deserializer.JSON; maxWidth = Double.MAX_VALUE
    }
    private val fromGroup    = ToggleGroup()
    private val fromLatest   = RadioButton("Latest (new messages)").apply { toggleGroup = fromGroup; isSelected = true; userData = ConsumeFrom.LATEST }
    private val fromEarliest = RadioButton("Earliest").apply { toggleGroup = fromGroup; userData = ConsumeFrom.EARLIEST }
    private val fromOffset   = RadioButton("Specific offset").apply { toggleGroup = fromGroup; userData = ConsumeFrom.SPECIFIC_OFFSET }
    private val fromDatetime = RadioButton("Specific datetime").apply { toggleGroup = fromGroup; userData = ConsumeFrom.SPECIFIC_DATETIME }
    private val fromGroup_   = RadioButton("Consumer group").apply { toggleGroup = fromGroup; userData = ConsumeFrom.CONSUMER_GROUP }
    private val specificOffsetField = TextField().apply { promptText = "0"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val specificDateField   = TextField().apply { promptText = "2024-01-15 10:30:00"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val consumerGroupField  = TextField().apply { promptText = "my-group"; isDisable = true; maxWidth = Double.MAX_VALUE }

    // ── Filter controls ──────────────────────────────────────────────────────
    private val filterField = TextField().apply { promptText = "Filter key or value…"; maxWidth = Double.MAX_VALUE }

    // ── Limit controls ───────────────────────────────────────────────────────
    private val limitGroup           = ToggleGroup()
    private val limitNoneBtn         = RadioButton("None (forever)").apply      { toggleGroup = limitGroup; isSelected = true; userData = ConsumeLimit.NONE }
    private val limitRecordsBtn      = RadioButton("Number of records").apply   { toggleGroup = limitGroup; userData = ConsumeLimit.RECORD_COUNT }
    private val limitDateBtn         = RadioButton("Specific date").apply       { toggleGroup = limitGroup; userData = ConsumeLimit.SPECIFIC_DATE }
    private val limitBytesBtn        = RadioButton("Max size (bytes)").apply    { toggleGroup = limitGroup; userData = ConsumeLimit.MAX_BYTES }
    private val limitPartRecordsBtn  = RadioButton("Number of records").apply   { toggleGroup = limitGroup; userData = ConsumeLimit.PER_PARTITION_RECORD_COUNT }
    private val limitPartBytesBtn    = RadioButton("Max size (bytes)").apply    { toggleGroup = limitGroup; userData = ConsumeLimit.PER_PARTITION_MAX_BYTES }
    private val limitRecordsField     = TextField("500").apply     { promptText = "records"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val limitDateField        = TextField().apply          { promptText = "2024-01-15 10:30:00"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val limitBytesField       = TextField("1048576").apply { promptText = "bytes";   isDisable = true; maxWidth = Double.MAX_VALUE }
    private val limitPartRecordsField = TextField("100").apply     { promptText = "records"; isDisable = true; maxWidth = Double.MAX_VALUE }
    private val limitPartBytesField   = TextField("1048576").apply { promptText = "bytes";   isDisable = true; maxWidth = Double.MAX_VALUE }

    private val actionBtn = Button("Start", FontIcon(FontAwesomeSolid.PLAY_CIRCLE)).apply {
        styleClass.add("accent"); minWidth = 100.0
    }

    // ── Message data ─────────────────────────────────────────────────────────
    private val allMessages  = FXCollections.observableArrayList<MessageRecord>()
    private val messageItems = FXCollections.observableArrayList<MessageRecord>()

    // ── Simple view ──────────────────────────────────────────────────────────
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val monospaceFont = SimpleBooleanProperty(false)
    private val simpleList = ListView(messageItems).apply {
        setCellFactory { SimpleMessageCell(formatter, monospaceFont) }
        fixedCellSize = 110.0   // fixed height bypasses the prefHeight(-1) measurement
        placeholder = Label("No messages. Configure settings and click Start Consuming.")
    }

    // ── Table view ───────────────────────────────────────────────────────────
    private val messageTable = TableView(messageItems)
    private val colVisible = linkedMapOf(
        "Topic"     to SimpleBooleanProperty(true),
        "Partition" to SimpleBooleanProperty(true),
        "Offset"    to SimpleBooleanProperty(true),
        "Timestamp" to SimpleBooleanProperty(true),
        "Key"       to SimpleBooleanProperty(true),
        "Value"     to SimpleBooleanProperty(true),
        "Headers"   to SimpleBooleanProperty(true)
    )

    // ── Detail pane ──────────────────────────────────────────────────────────
    private val messageDetail = TextArea().apply {
        isEditable = false; isWrapText = true; prefHeight = 180.0
        promptText = "Select a message to see details"
        styleClass.add("code-area")
    }

    // ── Toolbar controls ─────────────────────────────────────────────────────
    private val progressIndicator = ProgressIndicator().apply { maxWidth = 28.0; maxHeight = 28.0; isVisible = false }
    private val statusLabel = Label("Ready")
    private val countLabel  = Label("")
    private val viewToggleBtn = ToggleButton(null, FontIcon(FontAwesomeSolid.COLUMNS)).apply {
        tooltip = Tooltip("Toggle table view")
    }
    private val fontToggleBtn = ToggleButton("Mono").apply {
        tooltip = Tooltip("Toggle monospace font")
    }
    private val columnsBtn = Button("Columns ▾").apply { isVisible = false; isManaged = false }

    // ── Collapse button ───────────────────────────────────────────────────────
    private var leftCollapsed = false
    private val collapseBtn = Button(null, FontIcon(FontAwesomeSolid.CHEVRON_LEFT)).apply {
        tooltip = Tooltip("Hide settings panel")
        styleClass.add("flat")
    }

    // ── Content container ────────────────────────────────────────────────────
    private val contentStack = StackPane(simpleList, messageTable)

    private var consumerService: KafkaConsumerService? = null

    // Left panel reference — set in buildLeftPanel(), used for collapse and disable
    private lateinit var leftPanel: VBox
    private lateinit var topicSection: VBox
    private lateinit var settingsTabs: TabPane

    init {
        setupMessageTable()
        setupViewToggle()
        setupColumnsMenu()
        wireEvents()
        leftPanel = buildLeftPanel()
        root.left   = leftPanel
        root.center = buildRightPanel()
        setupTopicCombo()
        collapseBtn.setOnAction { toggleLeftPanel() }
    }

    // ── Left panel collapse ──────────────────────────────────────────────────

    private fun toggleLeftPanel() {
        leftCollapsed = !leftCollapsed
        leftPanel.isVisible = !leftCollapsed
        leftPanel.isManaged = !leftCollapsed
        collapseBtn.graphic = FontIcon(
            if (leftCollapsed) FontAwesomeSolid.CHEVRON_RIGHT else FontAwesomeSolid.CHEVRON_LEFT
        )
        collapseBtn.tooltip = Tooltip(
            if (leftCollapsed) "Show settings panel" else "Hide settings panel"
        )
    }

    private fun collapseLeftPanel() {
        if (!leftCollapsed) toggleLeftPanel()
    }

    // ── Disable / enable controls during consumption ─────────────────────────

    private fun setControlsDisabled(disabled: Boolean) {
        topicCombo.isDisable  = disabled
        keyDeserBox.isDisable = disabled
        valueDeserBox.isDisable = disabled
        fromLatest.isDisable   = disabled
        fromEarliest.isDisable = disabled
        fromOffset.isDisable   = disabled
        fromDatetime.isDisable = disabled
        fromGroup_.isDisable   = disabled
        limitNoneBtn.isDisable        = disabled
        limitRecordsBtn.isDisable     = disabled
        limitDateBtn.isDisable        = disabled
        limitBytesBtn.isDisable       = disabled
        limitPartRecordsBtn.isDisable = disabled
        limitPartBytesBtn.isDisable   = disabled

        if (disabled) {
            // Disable all conditional input fields too
            specificOffsetField.isDisable   = true
            specificDateField.isDisable     = true
            consumerGroupField.isDisable    = true
            limitRecordsField.isDisable     = true
            limitDateField.isDisable        = true
            limitBytesField.isDisable       = true
            limitPartRecordsField.isDisable = true
            limitPartBytesField.isDisable   = true
        } else {
            // Re-apply conditional disables from current toggle state
            val fromSel  = fromGroup.selectedToggle?.userData  as? ConsumeFrom
            specificOffsetField.isDisable = fromSel != ConsumeFrom.SPECIFIC_OFFSET
            specificDateField.isDisable   = fromSel != ConsumeFrom.SPECIFIC_DATETIME
            consumerGroupField.isDisable  = fromSel != ConsumeFrom.CONSUMER_GROUP

            val limitSel = limitGroup.selectedToggle?.userData as? ConsumeLimit
            limitRecordsField.isDisable     = limitSel != ConsumeLimit.RECORD_COUNT
            limitDateField.isDisable        = limitSel != ConsumeLimit.SPECIFIC_DATE
            limitBytesField.isDisable       = limitSel != ConsumeLimit.MAX_BYTES
            limitPartRecordsField.isDisable = limitSel != ConsumeLimit.PER_PARTITION_RECORD_COUNT
            limitPartBytesField.isDisable   = limitSel != ConsumeLimit.PER_PARTITION_MAX_BYTES
        }

        // While consuming the collapse button is hidden so the user cannot expand the
        // settings panel mid-run. Re-enabling restores the button and brings the panel back.
        collapseBtn.isVisible = !disabled
        collapseBtn.isManaged = !disabled
        if (!disabled) expandLeftPanel()
    }

    private fun expandLeftPanel() {
        if (leftCollapsed) toggleLeftPanel()
    }

    // ── Topic combo setup ────────────────────────────────────────────────────

    private fun setupTopicCombo() {
        topicCombo.valueProperty().addListener { _, _, newValue ->
            if (!newValue.isNullOrBlank())
                setWindowTitle("Consume from Topic: $newValue  [${cluster.name}]")
        }
        if (topicName.isNotBlank())
            setWindowTitle("Consume from Topic: $topicName  [${cluster.name}]")

        Thread {
            try {
                val names = adminService.listTopics().map { it.name }.sorted()
                Platform.runLater {
                    topicCombo.items.setAll(names)
                    if (topicCombo.value.isNullOrBlank() && names.isNotEmpty())
                        topicCombo.value = names.first()
                }
            } catch (_: Exception) { }
        }.also { it.isDaemon = true }.start()
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    private fun setupViewToggle() {
        messageTable.isVisible = false
        messageTable.isManaged = false

        viewToggleBtn.selectedProperty().addListener { _, _, tableMode ->
            simpleList.isVisible  = !tableMode
            simpleList.isManaged  = !tableMode
            messageTable.isVisible = tableMode
            messageTable.isManaged = tableMode
            columnsBtn.isVisible = tableMode
            columnsBtn.isManaged = tableMode
            fontToggleBtn.isVisible = !tableMode
            fontToggleBtn.isManaged = !tableMode
            if (tableMode) viewToggleBtn.styleClass.add("accent")
            else           viewToggleBtn.styleClass.remove("accent")
        }

        fontToggleBtn.selectedProperty().bindBidirectional(monospaceFont)
        monospaceFont.addListener { _, _, _ ->
            if (monospaceFont.get()) fontToggleBtn.styleClass.add("accent")
            else                    fontToggleBtn.styleClass.remove("accent")
            simpleList.refresh()
        }
    }

    private fun setupColumnsMenu() {
        val menu = ContextMenu()
        colVisible.forEach { (name, prop) ->
            val item = CheckMenuItem(name).apply {
                isSelected = prop.get()
                selectedProperty().bindBidirectional(prop)
            }
            menu.items.add(item)
        }
        columnsBtn.setOnAction { menu.show(columnsBtn, Side.BOTTOM, 0.0, 0.0) }
    }

    private fun setupMessageTable() {
        fun col(name: String, pref: Double, rightAlign: Boolean = false, value: (MessageRecord) -> String): TableColumn<MessageRecord, String> =
            TableColumn<MessageRecord, String>(name).apply {
                setCellValueFactory { SimpleStringProperty(value(it.value)) }
                prefWidth = pref
                if (rightAlign) style = "-fx-alignment: CENTER-RIGHT;"
                visibleProperty().bindBidirectional(colVisible[name]!!)
            }

        val valueCol = TableColumn<MessageRecord, String>("Value").apply {
            setCellValueFactory {
                val v = it.value.value ?: "(null)"
                val preview = v.replace('\n', ' ').replace('\r', ' ')
                SimpleStringProperty(if (preview.length > 120) preview.substring(0, 120) + "…" else preview)
            }
            prefWidth = 260.0
            visibleProperty().bindBidirectional(colVisible["Value"]!!)
        }

        messageTable.columns.addAll(
            col("Topic",      110.0) { it.topic },
            col("Partition",   70.0, rightAlign = true) { it.partition.toString() },
            col("Offset",      80.0, rightAlign = true) { it.offset.toString() },
            col("Timestamp",  170.0) { formatter.format(Instant.ofEpochMilli(it.timestamp)) },
            col("Key",        120.0) { it.key ?: "(null)" },
            valueCol,
            col("Headers",    150.0) { msg -> msg.headers.entries.joinToString("  ") { "${it.key}=${it.value}" } }
        )
        messageTable.placeholder = Label("No messages. Configure settings and click Start Consuming.")
        messageTable.selectionModel.selectionMode = SelectionMode.SINGLE
        VBox.setVgrow(messageTable, Priority.ALWAYS)
        messageTable.selectionModel.selectedItemProperty().addListener { _, _, msg ->
            if (msg != null) showDetail(msg)
        }
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private fun sectionLabel(text: String) = Label(text).apply { styleClass.add("config-section-label") }

    private fun tabScrollPane(content: VBox): ScrollPane = ScrollPane(content).apply {
        isFitToWidth = true
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
        style = "-fx-background-color: transparent; -fx-background: transparent;"
    }

    private fun limitRow(radio: RadioButton, field: TextField) = HBox(6.0, radio, field).apply {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(field, Priority.ALWAYS)
    }

    private fun buildFormatTab(): Tab {
        fun row(vararg nodes: javafx.scene.Node) = HBox(4.0, *nodes).apply { alignment = Pos.CENTER_LEFT }

        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            children.addAll(
                sectionLabel("FORMAT"),
                row(Label("Key:").apply { minWidth = 44.0 }, keyDeserBox).also   { HBox.setHgrow(keyDeserBox,   Priority.ALWAYS) },
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
                consumerGroupField
            )
        }
        return Tab("Format", tabScrollPane(content))
    }

    private fun buildFilterTab(): Tab {
        val descLabel = Label("Show only messages whose key or value contains the filter text (case-insensitive).").apply {
            isWrapText = true
            style = "-fx-text-fill: -color-fg-muted; -fx-font-size: 11px;"
        }
        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            children.addAll(sectionLabel("FILTER"), filterField, descLabel)
        }
        return Tab("Filter", tabScrollPane(content))
    }

    private fun buildAdvancedTab(): Tab {
        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            children.addAll(
                sectionLabel("LIMIT"),
                limitNoneBtn,
                limitRow(limitRecordsBtn, limitRecordsField),
                limitRow(limitDateBtn,    limitDateField),
                limitRow(limitBytesBtn,   limitBytesField),
                Label("For each partition").apply {
                    style = "-fx-text-fill: -color-fg-muted; -fx-font-size: 11px; -fx-padding: 4 0 0 0;"
                },
                limitRow(limitPartRecordsBtn, limitPartRecordsField),
                limitRow(limitPartBytesBtn,   limitPartBytesField)
            )
        }
        return Tab("Advanced", tabScrollPane(content))
    }

    private fun buildLeftPanel(): VBox {
        topicSection = VBox(6.0).apply {
            padding = Insets(12.0, 12.0, 10.0, 12.0)
            children.addAll(sectionLabel("TOPIC"), topicCombo)
        }

        settingsTabs = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs.addAll(buildFormatTab(), buildFilterTab(), buildAdvancedTab())
        }
        VBox.setVgrow(settingsTabs, Priority.ALWAYS)

        return VBox(topicSection, Separator(), settingsTabs).apply {
            style = "-fx-background-color: -color-bg-subtle;" +
                    "-fx-border-color: -color-border-default; -fx-border-width: 0 1 0 0;"
            // Fixed width — not resizable by dragging
            prefWidth = 280.0
            minWidth  = 280.0
            maxWidth  = 280.0
        }
    }

    private fun buildRightPanel(): VBox {
        val toolbar = HBox(8.0).apply {
            padding = Insets(6.0, 8.0, 6.0, 8.0)
            alignment = Pos.CENTER_LEFT
            styleClass.add("message-toolbar")
            children.addAll(
                collapseBtn,
                progressIndicator,
                statusLabel,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                countLabel,
                viewToggleBtn,
                fontToggleBtn,
                columnsBtn,
                Button("Clear").apply {
                    setOnAction {
                        allMessages.clear(); messageItems.clear()
                        messageDetail.clear(); countLabel.text = ""
                    }
                }
            )
        }

        VBox.setVgrow(contentStack, Priority.ALWAYS)

        val splitPane = SplitPane().apply {
            orientation = Orientation.VERTICAL
            items.addAll(contentStack, messageDetail)
            setDividerPositions(0.72)
        }
        VBox.setVgrow(splitPane, Priority.ALWAYS)

        val bottomBar = HBox(Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }, actionBtn).apply {
            padding = Insets(6.0, 8.0, 6.0, 8.0)
            alignment = Pos.CENTER_RIGHT
            style = "-fx-border-color: -color-border-default; -fx-border-width: 1 0 0 0;"
        }

        return VBox(toolbar, splitPane, bottomBar)
    }

    // ── Events ───────────────────────────────────────────────────────────────

    private fun wireEvents() {
        fromGroup.selectedToggleProperty().addListener { _, _, toggle ->
            specificOffsetField.isDisable = toggle?.userData != ConsumeFrom.SPECIFIC_OFFSET
            specificDateField.isDisable   = toggle?.userData != ConsumeFrom.SPECIFIC_DATETIME
            consumerGroupField.isDisable  = toggle?.userData != ConsumeFrom.CONSUMER_GROUP
        }

        val limitFieldMap = mapOf(
            ConsumeLimit.RECORD_COUNT               to limitRecordsField,
            ConsumeLimit.SPECIFIC_DATE              to limitDateField,
            ConsumeLimit.MAX_BYTES                  to limitBytesField,
            ConsumeLimit.PER_PARTITION_RECORD_COUNT to limitPartRecordsField,
            ConsumeLimit.PER_PARTITION_MAX_BYTES    to limitPartBytesField
        )
        limitGroup.selectedToggleProperty().addListener { _, _, toggle ->
            val selected = toggle?.userData as? ConsumeLimit
            limitFieldMap.forEach { (limit, field) -> field.isDisable = selected != limit }
        }

        filterField.textProperty().addListener { _, _, text -> applyFilter(text) }
        actionBtn.setOnAction { startConsuming() }
        simpleList.selectionModel.selectedItemProperty().addListener { _, _, msg ->
            if (msg != null) showDetail(msg)
        }
    }

    private fun currentSettings(): ConsumeSettings {
        val topic = topicCombo.value?.trim() ?: ""

        val from = fromGroup.selectedToggle?.userData as? ConsumeFrom ?: ConsumeFrom.LATEST
        val fromTimestamp = if (from == ConsumeFrom.SPECIFIC_DATETIME) {
            try {
                val dt = LocalDateTime.parse(specificDateField.text.trim().replace(" ", "T"))
                dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
        } else null

        val limit = limitGroup.selectedToggle?.userData as? ConsumeLimit ?: ConsumeLimit.NONE
        val limitValue: Long? = when (limit) {
            ConsumeLimit.NONE -> null
            ConsumeLimit.RECORD_COUNT -> limitRecordsField.text.trim().toLongOrNull()
            ConsumeLimit.SPECIFIC_DATE -> try {
                val dt = LocalDateTime.parse(limitDateField.text.trim().replace(" ", "T"))
                dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) { null }
            ConsumeLimit.MAX_BYTES               -> limitBytesField.text.trim().toLongOrNull()
            ConsumeLimit.PER_PARTITION_RECORD_COUNT -> limitPartRecordsField.text.trim().toLongOrNull()
            ConsumeLimit.PER_PARTITION_MAX_BYTES -> limitPartBytesField.text.trim().toLongOrNull()
        }

        return ConsumeSettings(
            topic            = topic,
            from             = from,
            limit            = limit,
            limitValue       = limitValue,
            keyDeserializer  = keyDeserBox.value,
            valueDeserializer = valueDeserBox.value,
            specificOffset   = specificOffsetField.text.trim().toLongOrNull(),
            specificTimestamp = fromTimestamp,
            consumerGroup    = consumerGroupField.text.trim().takeIf { it.isNotBlank() }
        )
    }

    private fun startConsuming() {
        val settings = currentSettings()
        if (settings.topic.isBlank()) { statusLabel.text = "Enter a topic name"; return }

        // Warn if selected topic isn't in the known topic list
        if (topicCombo.items.isNotEmpty() && settings.topic !in topicCombo.items) {
            statusLabel.text = "Warning: '${settings.topic}' not found in cluster topic list"
        }

        allMessages.clear(); messageItems.clear(); messageDetail.clear()
        progressIndicator.isVisible = true
        setActionBtn(running = true)
        setControlsDisabled(true)
        collapseLeftPanel()            // auto-collapse settings when consumption starts
        statusLabel.text = "Consuming from ${settings.topic}…"
        countLabel.text  = ""

        val svc = KafkaConsumerService(cluster)
        consumerService = svc

        Thread {
            svc.consume(
                settings   = settings,
                onMessage  = { msg ->
                    Platform.runLater {
                        allMessages.add(msg)
                        applyFilter(filterField.text)
                        countLabel.text = "${allMessages.size} messages"
                    }
                },
                onComplete = {
                    Platform.runLater {
                        progressIndicator.isVisible = false
                        setActionBtn(running = false)
                        setControlsDisabled(false)
                        statusLabel.text = "Done — ${allMessages.size} messages"
                        setStatus("Consumed ${allMessages.size} messages from ${settings.topic}")
                    }
                },
                onError    = { e ->
                    Platform.runLater {
                        progressIndicator.isVisible = false
                        setActionBtn(running = false)
                        setControlsDisabled(false)
                        statusLabel.text = "Error: ${e.message}"
                    }
                }
            )
        }.also { it.isDaemon = true }.start()
    }

    fun stopConsuming() {
        consumerService?.stopConsuming()
        progressIndicator.isVisible = false
        setActionBtn(running = false)
        setControlsDisabled(false)
        statusLabel.text = "Stopped"
    }

    private fun setActionBtn(running: Boolean) {
        if (running) {
            actionBtn.text = "Stop"
            actionBtn.graphic = FontIcon(FontAwesomeSolid.STOP_CIRCLE)
            actionBtn.styleClass.remove("accent")
            actionBtn.styleClass.add("danger")
            actionBtn.setOnAction { stopConsuming() }
        } else {
            actionBtn.text = "Start"
            actionBtn.graphic = FontIcon(FontAwesomeSolid.PLAY_CIRCLE)
            actionBtn.styleClass.remove("danger")
            actionBtn.styleClass.add("accent")
            actionBtn.setOnAction { startConsuming() }
        }
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
            if (msg.headers.isNotEmpty()) {
                appendLine()
                appendLine("--- Headers ---")
                msg.headers.forEach { (k, v) -> appendLine("$k: $v") }
            }
            appendLine()
            appendLine("--- Key ---")
            appendLine(msg.key ?: "(null)")
            appendLine()
            appendLine("--- Value ---")
            appendLine(msg.value ?: "(null)")
        }
    }
}
