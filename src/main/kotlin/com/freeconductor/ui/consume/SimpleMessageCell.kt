package com.freeconductor.ui.consume

import com.freeconductor.model.MessageRecord
import javafx.beans.property.BooleanProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import java.time.Instant
import java.time.format.DateTimeFormatter

class SimpleMessageCell(
    private val formatter: DateTimeFormatter,
    private val monospaceFont: BooleanProperty
) : ListCell<MessageRecord>() {

    private val metaLabel = Label()
    private val keyLabel  = Label()
    private val row1 = HBox(8.0, metaLabel, keyLabel).apply { alignment = Pos.CENTER_LEFT }
    private val valueLabel = Label().apply { isWrapText = false }
    private val box = VBox(2.0, row1, valueLabel).apply {
        padding = Insets(6.0, 8.0, 6.0, 8.0)
    }

    override fun updateItem(msg: MessageRecord?, empty: Boolean) {
        super.updateItem(msg, empty)
        if (empty || msg == null) {
            graphic = null
            text = null
            return
        }

        val mono = monospaceFont.get()
        val baseFont   = if (mono) "-fx-font-family: monospace;" else ""
        val mutedStyle = "$baseFont -fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;"
        val keyStyle   = "$baseFont -fx-font-size: 12px;"
        val valueStyle = "$baseFont -fx-font-size: 12px;"

        metaLabel.style = mutedStyle
        keyLabel.style  = keyStyle
        valueLabel.style = valueStyle

        metaLabel.text = "${formatter.format(Instant.ofEpochMilli(msg.timestamp))}  (P:${msg.partition}  #${msg.offset})"

        if (msg.key != null) {
            keyLabel.text = msg.key
            keyLabel.isVisible = true
            keyLabel.isManaged = true
        } else {
            keyLabel.isVisible = false
            keyLabel.isManaged = false
        }

        val raw = msg.value ?: "(null)"
        val preview = raw.replace('\n', ' ').replace('\r', ' ')
        valueLabel.text = if (preview.length > 200) preview.substring(0, 200) + "…" else preview

        graphic = box
        text = null
    }
}
