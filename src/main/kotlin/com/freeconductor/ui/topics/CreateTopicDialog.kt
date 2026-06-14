package com.freeconductor.ui.topics

import atlantafx.base.controls.ToggleSwitch
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

data class CreateTopicRequest(
    val name: String,
    val partitions: Int,
    val replicationFactor: Short,
    val configs: Map<String, String>
)

class CreateTopicDialog : Dialog<CreateTopicRequest>() {

    private val nameField = TextField().apply {
        promptText = "My new Topic name"
    }

    private val partitionsSpinner = Spinner<Int>(1, 10_000, 3).apply {
        isEditable = true
        prefWidth = 90.0
    }

    private val replicationSpinner = Spinner<Int>(1, 100, 1).apply {
        isEditable = true
        prefWidth = 90.0
    }

    private val retentionToggle = ToggleSwitch("Retention (time or size)").apply {
        isSelected = true
    }
    private val compactionToggle = ToggleSwitch("Compaction (key-based)")

    private val partitionsHint = Label().apply {
        styleClass.add("description")
    }

    private val advancedConfigArea = TextArea().apply {
        promptText = "retention.ms=604800000\ncleanup.policy=delete"
        prefHeight = 110.0
    }

    init {
        title = "Create New Topic"
        headerText = null

        updateHint(partitionsSpinner.value)
        partitionsSpinner.valueProperty().addListener { _, _, n -> updateHint(n) }

        val content = VBox(14.0).apply {
            padding = Insets(16.0, 20.0, 8.0, 20.0)
            prefWidth = 540.0

            children.addAll(
                formRow("Name", nameField),

                HBox(16.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("Partitions").apply { minWidth = 150.0 },
                        partitionsSpinner
                    )
                },

                HBox(12.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("Replication Factor").apply { minWidth = 150.0 },
                        replicationSpinner,
                        FontIcon(FontAwesomeSolid.INFO_CIRCLE).also { it.iconSize = 13 },
                        partitionsHint
                    )
                },

                HBox(16.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("Cleanup Policy").apply { minWidth = 150.0 },
                        retentionToggle,
                        compactionToggle
                    )
                },

                TitledPane(
                    "Advanced Configuration",
                    VBox(advancedConfigArea).apply { padding = Insets(8.0, 0.0, 0.0, 0.0) }
                ).apply { isExpanded = false }
            )
        }

        dialogPane.content = content

        val createBtn = ButtonType("CREATE TOPIC", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(createBtn, ButtonType.CANCEL)
        dialogPane.lookupButton(createBtn)?.styleClass?.add("accent")

        setResultConverter { bt ->
            if (bt.buttonData == ButtonBar.ButtonData.OK_DONE) {
                val configs = parseConfigs(advancedConfigArea.text).toMutableMap()
                if (!configs.containsKey("cleanup.policy")) {
                    val policies = buildList {
                        if (retentionToggle.isSelected) add("delete")
                        if (compactionToggle.isSelected) add("compact")
                    }.joinToString(",")
                    if (policies.isNotEmpty()) configs["cleanup.policy"] = policies
                }
                CreateTopicRequest(
                    name = nameField.text.trim(),
                    partitions = partitionsSpinner.value,
                    replicationFactor = replicationSpinner.value.toShort(),
                    configs = configs
                )
            } else null
        }
    }

    private fun updateHint(partitions: Int) {
        partitionsHint.text = "You will create $partitions new partitions on your cluster"
    }

    private fun formRow(labelText: String, field: Control) = HBox(16.0).apply {
        alignment = Pos.CENTER_LEFT
        children.addAll(
            Label(labelText).apply { minWidth = 150.0 },
            field.also { HBox.setHgrow(it, Priority.ALWAYS) }
        )
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
