package com.freeconductor.ui.topics

import atlantafx.base.controls.ToggleSwitch
import com.freeconductor.ui.util.applyAppIcon
import javafx.application.Platform
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

class CreateTopicDialog(private val brokerCount: Int = 1) : Dialog<CreateTopicRequest>() {

    private val nameField = TextField().apply {
        promptText = "My new Topic name"
    }

    private val partitionsSpinner = Spinner<Int>(1, 10_000, 3).apply {
        isEditable = true
        prefWidth = 90.0
    }

    private val replicationSpinner = Spinner<Int>(1, brokerCount, 1).apply {
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
        promptText = "key=value (one per line)"
        prefHeight = 110.0
    }

    init {
        title = "Create New Topic"
        headerText = null
        var collapsedHeight = 0.0
        setOnShown { collapsedHeight = dialogPane.scene?.window?.height ?: 0.0 }
        applyAppIcon()

        updateHint(partitionsSpinner.value, replicationSpinner.value)
        partitionsSpinner.valueProperty().addListener { _, _, n -> updateHint(n, replicationSpinner.value) }
        replicationSpinner.valueProperty().addListener { _, _, n -> updateHint(partitionsSpinner.value, n) }

        val content = VBox(14.0).apply {
            padding = Insets(16.0, 20.0, 8.0, 20.0)

            children.addAll(
                formRow("Name", nameField),

                HBox(16.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("Partitions").apply { minWidth = 150.0 },
                        partitionsSpinner
                    )
                },

                HBox(16.0).apply {
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
                ).apply {
                    isExpanded = false
                    isAnimated = false
                    styleClass.add("borderless-titled-pane")
                    expandedProperty().addListener { _, _, expanded ->
                        val window = dialogPane.scene?.window ?: return@addListener
                        if (expanded) window.height = 600.0
                        else if (collapsedHeight > 0) window.height = collapsedHeight
                    }
                }
            )
        }

        dialogPane.content = content
        dialogPane.prefWidth = 800.0
        dialogPane.minWidth = 800.0

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

    private fun updateHint(partitions: Int, replicationFactor: Int) {
        partitionsHint.text = if (replicationFactor <= 1)
            "You will create $partitions new partitions on your cluster"
        else
            "You will create $partitions new partitions, replicated $replicationFactor times"
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
