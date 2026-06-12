package com.freeconductor.ui.consume

import com.freeconductor.model.MessageRecord
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.*
import javafx.stage.Stage
import java.time.Instant
import java.time.format.DateTimeFormatter

class MessageDetailWindow(msg: MessageRecord, formatter: DateTimeFormatter) {
    private val stage = Stage()

    init {
        val metaText = buildString {
            appendLine("Topic:     ${msg.topic}")
            appendLine("Partition: ${msg.partition}    Offset: ${msg.offset}")
            appendLine("Timestamp: ${formatter.format(Instant.ofEpochMilli(msg.timestamp))}")
            append("Key size:  ${msg.keySize} bytes    Value size: ${msg.valueSize} bytes")
            if (msg.headers.isNotEmpty()) {
                appendLine(); appendLine()
                appendLine("Headers:")
                msg.headers.forEach { (k, v) -> appendLine("  $k: $v") }
            }
        }

        val metaArea = TextArea(metaText).apply {
            isEditable = false
            isWrapText = false
            styleClass.add("code-area")
            prefHeight = if (msg.headers.isNotEmpty()) 120.0 else 80.0
            maxHeight = prefHeight
        }

        val keyArea = TextArea(msg.key ?: "(null)").apply {
            isEditable = false
            isWrapText = true
            styleClass.add("code-area")
            prefHeight = 100.0
        }

        val valueArea = TextArea(msg.value ?: "(null)").apply {
            isEditable = false
            isWrapText = true
            styleClass.add("code-area")
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        fun sectionLabel(text: String) = Label(text).apply { styleClass.add("config-section-label") }

        val content = VBox(8.0).apply {
            padding = Insets(12.0, 12.0, 8.0, 12.0)
            VBox.setVgrow(this, Priority.ALWAYS)
            children.addAll(
                sectionLabel("METADATA"), metaArea,
                sectionLabel("KEY"),      keyArea,
                sectionLabel("VALUE"),    valueArea
            )
        }

        val bottomBar = buildBottomBar(msg, formatter)

        val root = VBox(content, bottomBar)
        VBox.setVgrow(content, Priority.ALWAYS)

        val scene = Scene(root, 640.0, 560.0)
        scene.stylesheets.add(
            MessageDetailWindow::class.java.getResource("/com/freeconductor/styles.css")!!.toExternalForm()
        )

        stage.title = "Message — ${msg.topic} / Partition ${msg.partition} / Offset ${msg.offset}"
        MessageDetailWindow::class.java
            .getResourceAsStream("/com/freeconductor/icons/free-conduktor-logo-32.png")
            ?.let { stage.icons.add(Image(it)) }
        stage.scene = scene
    }

    private fun buildBottomBar(msg: MessageRecord, formatter: DateTimeFormatter): HBox {
        val copyToProducerBtn = Button("Copy to a Producer Template").apply {
            tooltip = Tooltip("Producer window coming soon")
            isDisable = true
        }

        val copyBtn = SplitMenuButton().apply {
            text = "Copy"
            styleClass.add("accent")
            setOnAction { putOnClipboard(msg.value ?: "") }
            items.addAll(
                MenuItem("Copy Value").apply        { setOnAction { putOnClipboard(msg.value ?: "") } },
                MenuItem("Copy Key").apply          { setOnAction { putOnClipboard(msg.key ?: "") } },
                MenuItem("Copy Key & Value").apply  { setOnAction { putOnClipboard(buildKeyValue(msg)) } },
                MenuItem("Copy as JSON").apply      { setOnAction { putOnClipboard(buildJson(msg, formatter)) } }
            )
        }

        val closeBtn = Button("Close").apply {
            setOnAction { stage.close() }
        }

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

    private fun putOnClipboard(text: String) {
        Clipboard.getSystemClipboard().setContent(ClipboardContent().also { it.putString(text) })
    }

    private fun buildKeyValue(msg: MessageRecord) = buildString {
        appendLine("Key:   ${msg.key ?: "(null)"}")
        append("Value: ${msg.value ?: "(null)"}")
    }

    private fun buildJson(msg: MessageRecord, formatter: DateTimeFormatter) = buildString {
        appendLine("{")
        appendLine("  \"topic\": \"${msg.topic}\",")
        appendLine("  \"partition\": ${msg.partition},")
        appendLine("  \"offset\": ${msg.offset},")
        appendLine("  \"timestamp\": \"${formatter.format(Instant.ofEpochMilli(msg.timestamp))}\",")
        appendLine("  \"key\": ${jsonString(msg.key)},")
        appendLine("  \"value\": ${jsonString(msg.value)},")
        appendLine("  \"headers\": {")
        msg.headers.entries.forEachIndexed { i, (k, v) ->
            val comma = if (i < msg.headers.size - 1) "," else ""
            appendLine("    ${jsonString(k)}: ${jsonString(v)}$comma")
        }
        appendLine("  }")
        append("}")
    }

    private fun jsonString(s: String?) =
        if (s == null) "null" else "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    fun show() {
        stage.show()
        stage.toFront()
    }
}
