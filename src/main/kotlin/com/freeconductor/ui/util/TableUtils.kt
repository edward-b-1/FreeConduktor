package com.freeconductor.ui.util

import javafx.beans.property.SimpleStringProperty
import javafx.collections.ObservableList
import javafx.scene.control.*
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

object TableUtils {

    fun <T> makeTableCopyable(table: TableView<T>) {
        val copyMenuItem = MenuItem("Copy")
        copyMenuItem.setOnAction {
            val selected = table.selectionModel.selectedItem
            if (selected != null) {
                val content = ClipboardContent()
                content.putString(selected.toString())
                Clipboard.getSystemClipboard().setContent(content)
            }
        }
        val contextMenu = ContextMenu(copyMenuItem)
        table.contextMenu = contextMenu
    }

    fun <T> setupTablePlaceholder(table: TableView<T>, message: String = "No data available") {
        table.placeholder = Label(message)
    }

    fun createStringColumn(header: String, valueExtractor: (row: Any?) -> String?): TableColumn<Any, String> {
        val col = TableColumn<Any, String>(header)
        col.setCellValueFactory { cellData ->
            SimpleStringProperty(valueExtractor(cellData.value) ?: "")
        }
        return col
    }

    fun <T> TableView<T>.growToFill() {
        VBox.setVgrow(this, Priority.ALWAYS)
    }

    fun <T> createSearchableTable(
        items: ObservableList<T>,
        filter: (T, String) -> Boolean
    ): Pair<TextField, TableView<T>> {
        val searchField = TextField()
        searchField.promptText = "Search..."

        val filteredItems = javafx.collections.FXCollections.observableArrayList(items)
        val table = TableView(filteredItems)

        searchField.textProperty().addListener { _, _, newValue ->
            filteredItems.setAll(items.filter { filter(it, newValue) })
        }

        items.addListener(javafx.collections.ListChangeListener {
            val text = searchField.text
            filteredItems.setAll(items.filter { filter(it, text) })
        })

        return Pair(searchField, table)
    }
}
