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
import javafx.stage.Stage

class ProducerDialog(
    private val cluster: ClusterConfig,
    private val initialTopic: String? = null,
    private val initialKey: String? = null,
    private val initialValue: String? = null,
    private val initialHeaders: Map<String, String> = emptyMap(),
    private val adminService: KafkaAdminService? = null
) {
    private val stage = Stage()

    private val formats = listOf("String", "JSON", "Int", "Long", "Float", "Double")

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

    private val outputItems = FXCollections.observableArrayList<String>()
    private val outputList  = ListView(outputItems).apply {
        placeholder = Label("No messages sent yet")
        VBox.setVgrow(this, Priority.ALWAYS)
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
        stage.setOnCloseRequest { producerService?.close() }

        sendBtn.setOnAction { sendMessage() }

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
            tabs.addAll(buildDataTab(), buildHeadersTab(), buildOptionsTab())
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val leftPane = VBox(topicRow, tabs).apply {
            VBox.setVgrow(tabs, Priority.ALWAYS)
        }

        val outputTitle = Label("OUTPUT").apply { styleClass.add("config-section-label") }
        val outputPane = VBox(8.0, outputTitle, outputList).apply {
            padding = Insets(10.0)
            VBox.setVgrow(outputList, Priority.ALWAYS)
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

    private fun sendMessage() {
        val topic = topicCombo.value?.trim() ?: ""
        if (topic.isBlank()) { appendOutput("Error: topic name is required", error = true); return }
        val value = valueArea.text
        if (value.isBlank()) { appendOutput("Error: message value is required", error = true); return }

        val svc       = producerService ?: KafkaProducerService(cluster).also { producerService = it }
        val headers   = headersItems.associate { it.first to it.second }
        val partition = partitionField.text.trim().toIntOrNull()

        Thread {
            try {
                val offset = svc.send(
                    topic       = topic,
                    key         = keyArea.text.takeIf { it.isNotBlank() },
                    value       = value,
                    keyFormat   = keyFormatBox.value.uppercase(),
                    valueFormat = valueFormatBox.value.uppercase(),
                    partition   = partition,
                    headers     = headers
                )
                Platform.runLater {
                    appendOutput("Sent  ✓  offset $offset  partition ${partition ?: "auto"}", error = false)
                }
            } catch (e: Exception) {
                Platform.runLater { appendOutput("Error: ${e.message}", error = true) }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun appendOutput(msg: String, error: Boolean) {
        outputItems.add(0, msg)
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
