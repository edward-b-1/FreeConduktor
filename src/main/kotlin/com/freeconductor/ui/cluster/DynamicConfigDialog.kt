package com.freeconductor.ui.cluster

import com.freeconductor.model.BrokerConfigEntry
import com.freeconductor.model.BrokerInfo
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

class DynamicConfigDialog(
    private val broker: BrokerInfo,
    private val entry: BrokerConfigEntry,
    private val adminService: KafkaAdminService,
    private val onConfigChanged: () -> Unit,
    private val ownerWindow: Window?
) {
    fun show() {
        val description = BrokerPropertyDescriptions.get(entry.name)

        // ── Title ─────────────────────────────────────────────────────────
        val titleLabel = Label(entry.name).apply {
            style = "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -color-accent-fg;"
            isWrapText = true
        }
        val titleBox: VBox = if (description.isNotEmpty()) {
            val descLabel = Label(description).apply {
                isWrapText = true
                style = "-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;"
            }
            VBox(4.0, titleLabel, descLabel)
        } else {
            VBox(titleLabel)
        }
        titleBox.padding = Insets(0.0, 0.0, 16.0, 0.0)

        // ── Fields ────────────────────────────────────────────────────────
        fun readOnlyField(value: String) = TextField(value.ifEmpty { "—" }).apply {
            isEditable = false
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(this, Priority.ALWAYS)
            style = "-fx-background-color: -color-bg-subtle; -fx-text-fill: -color-fg-muted;"
        }
        fun fieldRow(labelText: String, field: javafx.scene.Node) = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(3.0, 0.0, 3.0, 0.0)
            children.addAll(
                Label(labelText).apply { prefWidth = 160.0; minWidth = 160.0 },
                field
            )
        }

        val newValueField = TextField(entry.value).apply {
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(this, Priority.ALWAYS)
        }

        val fieldsBox = VBox(4.0,
            fieldRow("Kafka Default Value", readOnlyField(entry.defaultValue ?: "—")),
            fieldRow("Current Value",       readOnlyField(entry.value)),
            fieldRow("New Value to set",    newValueField)
        ).apply { padding = Insets(0.0, 0.0, 14.0, 0.0) }

        // ── Cluster-wide checkbox ─────────────────────────────────────────
        val clusterWideCheck = CheckBox("CLUSTER-WIDE (all brokers)").apply {
            padding = Insets(0.0, 0.0, 16.0, 0.0)
        }

        // ── Buttons ───────────────────────────────────────────────────────
        val cancelBtn   = Button("CANCEL")
        val deleteBtn   = Button("DELETE").apply {
            styleClass.add("danger")
            graphic = FontIcon(FontAwesomeSolid.TRASH).also { it.iconSize = 12 }
            // Can only DELETE an existing dynamic override (broker-specific or cluster-wide)
            isDisable = entry.overrideSource !in setOf("BROKER", "CLUSTER")
        }
        val overrideBtn = Button("OVERRIDE").apply { styleClass.add("accent") }

        val btnRow = HBox(8.0, cancelBtn, deleteBtn, overrideBtn).apply {
            alignment = Pos.CENTER_RIGHT
            padding = Insets(8.0, 0.0, 0.0, 0.0)
        }

        // ── Layout ────────────────────────────────────────────────────────
        val spacer = Region().also { VBox.setVgrow(it, Priority.ALWAYS) }
        val content = VBox(titleBox, fieldsBox, clusterWideCheck, spacer, btnRow).apply {
            padding = Insets(20.0)
        }

        val stage = Stage().apply {
            title = "Dynamic configuration"
            isResizable = false
            initModality(Modality.APPLICATION_MODAL)
            ownerWindow?.let { initOwner(it) }
            DynamicConfigDialog::class.java
                .getResourceAsStream("/com/freeconductor/icons/free-conduktor-logo-32.png")
                ?.let { icons.setAll(javafx.scene.image.Image(it)) }
            scene = Scene(content, 480.0, 360.0)
        }

        cancelBtn.setOnAction { stage.close() }

        fun runAdminOp(newValue: String?) {
            overrideBtn.isDisable = true
            deleteBtn.isDisable  = true
            Thread {
                try {
                    adminService.alterBrokerConfig(
                        brokerId     = broker.id,
                        propertyName = entry.name,
                        newValue     = newValue,
                        clusterWide  = clusterWideCheck.isSelected
                    )
                    Platform.runLater {
                        onConfigChanged()
                        stage.close()
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        overrideBtn.isDisable = false
                        deleteBtn.isDisable   = entry.overrideSource !in setOf("BROKER", "CLUSTER")
                        Alert(Alert.AlertType.ERROR).apply {
                            title       = "Error"
                            headerText  = if (newValue != null) "Failed to set override" else "Failed to delete override"
                            contentText = e.message
                            initOwner(stage)
                            showAndWait()
                        }
                    }
                }
            }.also { it.isDaemon = true }.start()
        }

        deleteBtn.setOnAction  { runAdminOp(null) }
        overrideBtn.setOnAction { runAdminOp(newValueField.text.trim()) }

        stage.showAndWait()
    }
}
