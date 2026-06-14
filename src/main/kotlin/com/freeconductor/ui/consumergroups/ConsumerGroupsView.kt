package com.freeconductor.ui.consumergroups

import com.freeconductor.model.*
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

class ConsumerGroupsView(
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit,
    private val onGroupSelected: ((ConsumerGroupInfo) -> Unit)? = null
) {
    val root: BorderPane = BorderPane()
    private val groupItems = FXCollections.observableArrayList<ConsumerGroupInfo>()
    private val groupTable = TableView(groupItems)
    private val progressIndicator = ProgressIndicator()

    init {
        setupUI()
    }

    private fun setupUI() {
        val toolbar = buildToolbar()
        setupGroupTable()

        progressIndicator.isVisible = false
        progressIndicator.maxWidth = 40.0; progressIndicator.maxHeight = 40.0

        val groupContainer = StackPane(groupTable, progressIndicator)
        VBox.setVgrow(groupTable, Priority.ALWAYS)

        root.top = toolbar
        root.center = groupContainer
    }

    private fun buildToolbar(): HBox {
        val searchField = TextField().apply {
            promptText = "Search groups..."
            prefWidth = 200.0
        }
        searchField.textProperty().addListener { _, _, newVal ->
            groupTable.items = if (newVal.isBlank()) groupItems
            else FXCollections.observableArrayList(groupItems.filter { it.groupId.contains(newVal, ignoreCase = true) })
        }

        val refreshButton = Button("Refresh", FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 12 }).apply {
            styleClass.add("accent")
            setOnAction { refresh() }
        }

        return HBox(8.0).apply {
            padding = Insets(8.0)
            children.addAll(
                Label("Consumer Groups").apply { styleClass.add("view-title") },
                searchField,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                refreshButton
            )
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }
    }

    private fun setupGroupTable() {
        val groupIdCol = TableColumn<ConsumerGroupInfo, String>("Group ID").apply {
            setCellValueFactory { SimpleStringProperty(it.value.groupId) }
            prefWidth = 250.0
            setCellFactory {
                object : TableCell<ConsumerGroupInfo, String>() {
                    private val link = Hyperlink().apply {
                        styleClass.add("topic-name-link")
                    }
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) {
                            graphic = null
                            text = null
                        } else {
                            link.text = item
                            link.setOnAction {
                                val group = tableView.items[index]
                                onGroupSelected?.invoke(group)
                            }
                            graphic = link
                            text = null
                        }
                    }
                }
            }
        }
        val stateCol = TableColumn<ConsumerGroupInfo, String>("State").apply {
            setCellValueFactory { SimpleStringProperty(it.value.state) }
            prefWidth = 120.0
        }
        val membersCol = TableColumn<ConsumerGroupInfo, String>("Members").apply {
            setCellValueFactory { SimpleStringProperty(it.value.members.toString()) }
            prefWidth = 80.0
        }
        val topicsCol = TableColumn<ConsumerGroupInfo, String>("Topics").apply {
            setCellValueFactory { SimpleStringProperty(it.value.topicCount.toString()) }
            prefWidth = 70.0
        }
        val partitionsCol = TableColumn<ConsumerGroupInfo, String>("Partitions").apply {
            setCellValueFactory { SimpleStringProperty(it.value.partitionCount.toString()) }
            prefWidth = 90.0
        }
        val lagCol = TableColumn<ConsumerGroupInfo, String>("Overall Lag").apply {
            setCellValueFactory { SimpleStringProperty(it.value.totalLag.toString()) }
            prefWidth = 110.0
        }

        groupTable.columns.addAll(groupIdCol, stateCol, membersCol, topicsCol, partitionsCol, lagCol)
        groupTable.placeholder = Label("No consumer groups found")
        VBox.setVgrow(groupTable, Priority.ALWAYS)
    }

    fun refresh() {
        progressIndicator.isVisible = true
        setStatus("Loading consumer groups...")
        Thread {
            try {
                val groups = adminService.listConsumerGroups()
                Platform.runLater {
                    groupItems.setAll(groups)
                    progressIndicator.isVisible = false
                    setStatus("Loaded ${groups.size} consumer groups")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to load consumer groups: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Error"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
