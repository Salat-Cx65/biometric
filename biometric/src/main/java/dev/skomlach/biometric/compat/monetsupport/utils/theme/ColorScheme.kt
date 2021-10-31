package dev.skomlach.biometric.compat.monetsupport.utils.theme

import dev.skomlach.biometric.compat.monetsupport.utils.colors.Color

typealias ColorSwatch = Map<Int, Color>

abstract class ColorScheme {
    abstract val neutral1: ColorSwatch
    abstract val neutral2: ColorSwatch

    abstract val accent1: ColorSwatch
    abstract val accent2: ColorSwatch
    abstract val accent3: ColorSwatch

    // Helpers
    val neutralColors: List<ColorSwatch>
        get() = listOf(neutral1, neutral2)
    val accentColors: List<ColorSwatch>
        get() = listOf(accent1, accent2, accent3)
}