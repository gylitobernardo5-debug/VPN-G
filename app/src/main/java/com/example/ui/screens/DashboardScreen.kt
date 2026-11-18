package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.LogEntry
import com.example.model.MobileNetworkState
import com.example.model.SmartEngineMode
import com.example.model.VpnConfig
import com.example.model.VpnConnectionState
import com.example.model.VpnTelemetry
import com.example.ui.components.CarbonPanelCard
import com.example.ui.components.MetricTile
import com.example.ui.components.PowerCoreButton
import com.example.ui.components.SmartModeSelector
import com.example.ui.components.formatBytes
import com.example.ui.components.formatElapsed
import com.example.ui.components.formatRate
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.CarbonCardBorder
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    config: VpnConfig,
    telemetry: VpnTelemetry,
    networkState: MobileNetworkState,
    recentLogs: List<LogEntry>,
    onTogglePower: () -> Unit,
    onSelectSmartMode: (SmartEngineMode) -> Unit,
    onRefreshPublicIp: () -> Unit,
    onOpenConfig: () -> Unit,
    modifier: Modifier = Modifier
) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(telemetry.connectedSinceEpochMs) {
        while (telemetry.connectedSinceEpochMs != null) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val statusColor = when (telemetry.state) {
        VpnConnectionState.CONNECTED -> NeonGreen
        VpnConnectionState.CONNECTING, VpnConnectionState.RECONNECTING -> AmberConnecting
        VpnConnectionState.ERROR -> CrimsonError
        VpnConnectionState.DISCONNECTED -> ElectricBlue
    }

    val statusLabel = when (telemetry.state) {
        VpnConnectionState.CONNECTED -> stringResource(R.string.state_connected)
        VpnConnectionState.CONNECTING -> stringResource(R.string.state_connecting)
        VpnConnectionState.RECONNECTING -> stringResource(R.string.state_reconnecting)
        VpnConnectionState.ERROR -> stringResource(R.string.state_error)
        VpnConnectionState.DISCONNECTED -> stringResource(R.string.state_disconnected)
    }

    val configuredServerPreview = when {
        telemetry.activeServer != "—" -> telemetry.activeServer
        config.smartMode == SmartEngineMode.SSH_PRIORITY && config.ssh.isConfigured() ->
            config.ssh.endpointLabel()
        config.smartMode == SmartEngineMode.TLS_PRIORITY && config.tls.isConfigured() ->
            config.tls.endpointLabel()
        config.ssh.isConfigured() && config.tls.isConfigured() ->
            "${config.ssh.endpointLabel()} / ${config.tls.endpointLabel()}"
        config.ssh.isConfigured() -> config.ssh.endpointLabel()
        config.tls.isConfigured() -> config.tls.endpointLabel()
        else -> "Não configurado"
    }

    val methodDisplay = telemetry.activeProtocol?.displayName ?: config.smartMode.label

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        statusColor.copy(alpha = 0.08f),
                        ObsidianBlack,
                        ObsidianBlack
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 620.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header + Mobile Network Pill
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "G-VPN",
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = NeonGreen.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "V6",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = NeonGreen,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Mobile Network Transport Badge
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CarbonElevated,
                        border = BorderStroke(
                            1.dp,
                            if (networkState.isConnected) ElectricBlue.copy(alpha = 0.45f)
                            else CrimsonError.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (networkState.isCellular) Icons.Rounded.CellTower else Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = if (networkState.isConnected) ElectricBlue else CrimsonError,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = networkState.transportLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            // Smart Engine Mode Selector (AUTO / SSH / TLS)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmartModeSelector(
                        selectedMode = config.smartMode,
                        onSelectMode = onSelectSmartMode
                    )
                    Text(
                        text = when (config.smartMode) {
                            SmartEngineMode.AUTO -> stringResource(R.string.mode_auto_desc)
                            SmartEngineMode.SSH_PRIORITY -> stringResource(R.string.mode_ssh_desc)
                            SmartEngineMode.TLS_PRIORITY -> stringResource(R.string.mode_tls_desc)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Hero Power Core + Live Status
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PowerCoreButton(
                        state = telemetry.state,
                        onClick = onTogglePower,
                        contentDesc = stringResource(R.string.cd_power_button)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.65f))
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .testTag("vpn_status_badge"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    letterSpacing = 1.4.sp
                                ),
                                color = statusColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = telemetry.statusStep,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            // Actionable Error Card (Honest feedback — never hides failures)
            item {
                AnimatedVisibility(visible = telemetry.lastError != null) {
                    CarbonPanelCard(
                        borderColor = CrimsonError.copy(alpha = 0.65f),
                        backgroundColor = CrimsonContainer
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = CrimsonError,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Ligação não estabelecida",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = CrimsonError
                                )
                            }
                            Text(
                                text = telemetry.lastError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextPrimary
                            )
                            OutlinedButton(
                                onClick = onOpenConfig,
                                border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.7f)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = TextPrimary
                                ),
                                modifier = Modifier.testTag("error_open_config_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verificar Config SSH / TLS")
                            }
                        }
                    }
                }
            }

            // Live Speed Grid (Download & Upload)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = stringResource(R.string.label_download),
                        value = formatRate(telemetry.downloadBytesPerSec),
                        subtitle = "Total: ${formatBytes(telemetry.totalRxBytes)}",
                        icon = Icons.Rounded.ArrowDownward,
                        accentColor = NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = stringResource(R.string.label_upload),
                        value = formatRate(telemetry.uploadBytesPerSec),
                        subtitle = "Total: ${formatBytes(telemetry.totalTxBytes)}",
                        icon = Icons.Rounded.ArrowUpward,
                        accentColor = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Ping & Duration Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = stringResource(R.string.label_ping),
                        value = telemetry.pingMs?.let { "$it ms" }
                            ?: networkState.latencyMs?.let { "$it ms" }
                            ?: "—",
                        subtitle = "Perda: ${"%.0f".format(networkState.packetLossPercent)}%",
                        icon = Icons.Rounded.Speed,
                        accentColor = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = stringResource(R.string.label_duration),
                        value = formatElapsed(telemetry.connectedSinceEpochMs, nowMs),
                        subtitle = if (telemetry.activeStreams > 0) "${telemetry.activeStreams} streams ativos" else "Segundo plano pronto",
                        icon = Icons.Rounded.Timer,
                        accentColor = NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Server, Protocol & Public IP Card
            item {
                CarbonPanelCard(borderColor = CarbonCardBorder) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = null,
                                    tint = ElectricBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column {
                                    Text(
                                        text = stringResource(R.string.label_public_ip),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = telemetry.publicIp,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontFamily = JetBrainsMonoFontFamily
                                        ),
                                        color = TextPrimary
                                    )
                                }
                            }
                            IconButton(
                                onClick = onRefreshPublicIp,
                                modifier = Modifier.testTag("refresh_ip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.cd_refresh_ip),
                                    tint = ElectricBlue
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(CarbonCardBorder)
                        )

                        TelemetryKeyValueRow(
                            icon = Icons.Rounded.Dns,
                            label = stringResource(R.string.label_server),
                            value = configuredServerPreview,
                            accent = NeonGreen
                        )
                        TelemetryKeyValueRow(
                            icon = Icons.Rounded.Security,
                            label = stringResource(R.string.label_method),
                            value = methodDisplay,
                            accent = ElectricBlue
                        )
                        TelemetryKeyValueRow(
                            icon = Icons.Rounded.Router,
                            label = stringResource(R.string.label_transport),
                            value = "${networkState.transportLabel} • IP ${networkState.localIp}",
                            accent = TextSecondary
                        )
                    }
                }
            }

            // Compact Live Event Strip
            if (recentLogs.isNotEmpty()) {
                item {
                    val last = recentLogs.last()
                    CarbonPanelCard(borderColor = CarbonCardBorder) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "[${last.tag}]",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                                color = NeonGreen
                            )
                            Text(
                                text = last.message,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontSize = 11.sp
                                ),
                                color = TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun TelemetryKeyValueRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    accent: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(0.45f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
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
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.55f)
        )
    }
}
