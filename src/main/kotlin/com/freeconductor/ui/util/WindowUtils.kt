package com.freeconductor.ui.util

import javafx.stage.Stage
import javafx.stage.Window

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
