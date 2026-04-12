package com.freeconductor.ui.consume

import com.freeconductor.model.ClusterConfig
import com.freeconductor.service.KafkaAdminService
import javafx.scene.Scene
import javafx.stage.Stage

class ConsumerWindow(
    topicName: String,
    cluster: ClusterConfig,
    adminService: KafkaAdminService,
    private val setMainStatus: (String) -> Unit = {}
) {
    private val stage = Stage()
    private val view = MessageBrowserView(topicName, cluster, adminService, setMainStatus)

    init {
        val scene = Scene(view.root, 1200.0, 740.0)
        stage.title = if (topicName.isBlank()) "Consumer  [${cluster.name}]"
                      else "Consumer — $topicName  [${cluster.name}]"
        ConsumerWindow::class.java
            .getResourceAsStream("/com/freeconductor/icons/free-conduktor-logo-32.png")
            ?.let { stage.icons.add(javafx.scene.image.Image(it)) }
        stage.scene = scene
        stage.setOnCloseRequest { view.stopConsuming() }
        stage.show()
    }

    fun bringToFront() = stage.toFront()
}
