package com.freeconductor.ui.consume

import com.freeconductor.model.*
import com.freeconductor.ui.util.applyAppIcon
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import java.time.LocalDateTime
import java.time.ZoneId

class ConsumeSettingsDialog(
    private val topicName: String,
    private val current: ConsumeSettings?
) : Dialog<ConsumeSettings>() {

    private val fromBox = ComboBox<ConsumeFrom>().apply {
        items.addAll(ConsumeFrom.values())
        value = current?.from ?: ConsumeFrom.LATEST
    }
    private val maxMessagesField = TextField((current?.limitValue ?: 100).toString())
    private val specificOffsetField = TextField((current?.specificOffset ?: 0L).toString())
    private val specificDatetimeField = TextField(
        current?.specificTimestamp?.let {
            java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())
                .toLocalDateTime().toString().replace("T", " ")
        } ?: ""
    )
    private val consumerGroupField = TextField(current?.consumerGroup ?: "")
    private val partitionField = TextField(current?.partitionFilter?.toString() ?: "")
    private val keyDeserBox = ComboBox<Deserializer>().apply {
        items.addAll(Deserializer.values())
        value = current?.keyDeserializer ?: Deserializer.STRING
    }
    private val valueDeserBox = ComboBox<Deserializer>().apply {
        items.addAll(Deserializer.values())
        value = current?.valueDeserializer ?: Deserializer.JSON
    }

    init {
        title = "Consume Settings"
        headerText = "Configure message consumption for: $topicName"
        applyAppIcon()

        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            padding = Insets(16.0)
        }

        fromBox.maxWidth = Double.MAX_VALUE
        keyDeserBox.maxWidth = Double.MAX_VALUE
        valueDeserBox.maxWidth = Double.MAX_VALUE

        grid.addRow(0, Label("Consume From:"), fromBox)
        grid.addRow(1, Label("Max Messages:"), maxMessagesField)
        grid.addRow(2, Label("Specific Offset:"), specificOffsetField)
        grid.addRow(3, Label("Datetime (YYYY-MM-DD HH:mm):"), specificDatetimeField)
        grid.addRow(4, Label("Consumer Group:"), consumerGroupField)
        grid.addRow(5, Label("Partition Filter:"), partitionField)
        grid.addRow(6, Label("Key Deserializer:"), keyDeserBox)
        grid.addRow(7, Label("Value Deserializer:"), valueDeserBox)

        for (col in listOf(fromBox, maxMessagesField, specificOffsetField, specificDatetimeField,
            consumerGroupField, partitionField, keyDeserBox, valueDeserBox)) {
            GridPane.setHgrow(col, Priority.ALWAYS)
        }

        specificDatetimeField.promptText = "2024-01-15 10:30:00"
        partitionField.promptText = "Leave blank for all partitions"
        consumerGroupField.promptText = "my-consumer-group"

        updateFieldVisibility(fromBox.value)
        fromBox.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
            updateFieldVisibility(newVal)
        }

        dialogPane.content = grid
        dialogPane.prefWidth = 500.0

        val consumeButton = ButtonType("Start Consuming", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(consumeButton, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            if (buttonType.buttonData == ButtonBar.ButtonData.OK_DONE) {
                buildSettings()
            } else null
        }
    }

    private fun updateFieldVisibility(from: ConsumeFrom) {
        specificOffsetField.isDisable = from != ConsumeFrom.SPECIFIC_OFFSET
        specificDatetimeField.isDisable = from != ConsumeFrom.SPECIFIC_DATETIME
        consumerGroupField.isDisable = from != ConsumeFrom.CONSUMER_GROUP
    }

    private fun buildSettings(): ConsumeSettings {
        val timestamp = if (fromBox.value == ConsumeFrom.SPECIFIC_DATETIME) {
            try {
                val dt = LocalDateTime.parse(specificDatetimeField.text.trim().replace(" ", "T"))
                dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        } else null

        return ConsumeSettings(
            topic = topicName,
            from = fromBox.value,
            limitValue = maxMessagesField.text.trim().toLongOrNull() ?: 100,
            keyDeserializer = keyDeserBox.value,
            valueDeserializer = valueDeserBox.value,
            specificOffset = specificOffsetField.text.trim().toLongOrNull(),
            specificTimestamp = timestamp,
            consumerGroup = consumerGroupField.text.trim().takeIf { it.isNotBlank() },
            partitionFilter = partitionField.text.trim().toIntOrNull()
        )
    }
}
