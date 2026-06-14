package com.freeconductor.ui.topics

import atlantafx.base.controls.ToggleSwitch
import com.freeconductor.service.BrokerTopicInfo
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

private val KAFKA_TOPIC_LONG_DESCRIPTIONS = mapOf(
    "min.insync.replicas"                     to "When a producer sets acks to \"all\" (or \"-1\"), this configuration specifies the minimum number of replicas that must acknowledge a write for the write to be considered successful. If this minimum cannot be met, then the producer will raise an exception (either NotEnoughReplicas or NotEnoughReplicasAfterAppend).\n\nWhen used together, min.insync.replicas and acks allow you to enforce greater durability guarantees. A typical scenario would be to create a topic with a replication factor of 3, set min.insync.replicas to 2, and produce with acks of \"all\". This will ensure that the producer raises an exception if a majority of replicas do not receive a write.",
    "retention.bytes"                         to "This configuration controls the maximum size a partition (which consists of log segments) can grow to before we will discard old log segments to free up space if we are using the \"delete\" retention policy. By default there is no size limit only a time limit. Since this limit is enforced at the partition level, multiply it by the number of partitions to compute the topic retention in bytes.",
    "retention.ms"                            to "This configuration controls the maximum time we will retain a log before we will discard old log segments to free up space if we are using the \"delete\" retention policy. This represents an SLA on how soon consumers must read their data. If set to -1, no time limit is applied.",
    "compression.type"                        to "Specify the final compression type for a given topic. This configuration accepts the standard compression codecs ('gzip', 'snappy', 'lz4', 'zstd'). It additionally accepts 'uncompressed' which is equivalent to no compression; and 'producer' which means retain the original compression codec set by the producer.",
    "delete.retention.ms"                     to "The amount of time to retain delete tombstone markers for log compacted topics. This setting also gives a bound on the time in which a consumer must complete a read if they begin from offset 0 to ensure that they get a valid snapshot of the final stage (otherwise delete tombstones may be collected before they complete their scan).",
    "file.delete.delay.ms"                    to "The time to wait before deleting a file from the filesystem.",
    "flush.messages"                          to "This setting allows specifying an interval at which we will force an fsync of data written to the log. For example if this was set to 1 we would fsync after every message; if it were 5 we would fsync after every five messages.\n\nIn general we recommend you not set this and use replication for durability and allow the operating system's background flush capabilities as it is more efficient.",
    "flush.ms"                                to "This setting allows specifying a time interval at which we will force an fsync of data written to the log. For example if this was set to 1000 we would fsync after 1000 ms had passed.\n\nIn general we recommend you not set this and use replication for durability and allow the operating system's background flush capabilities as it is more efficient.",
    "index.interval.bytes"                    to "This setting controls how frequently Kafka adds an index entry to its offset index. The default setting ensures that we index a message roughly every 4096 bytes. More indexing allows reads to jump closer to the exact position in the log but makes the index larger. You probably don't need to change this.",
    "local.retention.bytes"                   to "The maximum size of local log segments that can grow for a partition before it deletes the old segments. Default value is -2, it represents retention.bytes value to be used. The effective value should always be less than or equal to retention.bytes value.",
    "local.retention.ms"                      to "The number of milliseconds to keep the local log segment before it gets deleted. Default value is -2, it represents retention.ms value is to be used. The effective value should always be less than or equal to retention.ms value.",
    "max.compaction.lag.ms"                   to "The maximum time a message will remain ineligible for compaction in the log. Only applicable for logs that are being compacted.",
    "max.message.bytes"                       to "The largest record batch size allowed by Kafka (after compression if compression is enabled). If this is increased and there are consumers older than 0.10.2, the consumers' fetch size must also be increased so that they can fetch record batches this large.\n\nIn the latest message format version, records are always grouped into batches for efficiency. In previous message format versions, uncompressed records are not grouped into batches and this limit only applies to a single record in that case.",
    "message.downconversion.enable"           to "This configuration controls whether down-conversion of message formats is enabled to satisfy consume requests. When set to false, broker will not perform down-conversion for consumers expecting an older message format. The broker responds with UNSUPPORTED_VERSION error for consume requests from such older clients.\n\nThis configuration does not apply to any message format conversion that might be required for replication to followers.",
    "message.format.version"                  to "[DEPRECATED] Specify the message format version the broker will use to append messages to the logs. The value of this config is always assumed to be 3.0 if inter.broker.protocol.version is 3.0 or higher (the actual config value is ignored). Otherwise, the value should be a valid ApiVersion. Some examples are: 0.10.0, 1.1, 2.8, 3.0.\n\nBy setting a particular message format version, the user is certifying that all the existing messages on disk are smaller or equal than the specified version. Setting this value incorrectly will cause consumers with older versions to break as they will receive messages with a format that they don't understand.",
    "message.timestamp.difference.max.ms"     to "The maximum difference allowed between the timestamp when a broker receives a message and the timestamp specified in the message. If message.timestamp.type=CreateTime, a message will be rejected if the difference in timestamp exceeds this threshold. This configuration is ignored if message.timestamp.type=LogAppendTime.",
    "message.timestamp.type"                  to "Define whether the timestamp in the message is message create time or log append time. The value should be either CreateTime or LogAppendTime.",
    "min.cleanable.dirty.ratio"               to "This configuration controls how frequently the log compactor will attempt to clean the log (assuming log compaction is enabled). By default we will avoid cleaning a log where more than 50% of the log has been compacted. This ratio bounds the maximum space wasted in the log by duplicates (at 50% at most 50% of the log could be duplicates). A higher ratio will mean fewer, more efficient cleanings but will mean more wasted space in the log.\n\nIf the max.compaction.lag.ms or the min.compaction.lag.ms configurations are also specified, then the log compactor considers the log to be eligible for compaction as soon as either: (i) the dirty ratio threshold has been met and the log has had dirty (uncompacted) records for at least the min.compaction.lag.ms duration, or (ii) if the log has had dirty (uncompacted) records for at most the max.compaction.lag.ms period.",
    "min.compaction.lag.ms"                   to "The minimum time a message will remain uncompacted in the log. Only applicable for logs that are being compacted.",
    "preallocate"                             to "True if we should preallocate the file on disk when creating a new log segment.",
    "remote.storage.enable"                   to "To enable tier storage for a topic, set remote.storage.enable as true. You can not disable this config once it is enabled. It will be provided in future versions.",
    "segment.bytes"                           to "This configuration controls the segment file size for the log. Retention and cleaning is always done a file at a time so a larger segment size means fewer files but less granular control over retention.",
    "segment.index.bytes"                     to "This configuration controls the size of the index that maps offsets to file positions. We preallocate this index file and shrink it only after log rolls. You generally should not need to change this setting.",
    "segment.jitter.ms"                       to "The maximum random jitter subtracted from the scheduled segment roll time to avoid thundering herds of segment rolling.",
    "segment.ms"                              to "This configuration controls the period of time after which Kafka will force the log to roll even if the segment file isn't full to ensure that retention can delete or compact old data.",
    "unclean.leader.election.enable"          to "Indicates whether to enable replicas not in the ISR set to be elected as leader as a last resort, even though doing so may result in data loss.",
    "leader.replication.throttled.replicas"   to "A list of replicas for which log replication should be throttled on the leader side. The list should describe a set of replicas in the form [PartitionId]:[BrokerId],[PartitionId]:[BrokerId]:... or alternatively the wildcard '*' can be used to throttle all replicas for this topic.",
    "follower.replication.throttled.replicas" to "A list of replicas for which log replication should be throttled on the follower side. The list should describe a set of replicas in the form [PartitionId]:[BrokerId],[PartitionId]:[BrokerId]:... or alternatively the wildcard '*' can be used to throttle all replicas for this topic."
)

// Known-deprecated keys as a static fallback; updated live from broker documentation() on connect.
private val KAFKA_TOPIC_DEPRECATED_STATIC = setOf("message.format.version")

private class TopicConfigRow(val key: String, kafkaDefault: String) {
    val kafkaDefault   = SimpleStringProperty(kafkaDefault)
    val brokerOverride = SimpleStringProperty("-")
    val topicOverride  = SimpleStringProperty("")
    val deprecated     = SimpleBooleanProperty(key in KAFKA_TOPIC_DEPRECATED_STATIC)
    val longDoc        = SimpleStringProperty(KAFKA_TOPIC_LONG_DESCRIPTIONS[key] ?: "")
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
                    private val keyLabel = Label()
                    private val badge    = Label("deprecated").apply { styleClass.add("topic-deprecated-badge") }
                    private val infoIcon = FontIcon(FontAwesomeSolid.INFO_CIRCLE).also { it.iconSize = 12 }
                    private val infoBtn  = Hyperlink().apply {
                        graphic = infoIcon
                        isVisited = false
                        padding = Insets(0.0, 0.0, 0.0, 2.0)
                    }
                    private val box = HBox(4.0, keyLabel, badge, infoBtn).apply { alignment = Pos.CENTER_LEFT }
                    override fun updateItem(item: TopicConfigRow?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; tooltip = null; return }
                        keyLabel.text    = item.key
                        badge.isVisible  = item.deprecated.get()
                        badge.isManaged  = item.deprecated.get()
                        infoBtn.setOnAction { showPropertyDetail(item) }
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
            prefWidth = 240.0
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

    private fun showPropertyDetail(row: TopicConfigRow) {
        Dialog<ButtonType>().apply {
            title = "Topic Configuration — ${row.key}"
            headerText = null
            applyAppIcon()
            val text = Label(row.longDoc.get().ifEmpty { "No documentation available." }).apply {
                isWrapText = true
                maxWidth = 520.0
            }
            dialogPane.content = VBox(text).apply { padding = Insets(24.0) }
            dialogPane.buttonTypes.add(ButtonType.CLOSE)
            showAndWait()
        }
    }

    private fun loadBrokerDefaults() {
        val service = adminService ?: return
        Thread {
            try {
                val info = service.getBrokerTopicInfo()
                Platform.runLater {
                    configRows.forEach { row ->
                        row.brokerOverride.set(info.defaults[row.key] ?: "-")
                        if (info.deprecatedKeys.contains(row.key)) row.deprecated.set(true)
                        info.docs[row.key]?.takeIf { it.isNotBlank() }?.let { row.longDoc.set(it) }
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
