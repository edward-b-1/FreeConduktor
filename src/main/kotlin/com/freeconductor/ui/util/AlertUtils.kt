package com.freeconductor.ui.util

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.TextArea
import javafx.scene.layout.GridPane
import javafx.scene.layout.Priority
import javafx.stage.Window
import java.io.PrintWriter
import java.io.StringWriter
import java.util.Optional

object AlertUtils {

    private fun focusedWindow() = Window.getWindows().find { it.isFocused }

    fun showError(title: String, message: String, exception: Exception? = null) {
        Platform.runLater {
            val alert = Alert(Alert.AlertType.ERROR)
            alert.initOwner(focusedWindow())
            alert.title = title
            alert.headerText = message

            if (exception != null) {
                val sw = StringWriter()
                exception.printStackTrace(PrintWriter(sw))
                val exceptionText = sw.toString()

                val textArea = TextArea(exceptionText)
                textArea.isEditable = false
                textArea.isWrapText = true
                textArea.maxWidth = Double.MAX_VALUE
                textArea.maxHeight = Double.MAX_VALUE
                GridPane.setVgrow(textArea, Priority.ALWAYS)
                GridPane.setHgrow(textArea, Priority.ALWAYS)

                val expContent = GridPane()
                expContent.maxWidth = Double.MAX_VALUE
                expContent.add(textArea, 0, 0)

                alert.dialogPane.expandableContent = expContent
            }
            alert.showAndWait()
        }
    }

    fun showInfo(title: String, message: String) {
        Platform.runLater {
            val alert = Alert(Alert.AlertType.INFORMATION)
            alert.initOwner(focusedWindow())
            alert.title = title
            alert.headerText = null
            alert.contentText = message
            alert.showAndWait()
        }
    }

    fun showWarning(title: String, message: String) {
        Platform.runLater {
            val alert = Alert(Alert.AlertType.WARNING)
            alert.initOwner(focusedWindow())
            alert.title = title
            alert.headerText = null
            alert.contentText = message
            alert.showAndWait()
        }
    }

    fun showConfirmation(title: String, message: String): Boolean {
        val alert = Alert(Alert.AlertType.CONFIRMATION)
        alert.initOwner(focusedWindow())
        alert.title = title
        alert.headerText = null
        alert.contentText = message
        val result: Optional<ButtonType> = alert.showAndWait()
        return result.isPresent && result.get() == ButtonType.OK
    }

    fun runBackground(task: () -> Unit, onError: (Exception) -> Unit = { e ->
        showError("Error", e.message ?: "An error occurred", e)
    }) {
        Thread {
            try {
                task()
            } catch (e: Exception) {
                onError(e)
            }
        }.also { it.isDaemon = true }.start()
    }
}
