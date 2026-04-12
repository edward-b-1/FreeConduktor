package com.freeconductor.ui.consumergroups

import com.freeconductor.model.ConsumeFrom
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import java.time.LocalDateTime
import java.time.ZoneId

data class ResetOffsetsRequest(
    val strategy: ConsumeFrom,
    val specificOffset: Long?,
    val specificTimestamp: Long?,
    val topic: String?
)

class ResetOffsetsDialog(private val groupId: String) : Dialog<ResetOffsetsRequest>() {

    private val strategyBox = ComboBox<ConsumeFrom>().apply {
        items.addAll(ConsumeFrom.EARLIEST, ConsumeFrom.LATEST, ConsumeFrom.SPECIFIC_OFFSET, ConsumeFrom.SPECIFIC_DATETIME)
        value = ConsumeFrom.EARLIEST
    }
    private val topicField = TextField()
    private val specificOffsetField = TextField("0")
    private val specificDatetimeField = TextField()

    init {
        title = "Reset Consumer Group Offsets"
        headerText = "Reset offsets for consumer group: $groupId"

        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            padding = Insets(16.0)
        }

        strategyBox.maxWidth = Double.MAX_VALUE
        topicField.promptText = "Leave blank for all topics"
        specificDatetimeField.promptText = "2024-01-15 10:30:00"

        grid.addRow(0, Label("Reset Strategy:"), strategyBox)
        grid.addRow(1, Label("Topic Filter:"), topicField)
        grid.addRow(2, Label("Specific Offset:"), specificOffsetField)
        grid.addRow(3, Label("Datetime (YYYY-MM-DD HH:mm:ss):"), specificDatetimeField)

        for (node in listOf(strategyBox, topicField, specificOffsetField, specificDatetimeField)) {
            GridPane.setHgrow(node, Priority.ALWAYS)
        }

        updateFieldVisibility(strategyBox.value)
        strategyBox.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
            updateFieldVisibility(newVal)
        }

        dialogPane.content = grid
        dialogPane.prefWidth = 460.0

        val resetButton = ButtonType("Reset Offsets", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(resetButton, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            if (buttonType.buttonData == ButtonBar.ButtonData.OK_DONE) {
                val timestamp = if (strategyBox.value == ConsumeFrom.SPECIFIC_DATETIME) {
                    try {
                        val dt = LocalDateTime.parse(specificDatetimeField.text.trim().replace(" ", "T"))
                        dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: Exception) { null }
                } else null

                ResetOffsetsRequest(
                    strategy = strategyBox.value,
                    specificOffset = specificOffsetField.text.trim().toLongOrNull(),
                    specificTimestamp = timestamp,
                    topic = topicField.text.trim().takeIf { it.isNotBlank() }
                )
            } else null
        }
    }

    private fun updateFieldVisibility(strategy: ConsumeFrom) {
        specificOffsetField.isDisable = strategy != ConsumeFrom.SPECIFIC_OFFSET
        specificDatetimeField.isDisable = strategy != ConsumeFrom.SPECIFIC_DATETIME
    }
}
