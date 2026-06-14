package com.freeconductor.ui.topics

import atlantafx.base.controls.ToggleSwitch
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.ui.util.applyAppIcon
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleBooleanProperty
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

private val KAFKA_TOPIC_DESCRIPTIONS = mapOf(
    "min.insync.replicas"                     to "Minimum replicas that must acknowledge a write for it to succeed (used with acks=all)",
    "retention.bytes"                         to "Maximum size a partition can grow before old segments are discarded. -1 = unlimited",
    "retention.ms"                            to "How long messages are retained. -1 = forever",
    "compression.type"                        to "Compression codec for stored messages: producer, gzip, snappy, lz4, zstd, or uncompressed",
    "delete.retention.ms"                     to "How long delete tombstone markers are kept on compacted topics before being removed",
    "file.delete.delay.ms"                    to "Time to wait before deleting a segment file from disk after it is marked for deletion",
    "flush.messages"                          to "Number of messages written before forcing an fsync to disk. Higher values improve throughput",
    "flush.ms"                                to "Maximum time between forced fsyncs. Higher values improve throughput at the cost of durability",
    "index.interval.bytes"                    to "How frequently an offset entry is added to the segment index (roughly every N bytes)",
    "local.retention.bytes"                   to "Max bytes retained locally before older data is moved to remote (tiered) storage. -2 = use retention.bytes",
    "local.retention.ms"                      to "Max time data is kept locally before being offloaded to remote storage. -2 = use retention.ms",
    "max.compaction.lag.ms"                   to "Maximum time a message can remain ineligible for log compaction",
    "max.message.bytes"                       to "Largest compressed record batch the broker will accept for this topic",
    "message.downconversion.enable"           to "Allow the broker to down-convert message formats for older consumer clients",
    "message.format.version"                  to "Override the message format version written to the log (deprecated in Kafka 3.x)",
    "message.timestamp.difference.max.ms"     to "Max allowed difference between broker time and message timestamp before the message is rejected",
    "message.timestamp.type"                  to "Whether the timestamp is set by the producer (CreateTime) or stamped by the broker (LogAppendTime)",
    "min.cleanable.dirty.ratio"               to "Minimum ratio of dirty log to total log that triggers compaction to run",
    "min.compaction.lag.ms"                   to "Minimum time a message must remain uncompacted in the log",
    "preallocate"                             to "Pre-allocate log segment files on disk to avoid fragmentation",
    "remote.storage.enable"                   to "Enable tiered remote storage for this topic (requires remote storage to be configured on the broker)",
    "segment.bytes"                           to "Target size of a single log segment file. A new segment is rolled when this size is reached",
    "segment.index.bytes"                     to "Maximum size of the offset index for a single log segment",
    "segment.jitter.ms"                       to "Random jitter applied to segment roll time to avoid many segments rolling simultaneously",
    "segment.ms"                              to "Maximum time before the log is forced to roll to a new segment, even if segment.bytes is not reached",
    "unclean.leader.election.enable"          to "Allow out-of-sync replicas to become leader when no in-sync replica is available (risks data loss)",
    "leader.replication.throttled.replicas"   to "Comma-separated list of partition:broker pairs throttled on the leader side during reassignment",
    "follower.replication.throttled.replicas" to "Comma-separated list of partition:broker pairs throttled on the follower side during reassignment"
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

// Known-deprecated keys as a static fallback; updated live from broker documentation() on connect.
private val KAFKA_TOPIC_DEPRECATED_STATIC = setOf("message.format.version")

private class TopicConfigRow(val key: String, kafkaDefault: String) {
    val kafkaDefault   = SimpleStringProperty(kafkaDefault)
    val brokerOverride = SimpleStringProperty("-")
    val topicOverride  = SimpleStringProperty("")
    val deprecated     = SimpleBooleanProperty(key in KAFKA_TOPIC_DEPRECATED_STATIC)
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
            VBox(configTable).apply {
                padding = Insets(8.0, 0.0, 0.0, 0.0)
                VBox.setVgrow(configTable, Priority.ALWAYS)
                maxHeight = Double.MAX_VALUE
            }
        ).apply {
            isExpanded = false
            isAnimated = false
            maxHeight = Double.MAX_VALUE
            styleClass.add("borderless-titled-pane")
            style = "-fx-background-color: transparent; -fx-background-insets: 0; -fx-background-radius: 0; -fx-border-color: transparent; -fx-border-width: 0;"
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
            maxHeight = Double.MAX_VALUE
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
            VBox.setVgrow(advancedPane, Priority.ALWAYS)
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
        val propCol = TableColumn<TopicConfigRow, TopicConfigRow>("Property").apply {
            setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
            setCellFactory {
                object : TableCell<TopicConfigRow, TopicConfigRow>() {
                    private val link  = Hyperlink().apply { isVisited = false }
                    private val badge = Label("deprecated").apply { styleClass.add("topic-deprecated-badge") }
                    private val box   = HBox(6.0, link, badge).apply { alignment = Pos.CENTER_LEFT }
                    override fun updateItem(item: TopicConfigRow?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; tooltip = null; return }
                        link.text = item.key
                        badge.isVisible  = item.deprecated.get()
                        badge.isManaged  = item.deprecated.get()
                        graphic = box
                        val dep  = item.deprecated.get()
                        val desc = KAFKA_TOPIC_DESCRIPTIONS[item.key]
                        val tip  = buildString {
                            if (dep) append("⚠ Deprecated\n\n")
                            append(item.key)
                            if (desc != null) append("\n\n$desc")
                        }
                        tooltip = Tooltip(tip)
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
            minHeight = 200.0
            maxHeight = Double.MAX_VALUE
            columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
            placeholder = Label("")
            styleClass.add("config-table")
        }

        configRows.forEach { row ->
            row.topicOverride.addListener { _ -> table.refresh() }
            row.brokerOverride.addListener { _ -> table.refresh() }
            row.deprecated.addListener { _ -> table.refresh() }
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
                val (defaults, deprecatedKeys) = service.getBrokerTopicInfo()
                Platform.runLater {
                    configRows.forEach { row ->
                        row.brokerOverride.set(defaults[row.key] ?: "-")
                        if (deprecatedKeys.contains(row.key)) row.deprecated.set(true)
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
