package com.freeconductor

import atlantafx.base.theme.PrimerLight
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.stage.Stage
import com.freeconductor.ui.MainWindow

class App : Application() {
    override fun start(stage: Stage) {
        Application.setUserAgentStylesheet(PrimerLight().userAgentStylesheet)
        val mainWindow = MainWindow(stage)
        val scene = Scene(mainWindow.root, 1280.0, 860.0)
        scene.stylesheets.add(App::class.java.getResource("/com/freeconductor/styles.css")?.toExternalForm() ?: "")
        // Set window icon — load multiple sizes so the OS picks the best one per context
        listOf("free-conduktor-logo-16.png", "free-conduktor-logo-32.png", "free-conduktor-logo-64.png")
            .forEach { name ->
                App::class.java.getResourceAsStream("/com/freeconductor/icons/$name")?.let {
                    stage.icons.add(Image(it))
                }
            }
        stage.title = "FreeConduktor"
        stage.scene = scene
        stage.show()
    }
}

fun main(args: Array<String>) {
    Application.launch(App::class.java, *args)
}
