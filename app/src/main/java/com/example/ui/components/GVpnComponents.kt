package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.SmartEngineMode
import com.example.model.VpnConnectionState
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.CarbonCardBorder
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun CarbonPanelCard(
    modifier: Modifier = Modifier,
    borderColor: Color = CarbonCardBorder,
    backgroundColor: Color = CarbonSurface,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 2.dp
    ) {
        content()
    }
}

@Composable
fun SmartModeSelector(
    selectedMode: SmartEngineMode,
    onSelectMode: (SmartEngineMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = CarbonSurface,
        border = BorderStroke(1.dp, CarbonCardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SmartEngineMode.entries.forEach { mode ->
                val isSelected = mode == selectedMode
                val accent = when (mode) {
                    SmartEngineMode.AUTO -> NeonGreen
                    SmartEngineMode.SSH_PRIORITY -> ElectricBlue
                    SmartEngineMode.TLS_PRIORITY -> Color(0xFF5CE1FF)
                }
                val bg by animateColorAsState(
                    targetValue = if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent,
                    animationSpec = tween(220),
                    label = "smart_mode_bg"
                )
                val border by animateColorAsState(
                    targetValue = if (isSelected) accent.copy(alpha = 0.7f) else Color.Transparent,
                    animationSpec = tween(220),
                    label = "smart_mode_border"
                )
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) accent else TextSecondary,
                    animationSpec = tween(220),
                    label = "smart_mode_text"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(12.dp))
                        .clickable { onSelectMode(mode) }
                        .testTag("smart_mode_${mode.shortLabel.lowercase()}")
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.shortLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = textColor,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun PowerCoreButton(
    state: VpnConnectionState,
    onClick: () -> Unit,
    contentDesc: String,
    modifier: Modifier = Modifier,
    size: Dp = 196.dp
) {
    val isConnecting = state == VpnConnectionState.CONNECTING || state == VpnConnectionState.RECONNECTING
    val isConnected = state == VpnConnectionState.CONNECTED
    val isError = state == VpnConnectionState.ERROR

    val coreColor by animateColorAsState(
        targetValue = when {
            isConnected -> NeonGreen
            isConnecting -> AmberConnecting
            isError -> CrimsonError
            else -> ElectricBlue
        },
        animationSpec = tween(320),
        label = "power_core_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "power_core_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    val actionText = when {
        isConnected -> "DESLIGAR"
        isConnecting -> "CANCELAR"
        else -> "LIGAR"
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = contentDesc }
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("power_core_button"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension / 2f

            // Ambient radial halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor.copy(alpha = if (isConnected) 0.28f else 0.16f),
                        coreColor.copy(alpha = 0.04f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius * pulseScale
                ),
                radius = maxRadius,
                center = center
            )

            // Outer precision ring
            drawCircle(
                color = coreColor.copy(alpha = if (isConnected) 0.55f else 0.25f),
                radius = maxRadius * 0.86f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // Animated sweep arc when connecting or connected
            if (isConnecting || isConnected) {
                val arcRadius = maxRadius * 0.86f
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.4f to coreColor.copy(alpha = 0.35f),
                        0.85f to coreColor,
                        1f to Color.Transparent
                    ),
                    startAngle = sweepAngle,
                    sweepAngle = if (isConnecting) 160f else 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                    size = Size(arcRadius * 2f, arcRadius * 2f),
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Inner obsidian core circle
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(CarbonElevated, ObsidianBlack)
                ),
                radius = maxRadius * 0.72f,
                center = center
            )

            // Inner border accent
            drawCircle(
                color = coreColor.copy(alpha = if (isConnected) 0.9f else 0.45f),
                radius = maxRadius * 0.72f,
                center = center,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = coreColor,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = actionText,
                style = MaterialTheme.typography.labelLarge.copy(
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = coreColor
            )
        }
    }
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    icon: ImageVector,
    accentColor: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    CarbonPanelCard(
        modifier = modifier,
        borderColor = accentColor.copy(alpha = 0.25f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BenchmarkScoreGauge(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 152.dp
) {
    val clamped = score.coerceIn(0, 100)
    val gaugeColor = when {
        clamped >= 75 -> NeonGreen
        clamped >= 45 -> ElectricBlue
        clamped > 0 -> AmberConnecting
        else -> TextMuted
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 12.dp.toPx()
            val inset = strokeWidth / 2f + 4.dp.toPx()
            val arcSize = Size(this.size.width - inset * 2f, this.size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            // Track
            drawArc(
                color = CarbonCardBorder,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active progress arc
            if (clamped > 0) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(ElectricBlue, NeonGreen, gaugeColor)
                    ),
                    startAngle = 135f,
                    sweepAngle = 270f * (clamped / 100f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (clamped > 0) "$clamped" else "—",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold
                ),
                color = gaugeColor
            )
            Text(
                text = "SCORE V6",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                color = TextSecondary
            )
        }
    }
}

fun formatRate(bytesPerSec: Long): String = when {
    bytesPerSec >= 1_048_576L -> "%.2f MB/s".format(bytesPerSec / 1_048_576.0)
    bytesPerSec >= 1_024L -> "%.1f KB/s".format(bytesPerSec / 1_024.0)
    else -> "$bytesPerSec B/s"
}

fun formatBytes(totalBytes: Long): String = when {
    totalBytes >= 1_073_741_824L -> "%.2f GB".format(totalBytes / 1_073_741_824.0)
    totalBytes >= 1_048_576L -> "%.1f MB".format(totalBytes / 1_048_576.0)
    totalBytes >= 1_024L -> "%.1f KB".format(totalBytes / 1_024.0)
    else -> "$totalBytes B"
}

fun formatElapsed(sinceEpochMs: Long?, nowEpochMs: Long): String {
    if (sinceEpochMs == null) return "00:00:00"
    val sec = ((nowEpochMs - sinceEpochMs) / 1000L).coerceAtLeast(0L)
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return "%02d:%02d:%02d".format(h, m, s)
}
