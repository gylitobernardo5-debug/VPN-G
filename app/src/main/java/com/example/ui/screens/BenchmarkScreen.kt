package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BenchmarkProgress
import com.example.model.BenchmarkSnapshot
import com.example.ui.components.BenchmarkScoreGauge
import com.example.ui.components.CarbonPanelCard
import com.example.ui.components.MetricTile
import com.example.ui.theme.AmberConnecting
import com.example.ui.theme.CarbonCardBorder
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BenchmarkScreen(
    progress: BenchmarkProgress,
    latest: BenchmarkSnapshot?,
    history: List<BenchmarkSnapshot>,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onSelectHistory: (BenchmarkSnapshot) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFmt = remember { SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBlack),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.benchmark_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Medições reais executadas no dispositivo e na rede atual — execução contínua em segundo plano.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Primary Action + V6 Score Hero Card
            item {
                CarbonPanelCard(
                    borderColor = if (progress.isRunning) AmberConnecting.copy(alpha = 0.6f)
                    else NeonGreen.copy(alpha = 0.35f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            BenchmarkScoreGauge(
                                score = latest?.performanceScore ?: 0,
                                size = 144.dp
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "GARGALO PRINCIPAL",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                Text(
                                    text = latest?.primaryBottleneck ?: "Execute o teste real",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (latest != null) ElectricBlue else TextMuted
                                )
                                if (latest != null) {
                                    Text(
                                        text = "Fonte: ${latest.speedTestSource}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "Medido em ${dateFmt.format(Date(latest.timestampMs))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        if (progress.isRunning) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = progress.currentStage,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AmberConnecting
                                )
                                LinearProgressIndicator(
                                    progress = { progress.progressFraction.coerceIn(0.05f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    color = NeonGreen,
                                    trackColor = CarbonCardBorder
                                )
                            }
                        }

                        Button(
                            onClick = if (progress.isRunning) onStopTest else onStartTest,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (progress.isRunning) CrimsonError else NeonGreen,
                                contentColor = if (progress.isRunning) TextPrimary else ObsidianBlack
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("benchmark_start_button")
                        ) {
                            Icon(
                                imageVector = if (progress.isRunning) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (progress.isRunning) {
                                    stringResource(R.string.benchmark_stop)
                                } else {
                                    stringResource(R.string.benchmark_start)
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            // Real Speed & Latency Grid
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "DOWNLOAD REAL",
                        value = latest?.let { "${"%.2f".format(it.downloadMbps)} Mbps" } ?: "—",
                        subtitle = latest?.speedTestSource ?: "Aguardando teste",
                        icon = Icons.Rounded.ArrowDownward,
                        accentColor = NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "UPLOAD REAL",
                        value = latest?.let { "${"%.2f".format(it.uploadMbps)} Mbps" } ?: "—",
                        subtitle = "Payload HTTPS real",
                        icon = Icons.Rounded.ArrowUpward,
                        accentColor = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "PING / LATÊNCIA",
                        value = latest?.let {
                            if (it.pingMs > 0) "${"%.1f".format(it.pingMs)} ms" else "Sem resposta"
                        } ?: "—",
                        subtitle = latest?.let {
                            "Conn: ${"%.1f".format(it.connectionLatencyMs)} ms • Jitter ${"%.1f".format(it.jitterMs)} ms"
                        },
                        icon = Icons.Rounded.Speed,
                        accentColor = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "ESTABILIDADE",
                        value = latest?.let { "${"%.1f".format(it.stabilityPercent)}%" } ?: "—",
                        subtitle = latest?.let { "Perda: ${"%.1f".format(it.packetLossPercent)}%" },
                        icon = Icons.Rounded.NetworkCheck,
                        accentColor = NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Protocol Handshakes (SSH Setup & TLS Setup)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "SSH SETUP",
                        value = latest?.sshSetupMs?.let { "${"%.1f".format(it)} ms" }
                            ?: (latest?.sshSetupStatus ?: "—"),
                        subtitle = latest?.sshSetupStatus ?: "TCP + Banner SSH-2",
                        icon = Icons.Rounded.Terminal,
                        accentColor = NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "TLS SETUP",
                        value = latest?.tlsSetupMs?.let { "${"%.1f".format(it)} ms" }
                            ?: (latest?.tlsSetupStatus ?: "—"),
                        subtitle = latest?.let { "${it.tlsNegotiatedProtocol} • ${it.tlsSetupStatus}" },
                        icon = Icons.Rounded.Lock,
                        accentColor = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Internal Engine Pipeline (Loopback TCP, TUN/PacketCodec, RAM)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MetricTile(
                        label = "LOOPBACK TCP",
                        value = latest?.let { "${"%.0f".format(it.loopbackTcpMbps)} Mbps" } ?: "—",
                        subtitle = latest?.let { "RTT local: ${"%.2f".format(it.loopbackRttMs)} ms" },
                        icon = Icons.Rounded.Router,
                        accentColor = ElectricBlue,
                        modifier = Modifier.weight(1f)
                    )
                    MetricTile(
                        label = "TUN / PACKETCODEC",
                        value = latest?.let { "${it.packetCodecPps / 1000}k pkt/s" } ?: "—",
                        subtitle = latest?.let {
                            "${"%.0f".format(it.packetCodecMbps)} Mbps • ${"%.1f".format(it.packetCodecAvgUs)} µs/pkt"
                        },
                        icon = Icons.Rounded.Bolt,
                        accentColor = NeonGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                MetricTile(
                    label = "MEMÓRIA RAM DO DISPOSITIVO",
                    value = latest?.let {
                        "${"%.0f".format(it.ramUsedMb)} / ${"%.0f".format(it.ramTotalMb)} MB"
                    } ?: "—",
                    subtitle = latest?.let {
                        "Delta heap durante benchmark: +${"%.2f".format(it.ramDeltaMb)} MB"
                    } ?: "Medição real ActivityManager + Runtime",
                    icon = Icons.Rounded.Memory,
                    accentColor = ElectricBlue,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Bottleneck Recommendations Card
            if (latest != null && latest.recommendations.isNotEmpty()) {
                item {
                    CarbonPanelCard(borderColor = AmberConnecting.copy(alpha = 0.4f)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    tint = AmberConnecting,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Diagnóstico & Otimização V6",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = AmberConnecting
                                )
                            }
                            latest.recommendations.forEach { rec ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WarningAmber,
                                        contentDescription = null,
                                        tint = ElectricBlue,
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .size(14.dp)
                                    )
                                    Text(
                                        text = rec,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Saved Benchmark History
            if (history.isNotEmpty()) {
                item {
                    CarbonPanelCard(borderColor = CarbonCardBorder) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                        imageVector = Icons.Rounded.History,
                                        contentDescription = null,
                                        tint = ElectricBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = "Histórico de Testes Reais (${history.size})",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                }
                                IconButton(onClick = onClearHistory) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteSweep,
                                        contentDescription = "Limpar histórico",
                                        tint = TextSecondary
                                    )
                                }
                            }

                            history.take(6).forEach { run ->
                                val isCurrent = latest?.id == run.id && run.id != 0L
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isCurrent) NeonGreen.copy(alpha = 0.1f) else CarbonElevated,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isCurrent) NeonGreen.copy(alpha = 0.5f) else CarbonCardBorder
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSelectHistory(run) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = dateFmt.format(Date(run.timestampMs)),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = "↓${"%.1f".format(run.downloadMbps)} ↑${"%.1f".format(run.uploadMbps)} Mbps • Ping ${"%.0f".format(run.pingMs)}ms",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            text = "${run.performanceScore}/100",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = if (run.performanceScore >= 70) NeonGreen else ElectricBlue
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}
