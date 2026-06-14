package com.freeconductor.ui.consume

import com.freeconductor.model.MessageRecord
import javafx.beans.property.BooleanProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import java.time.Instant
import java.time.format.DateTimeFormatter

class SimpleMessageCell(
    private val formatter: DateTimeFormatter,
    private val monospaceFont: BooleanProperty
) : ListCell<MessageRecord>() {

    private val metaLabel = Label()
    private val row1 = HBox(metaLabel).apply {
        alignment = Pos.CENTER_LEFT
    }

    // isWrapText works here because the ListView uses fixedCellSize, which means
    // cell sizing goes through resize(w, h) rather than prefHeight(-1).  The label
    // therefore receives a real width and wraps correctly.
    private val valueLabel = Label().apply {
        isWrapText = true
        maxWidth   = Double.MAX_VALUE
    }

    private val box = VBox(2.0, row1, valueLabel).apply {
        padding = Insets(6.0, 8.0, 6.0, 8.0)
        VBox.setVgrow(valueLabel, Priority.ALWAYS)
    }

    init {
        prefWidth = 0.0
    }

    override fun updateItem(msg: MessageRecord?, empty: Boolean) {
        super.updateItem(msg, empty)
        if (empty || msg == null) {
            graphic = null
            text    = null
            return
        }

        val mono       = monospaceFont.get()
        val baseFont   = if (mono) "-fx-font-family: monospace;" else ""
        val mutedStyle = "$baseFont-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: -color-fg-muted;"
        val valueStyle = "$baseFont-fx-font-size: 12px;"

        metaLabel.style  = mutedStyle
        valueLabel.style = valueStyle

        val ts = formatter.format(Instant.ofEpochMilli(msg.timestamp))
        metaLabel.text = buildString {
            append(ts)
            append(" (P:${msg.partition} #${msg.offset})")
            if (msg.key != null) append(" ${msg.key}")
        }

        // Collapse internal newlines so the value flows as a single paragraph.
        // Cap at 2000 chars to avoid rendering huge payloads.
        val raw = (msg.value ?: "(null)").replace('\n', ' ').replace('\r', ' ')
        valueLabel.text = raw.take(2000)

        graphic = box
        text    = null
    }
}
