package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ServerProfileEntity
import com.example.model.SpeedEngineConfig
import com.example.model.SshConfig
import com.example.model.TlsGatewayMode
import com.example.model.TlsSniConfig
import com.example.model.VpnConfig
import com.example.ui.components.CarbonPanelCard
import com.example.ui.components.SmartModeSelector
import com.example.ui.theme.CarbonCardBorder
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricBlueContainer
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.NeonGreenContainer
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigScreen(
    config: VpnConfig,
    savedProfiles: List<ServerProfileEntity>,
    onSaveConfig: (VpnConfig) -> Unit,
    onSaveProfile: (String, VpnConfig) -> Unit,
    onApplyProfile: (ServerProfileEntity) -> Unit,
    onDeleteProfile: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = SSH, 1 = TLS/SNI, 2 = Speed

    // Editable draft fields synced from persisted config
    var smartMode by remember(config.smartMode) { mutableStateOf(config.smartMode) }

    var sshHost by remember(config.ssh.host) { mutableStateOf(config.ssh.host) }
    var sshPort by remember(config.ssh.port) { mutableStateOf(config.ssh.port.toString()) }
    var sshUser by remember(config.ssh.username) { mutableStateOf(config.ssh.username) }
    var sshPass by remember(config.ssh.password) { mutableStateOf(config.ssh.password) }
    var sshUseTls by remember(config.ssh.useTlsWrapper) { mutableStateOf(config.ssh.useTlsWrapper) }
    var sshTlsSni by remember(config.ssh.tlsSniHost) { mutableStateOf(config.ssh.tlsSniHost) }
    var sshKeepAlive by remember(config.ssh.keepAliveSeconds) { mutableStateOf(config.ssh.keepAliveSeconds.toString()) }
    var sshVerifyTofu by remember(config.ssh.verifyHostKeyTofu) { mutableStateOf(config.ssh.verifyHostKeyTofu) }
    var passwordVisible by remember { mutableStateOf(false) }

    var tlsHost by remember(config.tls.gatewayHost) { mutableStateOf(config.tls.gatewayHost) }
    var tlsPort by remember(config.tls.port) { mutableStateOf(config.tls.port.toString()) }
    var tlsSni by remember(config.tls.sniHostname) { mutableStateOf(config.tls.sniHostname) }
    var tlsGatewayMode by remember(config.tls.gatewayMode) { mutableStateOf(config.tls.gatewayMode) }
    var tlsPath by remember(config.tls.customPath) { mutableStateOf(config.tls.customPath) }

    var bufferKb by remember(config.speed.bufferSizeKb) { mutableIntStateOf(config.speed.bufferSizeKb) }
    var tcpNoDelay by remember(config.speed.tcpNoDelay) { mutableStateOf(config.speed.tcpNoDelay) }
    var keepAliveEnabled by remember(config.speed.keepAliveEnabled) { mutableStateOf(config.speed.keepAliveEnabled) }
    var mtu by remember(config.speed.mtu) { mutableIntStateOf(config.speed.mtu) }
    var autoReconnect by remember(config.speed.autoReconnect) { mutableStateOf(config.speed.autoReconnect) }

    var profileName by remember { mutableStateOf("") }

    fun buildDraftConfig(): VpnConfig = VpnConfig(
        smartMode = smartMode,
        ssh = SshConfig(
            host = sshHost.trim(),
            port = sshPort.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
            username = sshUser.trim(),
            password = sshPass,
            useTlsWrapper = sshUseTls,
            tlsSniHost = sshTlsSni.trim(),
            keepAliveSeconds = sshKeepAlive.toIntOrNull()?.coerceIn(5, 300) ?: 25,
            verifyHostKeyTofu = sshVerifyTofu
        ),
        tls = TlsSniConfig(
            gatewayHost = tlsHost.trim(),
            port = tlsPort.toIntOrNull()?.coerceIn(1, 65535) ?: 443,
            sniHostname = tlsSni.trim(),
            gatewayMode = tlsGatewayMode,
            customPath = tlsPath.ifBlank { "/" }
        ),
        speed = SpeedEngineConfig(
            bufferSizeKb = bufferKb,
            tcpNoDelay = tcpNoDelay,
            keepAliveEnabled = keepAliveEnabled,
            keepAliveIntervalSec = sshKeepAlive.toIntOrNull()?.coerceIn(5, 300) ?: 25,
            mtu = mtu,
            maxConcurrentStreams = config.speed.maxConcurrentStreams,
            autoReconnect = autoReconnect
        )
    )

    // Keep local draft aligned if profile is applied externally
    LaunchedEffect(config) {
        smartMode = config.smartMode
    }

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
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.config_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "Configure os seus servidores SSH e TLS/SNI reais sem credenciais fictícias.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            // Smart Engine Mode Selector
            item {
                SmartModeSelector(
                    selectedMode = smartMode,
                    onSelectMode = { smartMode = it }
                )
            }

            // Section Switcher (SSH / TLS-SNI / Extreme Speed)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SectionChip(
                        selected = selectedSection == 0,
                        label = "SSH Tunnel",
                        icon = Icons.Rounded.Terminal,
                        accent = NeonGreen,
                        onClick = { selectedSection = 0 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("config_tab_ssh")
                    )
                    SectionChip(
                        selected = selectedSection == 1,
                        label = "TLS / SNI",
                        icon = Icons.Rounded.Lock,
                        accent = ElectricBlue,
                        onClick = { selectedSection = 1 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("config_tab_tls")
                    )
                    SectionChip(
                        selected = selectedSection == 2,
                        label = "Speed V6",
                        icon = Icons.Rounded.Speed,
                        accent = NeonGreen,
                        onClick = { selectedSection = 2 },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("config_tab_speed")
                    )
                }
            }

            when (selectedSection) {
                0 -> {
                    item {
                        CarbonPanelCard(borderColor = NeonGreen.copy(alpha = 0.35f)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.config_ssh_section),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NeonGreen
                                )

                                ConfigTextField(
                                    value = sshHost,
                                    onValueChange = { sshHost = it },
                                    label = "Host / IP do Servidor SSH",
                                    placeholder = "ex.: ssh.seu-servidor.com ou IP real",
                                    testTag = "ssh_host_input"
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ConfigTextField(
                                        value = sshPort,
                                        onValueChange = { sshPort = it.filter(Char::isDigit).take(5) },
                                        label = "Porta",
                                        placeholder = "22 / 443",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(0.4f),
                                        testTag = "ssh_port_input"
                                    )
                                    ConfigTextField(
                                        value = sshKeepAlive,
                                        onValueChange = { sshKeepAlive = it.filter(Char::isDigit).take(3) },
                                        label = "KeepAlive (s)",
                                        placeholder = "25",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(0.6f),
                                        testTag = "ssh_keepalive_input"
                                    )
                                }

                                ConfigTextField(
                                    value = sshUser,
                                    onValueChange = { sshUser = it },
                                    label = "Utilizador SSH",
                                    placeholder = "Introduza o utilizador real",
                                    testTag = "ssh_user_input"
                                )

                                OutlinedTextField(
                                    value = sshPass,
                                    onValueChange = { sshPass = it },
                                    label = { Text("Palavra-passe SSH") },
                                    placeholder = { Text("Introduza a palavra-passe", color = TextMuted) },
                                    singleLine = true,
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Icon(
                                                imageVector = if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                                contentDescription = stringResource(R.string.cd_toggle_password),
                                                tint = TextSecondary
                                            )
                                        }
                                    },
                                    colors = gvpnTextFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ssh_password_input")
                                )

                                ToggleRow(
                                    title = "SSH sobre TLS (SSL/SNI Wrapper)",
                                    subtitle = "Encapsula a sessão SSH-2 em TLS 1.2/1.3 com validação de certificado",
                                    checked = sshUseTls,
                                    onCheckedChange = { sshUseTls = it },
                                    accent = ElectricBlue,
                                    testTag = "ssh_use_tls_switch"
                                )

                                if (sshUseTls) {
                                    ConfigTextField(
                                        value = sshTlsSni,
                                        onValueChange = { sshTlsSni = it },
                                        label = "Hostname SNI para SSH/TLS",
                                        placeholder = "Deixe vazio para usar o Host SSH",
                                        testTag = "ssh_tls_sni_input"
                                    )
                                }

                                ToggleRow(
                                    title = "Verificação Host Key (TOFU SHA-256)",
                                    subtitle = "Protege contra alterações inesperadas na chave do servidor SSH",
                                    checked = sshVerifyTofu,
                                    onCheckedChange = { sshVerifyTofu = it },
                                    accent = NeonGreen,
                                    testTag = "ssh_tofu_switch"
                                )
                            }
                        }
                    }
                }

                1 -> {
                    item {
                        CarbonPanelCard(borderColor = ElectricBlue.copy(alpha = 0.35f)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.config_tls_section),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ElectricBlue
                                )
                                Text(
                                    text = "Modo independente: não requer conta SSH. Encaminha tráfego através de um gateway TLS 1.2/1.3 compatível.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )

                                ConfigTextField(
                                    value = tlsHost,
                                    onValueChange = { tlsHost = it },
                                    label = "Host / IP do Gateway TLS",
                                    placeholder = "ex.: gateway.seu-dominio.com",
                                    testTag = "tls_host_input"
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    ConfigTextField(
                                        value = tlsPort,
                                        onValueChange = { tlsPort = it.filter(Char::isDigit).take(5) },
                                        label = "Porta TLS",
                                        placeholder = "443",
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(0.38f),
                                        testTag = "tls_port_input"
                                    )
                                    ConfigTextField(
                                        value = tlsSni,
                                        onValueChange = { tlsSni = it },
                                        label = "Hostname SNI",
                                        placeholder = "gateway.seu-dominio.com",
                                        modifier = Modifier.weight(0.62f),
                                        testTag = "tls_sni_input"
                                    )
                                }

                                Text(
                                    text = "Modo do Gateway TLS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    TlsGatewayMode.entries.forEach { mode ->
                                        val selected = tlsGatewayMode == mode
                                        FilterChip(
                                            selected = selected,
                                            onClick = { tlsGatewayMode = mode },
                                            label = { Text(mode.label, style = MaterialTheme.typography.bodySmall) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = ElectricBlueContainer,
                                                selectedLabelColor = ElectricBlue
                                            )
                                        )
                                    }
                                }

                                if (tlsGatewayMode == TlsGatewayMode.RAW_TLS_STREAM) {
                                    ConfigTextField(
                                        value = tlsPath,
                                        onValueChange = { tlsPath = it },
                                        label = "Caminho de verificação do Gateway",
                                        placeholder = "/",
                                        testTag = "tls_path_input"
                                    )
                                }

                                // Mandatory TLS Certificate Verification Guarantee Badge
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = NeonGreenContainer,
                                    border = BorderStroke(1.dp, NeonGreen.copy(alpha = 0.45f))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.VerifiedUser,
                                            contentDescription = null,
                                            tint = NeonGreen,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Column {
                                            Text(
                                                text = "Validação TLS 1.2 / 1.3 Estrita Ativa",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = NeonGreen
                                            )
                                            Text(
                                                text = "Cadeia de certificados X.509 e correspondência de hostname HTTPS são sempre verificadas.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = TextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    item {
                        CarbonPanelCard(borderColor = NeonGreen.copy(alpha = 0.35f)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.config_speed_section),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = NeonGreen
                                )
                                Text(
                                    text = "Otimização de buffers de leitura/escrita, pool de pacotes TUN e parâmetros TCP sem reduzir segurança.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )

                                Text(
                                    text = "Tamanho do Buffer de I/O (KB)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(16, 32, 64, 128).forEach { kb ->
                                        FilterChip(
                                            selected = bufferKb == kb,
                                            onClick = { bufferKb = kb },
                                            label = {
                                                Text(
                                                    "$kb KB",
                                                    fontFamily = JetBrainsMonoFontFamily
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = NeonGreenContainer,
                                                selectedLabelColor = NeonGreen
                                            )
                                        )
                                    }
                                }

                                Text(
                                    text = "MTU da Interface TUN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(1280, 1420, 1500).forEach { m ->
                                        FilterChip(
                                            selected = mtu == m,
                                            onClick = { mtu = m },
                                            label = {
                                                Text(
                                                    "$m B",
                                                    fontFamily = JetBrainsMonoFontFamily
                                                )
                                            },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = ElectricBlueContainer,
                                                selectedLabelColor = ElectricBlue
                                            )
                                        )
                                    }
                                }

                                ToggleRow(
                                    title = "TCP_NODELAY (Desativar Nagle)",
                                    subtitle = "Reduz latência de pacotes interativos no túnel",
                                    checked = tcpNoDelay,
                                    onCheckedChange = { tcpNoDelay = it },
                                    accent = NeonGreen,
                                    testTag = "speed_tcp_nodelay_switch"
                                )

                                ToggleRow(
                                    title = "SO_KEEPALIVE & Heartbeat",
                                    subtitle = "Mantém sessões NAT móveis (4G/5G) vivas sem quedas silenciosas",
                                    checked = keepAliveEnabled,
                                    onCheckedChange = { keepAliveEnabled = it },
                                    accent = ElectricBlue,
                                    testTag = "speed_keepalive_switch"
                                )

                                ToggleRow(
                                    title = "Reconexão Inteligente Automática",
                                    subtitle = "Recuperação controlada após mudanças de IP ou oscilações 4G/5G",
                                    checked = autoReconnect,
                                    onCheckedChange = { autoReconnect = it },
                                    accent = NeonGreen,
                                    testTag = "speed_autoreconnect_switch"
                                )
                            }
                        }
                    }
                }
            }

            // Primary Save Action
            item {
                Button(
                    onClick = { onSaveConfig(buildDraftConfig()) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonGreen,
                        contentColor = ObsidianBlack
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("save_config_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Save,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.config_save),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            // Save as Reusable Local Profile
            item {
                CarbonPanelCard(borderColor = CarbonCardBorder) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Perfis Guardados no Dispositivo",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ConfigTextField(
                                value = profileName,
                                onValueChange = { profileName = it },
                                label = "Nome do Perfil",
                                placeholder = "ex.: Servidor Europa 1",
                                modifier = Modifier.weight(1f),
                                testTag = "profile_name_input"
                            )
                            OutlinedButton(
                                onClick = {
                                    onSaveProfile(profileName, buildDraftConfig())
                                    profileName = ""
                                },
                                border = BorderStroke(1.dp, ElectricBlue.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricBlue),
                                modifier = Modifier
                                    .height(54.dp)
                                    .testTag("save_profile_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.BookmarkAdd,
                                    contentDescription = "Guardar perfil"
                                )
                            }
                        }

                        if (savedProfiles.isEmpty()) {
                            Text(
                                text = "Nenhum perfil guardado ainda. Insira os dados reais do seu servidor e guarde como perfil para alternar rapidamente.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        } else {
                            savedProfiles.forEach { profile ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = CarbonElevated,
                                    border = BorderStroke(1.dp, CarbonCardBorder),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onApplyProfile(profile) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = profile.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = buildString {
                                                    if (profile.sshHost.isNotBlank()) append("SSH: ${profile.sshHost}:${profile.sshPort}")
                                                    if (profile.sshHost.isNotBlank() && profile.tlsHost.isNotBlank()) append(" • ")
                                                    if (profile.tlsHost.isNotBlank()) append("TLS: ${profile.tlsHost}:${profile.tlsPort}")
                                                }.ifBlank { "Sem endpoint" },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        IconButton(onClick = { onDeleteProfile(profile.id) }) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = "Remover perfil",
                                                tint = CrimsonError
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Honest Mobile Transport Notice
            item {
                CarbonPanelCard(
                    borderColor = ElectricBlue.copy(alpha = 0.25f),
                    backgroundColor = ElectricBlueContainer.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = ElectricBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.config_transport_notice),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun SectionChip(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.16f) else CarbonElevated,
        border = BorderStroke(1.dp, if (selected) accent.copy(alpha = 0.7f) else CarbonCardBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) accent else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = if (selected) accent else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ConfigTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    testTag: String
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = TextMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = gvpnTextFieldColors(),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
    )
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = ObsidianBlack,
                checkedTrackColor = accent,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = CarbonElevated,
                uncheckedBorderColor = CarbonCardBorder
            )
        )
    }
}

@Composable
private fun gvpnTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonGreen,
    unfocusedBorderColor = CarbonCardBorder,
    focusedLabelColor = NeonGreen,
    unfocusedLabelColor = TextSecondary,
    cursorColor = NeonGreen,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedContainerColor = CarbonElevated,
    unfocusedContainerColor = CarbonElevated
)
