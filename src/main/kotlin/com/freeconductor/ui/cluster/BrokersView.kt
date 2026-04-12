package com.freeconductor.ui.cluster

import com.freeconductor.model.BrokerClusterInfo
import com.freeconductor.model.BrokerInfo
import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*

class BrokersView(
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit,
    private val onBrokerSelected: ((BrokerInfo) -> Unit)? = null
) {
    val root: BorderPane = BorderPane()
    private val brokerItems = FXCollections.observableArrayList<BrokerInfo>()
    private val brokerTable = TableView(brokerItems)
    private val progressIndicator = ProgressIndicator()
    private val statsBar = HBox()

    init {
        setupUI()
    }

    private fun setupUI() {
        // ── Header ────────────────────────────────────────────────────────
        val header = HBox(8.0).apply {
            padding = Insets(10.0, 16.0, 10.0, 16.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("BROKERS").apply {
                    styleClass.addAll("view-title")
                    style = "-fx-font-size: 24px;"
                    minWidth = Label.USE_PREF_SIZE
                },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                Button("↻ Refresh").apply {
                    minWidth = Button.USE_PREF_SIZE
                    setOnAction { refresh() }
                }
            )
        }

        // ── Stats bar placeholder ─────────────────────────────────────────
        statsBar.apply {
            styleClass.add("topics-stats-bar")
            children.add(Label("Loading…").apply {
                styleClass.add("topics-stat-label")
                padding = Insets(10.0, 16.0, 10.0, 16.0)
            })
        }

        // ── Table ─────────────────────────────────────────────────────────
        setupTable()
        progressIndicator.isVisible = false
        progressIndicator.maxWidth = 40.0; progressIndicator.maxHeight = 40.0

        root.top = VBox(header, statsBar)
        root.center = StackPane(brokerTable, progressIndicator)
    }

    // ── Stats bar ─────────────────────────────────────────────────────────

    private fun refreshStatsBar(info: BrokerClusterInfo, brokers: List<BrokerInfo>) {
        val controllerLabel = if (info.controllerId >= 0) "Broker ${info.controllerId}" else "No Leader"
        val cells = listOf(
            statCell(info.brokerCount.toString(),          "Brokers"),
            statCell(controllerLabel,                      "Controller"),
            statCell(info.protocolVersion,                 "Protocol Version"),
            statCell(if (info.similarConfig) "Yes" else "No", "Similar Config"),
            statCell(info.clusterId,                       "Cluster ID")
        )
        statsBar.children.setAll(cells)
    }

    private fun statCell(value: String, title: String): VBox {
        return VBox(2.0).apply {
            styleClass.add("topics-stat-box")
            alignment = Pos.CENTER
            HBox.setHgrow(this, Priority.ALWAYS)
            maxWidth = Double.MAX_VALUE
            children.addAll(
                Label(value).apply { styleClass.add("topics-stat-value") },
                Label(title).apply { styleClass.add("topics-stat-label") }
            )
        }
    }

    // ── Table ─────────────────────────────────────────────────────────────

    private fun setupTable() {
        val idCol = TableColumn<BrokerInfo, String>("ID").apply {
            setCellValueFactory { SimpleStringProperty(it.value.id.toString()) }
            prefWidth = 60.0; style = "-fx-alignment: CENTER;"
        }
        val rackCol = TableColumn<BrokerInfo, String>("Rack").apply {
            setCellValueFactory { SimpleStringProperty(it.value.rack ?: "-") }
            prefWidth = 80.0; style = "-fx-alignment: CENTER;"
        }
        val listenerCol = TableColumn<BrokerInfo, String>("Listener").apply {
            setCellValueFactory { SimpleStringProperty("${it.value.host}:${it.value.port}") }
            prefWidth = 220.0
            setCellFactory {
                object : TableCell<BrokerInfo, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; return }
                        val broker = tableView.items[index]
                        graphic = Hyperlink(item).apply {
                            setOnAction {
                                onBrokerSelected?.invoke(broker)
                            }
                        }
                        text = null
                    }
                }
            }
        }
        val controllerCol = TableColumn<BrokerInfo, String>("Controller").apply {
            setCellValueFactory { SimpleStringProperty(if (it.value.isController) "★ Yes" else "") }
            prefWidth = 100.0; style = "-fx-alignment: CENTER;"
        }
        val partitionsCol = TableColumn<BrokerInfo, String>("Partitions").apply {
            setCellValueFactory { SimpleStringProperty(it.value.partitionCount.toString()) }
            prefWidth = 90.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val partSkewCol = TableColumn<BrokerInfo, String>("Part. Skew").apply {
            setCellValueFactory { cell ->
                SimpleStringProperty(formatSkew(cell.value.partitionCount, brokerItems.map { it.partitionCount }))
            }
            prefWidth = 90.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val leadersCol = TableColumn<BrokerInfo, String>("Leaders").apply {
            setCellValueFactory { SimpleStringProperty(it.value.leaderCount.toString()) }
            prefWidth = 80.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val leaderSkewCol = TableColumn<BrokerInfo, String>("Leader Skew").apply {
            setCellValueFactory { cell ->
                SimpleStringProperty(formatSkew(cell.value.leaderCount, brokerItems.map { it.leaderCount }))
            }
            prefWidth = 100.0; style = "-fx-alignment: CENTER-RIGHT;"
        }
        val sizeCol = TableColumn<BrokerInfo, String>("Size").apply {
            setCellValueFactory { SimpleStringProperty(formatBytes(it.value.logSize)) }
            prefWidth = 100.0; style = "-fx-alignment: CENTER-RIGHT;"
        }

        brokerTable.columns.addAll(
            idCol, rackCol, listenerCol, controllerCol,
            partitionsCol, partSkewCol, leadersCol, leaderSkewCol, sizeCol
        )
        brokerTable.placeholder = Label("No brokers found")
        VBox.setVgrow(brokerTable, Priority.ALWAYS)
    }

    // ── Data loading ──────────────────────────────────────────────────────

    fun refresh() {
        progressIndicator.isVisible = true
        setStatus("Loading brokers...")
        Thread {
            try {
                val (clusterInfo, brokers) = adminService.listBrokersDetailed()
                Platform.runLater {
                    brokerItems.setAll(brokers)
                    refreshStatsBar(clusterInfo, brokers)
                    progressIndicator.isVisible = false
                    setStatus("Loaded ${brokers.size} broker(s)")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to load brokers: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun formatSkew(value: Int, all: List<Int>): String {
        if (all.size <= 1) return "0%"
        val avg = all.average()
        if (avg == 0.0) return "0%"
        val pct = (value - avg) / avg * 100.0
        return "%.0f%%".format(pct)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        return when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024L         -> "%.1f kB".format(bytes / 1_024.0)
            else                    -> "$bytes B"
        }
    }
}
