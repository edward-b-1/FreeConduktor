package com.freeconductor.ui.cluster

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Circle

class AddClusterDialog(private val existing: ClusterConfig?) : Dialog<ClusterConfig>() {

    private val nameField = TextField(existing?.name ?: "")
    private val bootstrapField = TextField(existing?.bootstrapServers ?: "localhost:9092")
    private val securityProtocolBox = ComboBox<String>().apply {
        items.addAll("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")
        value = existing?.securityProtocol ?: "PLAINTEXT"
    }
    private val saslMechanismBox = ComboBox<String>().apply {
        items.addAll("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512", "GSSAPI")
        value = existing?.saslMechanism ?: "PLAIN"
    }
    private val saslUsernameField = TextField(existing?.saslUsername ?: "")
    private val saslPasswordField = PasswordField().apply { text = existing?.saslPassword ?: "" }
    private val sslTruststorePathField = TextField(existing?.sslTruststorePath ?: "")
    private val sslTruststorePasswordField = PasswordField().apply { text = existing?.sslTruststorePassword ?: "" }
    private val schemaRegistryUrlField = TextField(existing?.schemaRegistryUrl ?: "")
    private val schemaRegistryUsernameField = TextField(existing?.schemaRegistryUsername ?: "")
    private val schemaRegistryPasswordField = PasswordField().apply { text = existing?.schemaRegistryPassword ?: "" }
    private val kafkaConnectUrlField = TextField(existing?.kafkaConnectUrl ?: "")

    // Color picker — named presets matching Conduktor's palette
    companion object {
        val COLOR_OPTIONS = listOf(
            "None"   to null,
            "Red"    to "#e05252",
            "Orange" to "#e07825",
            "Yellow" to "#FBC14B",
            "Green"  to "#52b452",
            "Teal"   to "#52c8b4",
            "Blue"   to "#5285e0",
            "Purple" to "#8c52e0",
            "Pink"   to "#e052a0"
        )
    }

    private val colorBox = ComboBox<String>().apply {
        items.addAll(COLOR_OPTIONS.map { it.first })
        val current = COLOR_OPTIONS.indexOfFirst { it.second == existing?.color }
        value = if (current >= 0) COLOR_OPTIONS[current].first else "None"
        maxWidth = Double.MAX_VALUE
        setCellFactory { ColorListCell() }
        buttonCell = ColorListCell()
    }

    // Test connectivity status
    private val testResultLabel = Label("").apply {
        styleClass.add("test-result-label")
        isWrapText = true
    }

    init {
        title = if (existing == null) "Add Cluster" else "Edit Cluster: ${existing.name}"
        headerText = null

        val tabPane = TabPane()
        tabPane.tabs.addAll(
            buildKafkaTab(),
            buildSaslTab(),
            buildSslTab(),
            buildServicesTab()
        )
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE

        dialogPane.content = tabPane
        dialogPane.prefWidth = 560.0
        dialogPane.prefHeight = 420.0

        val saveButton = ButtonType("Save", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(saveButton, ButtonType.CANCEL)

        securityProtocolBox.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
            updateSaslVisibility(newVal)
        }
        updateSaslVisibility(securityProtocolBox.value)

        setResultConverter { buttonType ->
            if (buttonType.buttonData == ButtonBar.ButtonData.OK_DONE) buildClusterConfig() else null
        }
    }

    private fun updateSaslVisibility(protocol: String) {
        val isSasl = protocol.contains("SASL")
        saslMechanismBox.isDisable = !isSasl
        saslUsernameField.isDisable = !isSasl
        saslPasswordField.isDisable = !isSasl
    }

    private fun buildKafkaTab(): Tab {
        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 12.0
            padding = Insets(16.0)
        }

        nameField.promptText = "My Kafka Cluster"
        bootstrapField.promptText = "host1:9092,host2:9092"

        grid.addRow(0, Label("Cluster Name *"), nameField)
        grid.addRow(1, Label("Bootstrap Servers *"), bootstrapField)
        grid.addRow(2, Label("Security Protocol"), securityProtocolBox)
        grid.addRow(3, Label("Color"), colorBox)

        listOf(nameField, bootstrapField, securityProtocolBox, colorBox).forEach {
            GridPane.setHgrow(it, Priority.ALWAYS)
            (it as? Region)?.maxWidth = Double.MAX_VALUE
        }

        // ── Test connectivity ──────────────────────────────────────────
        val testBtn = Button("⚡ Test Kafka Connectivity").apply {
            styleClass.add("test-connectivity-btn")
            setOnAction { runConnectivityTest() }
        }

        val testRow = HBox(10.0, testBtn, testResultLabel).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 0.0, 0.0, 0.0)
        }
        HBox.setHgrow(testResultLabel, Priority.ALWAYS)

        grid.add(testRow, 0, 4, 2, 1)

        return Tab("Kafka Cluster", grid)
    }

    private fun runConnectivityTest() {
        testResultLabel.text = "Testing…"
        testResultLabel.styleClass.removeAll("test-ok", "test-fail")

        val config = buildClusterConfig()
        Thread {
            try {
                KafkaAdminService(config).use { svc ->
                    val clusterId = svc.getClusterInfo()
                    Platform.runLater {
                        testResultLabel.styleClass.removeAll("test-ok", "test-fail")
                        testResultLabel.styleClass.add("test-ok")
                        testResultLabel.text = "✔  Connected — cluster ID: $clusterId"
                    }
                }
            } catch (e: Exception) {
                Platform.runLater {
                    testResultLabel.styleClass.removeAll("test-ok", "test-fail")
                    testResultLabel.styleClass.add("test-fail")
                    testResultLabel.text = "✘  ${e.message}"
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun buildSaslTab(): Tab {
        val grid = GridPane().apply { hgap = 12.0; vgap = 12.0; padding = Insets(16.0) }
        grid.addRow(0, Label("SASL Mechanism"), saslMechanismBox)
        grid.addRow(1, Label("Username"), saslUsernameField)
        grid.addRow(2, Label("Password"), saslPasswordField)
        saslMechanismBox.maxWidth = Double.MAX_VALUE
        listOf(saslMechanismBox, saslUsernameField, saslPasswordField).forEach {
            GridPane.setHgrow(it, Priority.ALWAYS)
        }
        return Tab("SASL", grid)
    }

    private fun buildSslTab(): Tab {
        val grid = GridPane().apply { hgap = 12.0; vgap = 12.0; padding = Insets(16.0) }
        grid.addRow(0, Label("Truststore Path"), sslTruststorePathField)
        grid.addRow(1, Label("Truststore Password"), sslTruststorePasswordField)
        sslTruststorePathField.promptText = "/path/to/truststore.jks"
        listOf(sslTruststorePathField, sslTruststorePasswordField).forEach {
            GridPane.setHgrow(it, Priority.ALWAYS)
        }
        return Tab("SSL", grid)
    }

    private fun buildServicesTab(): Tab {
        val grid = GridPane().apply { hgap = 12.0; vgap = 12.0; padding = Insets(16.0) }
        grid.addRow(0, Label("Schema Registry URL"), schemaRegistryUrlField)
        grid.addRow(1, Label("SR Username"), schemaRegistryUsernameField)
        grid.addRow(2, Label("SR Password"), schemaRegistryPasswordField)
        grid.addRow(3, Label("Kafka Connect URL"), kafkaConnectUrlField)
        schemaRegistryUrlField.promptText = "http://localhost:8081"
        kafkaConnectUrlField.promptText = "http://localhost:8083"
        listOf(schemaRegistryUrlField, schemaRegistryUsernameField, schemaRegistryPasswordField, kafkaConnectUrlField)
            .forEach { GridPane.setHgrow(it, Priority.ALWAYS) }
        return Tab("Services", grid)
    }

    private fun buildClusterConfig(): ClusterConfig {
        val selectedColor = COLOR_OPTIONS.find { it.first == colorBox.value }?.second
        return ClusterConfig(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = nameField.text.trim(),
            bootstrapServers = bootstrapField.text.trim(),
            securityProtocol = securityProtocolBox.value,
            saslMechanism = saslMechanismBox.value.takeIf { !saslMechanismBox.isDisable },
            saslUsername = saslUsernameField.text.trim().takeIf { it.isNotBlank() && !saslUsernameField.isDisable },
            saslPassword = saslPasswordField.text.takeIf { it.isNotBlank() && !saslPasswordField.isDisable },
            sslTruststorePath = sslTruststorePathField.text.trim().takeIf { it.isNotBlank() },
            sslTruststorePassword = sslTruststorePasswordField.text.takeIf { it.isNotBlank() },
            schemaRegistryUrl = schemaRegistryUrlField.text.trim().takeIf { it.isNotBlank() },
            schemaRegistryUsername = schemaRegistryUsernameField.text.trim().takeIf { it.isNotBlank() },
            schemaRegistryPassword = schemaRegistryPasswordField.text.takeIf { it.isNotBlank() },
            kafkaConnectUrl = kafkaConnectUrlField.text.trim().takeIf { it.isNotBlank() },
            color = selectedColor
        )
    }

    /** List cell that renders a colored circle + label for each color option. */
    private inner class ColorListCell : ListCell<String>() {
        override fun updateItem(item: String?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) { graphic = null; text = null; return }
            val hex = COLOR_OPTIONS.find { it.first == item }?.second
            graphic = if (hex != null) {
                Circle(6.0, Color.web(hex)).also { it.styleClass.add("color-dot") }
            } else {
                Circle(6.0, Color.TRANSPARENT).apply {
                    stroke = Color.GRAY; strokeWidth = 1.0
                }
            }
            text = item
        }
    }
}
