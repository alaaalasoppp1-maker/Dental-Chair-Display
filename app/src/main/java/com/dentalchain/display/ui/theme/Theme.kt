package com.dentalchain.display.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DentalChairColorScheme = darkColorScheme(
    primary = DentalBlue,
    secondary = DentalTeal,
    background = DentalBackground,
    surface = DentalSurface,
    onPrimary = DentalText,
    onSecondary = DentalBackground,
    onBackground = DentalText,
    onSurface = DentalText
)

@Composable
fun DentalChairDisplayTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DentalChairColorScheme,
        typography = DentalTypography,
        content = content
    )
}
