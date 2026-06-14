package com.freeconductor.ui.connect

import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.ConnectorInfo
import com.freeconductor.model.ConnectorTask
import com.freeconductor.service.KafkaConnectService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

class KafkaConnectView(
    private val cluster: ClusterConfig,
    private val connectService: KafkaConnectService,
    private val setStatus: (String) -> Unit
) {
    val root: BorderPane = BorderPane()
    private val connectorItems = FXCollections.observableArrayList<ConnectorInfo>()
    private val connectorTable = TableView(connectorItems)
    private val taskItems = FXCollections.observableArrayList<ConnectorTask>()
    private val taskTable = TableView(taskItems)
    private val configText = TextArea().apply {
        isEditable = false
        isWrapText = true
        prefHeight = 200.0
        styleClass.add("code-area")
    }
    private val progressIndicator = ProgressIndicator()
    private var selectedConnector: ConnectorInfo? = null

    init {
        setupUI()
    }

    private fun setupUI() {
        val toolbar = buildToolbar()
        setupConnectorTable()
        setupTaskTable()

        progressIndicator.isVisible = false
        progressIndicator.maxWidth = 40.0; progressIndicator.maxHeight = 40.0

        val leftContainer = StackPane(connectorTable, progressIndicator).also {
            VBox.setVgrow(connectorTable, Priority.ALWAYS)
        }

        val tasksLabel = Label("Tasks").apply { styleClass.add("section-header"); padding = Insets(4.0, 8.0, 4.0, 8.0) }
        val configLabel = Label("Configuration").apply { styleClass.add("section-header"); padding = Insets(4.0, 8.0, 4.0, 8.0) }

        val rightPanel = VBox(
            tasksLabel,
            taskTable,
            configLabel,
            configText
        ).apply {
            VBox.setVgrow(taskTable, Priority.SOMETIMES)
            VBox.setVgrow(configText, Priority.SOMETIMES)
        }

        val splitPane = SplitPane()
        splitPane.items.addAll(leftContainer, rightPanel)
        splitPane.setDividerPositions(0.5)

        root.top = toolbar
        root.center = splitPane
    }

    private fun buildToolbar(): HBox {
        val createButton = Button("Create Connector", FontIcon(FontAwesomeSolid.PLUS).also { it.iconSize = 12 }).apply {
            styleClass.add("accent")
            setOnAction { showCreateDialog() }
        }

        val pauseButton = Button("Pause").apply {
            setOnAction { actionOnSelected("pause") }
        }
        val resumeButton = Button("Resume").apply {
            setOnAction { actionOnSelected("resume") }
        }
        val restartButton = Button("Restart").apply {
            setOnAction { actionOnSelected("restart") }
        }
        val deleteButton = Button("Delete").apply {
            styleClass.add("danger")
            setOnAction { actionOnSelected("delete") }
        }
        val refreshButton = Button("Refresh", FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 12 }).apply {
            setOnAction { refresh() }
        }

        return HBox(8.0).apply {
            padding = Insets(8.0)
            children.addAll(
                Label("Kafka Connect").apply { styleClass.add("view-title") },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                createButton, pauseButton, resumeButton, restartButton, deleteButton, refreshButton
            )
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }
    }

    private fun setupConnectorTable() {
        val nameCol = TableColumn<ConnectorInfo, String>("Name").apply {
            setCellValueFactory { SimpleStringProperty(it.value.name) }
            prefWidth = 200.0
        }
        val stateCol = TableColumn<ConnectorInfo, String>("State").apply {
            setCellValueFactory { SimpleStringProperty(it.value.state) }
            prefWidth = 100.0
            setCellFactory { _ ->
                object : TableCell<ConnectorInfo, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; style = "" }
                        else {
                            text = item
                            style = when (item) {
                                "RUNNING" -> "-fx-text-fill: #4caf50;"
                                "PAUSED" -> "-fx-text-fill: #ff9800;"
                                "FAILED" -> "-fx-text-fill: #f44336;"
                                else -> ""
                            }
                        }
                    }
                }
            }
        }
        val typeCol = TableColumn<ConnectorInfo, String>("Type").apply {
            setCellValueFactory { SimpleStringProperty(it.value.type) }
            prefWidth = 80.0
        }
        val tasksCol = TableColumn<ConnectorInfo, String>("Tasks").apply {
            setCellValueFactory { SimpleStringProperty(it.value.tasks.size.toString()) }
            prefWidth = 60.0
        }

        connectorTable.columns.addAll(nameCol, stateCol, typeCol, tasksCol)
        connectorTable.placeholder = Label("No connectors found")
        VBox.setVgrow(connectorTable, Priority.ALWAYS)

        connectorTable.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
            if (newVal != null) {
                selectedConnector = newVal
                showConnectorDetail(newVal)
            }
        }
    }

    private fun setupTaskTable() {
        val idCol = TableColumn<ConnectorTask, String>("Task ID").apply {
            setCellValueFactory { SimpleStringProperty(it.value.taskId.toString()) }
            prefWidth = 70.0
        }
        val stateCol = TableColumn<ConnectorTask, String>("State").apply {
            setCellValueFactory { SimpleStringProperty(it.value.state) }
            prefWidth = 90.0
        }
        val workerCol = TableColumn<ConnectorTask, String>("Worker").apply {
            setCellValueFactory { SimpleStringProperty(it.value.workerId ?: "") }
            prefWidth = 150.0
        }
        val traceCol = TableColumn<ConnectorTask, String>("Error").apply {
            setCellValueFactory {
                val trace = it.value.trace ?: ""
                SimpleStringProperty(if (trace.length > 80) trace.substring(0, 80) + "..." else trace)
            }
            prefWidth = 200.0
        }

        taskTable.columns.addAll(idCol, stateCol, workerCol, traceCol)
        taskTable.placeholder = Label("No tasks")
        VBox.setVgrow(taskTable, Priority.SOMETIMES)
    }

    private fun showConnectorDetail(connector: ConnectorInfo) {
        taskItems.setAll(connector.tasks)
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        configText.text = try {
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(connector.config)
        } catch (_: Exception) {
            connector.config.entries.joinToString("\n") { "${it.key}=${it.value}" }
        }
    }

    private fun actionOnSelected(action: String) {
        val connector = selectedConnector ?: run {
            val alert = Alert(Alert.AlertType.WARNING)
            alert.contentText = "Please select a connector."
            alert.showAndWait()
            return
        }

        if (action == "delete") {
            val confirm = Alert(Alert.AlertType.CONFIRMATION)
            confirm.title = "Delete Connector"
            confirm.contentText = "Delete connector '${connector.name}'?"
            val result = confirm.showAndWait()
            if (!result.isPresent || result.get() != ButtonType.OK) return
        }

        setStatus("${action.replaceFirstChar { it.uppercase() }}ing connector ${connector.name}...")
        Thread {
            try {
                when (action) {
                    "pause" -> connectService.pauseConnector(connector.name)
                    "resume" -> connectService.resumeConnector(connector.name)
                    "restart" -> connectService.restartConnector(connector.name)
                    "delete" -> connectService.deleteConnector(connector.name)
                }
                Platform.runLater {
                    setStatus("${action.replaceFirstChar { it.uppercase() }}d connector ${connector.name}")
                    refresh()
                }
            } catch (e: Exception) {
                Platform.runLater {
                    setStatus("Failed: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Action Failed"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun showCreateDialog() {
        val dialog = KafkaConnectConfigDialog()
        dialog.showAndWait().ifPresent { request ->
            if (request.name.isBlank() || request.configJson.isBlank()) return@ifPresent
            Thread {
                try {
                    val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                    val config = mapper.readValue(request.configJson, Map::class.java)
                        .map { (k, v) -> k.toString() to v.toString() }.toMap()
                    connectService.createConnector(request.name, config)
                    Platform.runLater {
                        setStatus("Created connector '${request.name}'")
                        refresh()
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        setStatus("Failed to create connector: ${e.message}")
                        val alert = Alert(Alert.AlertType.ERROR)
                        alert.title = "Create Failed"
                        alert.contentText = e.message
                        alert.showAndWait()
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    fun refresh() {
        progressIndicator.isVisible = true
        setStatus("Loading Kafka Connect connectors...")
        Thread {
            try {
                val connectors = connectService.listConnectors()
                Platform.runLater {
                    connectorItems.setAll(connectors)
                    progressIndicator.isVisible = false
                    setStatus("Loaded ${connectors.size} connectors")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to load connectors: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Kafka Connect Error"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
