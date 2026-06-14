package com.freeconductor.ui.schema

import com.freeconductor.ui.util.applyAppIcon
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*

data class SchemaRegistryConfig(
    val url: String,
    val username: String?,
    val password: String?
)

class SchemaRegistryConfigDialog(
    private val currentUrl: String?,
    private val currentUsername: String?,
    private val currentPassword: String?
) : Dialog<SchemaRegistryConfig>() {

    private val urlField = TextField(currentUrl ?: "http://localhost:8081")
    private val usernameField = TextField(currentUsername ?: "")
    private val passwordField = PasswordField().apply { text = currentPassword ?: "" }

    init {
        title = "Schema Registry Configuration"
        headerText = "Configure Schema Registry connection"
        applyAppIcon()

        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 10.0
            padding = Insets(16.0)
        }

        urlField.promptText = "http://localhost:8081"
        usernameField.promptText = "Optional"
        passwordField.promptText = "Optional"

        grid.addRow(0, Label("Schema Registry URL:"), urlField)
        grid.addRow(1, Label("Username:"), usernameField)
        grid.addRow(2, Label("Password:"), passwordField)

        GridPane.setHgrow(urlField, Priority.ALWAYS)
        GridPane.setHgrow(usernameField, Priority.ALWAYS)
        GridPane.setHgrow(passwordField, Priority.ALWAYS)

        dialogPane.content = grid
        dialogPane.prefWidth = 440.0

        val saveButton = ButtonType("Save", ButtonBar.ButtonData.OK_DONE)
        dialogPane.buttonTypes.addAll(saveButton, ButtonType.CANCEL)

        setResultConverter { buttonType ->
            if (buttonType.buttonData == ButtonBar.ButtonData.OK_DONE) {
                SchemaRegistryConfig(
                    url = urlField.text.trim(),
                    username = usernameField.text.trim().takeIf { it.isNotBlank() },
                    password = passwordField.text.takeIf { it.isNotBlank() }
                )
            } else null
        }
    }
}
