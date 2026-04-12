package com.freeconductor.ui

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.ClusterConfigService
import com.freeconductor.ui.cluster.AddClusterDialog
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon
import java.net.InetSocketAddress
import java.net.Socket

class ClusterSidebar(
    private val onClusterSelected: (ClusterConfig) -> Unit,
    private val onNavigate: (String) -> Unit,
    private val onClustersChanged: () -> Unit = {},
    private val onDisconnect: () -> Unit = {}
) {
    val root: VBox = VBox()
    private val configService = ClusterConfigService()
    private var clusters = mutableListOf<ClusterConfig>()
    private val clusterList = ListView<ClusterConfig>()
    private val navBox = VBox(2.0)
    private var connectedCluster: ClusterConfig? = null
    private val navButtons = mutableListOf<Pair<String, Button>>()
    private var activeView: String? = null

    // null = unchecked, true = reachable, false = unreachable
    private val onlineStatus = mutableMapOf<String, Boolean?>()

    private val disconnectedPane = VBox()
    private val connectedPane = VBox()

    private val connectedHeader = VBox().apply {
        styleClass.add("connected-cluster-header")
        padding = Insets(0.0)
    }

    // ── Collapse state ────────────────────────────────────────────────────
    private val EXPANDED_WIDTH = 220.0
    private val COLLAPSED_WIDTH = 52.0
    private var isCollapsed = false

    // References kept across setConnected() calls so applyCollapseState()
    // can toggle between the two visual modes without rebuilding the tree.
    private var storedDisconnectBtn: Button? = null
    private var storedClusterRow: HBox? = null          // the HBox inside connectedHeader
    private var storedColorStrip: Region? = null        // the 8-px colour strip
    private var storedClusterInfo: VBox? = null         // name + host VBox
    private var storedClusterColor: String = "-color-accent-fg"
    // Separate icon node used only in the collapsed icon area
    private var storedBadgeIcon: javafx.scene.Node? = null

    private val collapseBtn = Button("‹").apply {
        styleClass.add("sidebar-collapse-btn")
        maxWidth = Double.MAX_VALUE
        setOnAction { toggleCollapse() }
    }
    private val collapseBtnRow = HBox(collapseBtn).apply {
        alignment = Pos.CENTER_RIGHT
        padding = Insets(4.0, 6.0, 6.0, 6.0)
        isVisible = false
        isManaged = false
    }

    init {
        root.prefWidth = EXPANDED_WIDTH
        root.minWidth = EXPANDED_WIDTH
        root.maxWidth = EXPANDED_WIDTH
        root.styleClass.add("sidebar")
        setupUI()
        loadClusters()
    }

    private fun setupUI() {
        val headerLabel = Label("CLUSTERS").apply {
            styleClass.add("sidebar-header")
            padding = Insets(8.0, 8.0, 4.0, 8.0)
        }
        clusterList.apply {
            styleClass.add("cluster-list")
            maxHeight = Double.MAX_VALUE
            setCellFactory { ClusterListCell() }
            setOnMouseClicked { event ->
                if (event.clickCount == 2)
                    selectionModel.selectedItem?.let { onClusterSelected(it) }
            }
        }
        clusterList.contextMenu = ContextMenu(
            MenuItem("Connect").apply {
                setOnAction { clusterList.selectionModel.selectedItem?.let { onClusterSelected(it) } }
            },
            SeparatorMenuItem(),
            MenuItem("Edit…").apply {
                setOnAction { clusterList.selectionModel.selectedItem?.let { showEditClusterDialog(it) } }
            },
            MenuItem("Delete").apply {
                setOnAction { clusterList.selectionModel.selectedItem?.let { deleteCluster(it) } }
            }
        )
        val addBox = VBox(Button("+ New Connection").apply {
            maxWidth = Double.MAX_VALUE
            styleClass.add("accent")
            setOnAction { showAddClusterDialog() }
        }).apply { padding = Insets(4.0, 8.0, 8.0, 8.0) }

        disconnectedPane.apply {
            children.addAll(headerLabel, clusterList, addBox)
            VBox.setVgrow(clusterList, Priority.ALWAYS)
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        navBox.padding = Insets(4.0, 6.0, 8.0, 6.0)
        connectedPane.apply {
            isVisible = false; isManaged = false
            children.addAll(connectedHeader, navBox)
            VBox.setVgrow(navBox, Priority.ALWAYS)
            VBox.setVgrow(this, Priority.ALWAYS)
        }

        root.children.addAll(disconnectedPane, connectedPane, collapseBtnRow)
        VBox.setVgrow(root, Priority.ALWAYS)
    }

    fun triggerAddCluster() = showAddClusterDialog()

    private fun createNavButton(label: String, viewName: String, icon: FontAwesomeSolid) =
        Button(label, FontIcon(icon).also { it.iconSize = 14 }).apply {
            maxWidth = Double.MAX_VALUE
            styleClass.add("nav-button")
            contentDisplay = ContentDisplay.LEFT
            setOnAction { activeView = viewName; refreshNavButtonStyles(); onNavigate(viewName) }
        }.also { navButtons.add(viewName to it) }

    private fun refreshNavButtonStyles() {
        navButtons.forEach { (v, btn) ->
            btn.styleClass.removeAll("nav-button-active")
            if (v == activeView) btn.styleClass.add("nav-button-active")
        }
    }

    fun setActive(viewName: String) { activeView = viewName; refreshNavButtonStyles() }

    fun setConnected(cluster: ClusterConfig) {
        connectedCluster = cluster
        storedClusterColor = cluster.color ?: "-color-accent-fg"

        // ── Disconnect button ─────────────────────────────────────────
        val disconnectBtn = Button("← All Clusters").apply {
            maxWidth = Double.MAX_VALUE
            styleClass.add("disconnect-btn")
            setOnAction { disconnect() }
        }
        storedDisconnectBtn = disconnectBtn

        // ── Colour strip ──────────────────────────────────────────────
        val colorStrip = Region().apply {
            prefWidth = 8.0; minWidth = 8.0; maxWidth = 8.0
            style = "-fx-background-color: $storedClusterColor;"
        }
        storedColorStrip = colorStrip

        // ── Expanded cluster info (name + host) ───────────────────────
        val kafkaStream = ClusterSidebar::class.java
            .getResourceAsStream("/com/freeconductor/icons/kafka-logo.png")
        val clusterIconExpanded = if (kafkaStream != null)
            javafx.scene.image.ImageView(
                javafx.scene.image.Image(kafkaStream, 0.0, 72.0, true, true)
            ).apply { fitHeight = 36.0; isPreserveRatio = true; isSmooth = true }
        else
            Label(null, FontIcon(FontAwesomeSolid.SITEMAP).also { it.iconSize = 20 })

        val nameRow = HBox(8.0, clusterIconExpanded, Label(cluster.name).apply {
            styleClass.add("connected-cluster-name")
            if (cluster.color != null) style = "-fx-text-fill: ${cluster.color};"
        }).apply { alignment = Pos.CENTER_LEFT }

        val clusterInfo = VBox(3.0).apply {
            padding = Insets(10.0, 12.0, 10.0, 14.0)
            children.addAll(
                nameRow,
                Label(cluster.bootstrapServers).apply { styleClass.add("connected-cluster-host") }
            )
        }
        storedClusterInfo = clusterInfo

        // ── Collapsed icon (separate node, not shared with expanded tree) ──
        val kafkaStream2 = ClusterSidebar::class.java
            .getResourceAsStream("/com/freeconductor/icons/kafka-logo.png")
        storedBadgeIcon = if (kafkaStream2 != null)
            javafx.scene.image.ImageView(
                javafx.scene.image.Image(kafkaStream2, 0.0, 40.0, true, true)
            ).apply { fitHeight = 40.0; isPreserveRatio = true; isSmooth = true }
        else
            FontIcon(FontAwesomeSolid.SITEMAP).also {
                it.iconSize = 36; it.style = "-fx-icon-color: white;"
            }

        // ── Cluster row (shared HBox, content swapped on collapse) ────
        val clusterRow = HBox(colorStrip, clusterInfo).apply {
            styleClass.add("connected-cluster-info-row")
            alignment = Pos.CENTER_LEFT
        }
        storedClusterRow = clusterRow

        connectedHeader.children.setAll(disconnectBtn, clusterRow)

        // ── Nav buttons ───────────────────────────────────────────────
        navButtons.clear()
        navBox.children.setAll(
            createNavButton("Overview",        "overview",       FontAwesomeSolid.HOME),
            createNavButton("Brokers",         "brokers",        FontAwesomeSolid.SERVER),
            createNavButton("Topics",          "topics",         FontAwesomeSolid.LIST),
            createNavButton("Consumer Groups", "consumergroups", FontAwesomeSolid.USERS),
            createNavButton("Schema Registry", "schema",         FontAwesomeSolid.DATABASE),
            createNavButton("Kafka Connect",   "connect",        FontAwesomeSolid.PLUG),
            createNavButton("Kafka Streams",   "streams",        FontAwesomeSolid.STREAM),
            createNavButton("Security",        "security",       FontAwesomeSolid.LOCK)
        )

        disconnectedPane.isVisible = false; disconnectedPane.isManaged = false
        connectedPane.isVisible = true;     connectedPane.isManaged = true
        collapseBtnRow.isVisible = true;    collapseBtnRow.isManaged = true
        applyCollapseState()
    }

    fun disconnect() {
        if (isCollapsed) { isCollapsed = false; applyCollapseState() }
        connectedCluster = null; activeView = null; navButtons.clear()
        storedDisconnectBtn = null; storedClusterRow = null
        storedColorStrip = null; storedClusterInfo = null; storedBadgeIcon = null
        connectedPane.isVisible = false; connectedPane.isManaged = false
        disconnectedPane.isVisible = true; disconnectedPane.isManaged = true
        collapseBtnRow.isVisible = false; collapseBtnRow.isManaged = false
        onDisconnect()
    }

    // ── Collapse / expand ─────────────────────────────────────────────────

    private fun toggleCollapse() { isCollapsed = !isCollapsed; applyCollapseState() }

    private fun applyCollapseState() {
        val clusterRow = storedClusterRow ?: return

        if (isCollapsed) {
            root.prefWidth = COLLAPSED_WIDTH; root.minWidth = COLLAPSED_WIDTH; root.maxWidth = COLLAPSED_WIDTH
            collapseBtn.text = "›"

            // Disconnect button → sign-out icon, centred
            storedDisconnectBtn?.apply {
                text = ""
                graphic = FontIcon(FontAwesomeSolid.SIGN_OUT_ALT).also { it.iconSize = 13 }
                contentDisplay = ContentDisplay.GRAPHIC_ONLY
                style = "-fx-padding: 7 0 7 0; -fx-alignment: center;"
            }

            // Capture the rendered height of clusterInfo *before* removing it
            // so the coloured badge area can be given the same minHeight.
            val infoHeight = storedClusterInfo?.let { info ->
                info.height.takeIf { it > 0.0 } ?: info.prefHeight(-1.0)
            } ?: 76.0

            // Swap clusterRow contents: full-width coloured area with badge icon
            val badge = storedBadgeIcon
            if (badge != null) {
                val coloredArea = StackPane(badge).apply {
                    alignment = Pos.CENTER
                    maxWidth = Double.MAX_VALUE
                    minHeight = infoHeight
                    prefHeight = infoHeight
                    style = "-fx-background-color: $storedClusterColor;"
                }
                HBox.setHgrow(coloredArea, Priority.ALWAYS)
                clusterRow.children.setAll(coloredArea)
            }

            // Nav buttons → icon only, centred
            navBox.padding = Insets(4.0, 2.0, 8.0, 2.0)
            navButtons.forEach { (_, btn) ->
                btn.contentDisplay = ContentDisplay.GRAPHIC_ONLY
                btn.style = "-fx-padding: 9 0 9 0; -fx-alignment: center;"
            }
        } else {
            root.prefWidth = EXPANDED_WIDTH; root.minWidth = EXPANDED_WIDTH; root.maxWidth = EXPANDED_WIDTH
            collapseBtn.text = "‹"

            // Restore disconnect button
            storedDisconnectBtn?.apply {
                text = "← All Clusters"; graphic = null
                contentDisplay = ContentDisplay.LEFT
                alignment = Pos.CENTER_LEFT; style = ""
            }

            // Restore clusterRow to colour strip + full info
            val strip = storedColorStrip
            val info  = storedClusterInfo
            if (strip != null && info != null) {
                clusterRow.children.setAll(strip, info)
            }

            // Restore nav buttons
            navBox.padding = Insets(4.0, 6.0, 8.0, 6.0)
            navButtons.forEach { (_, btn) ->
                btn.contentDisplay = ContentDisplay.LEFT
                btn.alignment = Pos.CENTER_LEFT; btn.style = ""
            }
        }
        refreshNavButtonStyles()
    }

    // ── Cluster management ────────────────────────────────────────────────

    private fun loadClusters() { clusters = configService.loadClusters(); refreshClusterList(); checkConnectivity() }

    private fun checkConnectivity() {
        clusters.forEach { cluster ->
            onlineStatus[cluster.id] = null
            Thread {
                val reachable = try {
                    val parts = cluster.bootstrapServers.split(",")[0].trim().split(":")
                    Socket().use { it.connect(InetSocketAddress(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 9092), 2000); true }
                } catch (_: Exception) { false }
                Platform.runLater { onlineStatus[cluster.id] = reachable; clusterList.refresh() }
            }.also { it.isDaemon = true }.start()
        }
    }

    private fun refreshClusterList() { clusterList.items.setAll(clusters) }

    private fun showAddClusterDialog() {
        AddClusterDialog(null).showAndWait().ifPresent {
            configService.addCluster(it); clusters = configService.loadClusters()
            refreshClusterList(); checkConnectivity(); onClustersChanged()
        }
    }

    private fun showEditClusterDialog(cluster: ClusterConfig) {
        AddClusterDialog(cluster).showAndWait().ifPresent {
            configService.updateCluster(it); clusters = configService.loadClusters()
            refreshClusterList(); checkConnectivity(); onClustersChanged()
        }
    }

    private fun deleteCluster(cluster: ClusterConfig) {
        Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "Delete Cluster"; headerText = "Delete cluster '${cluster.name}'?"
            contentText = "This cannot be undone."
        }.showAndWait().ifPresent {
            if (it == ButtonType.OK) {
                configService.deleteCluster(cluster.id); clusters = configService.loadClusters()
                refreshClusterList(); checkConnectivity(); onClustersChanged()
                if (connectedCluster?.id == cluster.id) disconnect()
            }
        }
    }

    private inner class ClusterListCell : ListCell<ClusterConfig>() {
        override fun updateItem(item: ClusterConfig?, empty: Boolean) {
            super.updateItem(item, empty)
            if (empty || item == null) { text = null; graphic = null; return }

            styleClass.removeAll("connected-cluster")
            if (connectedCluster?.id == item.id) styleClass.add("connected-cluster")

            val strip = Region().apply {
                prefWidth = 6.0; minWidth = 6.0; maxWidth = 6.0
                style = "-fx-background-color: ${item.color ?: "#000000"};"
            }
            val nameLabel = Label(item.name).apply {
                styleClass.add("cluster-cell-name")
                minWidth = 0.0; maxWidth = Double.MAX_VALUE
                if (item.color != null) style = "-fx-text-fill: ${item.color};"
            }
            val hostLabel = Label(item.bootstrapServers).apply {
                styleClass.add("cluster-cell-detail"); minWidth = 0.0; maxWidth = Double.MAX_VALUE
            }
            val authLabel = Label(item.securityProtocol).apply {
                styleClass.add("cluster-cell-detail-plain"); minWidth = 0.0; maxWidth = Double.MAX_VALUE
            }
            val statusLabel = Label(when (onlineStatus[item.id]) {
                true -> "CONNECTED"; false -> "UNREACHABLE"; else -> "CONNECTING..."
            }).apply {
                styleClass.add(when (onlineStatus[item.id]) {
                    true -> "cluster-cell-status-connected"
                    false -> "cluster-cell-status-unreachable"
                    else -> "cluster-cell-status-checking"
                })
                minWidth = 0.0; maxWidth = Double.MAX_VALUE
            }
            val textBox = VBox(2.0, nameLabel, hostLabel, authLabel, statusLabel).apply {
                padding = Insets(6.0, 8.0, 6.0, 8.0); maxWidth = Double.MAX_VALUE
            }
            graphic = HBox(strip, textBox).apply {
                alignment = Pos.CENTER_LEFT
                HBox.setHgrow(textBox, Priority.ALWAYS)
                padding = Insets(0.0, 0.0, 0.0, 8.0)
                prefWidthProperty().bind(clusterList.widthProperty().subtract(2))
            }
            text = null
        }
    }
}
