package com.freeconductor.ui.topics

import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*

data class CreateTopicRequest(
    val name: String,
    val partitions: Int,
    val replicationFactor: Short,
    val configs: Map<String, String>
)

class CreateTopicDialog : Dialog<CreateTopicRequest>() {
    private val nameField = TextField()
    private val partitionsField = TextField("1")
    private val replicationField = TextField("1")
    private val configsArea = TextArea().apply {
        promptText = "retention.ms=604800000\ncleanup.policy=delete"
        prefHeight = 120.0
    }

    init {
        title = "Create Topic"
        headerText = "Create a new Kafka topic"

        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            padding = Insets(16.0)
        }

        nameField.promptText = "my-topic"
        grid.addRow(0, Label("Topic Name:"), nameField)
        grid.addRow(1, Label("Partitions:"), partitionsField)
        grid.addRow(2, Label("Replication Factor:"), replicationField)
        grid.addRow(3, Label("Config Overrides:"), configsArea)

        GridPane.setHgrow(nameField, Priority.ALWAYS)
        GridPane.setHgrow(partitionsField, Priority.ALWAYS)
        GridPane.setHgrow(replicationField, Priority.ALWAYS)
        GridPane.setHgrow(configsArea, Priority.ALWAYS)

        dialogPane.content = grid
        dialogPane.prefWidth = 480.0

        val createButton = ButtonType("Create", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(createButton, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            if (buttonType.buttonData == ButtonBar.ButtonData.OK_DONE) {
                val configs = parseConfigs(configsArea.text)
                CreateTopicRequest(
                    name = nameField.text.trim(),
                    partitions = partitionsField.text.trim().toIntOrNull() ?: 1,
                    replicationFactor = replicationField.text.trim().toShortOrNull() ?: 1,
                    configs = configs
                )
            } else null
        }
    }

    private fun parseConfigs(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                result[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
            }
        }
        return result
    }
}
