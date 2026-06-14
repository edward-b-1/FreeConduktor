package com.freeconductor.ui.util

import javafx.scene.control.Dialog
import javafx.scene.image.Image
import javafx.stage.Stage
import javafx.stage.Window

private val APP_ICON_NAMES = listOf(
    "free-conduktor-logo-16.png",
    "free-conduktor-logo-32.png",
    "free-conduktor-logo-64.png"
)

fun Stage.applyAppIcon() {
    APP_ICON_NAMES.forEach { name ->
        WindowUtils::class.java.getResourceAsStream("/com/freeconductor/icons/$name")?.let {
            icons.add(Image(it))
        }
    }
}

private val APP_STYLESHEET =
    WindowUtils::class.java.getResource("/com/freeconductor/styles.css")?.toExternalForm()

fun Dialog<*>.applyAppIcon() {
    fun injectStylesheet(scene: javafx.scene.Scene) {
        APP_STYLESHEET?.let { if (!scene.stylesheets.contains(it)) scene.stylesheets.add(it) }
    }
    // Inject stylesheet as soon as the scene is available
    dialogPane.scene?.let { injectStylesheet(it) }
    dialogPane.sceneProperty().addListener { _, _, scene -> scene?.let { injectStylesheet(it) } }
    // Apply icon when the dialog is shown — window is guaranteed to exist at this point
    setOnShown { (dialogPane.scene?.window as? Stage)?.applyAppIcon() }
}

private object WindowUtils

fun Stage.centerOnActiveWindow() {
    if (owner == null) {
        Window.getWindows()
            .filterIsInstance<Stage>()
            .find { it.isFocused && it != this }
            ?.let { initOwner(it) }
    }
    setOnShown {
        val ref = owner ?: return@setOnShown
        if (!width.isNaN() && !height.isNaN()) {
            x = ref.x + (ref.width - width) / 2
            y = ref.y + (ref.height - height) / 2
        }
    }
}
