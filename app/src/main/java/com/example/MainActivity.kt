package com.example

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.VpnConnectionState
import com.example.ui.GVpnViewModel
import com.example.ui.screens.BenchmarkScreen
import com.example.ui.screens.ConfigScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LogsScreen
import com.example.ui.theme.CarbonElevated
import com.example.ui.theme.CarbonSurface
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.GVpnTheme
import com.example.ui.theme.NeonGreen
import com.example.ui.theme.ObsidianBlack
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

enum class GVpnTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    DASHBOARD("dashboard", "Painel", Icons.Rounded.Shield, Icons.Outlined.Shield),
    CONFIG("config", "Config", Icons.Rounded.Tune, Icons.Outlined.Tune),
    BENCHMARK("benchmark", "Benchmark", Icons.Rounded.Analytics, Icons.Outlined.Analytics),
    LOGS("logs", "Logs", Icons.Rounded.Terminal, Icons.Outlined.Terminal)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(ObsidianBlack.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(CarbonSurface.toArgb())
        )
        setContent {
            GVpnTheme {
                GVpnApp()
            }
        }
    }
}

@Composable
fun GVpnApp(
    viewModel: GVpnViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val config by viewModel.config.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val benchmarkProgress by viewModel.benchmarkProgress.collectAsStateWithLifecycle()
    val latestBenchmark by viewModel.latestBenchmark.collectAsStateWithLifecycle()
    val benchmarkHistory by viewModel.benchmarkHistory.collectAsStateWithLifecycle()
    val savedProfiles by viewModel.savedProfiles.collectAsStateWithLifecycle()
    val uiMessage by viewModel.uiMessage.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(GVpnTab.DASHBOARD) }
    var pendingConnectAfterNotif by remember { mutableStateOf(false) }

    // Android VpnService.prepare() consent launcher
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnConnection()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Permissão VPN Android recusada pelo utilizador.")
            }
        }
    }

    // Android 13+ runtime POST_NOTIFICATIONS launcher for foreground service notification
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Proceed to VPN consent regardless so tunnel remains usable even if user declined notifications
        if (pendingConnectAfterNotif) {
            pendingConnectAfterNotif = false
            val prepareIntent = viewModel.prepareVpnIntent()
            if (prepareIntent != null) {
                vpnPermissionLauncher.launch(prepareIntent)
            } else {
                viewModel.startVpnConnection()
            }
        }
    }

    fun requestConnectFlow() {
        val isActive = telemetry.state == VpnConnectionState.CONNECTED ||
            telemetry.state == VpnConnectionState.CONNECTING ||
            telemetry.state == VpnConnectionState.RECONNECTING
        if (isActive) {
            viewModel.stopVpnConnection()
            return
        }

        if (!config.ssh.isConfigured() && !config.tls.isConfigured()) {
            viewModel.startVpnConnection() // Emits clear actionable error state
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                pendingConnectAfterNotif = true
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        val prepareIntent = viewModel.prepareVpnIntent()
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            viewModel.startVpnConnection()
        }
    }

    LaunchedEffect(uiMessage) {
        val msg = uiMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearUiMessage()
    }

    // Back press on secondary tabs returns cleanly to Painel
    BackHandler(enabled = currentTab != GVpnTab.DASHBOARD) {
        currentTab = GVpnTab.DASHBOARD
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 680.dp

        Scaffold(
            containerColor = ObsidianBlack,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = CarbonSurface,
                        tonalElevation = 0.dp
                    ) {
                        GVpnTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = NeonGreen,
                                    selectedTextColor = NeonGreen,
                                    indicatorColor = CarbonElevated,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_${tab.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = CarbonSurface
                    ) {
                        GVpnTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = NeonGreen,
                                    selectedTextColor = NeonGreen,
                                    indicatorColor = CarbonElevated,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_${tab.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        GVpnTab.DASHBOARD -> DashboardScreen(
                            config = config,
                            telemetry = telemetry,
                            networkState = networkState,
                            recentLogs = logs,
                            onTogglePower = { requestConnectFlow() },
                            onSelectSmartMode = { viewModel.setSmartMode(it) },
                            onRefreshPublicIp = { viewModel.refreshPublicIp() },
                            onOpenConfig = { currentTab = GVpnTab.CONFIG }
                        )

                        GVpnTab.CONFIG -> ConfigScreen(
                            config = config,
                            savedProfiles = savedProfiles,
                            onSaveConfig = { viewModel.saveConfig(it) },
                            onSaveProfile = { name, cfg -> viewModel.saveCurrentAsProfile(name, cfg) },
                            onApplyProfile = { viewModel.applyProfile(it) },
                            onDeleteProfile = { viewModel.deleteProfile(it) }
                        )

                        GVpnTab.BENCHMARK -> BenchmarkScreen(
                            progress = benchmarkProgress,
                            latest = latestBenchmark,
                            history = benchmarkHistory,
                            onStartTest = { viewModel.startBenchmarkV6() },
                            onStopTest = { viewModel.stopBenchmarkV6() },
                            onSelectHistory = { viewModel.selectBenchmarkSnapshot(it) },
                            onClearHistory = { viewModel.clearBenchmarkHistory() }
                        )

                        GVpnTab.LOGS -> LogsScreen(
                            logs = logs,
                            telemetry = telemetry,
                            onClearLogs = { viewModel.clearLogs() },
                            onCopiedNotice = { msg ->
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        )
                    }
                }
            }
        }
    }
}
