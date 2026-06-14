package com.freeconductor.ui.connect

import com.freeconductor.ui.util.applyAppIcon
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*

data class KafkaConnectCreateRequest(
    val name: String,
    val configJson: String
)

class KafkaConnectConfigDialog(
    private val existingName: String? = null,
    private val existingConfig: Map<String, String> = emptyMap()
) : Dialog<KafkaConnectCreateRequest>() {

    private val nameField = TextField(existingName ?: "")
    private val configArea = TextArea().apply {
        isWrapText = true
        prefHeight = 400.0
        styleClass.add("code-area")
    }

    init {
        title = if (existingName == null) "Create Connector" else "Edit Connector Config"
        headerText = if (existingName == null) "Create a new Kafka Connect connector" else "Edit configuration for: $existingName"
        applyAppIcon()

        nameField.promptText = "connector-name"
        nameField.isDisable = existingName != null

        // Populate config
        val configText = if (existingConfig.isNotEmpty()) {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(existingConfig)
        } else {
            """{
  "connector.class": "org.apache.kafka.connect.file.FileStreamSinkConnector",
  "tasks.max": "1",
  "topics": "my-topic",
  "file": "/tmp/output.txt"
}"""
        }
        configArea.text = configText

        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            children.addAll(
                Label("Connector Name:"),
                nameField,
                Label("Configuration (JSON):"),
                configArea
            )
            VBox.setVgrow(configArea, Priority.ALWAYS)
        }

        dialogPane.content = content
        dialogPane.prefWidth = 600.0
        dialogPane.prefHeight = 550.0

        val createButton = ButtonType(
            if (existingName == null) "Create" else "Save",
            ButtonBar.ButtonData.OK_DONE
        )
        dialogPane.buttonTypes.addAll(createButton, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            if (buttonType.buttonData == ButtonBar.ButtonData.OK_DONE) {
                KafkaConnectCreateRequest(
                    name = if (existingName != null) existingName else nameField.text.trim(),
                    configJson = configArea.text.trim()
                )
            } else null
        }
    }
}
