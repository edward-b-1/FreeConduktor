package com.freeconductor.ui

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.*
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.util.Duration
import org.kordamp.ikonli.fontawesome5.FontAwesomeSolid
import org.kordamp.ikonli.javafx.FontIcon
import com.freeconductor.ui.acl.AclView
import com.freeconductor.ui.security.SecurityView
import com.freeconductor.ui.streams.KafkaStreamsView
import com.freeconductor.ui.cluster.BrokerDetailView
import com.freeconductor.ui.cluster.BrokersView
import com.freeconductor.ui.connect.KafkaConnectView
import com.freeconductor.ui.consume.ConsumerWindow
import com.freeconductor.ui.consumergroups.ConsumerGroupDetailView
import com.freeconductor.ui.consumergroups.ConsumerGroupsView
import com.freeconductor.ui.produce.ProducerDialog
import com.freeconductor.ui.schema.SchemaRegistryView
import com.freeconductor.ui.topics.TopicDetailView
import com.freeconductor.ui.topics.TopicsView
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.Side
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.*
import javafx.stage.Stage

class MainWindow(private val stage: Stage) {
    val root: BorderPane = BorderPane()
    private val statusLabel = Label("Ready")
    private val statusBar = HBox(statusLabel).apply {
        padding = Insets(4.0, 8.0, 4.0, 8.0)
        styleClass.add("status-bar")
    }

    private var currentCluster: ClusterConfig? = null
    private var adminService: KafkaAdminService? = null
    private val contentArea = StackPane().apply { styleClass.add("content-area") }
    private lateinit var sidebar: ClusterSidebar
    private val configService = ClusterConfigService()
    private val consumerBtn = Button("⬇ Consume")
    private val producerBtn = Button("⬆ Produce")
    private val homeBtn = Button("Clusters", FontIcon(FontAwesomeSolid.SIGN_OUT_ALT).also { it.iconSize = 14 }).apply {
        styleClass.addAll("home-btn", "toolbar-action-btn")
        isVisible = false
        isManaged = false
    }

    // ── Auto-refresh ──────────────────────────────────────────────────────
    private var autoRefreshTimeline: Timeline? = null
    private val refreshBtn = SplitMenuButton().apply {
        graphic = FontIcon(FontAwesomeSolid.SYNC_ALT).also { it.iconSize = 11 }
        styleClass.add("refresh-split-btn")
        isDisable = true
    }
    private lateinit var settingsBtn: MenuButton

    // ── Navigation history ────────────────────────────────────────────────
    private val backStack = ArrayDeque<() -> Unit>()
    private val forwardStack = ArrayDeque<() -> Unit>()
    private var currentNav: (() -> Unit)? = null

    private val backBtn = Button("←").apply {
        styleClass.add("nav-btn")
        isDisable = true
    }
    private val forwardBtn = Button("→").apply {
        styleClass.add("nav-btn")
        isDisable = true
    }

    init {
        setupMenuBar()
        setupLayout()
    }

    /** Push current view onto the back stack and display [action] as the new current view. */
    private fun navigate(action: () -> Unit) {
        currentNav?.let { backStack.addLast(it) }
        forwardStack.clear()
        currentNav = action
        action()
        updateNavButtons()
    }

    private fun navigateBack() {
        if (backStack.isEmpty()) return
        currentNav?.let { forwardStack.addLast(it) }
        currentNav = backStack.removeLast()
        currentNav!!()
        updateNavButtons()
    }

    private fun navigateForward() {
        if (forwardStack.isEmpty()) return
        currentNav?.let { backStack.addLast(it) }
        currentNav = forwardStack.removeLast()
        currentNav!!()
        updateNavButtons()
    }

    private fun updateNavButtons() {
        backBtn.isDisable = backStack.isEmpty()
        forwardBtn.isDisable = forwardStack.isEmpty()
    }

    private fun resetNavHistory() {
        backStack.clear()
        forwardStack.clear()
        currentNav = null
        updateNavButtons()
    }

    private fun setupMenuBar() {
        // ── Action buttons ────────────────────────────────────────────────
        consumerBtn.apply {
            styleClass.addAll("accent", "toolbar-action-btn")
            isDisable = true
            setOnAction {
                val cluster = currentCluster ?: return@setOnAction
                val admin = adminService ?: return@setOnAction
                ConsumerWindow("", cluster, admin, ::setStatus)
            }
        }
        producerBtn.apply {
            styleClass.addAll("success", "toolbar-action-btn")
            isDisable = true
            setOnAction {
                val cluster = currentCluster ?: return@setOnAction
                ProducerDialog(cluster, null).show()
            }
        }

        backBtn.setOnAction { navigateBack() }
        forwardBtn.setOnAction { navigateForward() }

        // ── Refresh / auto-refresh button ─────────────────────────────────
        fun doRefresh() { currentNav?.invoke() }
        fun setAutoRefresh(seconds: Int) {
            autoRefreshTimeline?.stop()
            autoRefreshTimeline = Timeline(
                KeyFrame(Duration.seconds(seconds.toDouble()), javafx.event.EventHandler { doRefresh() })
            ).apply { cycleCount = Timeline.INDEFINITE; play() }
        }
        fun stopAutoRefresh() {
            autoRefreshTimeline?.stop()
            autoRefreshTimeline = null
        }

        refreshBtn.apply {
            setOnAction { stopAutoRefresh(); doRefresh() }
            items.addAll(
                MenuItem("Just Now  [Ctrl+R]").apply { setOnAction { stopAutoRefresh(); doRefresh() } },
                SeparatorMenuItem(),
                MenuItem("Every 10s").apply  { setOnAction { setAutoRefresh(10) } },
                MenuItem("Every 30s").apply  { setOnAction { setAutoRefresh(30) } },
                MenuItem("Every 1min").apply { setOnAction { setAutoRefresh(60) } }
            )
        }

        // Ctrl+R shortcut — attach once the scene is available
        root.sceneProperty().addListener { _, _, scene ->
            scene?.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
                if (e.isControlDown && e.code == KeyCode.R) { stopAutoRefresh(); doRefresh(); e.consume() }
            }
        }

        // ── Settings / options button (replaces menu bar) ─────────────────
        settingsBtn = MenuButton("Options", FontIcon(FontAwesomeSolid.COG).also { it.iconSize = 12 }).apply {
            styleClass.add("settings-btn")
            items.addAll(
                MenuItem("Light Theme").apply {
                    setOnAction { Application.setUserAgentStylesheet(atlantafx.base.theme.PrimerLight().userAgentStylesheet) }
                },
                MenuItem("Dark Theme").apply {
                    setOnAction { Application.setUserAgentStylesheet(atlantafx.base.theme.NordDark().userAgentStylesheet) }
                },
                SeparatorMenuItem(),
                MenuItem("About FreeConduktor").apply {
                    setOnAction {
                        Alert(Alert.AlertType.INFORMATION).apply {
                            title = "About"; headerText = "FreeConduktor"
                            contentText = "Kafka Management GUI\nVersion 0.1.1\n\nVibe coded by Claude (Sonnet 4.6)"
                            showAndWait()
                        }
                    }
                },
                SeparatorMenuItem(),
                MenuItem("Exit").apply { setOnAction { Platform.exit() } }
            )
        }
        // Toolbar is assembled in setupLayout() where sidebar width is available for binding
    }

    private fun disconnectCluster() {
        autoRefreshTimeline?.stop(); autoRefreshTimeline = null
        currentCluster = null
        adminService = null
        consumerBtn.isDisable = true
        producerBtn.isDisable = true
        refreshBtn.isDisable = true
        homeBtn.isVisible = false
        homeBtn.isManaged = false
        resetNavHistory()
        showWelcome()
    }

    private fun setupLayout() {
        sidebar = ClusterSidebar(
            onClusterSelected = { cluster -> connectToCluster(cluster) },
            onNavigate = { view -> showView(view) },
            onClustersChanged = { if (currentCluster == null) showWelcome() },
            onDisconnect = { disconnectCluster() }
        )
        homeBtn.setOnAction { sidebar.disconnect() }

        // ── Toolbar ───────────────────────────────────────────────────────
        // Logo container width is bound to the sidebar width so that the nav
        // buttons land exactly at the left edge of the main content pane.
        // Toolbar has 16px left padding + 10px HBox spacing = subtract 26.
        val stream = MainWindow::class.java
            .getResourceAsStream("/com/freeconductor/icons/free-conduktor-banner-400.png")
        val logoBox = HBox().apply {
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            prefWidthProperty().bind(sidebar.root.widthProperty().subtract(26.0))
            children.add(
                if (stream != null) ImageView(Image(stream, 190.0, 0.0, true, true))
                else Label("FREECONDUKTOR").apply { styleClass.add("sidebar-logo-text") }
            )
        }
        root.top = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(0.0, 8.0, 0.0, 16.0)
            styleClass.add("primary-toolbar")
            children.addAll(
                logoBox,
                HBox(4.0, backBtn, forwardBtn).apply { alignment = Pos.CENTER_LEFT },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                consumerBtn, producerBtn, refreshBtn, settingsBtn, homeBtn
            )
        }

        // ── Main area: fixed sidebar + growing content ────────────────────
        // HBox instead of SplitPane removes the draggable divider; sidebar
        // width is controlled solely by the collapse toggle button.
        val mainArea = HBox()
        HBox.setHgrow(contentArea, Priority.ALWAYS)
        mainArea.children.addAll(sidebar.root, contentArea)

        root.center = mainArea
        root.bottom = statusBar

        showWelcome()
    }

    private fun showWelcome() {
        val clusters = configService.loadClusters()

        val page = VBox(28.0).apply {
            padding = Insets(36.0, 48.0, 36.0, 48.0)
        }

        // Header row: title + new connection button
        val titleRow = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("Clusters").apply { styleClass.add("view-title") },
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                Button("+ New Connection").apply {
                    styleClass.add("accent")
                    setOnAction { sidebar.triggerAddCluster() }
                }
            )
        }

        if (clusters.isEmpty()) {
            // Empty state: logo + prompt
            val logoImg = MainWindow::class.java
                .getResourceAsStream("/com/freeconductor/icons/free-conduktor-banner-400.png")
                ?.let { ImageView(Image(it, 200.0, 0.0, true, true)) }

            val emptyBox = VBox(20.0).apply {
                alignment = Pos.CENTER
                padding = Insets(80.0, 0.0, 0.0, 0.0)
                if (logoImg != null) children.add(logoImg)
                children.addAll(
                    Label("No clusters configured yet").apply {
                        styleClass.add("subtitle-label")
                        padding = Insets(8.0, 0.0, 0.0, 0.0)
                    },
                    Button("+ Add Your First Cluster").apply {
                        styleClass.add("accent")
                        setOnAction { sidebar.triggerAddCluster() }
                    }
                )
            }
            page.children.addAll(titleRow, emptyBox)
        } else {
            val cardWrap = FlowPane(16.0, 16.0)
            clusters.forEach { cluster -> cardWrap.children.add(buildClusterCard(cluster)) }
            page.children.addAll(titleRow, cardWrap)
        }

        val scroll = ScrollPane(page).apply {
            isFitToWidth = true
            styleClass.add("edge-to-edge")
        }

        contentArea.children.setAll(scroll)
    }

    private fun buildClusterCard(cluster: ClusterConfig): VBox {
        val badgeClass = when (cluster.securityProtocol) {
            "SSL" -> "security-badge-ssl"
            "SASL_PLAINTEXT" -> "security-badge-sasl"
            "SASL_SSL" -> "security-badge-sasl-ssl"
            else -> "security-badge-plaintext"
        }

        val badgesRow = HBox(6.0).apply {
            alignment = Pos.CENTER_LEFT
            children.add(Label(cluster.securityProtocol).apply {
                styleClass.addAll("security-badge", badgeClass)
            })
            if (!cluster.schemaRegistryUrl.isNullOrBlank()) {
                children.add(Label("Schema Registry").apply {
                    styleClass.addAll("security-badge", "security-badge-sasl-ssl")
                })
            }
            if (!cluster.kafkaConnectUrl.isNullOrBlank()) {
                children.add(Label("Kafka Connect").apply {
                    styleClass.addAll("security-badge", "security-badge-sasl")
                })
            }
        }

        val connectBtn = Button("Connect").apply {
            maxWidth = Double.MAX_VALUE
            styleClass.add("accent")
            setOnAction { connectToCluster(cluster) }
        }

        val nameLabel = Label(cluster.name).apply {
            styleClass.add("cluster-card-name")
            isWrapText = true
            if (cluster.color != null) style = "-fx-text-fill: ${cluster.color};"
        }

        return VBox(10.0).apply {
            styleClass.add("cluster-card")
            prefWidth = 260.0
            if (cluster.color != null)
                style = "-fx-border-color: ${cluster.color} -color-border-default -color-border-default -color-border-default; -fx-border-width: 3 1 1 1;"
            children.addAll(
                nameLabel,
                Label(cluster.bootstrapServers).apply {
                    styleClass.add("cluster-card-host")
                    isWrapText = true
                    maxWidth = 220.0
                },
                badgesRow,
                Region().apply { VBox.setVgrow(this, Priority.ALWAYS) },
                connectBtn
            )
            setOnMouseClicked { e -> if (e.clickCount == 2) connectToCluster(cluster) }
        }
    }

    private fun connectToCluster(cluster: ClusterConfig) {
        setStatus("Connecting to ${cluster.name}...")
        currentCluster = cluster

        Thread {
            try {
                val admin = KafkaAdminService(cluster)
                admin.getClusterInfo() // Test connection
                adminService = admin
                Platform.runLater {
                    setStatus("Connected to ${cluster.name}  —  ${cluster.bootstrapServers}")
                    sidebar.setConnected(cluster)
                    consumerBtn.isDisable = false
                    producerBtn.isDisable = false
                    refreshBtn.isDisable = false
                    homeBtn.isVisible = true
                    homeBtn.isManaged = true
                    resetNavHistory()
                    navigate { sidebar.setActive("overview"); showOverview() }
                }
            } catch (e: Exception) {
                Platform.runLater {
                    currentCluster = null
                    setStatus("Failed to connect to ${cluster.name}")
                    val alert = Alert(Alert.AlertType.ERROR)
                    alert.title = "Connection Failed"
                    alert.headerText = "Could not connect to ${cluster.name}"
                    alert.contentText = e.message
                    alert.showAndWait()
                }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun showView(viewName: String) {
        navigate {
            sidebar.setActive(viewName)
            when (viewName) {
                "overview" -> showOverview()
                "topics" -> showTopicsView()
                "consumergroups" -> showConsumerGroupsView()
                "schema" -> showSchemaRegistryView()
                "connect" -> showKafkaConnectView()
                "security" -> showSecurityView()
                "streams" -> showKafkaStreamsView()
                "brokers" -> showBrokersView()
            }
        }
    }

    private fun showOverview() {
        val cluster = currentCluster ?: return
        val admin = adminService ?: return
        val view = OverviewView(cluster, admin, ::setStatus, ::showView)
        contentArea.children.setAll(view.root)
    }

    private fun showTopicsView() {
        val cluster = currentCluster ?: return
        val admin = adminService ?: return
        val view = TopicsView(cluster, admin, ::setStatus,
            onTopicSelected = { topic -> showTopicDetail(topic) }
        )
        contentArea.children.setAll(view.root)
        view.refresh()
    }

    private fun showTopicDetail(topic: com.freeconductor.model.TopicInfo) {
        navigate {
            sidebar.setActive("topics")
            val cluster = currentCluster ?: return@navigate
            val admin = adminService ?: return@navigate
            val view = TopicDetailView(topic, cluster, admin, ::setStatus,
                onBack = { navigateBack() }
            )
            contentArea.children.setAll(view.root)
        }
    }

    private fun showConsumerGroupsView() {
        val cluster = currentCluster ?: return
        val admin = adminService ?: return
        val view = ConsumerGroupsView(cluster, admin, ::setStatus,
            onGroupSelected = { group -> showConsumerGroupDetail(group) }
        )
        contentArea.children.setAll(view.root)
        view.refresh()
    }

    private fun showConsumerGroupDetail(group: com.freeconductor.model.ConsumerGroupInfo) {
        navigate {
            sidebar.setActive("consumergroups")
            val cluster = currentCluster ?: return@navigate
            val admin = adminService ?: return@navigate
            val view = ConsumerGroupDetailView(group, cluster, admin, ::setStatus)
            contentArea.children.setAll(view.root)
        }
    }

    private fun showSchemaRegistryView() {
        val cluster = currentCluster ?: return
        if (cluster.schemaRegistryUrl.isNullOrBlank()) {
            Alert(Alert.AlertType.INFORMATION).apply {
                title = "Schema Registry"
                headerText = "Schema Registry not configured"
                contentText = "Please edit the cluster configuration to add a Schema Registry URL."
                showAndWait()
            }
            return
        }
        val service = SchemaRegistryService(cluster)
        val view = SchemaRegistryView(cluster, service, ::setStatus)
        contentArea.children.setAll(view.root)
        view.refresh()
    }

    private fun showKafkaConnectView() {
        val cluster = currentCluster ?: return
        if (cluster.kafkaConnectUrl.isNullOrBlank()) {
            Alert(Alert.AlertType.INFORMATION).apply {
                title = "Kafka Connect"
                headerText = "Kafka Connect not configured"
                contentText = "Please edit the cluster configuration to add a Kafka Connect URL."
                showAndWait()
            }
            return
        }
        val service = KafkaConnectService(cluster)
        val view = KafkaConnectView(cluster, service, ::setStatus)
        contentArea.children.setAll(view.root)
        view.refresh()
    }

    private fun showSecurityView() {
        val cluster = currentCluster ?: return
        val admin = adminService ?: return
        val view = SecurityView(cluster, admin, ::setStatus)
        contentArea.children.setAll(view.root)
    }

    private fun showKafkaStreamsView() {
        val admin = adminService ?: return
        val view = KafkaStreamsView(admin, ::setStatus)
        contentArea.children.setAll(view.root)
        view.refresh()
    }

    private fun showBrokersView() {
        val cluster = currentCluster ?: return
        val admin = adminService ?: return
        val view = BrokersView(cluster, admin, ::setStatus,
            onBrokerSelected = { broker -> showBrokerDetail(broker) }
        )
        contentArea.children.setAll(view.root)
        view.refresh()
    }

    private fun showBrokerDetail(broker: com.freeconductor.model.BrokerInfo) {
        navigate {
            sidebar.setActive("brokers")
            val admin = adminService ?: return@navigate
            val view = BrokerDetailView(broker, admin, ::setStatus)
            contentArea.children.setAll(view.root)
        }
    }

    fun setStatus(message: String) {
        Platform.runLater { statusLabel.text = message }
    }
}

// Kotlin extension to enable Application reference in inner class
private object Application {
    fun setUserAgentStylesheet(stylesheet: String) {
        javafx.application.Application.setUserAgentStylesheet(stylesheet)
    }
}
