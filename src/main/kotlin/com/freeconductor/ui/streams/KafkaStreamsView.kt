package com.freeconductor.ui.streams

import com.freeconductor.model.StreamsAppInfo
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.text.FontWeight
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

class KafkaStreamsView(
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit
) {
    val root: BorderPane = BorderPane()
    private val streamsItems = FXCollections.observableArrayList<StreamsAppInfo>()
    private val streamsTable = TableView(streamsItems)
    private val progressIndicator = ProgressIndicator().apply {
        maxWidth = 40.0
        maxHeight = 40.0
        isVisible = false
    }
    private val detailPane = VBox().apply {
        val placeholder = Label("Select an application to view details").apply {
            styleClass.add("subtitle-label")
        }
        alignment = Pos.CENTER
        children.add(placeholder)
    }

    init {
        setupUI()
    }

    private fun setupUI() {
        val titleLabel = Label("KAFKA STREAMS").apply {
            styleClass.add("view-title")
        }

        val refreshButton = Button("Refresh", FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 12 }).apply {
            setOnAction { refresh() }
        }

        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }

        val titleRow = HBox(8.0).apply {
            padding = Insets(8.0, 16.0, 4.0, 16.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(titleLabel, spacer, refreshButton)
        }

        val subtitleLabel = Label(
            "Kafka Streams is a library for building real-time stream processing applications. " +
            "Applications are detected by their internal changelog and repartition topics."
        ).apply {
            styleClass.add("subtitle-label")
            isWrapText = true
        }

        val subtitleRow = HBox(subtitleLabel).apply {
            padding = Insets(0.0, 16.0, 8.0, 16.0)
        }

        val topVBox = VBox(titleRow, subtitleRow)

        setupTable()

        val tableStack = StackPane(streamsTable, progressIndicator)
        VBox.setVgrow(streamsTable, Priority.ALWAYS)

        val splitPane = SplitPane().apply {
            items.addAll(tableStack, detailPane)
            setDividerPositions(0.55)
        }

        streamsTable.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
            if (newVal != null) {
                showDetailPanel(newVal)
            }
        }

        root.top = topVBox
        root.center = splitPane
    }

    private fun setupTable() {
        val appIdCol = TableColumn<StreamsAppInfo, String>("Application ID").apply {
            setCellValueFactory { SimpleStringProperty(it.value.applicationId) }
            prefWidth = 280.0
        }

        val stateCol = TableColumn<StreamsAppInfo, String>("State").apply {
            setCellValueFactory { SimpleStringProperty(it.value.state) }
            prefWidth = 120.0
            setCellFactory { _ ->
                object : TableCell<StreamsAppInfo, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) {
                            text = null
                            style = ""
                        } else {
                            text = item
                            style = when {
                                item.equals("Stable", ignoreCase = true) || item == "STABLE" ->
                                    "-fx-text-fill: #1a7f37;"
                                item == "PreparingRebalance" || item == "CompletingRebalance" || item == "REBALANCING" ->
                                    "-fx-text-fill: #9a6700;"
                                item.equals("Dead", ignoreCase = true) || item == "DEAD" ->
                                    "-fx-text-fill: #cf222e;"
                                else ->
                                    "-fx-text-fill: #57606a;"
                            }
                        }
                    }
                }
            }
        }

        val membersCol = TableColumn<StreamsAppInfo, String>("Members").apply {
            setCellValueFactory { SimpleStringProperty(it.value.memberCount.toString()) }
            prefWidth = 80.0
            style = "-fx-alignment: CENTER;"
        }

        val internalTopicsCol = TableColumn<StreamsAppInfo, String>("Internal Topics").apply {
            setCellValueFactory { SimpleStringProperty(it.value.internalTopics.size.toString()) }
            prefWidth = 100.0
            style = "-fx-alignment: CENTER;"
        }

        streamsTable.columns.addAll(appIdCol, stateCol, membersCol, internalTopicsCol)
        streamsTable.placeholder = Label("No Kafka Streams applications detected")
        VBox.setVgrow(streamsTable, Priority.ALWAYS)
    }

    private fun showDetailPanel(app: StreamsAppInfo) {
        detailPane.children.clear()
        detailPane.alignment = Pos.TOP_LEFT
        detailPane.padding = Insets(16.0)
        detailPane.spacing = 4.0

        val appIdLabel = Label("Application ID").apply {
            styleClass.add("config-section-label")
        }
        val appIdValue = Label(app.applicationId).apply {
            style = "-fx-font-weight: bold;"
        }

        val topicsHeader = Label("Internal Topics").apply {
            styleClass.add("config-section-label")
            padding = Insets(8.0, 0.0, 0.0, 0.0)
        }

        val topicsList = ListView<String>().apply {
            items.setAll(app.internalTopics)
            prefHeight = 200.0
            styleClass.add("code-area")
        }

        val noteLabel = Label(
            "Note: These topics store Kafka Streams state (changelogs) and intermediate data (repartitions)."
        ).apply {
            styleClass.add("subtitle-label")
            isWrapText = true
            padding = Insets(8.0, 0.0, 0.0, 0.0)
        }

        detailPane.children.addAll(appIdLabel, appIdValue, topicsHeader, topicsList, noteLabel)
    }

    fun refresh() {
        progressIndicator.isVisible = true
        setStatus("Detecting Kafka Streams applications...")
        Thread {
            try {
                val apps = adminService.detectStreamsApplications()
                Platform.runLater {
                    streamsItems.setAll(apps)
                    progressIndicator.isVisible = false
                    setStatus("Detected ${apps.size} Kafka Streams application(s)")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to detect Kafka Streams applications: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Error"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
