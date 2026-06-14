package com.freeconductor.ui.topics

import atlantafx.base.controls.ToggleSwitch
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.ui.util.applyAppIcon
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleStringProperty
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

private val KAFKA_TOPIC_DEFAULTS = linkedMapOf(
    "min.insync.replicas"                    to "1",
    "retention.bytes"                        to "-1",
    "retention.ms"                           to "604800000",
    "compression.type"                       to "producer",
    "delete.retention.ms"                    to "86400000",
    "file.delete.delay.ms"                   to "60000",
    "flush.messages"                         to "9223372036854775807",
    "flush.ms"                               to "9223372036854775807",
    "index.interval.bytes"                   to "4096",
    "local.retention.bytes"                  to "-2",
    "local.retention.ms"                     to "-2",
    "max.compaction.lag.ms"                  to "9223372036854775807",
    "max.message.bytes"                      to "1048588",
    "message.downconversion.enable"          to "true",
    "message.format.version"                 to "",
    "message.timestamp.difference.max.ms"    to "9223372036854775807",
    "message.timestamp.type"                 to "CreateTime",
    "min.cleanable.dirty.ratio"              to "0.5",
    "min.compaction.lag.ms"                  to "0",
    "preallocate"                            to "false",
    "remote.storage.enable"                  to "false",
    "segment.bytes"                          to "1073741824",
    "segment.index.bytes"                    to "10485760",
    "segment.jitter.ms"                      to "0",
    "segment.ms"                             to "604800000",
    "unclean.leader.election.enable"         to "false",
    "leader.replication.throttled.replicas"  to "",
    "follower.replication.throttled.replicas" to ""
)

private class TopicConfigRow(val key: String, kafkaDefault: String) {
    val kafkaDefault  = SimpleStringProperty(kafkaDefault)
    val brokerOverride = SimpleStringProperty("-")
    val topicOverride  = SimpleStringProperty("")
}

class CreateTopicDialog(
    private val brokerCount: Int = 1,
    private val adminService: KafkaAdminService? = null
) : Dialog<CreateTopicRequest>() {

    private val nameField = TextField().apply { promptText = "My new Topic name" }

    private val partitionsSpinner = Spinner<Int>(1, 10_000, 3).apply {
        isEditable = true
        prefWidth = 90.0
    }

    private val replicationSpinner = Spinner<Int>(1, brokerCount, 1).apply {
        isEditable = true
        prefWidth = 90.0
    }

    private val retentionToggle  = ToggleSwitch("Retention (time or size)").apply { isSelected = true }
    private val compactionToggle = ToggleSwitch("Compaction (key-based)")

    private val partitionsHint = Label().apply { styleClass.add("description") }

    private val configRows = KAFKA_TOPIC_DEFAULTS.map { (k, v) -> TopicConfigRow(k, v) }
    private val configTable = buildConfigTable()

    init {
        title = "Create New Topic"
        headerText = null
        isResizable = true
        var collapsedHeight = 0.0
        setOnShown { collapsedHeight = dialogPane.scene?.window?.height ?: 0.0 }
        applyAppIcon()

        updateHint(partitionsSpinner.value, replicationSpinner.value)
        partitionsSpinner.valueProperty().addListener { _, _, n -> updateHint(n, replicationSpinner.value) }
        replicationSpinner.valueProperty().addListener { _, _, n -> updateHint(partitionsSpinner.value, n) }

        val advancedPane = TitledPane("Advanced Configuration",
            VBox(configTable).apply { padding = Insets(8.0, 0.0, 0.0, 0.0) }
        ).apply {
            isExpanded = false
            isAnimated = false
            styleClass.add("borderless-titled-pane")
            expandedProperty().addListener { _, _, expanded ->
                val window = dialogPane.scene?.window ?: return@addListener
                if (expanded) {
                    loadBrokerDefaults()
                    window.height = 620.0
                } else if (collapsedHeight > 0) {
                    window.height = collapsedHeight
                }
            }
        }

        val content = VBox(14.0).apply {
            padding = Insets(16.0, 20.0, 8.0, 20.0)
            children.addAll(
                formRow("Name", nameField),
                HBox(16.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(Label("Partitions").apply { minWidth = 150.0 }, partitionsSpinner)
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
                        retentionToggle, compactionToggle
                    )
                },
                advancedPane
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
                val configs = mutableMapOf<String, String>()
                val policies = buildList {
                    if (retentionToggle.isSelected) add("delete")
                    if (compactionToggle.isSelected) add("compact")
                }
                if (policies.isNotEmpty()) configs["cleanup.policy"] = policies.joinToString(",")
                configRows.forEach { row ->
                    val v = row.topicOverride.get().trim()
                    if (v.isNotEmpty()) configs[row.key] = v
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

    private fun buildConfigTable(): TableView<TopicConfigRow> {
        val propCol = TableColumn<TopicConfigRow, String>("Property").apply {
            setCellValueFactory { SimpleStringProperty(it.value.key) }
            setCellFactory {
                object : TableCell<TopicConfigRow, String>() {
                    private val link = Hyperlink().apply { isVisited = false }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; tooltip = null; return }
                        graphic = link.also { it.text = item }
                        tooltip = Tooltip(item)
                    }
                }
            }
            prefWidth = 220.0
        }

        val defaultCol = TableColumn<TopicConfigRow, String>("Kafka Default").apply {
            setCellValueFactory { it.value.kafkaDefault }
            prefWidth = 150.0
            style = "-fx-alignment: CENTER-RIGHT;"
        }

        val brokerCol = TableColumn<TopicConfigRow, String>("Broker Override").apply {
            setCellValueFactory { it.value.brokerOverride }
            prefWidth = 150.0
            style = "-fx-alignment: CENTER-RIGHT;"
        }

        val overrideCol = TableColumn<TopicConfigRow, TopicConfigRow>("Topic Override").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            setCellFactory {
                object : TableCell<TopicConfigRow, TopicConfigRow>() {
                    override fun updateItem(item: TopicConfigRow?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; return }
                        graphic = buildOverrideCell(item)
                    }
                }
            }
            prefWidth = 160.0
            style = "-fx-alignment: CENTER-RIGHT;"
        }

        val table = TableView<TopicConfigRow>().apply {
            columns.addAll(propCol, defaultCol, brokerCol, overrideCol)
            items.addAll(configRows)
            prefHeight = 300.0
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            placeholder = Label("")
            styleClass.add("config-table")
        }

        configRows.forEach { row ->
            row.topicOverride.addListener { _ -> table.refresh() }
            row.brokerOverride.addListener { _ -> table.refresh() }
        }

        return table
    }

    private fun buildOverrideCell(row: TopicConfigRow): HBox {
        val value = row.topicOverride.get().trim()
        val editLink = Hyperlink().apply {
            graphic = FontIcon(FontAwesomeSolid.PENCIL_ALT).also { it.iconSize = 11 }
            text = "Edit"
            isVisited = false
            setOnAction { editOverride(row) }
        }
        return if (value.isEmpty()) {
            HBox(editLink).apply { alignment = Pos.CENTER_RIGHT }
        } else {
            HBox(4.0, Label(value), editLink).apply { alignment = Pos.CENTER_RIGHT }
        }
    }

    private fun editOverride(row: TopicConfigRow) {
        val input = TextInputDialog(row.topicOverride.get()).apply {
            title = "Topic Override"
            headerText = row.key
            contentText = "Value:"
            applyAppIcon()
        }
        input.showAndWait().ifPresent { row.topicOverride.set(it.trim()) }
    }

    private fun loadBrokerDefaults() {
        val service = adminService ?: return
        Thread {
            try {
                val defaults = service.getBrokerTopicDefaults()
                Platform.runLater {
                    configRows.forEach { row ->
                        row.brokerOverride.set(defaults[row.key] ?: "-")
                    }
                }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true }.start()
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
}
