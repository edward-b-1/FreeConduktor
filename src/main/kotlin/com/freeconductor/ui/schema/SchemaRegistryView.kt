package com.freeconductor.ui.schema

import com.freeconductor.model.ClusterConfig
import com.freeconductor.model.SchemaSubject
import com.freeconductor.model.SchemaVersion
import com.freeconductor.service.SchemaRegistryService
import javafx.application.Platform
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon

class SchemaRegistryView(
    private val cluster: ClusterConfig,
    private var schemaService: SchemaRegistryService,
    private val setStatus: (String) -> Unit
) {
    val root: BorderPane = BorderPane()
    private val subjectItems = FXCollections.observableArrayList<SchemaSubject>()
    private val subjectTable = TableView(subjectItems)
    private val versionItems = FXCollections.observableArrayList<SchemaVersion>()
    private val versionList = ListView(versionItems)
    private val schemaText = TextArea().apply {
        isEditable = false
        isWrapText = true
        styleClass.add("code-area")
    }
    private val compatibilityLabel = Label("Compatibility: -")
    private val progressIndicator = ProgressIndicator()
    private var selectedSubject: SchemaSubject? = null

    init {
        setupUI()
    }

    private fun setupUI() {
        val toolbar = buildToolbar()
        setupSubjectTable()
        setupVersionList()

        progressIndicator.isVisible = false
        progressIndicator.maxWidth = 40.0; progressIndicator.maxHeight = 40.0

        val subjectContainer = VBox(
            Label("Subjects").apply { styleClass.add("section-header"); padding = Insets(4.0, 8.0, 4.0, 8.0) },
            StackPane(subjectTable, progressIndicator)
        ).apply {
            VBox.setVgrow(subjectTable, Priority.ALWAYS)
            VBox.setVgrow(children[1] as StackPane, Priority.ALWAYS)
        }

        val versionContainer = VBox(
            Label("Versions").apply { styleClass.add("section-header"); padding = Insets(4.0, 8.0, 4.0, 8.0) },
            versionList
        ).apply {
            VBox.setVgrow(versionList, Priority.ALWAYS)
            prefWidth = 120.0
        }

        val schemaContainer = VBox(
            HBox(8.0, Label("Schema").apply { styleClass.add("section-header") },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                compatibilityLabel
            ).apply { padding = Insets(4.0, 8.0, 4.0, 8.0); alignment = javafx.geometry.Pos.CENTER_LEFT },
            schemaText
        ).apply {
            VBox.setVgrow(schemaText, Priority.ALWAYS)
        }

        val rightSplit = SplitPane(versionContainer, schemaContainer).apply {
            orientation = Orientation.HORIZONTAL
            setDividerPositions(0.25)
        }

        val mainSplit = SplitPane(subjectContainer, rightSplit).apply {
            orientation = Orientation.HORIZONTAL
            setDividerPositions(0.35)
        }

        root.top = toolbar
        root.center = mainSplit
    }

    private fun buildToolbar(): HBox {
        val searchField = TextField().apply {
            promptText = "Search subjects..."
            prefWidth = 200.0
        }
        searchField.textProperty().addListener { _, _, newVal ->
            subjectTable.items = if (newVal.isBlank()) subjectItems
            else FXCollections.observableArrayList(subjectItems.filter {
                it.name.contains(newVal, ignoreCase = true)
            })
        }

        val registerButton = Button("Register Schema").apply {
            styleClass.add("accent")
            setOnAction { showRegisterDialog() }
        }

        val deleteSubjectButton = Button("Delete Subject").apply {
            styleClass.add("danger")
            setOnAction { deleteSelectedSubject() }
        }

        val checkCompatButton = Button("Check Compatibility").apply {
            setOnAction { checkCompatibility() }
        }

        val refreshButton = Button("Refresh", FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 12 }).apply {
            setOnAction { refresh() }
        }

        return HBox(8.0).apply {
            padding = Insets(8.0)
            children.addAll(
                Label("Schema Registry").apply { styleClass.add("view-title") },
                searchField,
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                registerButton,
                checkCompatButton,
                deleteSubjectButton,
                refreshButton
            )
            alignment = javafx.geometry.Pos.CENTER_LEFT
        }
    }

    private fun setupSubjectTable() {
        val nameCol = TableColumn<SchemaSubject, String>("Subject").apply {
            setCellValueFactory { SimpleStringProperty(it.value.name) }
            prefWidth = 250.0
        }
        val versionCol = TableColumn<SchemaSubject, String>("Versions").apply {
            setCellValueFactory { SimpleStringProperty(it.value.versions.size.toString()) }
            prefWidth = 80.0
        }
        val latestCol = TableColumn<SchemaSubject, String>("Latest").apply {
            setCellValueFactory { SimpleStringProperty(it.value.latestVersion.toString()) }
            prefWidth = 80.0
        }
        val typeCol = TableColumn<SchemaSubject, String>("Type").apply {
            setCellValueFactory { SimpleStringProperty(it.value.schemaType) }
            prefWidth = 80.0
        }

        subjectTable.columns.addAll(nameCol, versionCol, latestCol, typeCol)
        subjectTable.placeholder = Label("No subjects found")
        VBox.setVgrow(subjectTable, Priority.ALWAYS)

        subjectTable.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
            if (newVal != null) {
                selectedSubject = newVal
                loadVersions(newVal)
            }
        }
    }

    private fun setupVersionList() {
        versionList.setCellFactory { _ ->
            object : ListCell<SchemaVersion>() {
                override fun updateItem(item: SchemaVersion?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else "v${item.version} (id: ${item.schemaId})"
                }
            }
        }

        versionList.selectionModel.selectedItemProperty().addListener { _, _, newVal ->
            if (newVal != null) {
                schemaText.text = formatSchema(newVal.schema)
                loadCompatibility(newVal.subject)
            }
        }
    }

    private fun loadVersions(subject: SchemaSubject) {
        versionItems.clear()
        schemaText.clear()
        Thread {
            try {
                val versions = subject.versions.mapNotNull { version ->
                    try { schemaService.getSchemaVersion(subject.name, version) } catch (_: Exception) { null }
                }
                Platform.runLater {
                    versionItems.setAll(versions)
                    if (versions.isNotEmpty()) {
                        versionList.selectionModel.selectLast()
                    }
                    setStatus("Loaded ${versions.size} versions for ${subject.name}")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    setStatus("Failed to load versions: ${e.message}")
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun loadCompatibility(subject: String) {
        Thread {
            try {
                val compat = schemaService.getCompatibility(subject)
                Platform.runLater {
                    compatibilityLabel.text = "Compatibility: $compat"
                }
            } catch (_: Exception) {
                Platform.runLater {
                    compatibilityLabel.text = "Compatibility: unknown"
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun formatSchema(schema: String): String {
        return try {
            val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
            val node = mapper.readTree(schema)
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node)
        } catch (_: Exception) {
            schema
        }
    }

    private fun showRegisterDialog() {
        val dialog = Dialog<Triple<String, String, String>>()
        dialog.title = "Register Schema"
        dialog.headerText = "Register a new schema version"

        val subjectField = TextField(selectedSubject?.name ?: "")
        subjectField.promptText = "subject-name"
        val schemaTypeBox = ComboBox<String>().apply {
            items.addAll("AVRO", "JSON", "PROTOBUF")
            value = "AVRO"
        }
        val schemaArea = TextArea().apply {
            promptText = "Paste schema here..."
            prefHeight = 300.0
            isWrapText = true
        }

        val grid = GridPane().apply {
            hgap = 12.0; vgap = 8.0; padding = Insets(12.0)
            addRow(0, Label("Subject:"), subjectField)
            addRow(1, Label("Schema Type:"), schemaTypeBox)
            addRow(2, Label("Schema:"), schemaArea)
            GridPane.setHgrow(subjectField, Priority.ALWAYS)
            GridPane.setHgrow(schemaTypeBox, Priority.ALWAYS)
            GridPane.setHgrow(schemaArea, Priority.ALWAYS)
            schemaTypeBox.maxWidth = Double.MAX_VALUE
        }

        dialog.dialogPane.content = grid
        dialog.dialogPane.prefWidth = 600.0
        dialog.dialogPane.prefHeight = 500.0
        val okBtn = ButtonType("Register", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(okBtn, ButtonType.CANCEL)
        dialog.setResultConverter { bt ->
            if (bt.buttonData == ButtonBar.ButtonData.OK_DONE) {
                Triple(subjectField.text.trim(), schemaTypeBox.value, schemaArea.text.trim())
            } else null
        }

        dialog.showAndWait().ifPresent { (subject, type, schema) ->
            if (subject.isBlank() || schema.isBlank()) return@ifPresent
            Thread {
                try {
                    val id = schemaService.registerSchema(subject, schema, type)
                    Platform.runLater {
                        setStatus("Registered schema for '$subject' with ID $id")
                        refresh()
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        setStatus("Failed to register schema: ${e.message}")
                        val alert = Alert(Alert.AlertType.ERROR)
                        alert.title = "Register Failed"
                        alert.contentText = e.message
                        alert.showAndWait()
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun deleteSelectedSubject() {
        val subject = selectedSubject ?: run {
            val alert = Alert(Alert.AlertType.WARNING)
            alert.title = "No Selection"
            alert.contentText = "Please select a subject."
            alert.showAndWait()
            return
        }

        val confirm = Alert(Alert.AlertType.CONFIRMATION)
        confirm.title = "Delete Subject"
        confirm.headerText = "Delete subject '${subject.name}'?"
        confirm.contentText = "All versions of this schema will be deleted."
        val result = confirm.showAndWait()
        if (!result.isPresent || result.get() != ButtonType.OK) return

        Thread {
            try {
                val deletedVersions = schemaService.deleteSubject(subject.name)
                Platform.runLater {
                    setStatus("Deleted subject '${subject.name}' (${deletedVersions.size} versions)")
                    refresh()
                }
            } catch (e: Exception) {
                Platform.runLater {
                    setStatus("Failed to delete subject: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Delete Failed"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun checkCompatibility() {
        val subject = selectedSubject ?: run {
            val alert = Alert(Alert.AlertType.WARNING)
            alert.contentText = "Please select a subject first."
            alert.showAndWait()
            return
        }

        val dialog = Dialog<Pair<String, String>>()
        dialog.title = "Check Compatibility"
        dialog.headerText = "Check schema compatibility for: ${subject.name}"
        val schemaArea = TextArea().apply {
            promptText = "Paste schema to check..."
            prefHeight = 200.0
            isWrapText = true
        }
        val schemaTypeBox = ComboBox<String>().apply {
            items.addAll("AVRO", "JSON", "PROTOBUF")
            value = "AVRO"
        }
        val vbox = VBox(8.0, Label("Schema Type:"), schemaTypeBox, Label("Schema:"), schemaArea)
        vbox.padding = Insets(12.0)
        schemaTypeBox.maxWidth = Double.MAX_VALUE
        dialog.dialogPane.content = vbox
        dialog.dialogPane.prefWidth = 500.0
        val checkBtn = ButtonType("Check", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(checkBtn, ButtonType.CANCEL)
        dialog.setResultConverter { bt ->
            if (bt.buttonData == ButtonBar.ButtonData.OK_DONE) Pair(schemaTypeBox.value, schemaArea.text.trim())
            else null
        }

        dialog.showAndWait().ifPresent { (type, schema) ->
            if (schema.isBlank()) return@ifPresent
            Thread {
                try {
                    val compatible = schemaService.checkCompatibility(subject.name, schema, type)
                    Platform.runLater {
                        val alert = Alert(if (compatible) Alert.AlertType.INFORMATION else Alert.AlertType.WARNING)
                        alert.title = "Compatibility Check"
                        alert.contentText = if (compatible) "Schema is COMPATIBLE" else "Schema is NOT COMPATIBLE"
                        alert.showAndWait()
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        val alert = Alert(Alert.AlertType.ERROR)
                        alert.title = "Error"
                        alert.contentText = e.message
                        alert.showAndWait()
                    }
                }
            }.also { it.isDaemon = true }.start()
        }
    }

    fun refresh() {
        progressIndicator.isVisible = true
        setStatus("Loading Schema Registry subjects...")
        Thread {
            try {
                val subjects = schemaService.listSubjects()
                Platform.runLater {
                    subjectItems.setAll(subjects)
                    progressIndicator.isVisible = false
                    setStatus("Loaded ${subjects.size} subjects")
                }
            } catch (e: Exception) {
                Platform.runLater {
                    progressIndicator.isVisible = false
                    setStatus("Failed to load Schema Registry: ${e.message}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Schema Registry Error"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }
}
