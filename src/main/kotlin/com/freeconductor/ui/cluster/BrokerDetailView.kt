package com.freeconductor.ui.cluster

import com.freeconductor.model.BrokerConfigEntry
import com.freeconductor.model.BrokerInfo
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

class BrokerDetailView(
    private val broker: BrokerInfo,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit
) {
    val root: BorderPane = BorderPane()

    private val allEntries  = FXCollections.observableArrayList<BrokerConfigEntry>()
    private val filtered    = FilteredList(allEntries)
    private val hideReadOnly    = SimpleBooleanProperty(false)
    private val showOverridesOnly = SimpleBooleanProperty(false)
    private val searchText  = SimpleStringProperty("")

    init {
        setupUI()
        rebuildPredicate()
        loadConfig()
    }

    private fun setupUI() {
        // ── Header ────────────────────────────────────────────────────────
        val title = Label("BROKER ${broker.id}").apply {
            styleClass.addAll("view-title")
            style = "-fx-font-size: 22px;"
        }
        val header = HBox(title).apply {
            padding = Insets(12.0, 16.0, 8.0, 16.0)
            alignment = Pos.CENTER_LEFT
        }

        // ── Info section ─────────────────────────────────────────────────
        fun infoRow(label: String, value: String) = HBox(6.0).apply {
            padding = Insets(2.0, 16.0, 2.0, 16.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("$label:").apply { style = "-fx-font-weight: bold;" },
                Label(value)
            )
        }
        val infoBox = VBox(2.0).apply {
            padding = Insets(0.0, 0.0, 10.0, 0.0)
            children.addAll(
                infoRow("Hostname",                    broker.host),
                infoRow("Partitions",                  broker.partitionCount.toString()),
                infoRow("Replicas",                    broker.partitionCount.toString()),
                infoRow("Under Replicated Partitions", "0")
            )
        }

        root.top = VBox(header, infoBox).apply {
            style = "-fx-border-color: -color-border-default; -fx-border-width: 0 0 1 0;"
        }

        // ── Tabs ─────────────────────────────────────────────────────────
        val tabPane = TabPane()
        tabPane.tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE

        tabPane.tabs.addAll(
            Tab("Configuration", buildConfigTab()),
            Tab("Logs", Label("Log directory info coming soon").apply {
                padding = Insets(24.0)
            })
        )

        root.center = tabPane
    }

    // ── Configuration tab ────────────────────────────────────────────────

    private fun buildConfigTab(): BorderPane {
        // Toolbar
        val hideReadOnlyCb = CheckBox("Hide read-only").apply {
            selectedProperty().bindBidirectional(hideReadOnly)
            hideReadOnly.addListener { _ -> rebuildPredicate() }
        }
        val overridesCb = CheckBox("Show Overrides Only").apply {
            selectedProperty().bindBidirectional(showOverridesOnly)
            showOverridesOnly.addListener { _ -> rebuildPredicate() }
        }
        val searchField = TextField().apply {
            promptText = "Search a property…"
            prefWidth = 220.0
            textProperty().bindBidirectional(searchText)
            searchText.addListener { _ -> rebuildPredicate() }
        }
        val viewRawBtn = Button("View Raw").apply {
            setOnAction { showRawDialog() }
        }
        val toolbar = HBox(8.0).apply {
            padding = Insets(8.0, 12.0, 8.0, 12.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(
                viewRawBtn,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                hideReadOnlyCb, overridesCb, searchField
            )
        }

        // Table
        val table = TableView(filtered)
        table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY

        val nameCol = TableColumn<BrokerConfigEntry, String>("Property").apply {
            prefWidth = 380.0
            setCellValueFactory { SimpleStringProperty(it.value.name) }
            setCellFactory {
                object : TableCell<BrokerConfigEntry, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; text = null; tooltip = null; return }
                        val entry = tableView.items[index]

                        val nameNode: javafx.scene.Node = if (!entry.isReadOnly) {
                            Hyperlink(item).apply {
                                styleClass.add("broker-config-link")
                                setOnAction {
                                    DynamicConfigDialog(
                                        broker          = broker,
                                        entry           = entry,
                                        adminService    = adminService,
                                        onConfigChanged = { loadConfig() },
                                        ownerWindow     = tableView.scene?.window
                                    ).show()
                                }
                            }
                        } else {
                            Label(item).apply { styleClass.add("broker-config-name") }
                        }

                        graphic = if (entry.isReadOnly) {
                            val lockIcon = FontIcon(FontAwesomeSolid.LOCK).apply {
                                styleClass.add("broker-lock-icon")
                            }
                            HBox(4.0, lockIcon, nameNode).apply { alignment = Pos.CENTER_LEFT }
                        } else nameNode

                        text = null
                        val sourceDesc = when (entry.overrideSource) {
                            "STATIC"  -> "Static (server.properties)"
                            "BROKER"  -> "Dynamic — this broker only"
                            "CLUSTER" -> "Dynamic — all brokers (cluster-wide default)"
                            "TOPIC"   -> "Dynamic — topic-level override"
                            else      -> "Default (Kafka built-in)"
                        }
                        val readOnlyDesc = if (entry.isReadOnly) "  🔒 read-only" else ""
                        tooltip = Tooltip("$item\nSource: $sourceDesc$readOnlyDesc")
                    }
                }
            }
        }

        val valueCol = TableColumn<BrokerConfigEntry, String>("Value").apply {
            prefWidth = 340.0
            setCellValueFactory { SimpleStringProperty(it.value.value.ifEmpty { "(null)" }) }
            setCellFactory {
                object : TableCell<BrokerConfigEntry, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { graphic = null; text = null; return }
                        val entry = tableView.items[index]
                        val badge = when (entry.overrideSource) {
                            "STATIC"  -> Label("S").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-static")
                                tooltip = Tooltip("Static — configured in server.properties (requires broker restart to change)")
                            }
                            "BROKER"  -> Label("B").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-broker")
                                tooltip = Tooltip("Broker — dynamic override applied to this broker only")
                            }
                            "CLUSTER" -> Label("C").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-cluster")
                                tooltip = Tooltip("Cluster — dynamic override applied to all brokers as a cluster-wide default")
                            }
                            "TOPIC"   -> Label("T").apply {
                                styleClass.addAll("broker-config-badge", "broker-config-badge-topic")
                                tooltip = Tooltip("Topic — dynamic override applied at the topic level")
                            }
                            else -> null
                        }
                        val lbl = Label(item).apply {
                            if (item == "(null)") style = "-fx-text-fill: -color-fg-muted;"
                            tooltip = Tooltip(item)
                        }
                        graphic = if (badge != null)
                            HBox(6.0, badge, lbl).apply { alignment = Pos.CENTER_LEFT }
                        else lbl
                        text = null
                    }
                }
            }
        }

        val defaultCol = TableColumn<BrokerConfigEntry, String>("Default").apply {
            setCellValueFactory { SimpleStringProperty(if (it.value.isDefault) "Yes" else "No") }
            prefWidth = 80.0
        }

        val defaultValueCol = TableColumn<BrokerConfigEntry, String>("Default Value").apply {
            setCellValueFactory { SimpleStringProperty(it.value.defaultValue ?: "") }
            prefWidth = 250.0
        }

        table.columns.addAll(nameCol, valueCol, defaultCol, defaultValueCol)
        table.placeholder = Label("Loading configuration…")

        return BorderPane().apply {
            top = toolbar
            center = table
        }
    }

    private fun rebuildPredicate() {
        filtered.setPredicate { entry ->
            if (hideReadOnly.get() && entry.isReadOnly) return@setPredicate false
            if (showOverridesOnly.get() && !entry.isOverride) return@setPredicate false
            val q = searchText.get()
            if (q.isNotEmpty() && !entry.name.contains(q, ignoreCase = true)) return@setPredicate false
            true
        }
    }

    private fun showRawDialog() {
        val text = allEntries.joinToString("\n") { "${it.name}=${it.value}" }

        val area = TextArea(text).apply {
            isEditable = false
        }
        VBox.setVgrow(area, Priority.ALWAYS)

        val copyBtn = Button("Copy")
        val closeBtn = Button("Close")

        copyBtn.setOnAction {
            val cb = javafx.scene.input.Clipboard.getSystemClipboard()
            val cc = javafx.scene.input.ClipboardContent()
            cc.putString(text)
            cb.setContent(cc)
            copyBtn.text = "Copied!"
        }

        val btnRow = HBox(8.0, copyBtn, closeBtn).apply {
            alignment = Pos.CENTER_RIGHT
            padding = Insets(8.0, 0.0, 0.0, 0.0)
        }

        val content = VBox(area, btnRow).apply {
            padding = Insets(10.0)
        }

        val stage = javafx.stage.Stage().apply {
            title = "Broker ${broker.id} — Raw Config"
            isResizable = true
            initModality(javafx.stage.Modality.APPLICATION_MODAL)
            root.scene?.window?.let { initOwner(it) }
            BrokerDetailView::class.java
                .getResourceAsStream("/com/freeconductor/icons/free-conduktor-logo-32.png")
                ?.let { icons.setAll(javafx.scene.image.Image(it)) }
            scene = javafx.scene.Scene(content, 700.0, 540.0)
        }

        closeBtn.setOnAction { stage.close() }
        stage.showAndWait()
    }

    // ── Data loading ──────────────────────────────────────────────────────

    fun loadConfig() {
        setStatus("Loading broker ${broker.id} config…")
        Thread {
            try {
                val entries = adminService.getBrokerConfig(broker.id)
                Platform.runLater {
                    allEntries.setAll(entries)
                    setStatus("Loaded ${entries.size} config entries for broker ${broker.id}")
                }
            } catch (e: Exception) {
                Platform.runLater { setStatus("Failed to load broker config: ${e.message}") }
            }
        }.also { it.isDaemon = true }.start()
    }
}
