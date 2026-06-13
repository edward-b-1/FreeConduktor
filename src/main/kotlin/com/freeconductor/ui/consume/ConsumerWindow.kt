package com.freeconductor.ui.consume

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import com.freeconductor.ui.util.centerOnActiveWindow
import javafx.scene.Scene
import javafx.stage.Stage

class ConsumerWindow(
    topicName: String,
    cluster: ClusterConfig,
    adminService: KafkaAdminService,
    private val setMainStatus: (String) -> Unit = {}
) {
    private val stage = Stage()
    private val view = MessageBrowserView(
        topicName      = topicName,
        cluster        = cluster,
        adminService   = adminService,
        setStatus      = setMainStatus,
        setWindowTitle = { stage.title = it }
    )

    init {
        val scene = Scene(view.root, 1000.0, 740.0)
        scene.stylesheets.add(
            ConsumerWindow::class.java.getResource("/com/freeconductor/styles.css")!!.toExternalForm()
        )
        stage.title = if (topicName.isBlank()) "Consumer  [${cluster.name}]"
                      else "Consume from Topic: $topicName  [${cluster.name}]"
        ConsumerWindow::class.java
            .getResourceAsStream("/com/freeconductor/icons/free-conduktor-logo-32.png")
            ?.let { stage.icons.add(javafx.scene.image.Image(it)) }
        stage.scene = scene
        stage.setOnCloseRequest { view.stopConsuming() }
        stage.centerOnActiveWindow()
        stage.show()
    }

    fun bringToFront() = stage.toFront()
}
