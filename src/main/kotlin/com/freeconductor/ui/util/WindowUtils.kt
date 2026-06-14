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
        Stage::class.java.getResourceAsStream("/com/freeconductor/icons/$name")?.let {
            icons.add(Image(it))
        }
    }
}

fun Dialog<*>.applyAppIcon() {
    dialogPane.sceneProperty().addListener { _, _, scene ->
        scene?.windowProperty()?.addListener { _, _, window ->
            (window as? Stage)?.applyAppIcon()
        }
    }
}

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
