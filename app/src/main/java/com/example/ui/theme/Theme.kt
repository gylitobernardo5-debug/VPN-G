package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ColorBorderSubtle = androidx.compose.ui.graphics.Color(0xFF15202D)

private val GVpnDarkColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = ObsidianBlack,
    primaryContainer = NeonGreenContainer,
    onPrimaryContainer = NeonGreen,
    secondary = ElectricBlue,
    onSecondary = ObsidianBlack,
    secondaryContainer = ElectricBlueContainer,
    onSecondaryContainer = ElectricBlue,
    tertiary = AmberConnecting,
    onTertiary = ObsidianBlack,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberConnecting,
    background = ObsidianBlack,
    onBackground = TextPrimary,
    surface = CarbonSurface,
    onSurface = TextPrimary,
    surfaceVariant = CarbonElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBlack,
    surfaceContainerLow = CarbonSurface,
    surfaceContainer = CarbonElevated,
    surfaceContainerHigh = CarbonMuted,
    outline = CarbonCardBorder,
    outlineVariant = ColorBorderSubtle,
    error = CrimsonError,
    onError = TextPrimary,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonError
)

val GVpnShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun GVpnTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GVpnDarkColorScheme,
        typography = GVpnTypography,
        shapes = GVpnShapes,
        content = content
    )
}
