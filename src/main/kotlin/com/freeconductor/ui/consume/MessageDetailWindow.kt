package com.freeconductor.ui.consume

import com.freeconductor.model.MessageRecord
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.*
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.time.format.DateTimeFormatter

class MessageDetailWindow(msg: MessageRecord, formatter: DateTimeFormatter) {
    private val stage = Stage()

    init {
        val tabPane = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
            tabs.addAll(buildDataTab(msg), buildMetadataTab(msg, formatter))
        }
        VBox.setVgrow(tabPane, Priority.ALWAYS)

        val root = VBox(tabPane, buildBottomBar(msg, formatter))
        VBox.setVgrow(tabPane, Priority.ALWAYS)

        val scene = Scene(root, 680.0, 580.0)
        scene.stylesheets.add(
            MessageDetailWindow::class.java.getResource("/com/freeconductor/styles.css")!!.toExternalForm()
        )

        stage.title = "Message — ${msg.topic} / Partition ${msg.partition} / Offset ${msg.offset}"
        MessageDetailWindow::class.java
            .getResourceAsStream("/com/freeconductor/icons/free-conduktor-logo-32.png")
            ?.let { stage.icons.add(Image(it)) }
        stage.scene = scene
    }

    private fun buildDataTab(msg: MessageRecord): Tab {
        fun label(text: String) = Label(text).apply { styleClass.add("config-section-label") }

        val keyArea = TextArea(msg.key ?: "(null)").apply {
            isEditable = false; isWrapText = true
            styleClass.add("code-area"); prefHeight = 90.0; maxHeight = 90.0
        }

        val valueArea = TextArea(msg.value ?: "(null)").apply {
            isEditable = false; isWrapText = true
            styleClass.add("code-area")
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        val headerNode: Node = if (msg.headers.isEmpty()) {
            Label("No headers").apply {
                style = "-fx-text-fill: -color-fg-muted; -fx-padding: 4 0 0 0;"
            }
        } else {
            TableView<Map.Entry<String, String>>().apply {
                items.addAll(msg.headers.entries)
                prefHeight = 120.0; maxHeight = 120.0
                columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
                columns.addAll(
                    TableColumn<Map.Entry<String, String>, String>("Header").apply {
                        setCellValueFactory { SimpleStringProperty(it.value.key) }
                    },
                    TableColumn<Map.Entry<String, String>, String>("Value").apply {
                        setCellValueFactory { SimpleStringProperty(it.value.value) }
                    }
                )
            }
        }

        val content = VBox(8.0).apply {
            padding = Insets(12.0)
            VBox.setVgrow(this, Priority.ALWAYS)
            children.addAll(label("KEY"), keyArea, label("VALUE"), valueArea, label("HEADERS"), headerNode)
        }
        VBox.setVgrow(valueArea, Priority.ALWAYS)

        return Tab("Data", content)
    }

    private fun buildMetadataTab(msg: MessageRecord, formatter: DateTimeFormatter): Tab {
        val grid = GridPane().apply {
            hgap = 16.0; vgap = 12.0
            padding = Insets(16.0)
            columnConstraints.addAll(
                ColumnConstraints(110.0, 130.0, 160.0),
                ColumnConstraints().also { it.hgrow = Priority.ALWAYS; it.isFillWidth = true }
            )
        }

        fun row(rowIdx: Int, label: String, value: String) {
            grid.add(Label(label).apply {
                style = "-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;"
            }, 0, rowIdx)
            grid.add(Label(value).apply {
                isWrapText = true; style = "-fx-font-size: 12px;"
            }, 1, rowIdx)
        }

        row(0, "Topic",          msg.topic)
        row(1, "Partition",      msg.partition.toString())
        row(2, "Offset",         msg.offset.toString())
        row(3, "Timestamp",      "${formatter.format(msg.timestampInstant)} (epoch: ${msg.timestamp})")
        row(4, "Timestamp Type", msg.timestampType)
        row(5, "Key Size",       formatSize(msg.keySize))
        row(6, "Value Size",     formatSize(msg.valueSize))

        return Tab("Metadata", grid)
    }

    private fun buildBottomBar(msg: MessageRecord, formatter: DateTimeFormatter): HBox {
        val copyToProducerBtn = Button("Copy to a Producer Template").apply {
            tooltip = Tooltip("Producer window coming soon")
            isDisable = true
        }

        val copyBtn = SplitMenuButton().apply {
            text = "Copy"
            styleClass.add("accent")
            setOnAction { saveAsFile(msg, formatter) }
            items.addAll(
                MenuItem("Save As…").apply          { setOnAction { saveAsFile(msg, formatter) } },
                MenuItem("Copy to Clipboard").apply { setOnAction { putOnClipboard(buildFullText(msg, formatter)) } }
            )
        }

        val closeBtn = Button("Close").apply { setOnAction { stage.close() } }

        return HBox(8.0).apply {
            padding = Insets(8.0, 12.0, 10.0, 12.0)
            alignment = Pos.CENTER_LEFT
            style = "-fx-border-color: -color-border-default; -fx-border-width: 1 0 0 0;"
            children.addAll(
                copyToProducerBtn,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                copyBtn,
                closeBtn
            )
        }
    }

    private fun saveAsFile(msg: MessageRecord, formatter: DateTimeFormatter) {
        val fc = FileChooser().apply {
            title = "Save Message"
            initialFileName = "${msg.topic}_p${msg.partition}_o${msg.offset}.txt"
            extensionFilters.addAll(
                FileChooser.ExtensionFilter("Text files (*.txt)", "*.txt"),
                FileChooser.ExtensionFilter("All files (*.*)", "*.*")
            )
        }
        fc.showSaveDialog(stage)?.writeText(buildFullText(msg, formatter))
    }

    private fun putOnClipboard(text: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().also { it.putString(text) })
    }

    private fun buildFullText(msg: MessageRecord, formatter: DateTimeFormatter) = buildString {
        appendLine("Topic:          ${msg.topic}")
        appendLine("Partition:      ${msg.partition}")
        appendLine("Offset:         ${msg.offset}")
        appendLine("Timestamp:      ${formatter.format(msg.timestampInstant)} (epoch: ${msg.timestamp})")
        appendLine("Timestamp Type: ${msg.timestampType}")
        appendLine("Key Size:       ${formatSize(msg.keySize)}")
        appendLine("Value Size:     ${formatSize(msg.valueSize)}")
        if (msg.headers.isNotEmpty()) {
            appendLine()
            appendLine("--- Headers ---")
            msg.headers.forEach { (k, v) -> appendLine("$k: $v") }
        }
        appendLine()
        appendLine("--- Key ---")
        appendLine(msg.key ?: "(null)")
        appendLine()
        appendLine("--- Value ---")
        append(msg.value ?: "(null)")
    }

    private fun formatSize(bytes: Int) = when {
        bytes == 0   -> "0 B"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else         -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    fun show() {
        stage.show()
        stage.toFront()
    }
}
