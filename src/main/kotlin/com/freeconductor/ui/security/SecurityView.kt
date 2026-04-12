package com.freeconductor.ui.security

import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.QuotaInfo
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.ui.acl.AclView
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*

class SecurityView(
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit
) {
    val root: BorderPane = BorderPane()
    private val aclView = AclView(cluster, adminService, setStatus)

    private val quotaItems = FXCollections.observableArrayList<QuotaInfo>()
    private val quotaTable = TableView(quotaItems)
    private var quotasLoaded = false

    init {
        setupUI()
        aclView.refresh()
    }

    private fun setupUI() {
        val titleLabel = Label("SECURITY").apply {
            styleClass.add("view-title")
        }

        val titleBox = HBox(titleLabel).apply {
            padding = Insets(12.0, 16.0, 8.0, 16.0)
        }

        val tabPane = TabPane().apply {
            tabClosingPolicy = TabPane.TabClosingPolicy.UNAVAILABLE
        }

        val aclTab = Tab("ACLs", aclView.root)
        val quotasTab = Tab("Quotas", buildQuotasPane())

        tabPane.tabs.addAll(aclTab, quotasTab)

        tabPane.selectionModel.selectedItemProperty().addListener { _, _, newTab ->
            if (newTab == quotasTab && !quotasLoaded) {
                quotasLoaded = true
                refreshQuotas()
            }
        }

        root.top = titleBox
        root.center = tabPane
    }

    private fun buildQuotasPane(): BorderPane {
        setupQuotaTable()

        val searchField = TextField().apply {
            promptText = "Search quotas…"
            prefWidth = 200.0
        }

        searchField.textProperty().addListener { _, _, newVal ->
            quotaTable.items = if (newVal.isBlank()) quotaItems
            else FXCollections.observableArrayList(quotaItems.filter {
                it.entityDescription.contains(newVal, ignoreCase = true)
            })
        }

        val createButton = Button("+ Create Quota").apply {
            styleClass.add("accent")
            setOnAction { showCreateQuotaDialog() }
        }

        val deleteButton = Button("Delete").apply {
            styleClass.add("danger")
            setOnAction { deleteSelectedQuota() }
        }

        val refreshButton = Button("↻ Refresh").apply {
            setOnAction { refreshQuotas() }
        }

        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }

        val toolbar = HBox(8.0).apply {
            padding = Insets(8.0)
            alignment = Pos.CENTER_LEFT
            children.addAll(searchField, spacer, createButton, deleteButton, refreshButton)
        }

        return BorderPane().apply {
            top = toolbar
            center = quotaTable
        }
    }

    private fun setupQuotaTable() {
        val entityCol = TableColumn<QuotaInfo, String>("Entity").apply {
            setCellValueFactory { SimpleStringProperty(it.value.entityDescription) }
            prefWidth = 260.0
        }

        val producerRateCol = TableColumn<QuotaInfo, String>("Producer Rate MB/s").apply {
            setCellValueFactory { item ->
                SimpleStringProperty(
                    item.value.producerByteRate?.let { "%.1f MB/s".format(it / 1_048_576.0) } ?: "-"
                )
            }
            prefWidth = 140.0
        }

        val consumerRateCol = TableColumn<QuotaInfo, String>("Consumer Rate MB/s").apply {
            setCellValueFactory { item ->
                SimpleStringProperty(
                    item.value.consumerByteRate?.let { "%.1f MB/s".format(it / 1_048_576.0) } ?: "-"
                )
            }
            prefWidth = 140.0
        }

        val requestPctCol = TableColumn<QuotaInfo, String>("Request %").apply {
            setCellValueFactory { item ->
                SimpleStringProperty(
                    item.value.requestPercentage?.let { "%.1f%%".format(it) } ?: "-"
                )
            }
            prefWidth = 100.0
        }

        quotaTable.columns.addAll(entityCol, producerRateCol, consumerRateCol, requestPctCol)
        quotaTable.placeholder = Label("No quotas configured")
        VBox.setVgrow(quotaTable, Priority.ALWAYS)
    }

    private fun showCreateQuotaDialog() {
        val dialog = Dialog<Unit>()
        dialog.title = "Create Quota"
        dialog.headerText = "Create a new client quota"

        val entityTypeBox = ComboBox<String>().apply {
            items.addAll("user", "client-id", "ip")
            value = "user"
            maxWidth = Double.MAX_VALUE
        }

        val entityNameField = TextField().apply {
            promptText = "leave blank for default"
            maxWidth = Double.MAX_VALUE
        }

        val quotaTypeBox = ComboBox<String>().apply {
            items.addAll("producer_byte_rate", "consumer_byte_rate", "request_percentage")
            value = "producer_byte_rate"
            maxWidth = Double.MAX_VALUE
        }

        val valueField = TextField().apply {
            promptText = "e.g. 1048576"
            maxWidth = Double.MAX_VALUE
        }

        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 8.0
            padding = Insets(16.0)
            addRow(0, Label("Entity Type:"), entityTypeBox)
            addRow(1, Label("Entity Name:"), entityNameField)
            addRow(2, Label("Quota Type:"), quotaTypeBox)
            addRow(3, Label("Value:"), valueField)
            for (node in listOf(entityTypeBox, entityNameField, quotaTypeBox, valueField)) {
                GridPane.setHgrow(node, Priority.ALWAYS)
            }
        }

        dialog.dialogPane.content = grid
        dialog.dialogPane.prefWidth = 440.0

        val okBtn = ButtonType("Create Quota", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(okBtn, ButtonType.CANCEL)

        dialog.setResultConverter { bt ->
            if (bt.buttonData == ButtonBar.ButtonData.OK_DONE) Unit else null
        }

        dialog.showAndWait().ifPresent {
            val entityType = entityTypeBox.value
            val entityName = entityNameField.text.trim().ifBlank { null }
            val quotaKey = quotaTypeBox.value
            val valueText = valueField.text.trim()

            val quotaValue = valueText.toDoubleOrNull()
            if (quotaValue == null) {
                val alert = Alert(Alert.AlertType.ERROR)
                alert.title = "Invalid Value"
                alert.contentText = "Please enter a valid numeric value."
                alert.showAndWait()
                return@ifPresent
            }

            Thread {
                try {
                    adminService.createQuota(entityType, entityName, quotaKey, quotaValue)
                    Platform.runLater {
                        setStatus("Quota created successfully")
                        refreshQuotas()
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        setStatus("Failed to create quota: ${e.message}")
                        val alert = Alert(Alert.AlertType.ERROR)
                        alert.title = "Create Quota Failed"
                        alert.contentText = e.message
                        alert.showAndWait()
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun deleteSelectedQuota() {
        val selected = quotaTable.selectionModel.selectedItem ?: run {
            val alert = Alert(Alert.AlertType.WARNING)
            alert.contentText = "Please select a quota to delete."
            alert.showAndWait()
            return
        }

        val confirm = Alert(Alert.AlertType.CONFIRMATION)
        confirm.title = "Delete Quota"
        confirm.contentText = "Delete quota for '${selected.entityDescription}'?"
        val result = confirm.showAndWait()
        if (!result.isPresent || result.get() != ButtonType.OK) return

        Thread {
            try {
                adminService.deleteQuotaEntity(selected)
                Platform.runLater {
                    setStatus("Quota deleted")
                    refreshQuotas()
                }
            } catch (e: Exception) {
                Platform.runLater {
                    setStatus("Failed to delete quota: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Delete Failed"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun refreshQuotas() {
        setStatus("Loading quotas...")
        Thread {
            try {
                val quotas = adminService.listQuotas()
                Platform.runLater {
                    quotaItems.setAll(quotas)
                    setStatus("Loaded ${quotas.size} quota(s)")
                }
            } catch (e: SecurityException) {
                Platform.runLater {
                    quotaTable.placeholder = Label("Quota management requires ALTER_CONFIGS permission on the cluster")
                    setStatus("Insufficient permissions to load quotas")
                }
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("ClusterAuthorizationException") || msg.contains("CLUSTER_AUTHORIZATION_FAILED") ||
                    msg.contains("ALTER_CONFIGS") || msg.contains("not authorized")) {
                    Platform.runLater {
                        quotaTable.placeholder = Label("Quota management requires ALTER_CONFIGS permission on the cluster")
                        setStatus("Insufficient permissions to load quotas")
                    }
                } else {
                    Platform.runLater {
                        setStatus("Failed to load quotas: ${e.message}")
                        val alert = Alert(Alert.AlertType.ERROR)
                        alert.title = "Error"
                        alert.contentText = e.message
                        alert.showAndWait()
                    }
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun refresh() {
        aclView.refresh()
    }
}
