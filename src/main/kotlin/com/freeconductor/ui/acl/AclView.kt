package com.freeconductor.ui.acl

import com.freeconductor.model.AclInfo
import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

class AclView(
    private val cluster: ClusterConfig,
    private val adminService: KafkaAdminService,
    private val setStatus: (String) -> Unit
) {
    val root: BorderPane = BorderPane()
    private val aclItems = FXCollections.observableArrayList<AclInfo>()
    private val aclTable = TableView(aclItems)
    private val progressIndicator = ProgressIndicator()

    init {
        setupUI()
    }

    private fun setupUI() {
        val toolbar = buildToolbar()
        setupTable()

        progressIndicator.isVisible = false
        progressIndicator.maxWidth = 40.0; progressIndicator.maxHeight = 40.0

        val tableContainer = StackPane(aclTable, progressIndicator)
        VBox.setVgrow(aclTable, Priority.ALWAYS)

        root.top = toolbar
        root.center = tableContainer
    }

    private fun buildToolbar(): HBox {
        val searchField = TextField().apply {
            promptText = "Search ACLs..."
            prefWidth = 200.0
        }
        searchField.textProperty().addListener { _, _, newVal ->
            aclTable.items = if (newVal.isBlank()) aclItems
            else FXCollections.observableArrayList(aclItems.filter {
                it.principal.contains(newVal, ignoreCase = true) ||
                it.resourceName.contains(newVal, ignoreCase = true) ||
                it.operation.contains(newVal, ignoreCase = true)
            })
        }

        val createButton = Button("Create ACL", FontIcon(FontAwesomeSolid.PLUS).also { it.iconSize = 12 }).apply {
            styleClass.add("accent")
            setOnAction { showCreateDialog() }
        }

        val deleteButton = Button("Delete ACL").apply {
            styleClass.add("danger")
            setOnAction { deleteSelectedAcl() }
        }

        val refreshButton = Button("Refresh").apply {
            setOnAction { refresh() }
        }

        return HBox(8.0).apply {
            padding = Insets(8.0)
            children.addAll(
                Label("ACLs").apply { styleClass.add("view-title") },
                searchField,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                createButton,
                deleteButton,
                refreshButton
            )
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }
    }

    private fun setupTable() {
        val resourceTypeCol = TableColumn<AclInfo, String>("Resource Type").apply {
            setCellValueFactory { SimpleStringProperty(it.value.resourceType) }
            prefWidth = 120.0
        }
        val resourceNameCol = TableColumn<AclInfo, String>("Resource Name").apply {
            setCellValueFactory { SimpleStringProperty(it.value.resourceName) }
            prefWidth = 200.0
        }
        val principalCol = TableColumn<AclInfo, String>("Principal").apply {
            setCellValueFactory { SimpleStringProperty(it.value.principal) }
            prefWidth = 180.0
        }
        val hostCol = TableColumn<AclInfo, String>("Host").apply {
            setCellValueFactory { SimpleStringProperty(it.value.host) }
            prefWidth = 120.0
        }
        val operationCol = TableColumn<AclInfo, String>("Operation").apply {
            setCellValueFactory { SimpleStringProperty(it.value.operation) }
            prefWidth = 120.0
        }
        val permissionCol = TableColumn<AclInfo, String>("Permission").apply {
            setCellValueFactory { SimpleStringProperty(it.value.permissionType) }
            prefWidth = 100.0
            setCellFactory { _ ->
                object : TableCell<AclInfo, String>() {
                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) { text = null; style = "" }
                        else {
                            text = item
                            style = when (item) {
                                "ALLOW" -> "-fx-text-fill: #4caf50;"
                                "DENY" -> "-fx-text-fill: #f44336;"
                                else -> ""
                            }
                        }
                    }
                }
            }
        }
        val patternCol = TableColumn<AclInfo, String>("Pattern Type").apply {
            setCellValueFactory { SimpleStringProperty(it.value.patternType) }
            prefWidth = 100.0
        }

        aclTable.columns.addAll(resourceTypeCol, resourceNameCol, principalCol, hostCol, operationCol, permissionCol, patternCol)
        aclTable.placeholder = Label("No ACLs found")
        VBox.setVgrow(aclTable, Priority.ALWAYS)
    }

    private fun showCreateDialog() {
        val dialog = Dialog<AclInfo>()
        dialog.title = "Create ACL"
        dialog.headerText = "Create a new ACL entry"

        val resourceTypeBox = ComboBox<String>().apply {
            items.addAll("TOPIC", "GROUP", "CLUSTER", "TRANSACTIONAL_ID", "DELEGATION_TOKEN")
            value = "TOPIC"
            maxWidth = Double.MAX_VALUE
        }
        val resourceNameField = TextField("*")
        val patternTypeBox = ComboBox<String>().apply {
            items.addAll("LITERAL", "PREFIXED")
            value = "LITERAL"
            maxWidth = Double.MAX_VALUE
        }
        val principalField = TextField("User:*")
        val hostField = TextField("*")
        val operationBox = ComboBox<String>().apply {
            items.addAll("ALL", "READ", "WRITE", "CREATE", "DELETE", "ALTER",
                "DESCRIBE", "CLUSTER_ACTION", "DESCRIBE_CONFIGS", "ALTER_CONFIGS", "IDEMPOTENT_WRITE")
            value = "READ"
            maxWidth = Double.MAX_VALUE
        }
        val permissionBox = ComboBox<String>().apply {
            items.addAll("ALLOW", "DENY")
            value = "ALLOW"
            maxWidth = Double.MAX_VALUE
        }

        val grid = GridPane().apply {
            hgap = 12.0; vgap = 8.0; padding = Insets(16.0)
            addRow(0, Label("Resource Type:"), resourceTypeBox)
            addRow(1, Label("Resource Name:"), resourceNameField)
            addRow(2, Label("Pattern Type:"), patternTypeBox)
            addRow(3, Label("Principal:"), principalField)
            addRow(4, Label("Host:"), hostField)
            addRow(5, Label("Operation:"), operationBox)
            addRow(6, Label("Permission:"), permissionBox)
            for (node in listOf(resourceTypeBox, resourceNameField, patternTypeBox,
                principalField, hostField, operationBox, permissionBox)) {
                GridPane.setHgrow(node, Priority.ALWAYS)
            }
        }

        dialog.dialogPane.content = grid
        dialog.dialogPane.prefWidth = 480.0
        val okBtn = ButtonType("Create ACL", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(okBtn, ButtonType.CANCEL)
        dialog.setResultConverter { bt ->
            if (bt.buttonData == ButtonBar.ButtonData.OK_DONE) {
                AclInfo(
                    resourceType = resourceTypeBox.value,
                    resourceName = resourceNameField.text.trim(),
                    principal = principalField.text.trim(),
                    host = hostField.text.trim(),
                    operation = operationBox.value,
                    permissionType = permissionBox.value,
                    patternType = patternTypeBox.value
                )
            } else null
        }

        dialog.showAndWait().ifPresent { aclInfo ->
            Thread {
                try {
                    adminService.createAcl(aclInfo)
                    Platform.runLater {
                        setStatus("ACL created successfully")
                        refresh()
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        setStatus("Failed to create ACL: ${e.message}")
                        val alert = Alert(Alert.AlertType.ERROR)
                        alert.title = "Create ACL Failed"
                        alert.contentText = e.message
                        alert.showAndWait()
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun deleteSelectedAcl() {
        val selected = aclTable.selectionModel.selectedItem ?: run {
            val alert = Alert(Alert.AlertType.WARNING)
            alert.contentText = "Please select an ACL to delete."
            alert.showAndWait()
            return
        }

        val confirm = Alert(Alert.AlertType.CONFIRMATION)
        confirm.title = "Delete ACL"
        confirm.contentText = "Delete ACL for principal '${selected.principal}' on '${selected.resourceName}'?"
        val result = confirm.showAndWait()
        if (!result.isPresent || result.get() != ButtonType.OK) return

        Thread {
            try {
                adminService.deleteAcl(selected)
                Platform.runLater {
                    setStatus("ACL deleted")
                    refresh()
                }
            } catch (e: Exception) {
                Platform.runLater {
                    setStatus("Failed to delete ACL: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Delete Failed"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun refresh() {
        progressIndicator.isVisible = true
        setStatus("Loading ACLs...")
        Thread {
            try {
                val acls = adminService.listAcls()
                Platform.runLater {
                    aclItems.setAll(acls)
                    progressIndicator.isVisible = false
                    setStatus("Loaded ${acls.size} ACLs")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to load ACLs: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Error"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
