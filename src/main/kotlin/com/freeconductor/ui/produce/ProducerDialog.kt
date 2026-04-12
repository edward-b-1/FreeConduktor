package com.freeconductor.ui.produce

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaProducerService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Modality

class ProducerDialog(
    private val cluster: ClusterConfig,
    private val initialTopic: String? = null
) : Dialog<Unit>() {

    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val topicField = TextField(initialTopic ?: "")
    private val partitionField = TextField()
    private val keyFormatBox = ComboBox<String>().apply {
        items.addAll("STRING", "JSON", "INTEGER", "LONG")
        value = "STRING"
    }
    private val valueFormatBox = ComboBox<String>().apply {
        items.addAll("STRING", "JSON", "INTEGER", "LONG")
        value = "STRING"
    }
    private val keyArea = TextArea().apply {
        promptText = "Message key (optional)"
        prefHeight = 80.0
        isWrapText = true
    }
    private val valueArea = TextArea().apply {
        promptText = "Message value"
        prefHeight = 160.0
        isWrapText = true
    }
    private val headersItems = FXCollections.observableArrayList<Pair<String, String>>()
    private val headersTable = TableView(headersItems)
    private val resultLabel = Label()
    private var producerService: KafkaProducerService? = null

    init {
        title = "Producer"
        headerText = "Send a message to Kafka"
        initModality(Modality.NONE)

        dialogPane.content = buildContent()
        dialogPane.prefWidth = 620.0
        dialogPane.prefHeight = 700.0

        val sendButton = ButtonType("Send", ButtonBar.ButtonData.APPLY)
        val closeButton = ButtonType.CLOSE
        dialogPane.buttonTypes.addAll(sendButton, closeButton)

        val sendBtn = dialogPane.lookupButton(sendButton)
        sendBtn.addEventFilter(javafx.event.ActionEvent.ACTION) { event ->
            event.consume()
            sendMessage()
        }

        setResultConverter { null }
        setOnCloseRequest { producerService?.close() }
    }

    private fun buildContent(): VBox {
        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 8.0
            padding = Insets(8.0)
        }

        topicField.promptText = "topic-name"
        partitionField.promptText = "Leave blank for auto"

        keyFormatBox.maxWidth = Double.MAX_VALUE
        valueFormatBox.maxWidth = Double.MAX_VALUE

        grid.addRow(0, Label("Topic:"), topicField)
        grid.addRow(1, Label("Partition:"), partitionField)
        grid.addRow(2, Label("Key Format:"), keyFormatBox)
        grid.addRow(3, Label("Value Format:"), valueFormatBox)

        for (node in listOf(topicField, partitionField, keyFormatBox, valueFormatBox)) {
            GridPane.setHgrow(node, Priority.ALWAYS)
        }

        // Headers section
        setupHeadersTable()
        val addHeaderButton = Button("+ Add Header").apply {
            setOnAction { addHeader() }
        }
        val removeHeaderButton = Button("Remove").apply {
            styleClass.add("danger")
            setOnAction {
                headersTable.selectionModel.selectedItem?.let {
                    headersItems.remove(it)
                }
            }
        }
        val headersToolbar = HBox(8.0, Label("Headers:"), Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            addHeaderButton, removeHeaderButton).apply {
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }

        headersTable.prefHeight = 100.0

        resultLabel.styleClass.add("result-label")

        return VBox(8.0).apply {
            padding = Insets(8.0)
            children.addAll(
                grid,
                Label("Key:"),
                keyArea,
                Label("Value:"),
                valueArea,
                headersToolbar,
                headersTable,
                resultLabel
            )
            VBox.setVgrow(valueArea, Priority.SOMETIMES)
        }
    }

    private fun setupHeadersTable() {
        val keyCol = TableColumn<Pair<String, String>, String>("Key").apply {
            setCellValueFactory { SimpleStringProperty(it.value.first) }
            prefWidth = 180.0
        }
        val valueCol = TableColumn<Pair<String, String>, String>("Value").apply {
            setCellValueFactory { SimpleStringProperty(it.value.second) }
            prefWidth = 280.0
        }
        headersTable.columns.addAll(keyCol, valueCol)
        headersTable.placeholder = Label("No headers")
    }

    private fun addHeader() {
        val dialog = Dialog<Pair<String, String>>()
        dialog.title = "Add Header"
        val keyField = TextField()
        keyField.promptText = "Header key"
        val valueField = TextField()
        valueField.promptText = "Header value"
        val grid = GridPane().apply {
            hgap = 8.0; vgap = 8.0; padding = Insets(12.0)
            addRow(0, Label("Key:"), keyField)
            addRow(1, Label("Value:"), valueField)
            GridPane.setHgrow(keyField, Priority.ALWAYS)
            GridPane.setHgrow(valueField, Priority.ALWAYS)
        }
        dialog.dialogPane.content = grid
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        dialog.setResultConverter { bt ->
            if (bt == ButtonType.OK) Pair(keyField.text.trim(), valueField.text.trim()) else null
        }
        dialog.showAndWait().ifPresent { pair ->
            if (pair.first.isNotBlank()) headersItems.add(pair)
        }
    }

    private fun sendMessage() {
        val topic = topicField.text.trim()
        if (topic.isBlank()) {
            resultLabel.text = "Error: Topic name is required"
            resultLabel.style = "-fx-text-fill: red;"
            return
        }

        val value = valueArea.text
        if (value.isBlank()) {
            resultLabel.text = "Error: Message value is required"
            resultLabel.style = "-fx-text-fill: red;"
            return
        }

        val headers = headersItems.associate { it.first to it.second }
        val partition = partitionField.text.trim().toIntOrNull()

        resultLabel.text = "Sending..."
        resultLabel.style = ""

        if (producerService == null) {
            producerService = KafkaProducerService(cluster)
        }

        val svc = producerService!!
        Thread {
            try {
                val offset = svc.send(
                    topic = topic,
                    key = keyArea.text.takeIf { it.isNotBlank() },
                    value = value,
                    keyFormat = keyFormatBox.value,
                    valueFormat = valueFormatBox.value,
                    partition = partition,
                    headers = headers
                )
                Platform.runLater {
                    resultLabel.text = "Sent successfully! Offset: $offset"
                    resultLabel.style = "-fx-text-fill: #4caf50;"
                }
            } catch (e: Exception) {
                Platform.runLater {
                    resultLabel.text = "Error: ${e.message}"
                    resultLabel.style = "-fx-text-fill: red;"
                }
            }
        }.also { it.isDaemon = true }.start()
    }

}
