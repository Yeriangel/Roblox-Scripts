package com.diagnosepro.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.diagnosepro.app.R
import com.diagnosepro.app.data.settings.AppSettings
import com.diagnosepro.app.domain.ConnectionState
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.DemoBadge
import com.diagnosepro.app.ui.screens.AboutScreen
import com.diagnosepro.app.ui.screens.AlertBanner
import com.diagnosepro.app.ui.screens.AlertsScreen
import com.diagnosepro.app.ui.screens.AllSensorsScreen
import com.diagnosepro.app.ui.screens.BatteryTestScreen
import com.diagnosepro.app.ui.screens.CustomPidsScreen
import com.diagnosepro.app.ui.screens.DtcSearchScreen
import com.diagnosepro.app.ui.screens.HudScreen
import com.diagnosepro.app.ui.screens.MaintenanceScreen
import com.diagnosepro.app.ui.screens.PerformanceScreen
import com.diagnosepro.app.ui.screens.CodeDetailScreen
import com.diagnosepro.app.ui.screens.CodesScreen
import com.diagnosepro.app.ui.screens.ConnectScreen
import com.diagnosepro.app.ui.screens.ConsoleScreen
import com.diagnosepro.app.ui.screens.DashboardScreen
import com.diagnosepro.app.ui.screens.FreezeFrameScreen
import com.diagnosepro.app.ui.screens.FuelScreen
import com.diagnosepro.app.ui.screens.GaugeConfigScreen
import com.diagnosepro.app.ui.screens.GraphScreen
import com.diagnosepro.app.ui.screens.HistoryScreen
import com.diagnosepro.app.ui.screens.Mode06Screen
import com.diagnosepro.app.ui.screens.OnboardingScreen
import com.diagnosepro.app.ui.screens.ReadinessScreen
import com.diagnosepro.app.ui.screens.ScanDetailScreen
import com.diagnosepro.app.ui.screens.SessionDetailScreen
import com.diagnosepro.app.ui.screens.SettingsScreen
import com.diagnosepro.app.ui.screens.ToolsScreen
import com.diagnosepro.app.ui.screens.VehicleInfoScreen
import com.diagnosepro.app.ui.stateText
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.Ok
import com.diagnosepro.app.ui.theme.Warn

object Routes {
    const val ONBOARDING = "onboarding"
    const val DASHBOARD = "dashboard"
    const val CODES = "codes"
    const val GRAPH = "graph"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val GAUGES = "gauges"
    const val TOOLS = "tools"
    const val READINESS = "readiness"
    const val FREEZE = "freeze"
    const val VEHICLE = "vehicle"
    const val MODE06 = "mode06"
    const val FUEL = "fuel"
    const val ACCEL = "accel"
    const val CONSOLE = "console"
    const val ABOUT = "about"
    const val SENSORS = "sensors"
    const val CUSTOM_PIDS = "custom_pids"
    const val ALERTS = "alerts"
    const val HUD = "hud"
    const val BATTERY = "battery"
    const val DTC_SEARCH = "dtc_search"
    const val MAINTENANCE = "maintenance"
    const val SELFTEST = "selftest"
    const val FULLSCAN = "fullscan"
    const val GLOSSARY = "glossary"
    fun code(code: String, ecu: String? = null, kind: String? = null) = "code/$code?ecu=${ecu.orEmpty()}&kind=${kind.orEmpty()}"
    fun scan(id: Long) = "scan/$id"
    fun session(id: Long) = "session/$id"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, R.string.nav_dashboard, Icons.Filled.Speed),
    Tab(Routes.CODES, R.string.nav_codes, Icons.Filled.ReportProblem),
    Tab(Routes.GRAPH, R.string.nav_graph, Icons.AutoMirrored.Filled.ShowChart),
    Tab(Routes.HISTORY, R.string.nav_history, Icons.Filled.History),
    Tab(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

@Composable
fun DiagnoseProApp(vm: MainViewModel, settings: AppSettings) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTab = tabs.any { it.route == route }

    LaunchedEffect(Unit) { vm.tryAutoConnect() }

    Scaffold(
        topBar = { if (isTab) MainTopBar(vm, route, onStatus = { nav.navigate(Routes.CONNECT) }) },
        bottomBar = {
            if (isTab) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) },
                        label = { Text(stringResource(t.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(if (isTab) padding else androidx.compose.foundation.layout.PaddingValues(0.dp))) {
            androidx.compose.foundation.layout.Column {
                // Firing alarms are visible on every main tab.
                if (isTab) AlertBanner(vm)
                AppNavHost(nav, vm, if (settings.onboardingDone) Routes.DASHBOARD else Routes.ONBOARDING)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(vm: MainViewModel, route: String?, onStatus: () -> Unit) {
    val state by vm.obd.state.collectAsStateWithLifecycle()
    val title = tabs.firstOrNull { it.route == route }?.label ?: R.string.app_name
    TopAppBar(
        title = { Text(stringResource(if (route == Routes.DASHBOARD) R.string.app_name else title), maxLines = 1) },
        actions = {
            if ((state as? ConnectionState.Connected)?.demo == true) { DemoBadge(); Spacer(Modifier.width(8.dp)) }
            StatusChip(state, onStatus)
            Spacer(Modifier.width(8.dp))
        },
    )
}

/** Always-visible connection status. Tap to open the connection screen. */
@Composable
fun StatusChip(state: ConnectionState, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val color = when (state) {
        is ConnectionState.Connected -> Ok
        is ConnectionState.Error -> Danger
        ConnectionState.Disconnected -> MaterialTheme.colorScheme.outline
        else -> Warn
    }
    val text = when (state) {
        // Short form in the chip; the full protocol name is shown on the connection screen.
        is ConnectionState.Connected -> stringResource(
            R.string.status_connected,
            Bidi.ltr(if (state.protocol?.isCan != false) "CAN" else state.protocol.displayName),
        )
        else -> stateText(ctx, state)
    }
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 200.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController, vm: MainViewModel, start: String) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(onDone = {
                vm.finishOnboarding()
                nav.navigate(Routes.DASHBOARD) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            })
        }
        composable(Routes.DASHBOARD) { DashboardScreen(vm, nav) }
        composable(Routes.CODES) { CodesScreen(vm, nav) }
        composable(Routes.GRAPH) { GraphScreen(vm) }
        composable(Routes.HISTORY) { HistoryScreen(vm, nav) }
        composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
        composable(Routes.CONNECT) { ConnectScreen(vm, back) }
        composable(Routes.GAUGES) { GaugeConfigScreen(vm, back) }
        composable(Routes.TOOLS) { ToolsScreen(vm, nav) }
        composable(Routes.READINESS) { ReadinessScreen(vm, back) }
        composable(Routes.FREEZE) { FreezeFrameScreen(vm, back, nav) }
        composable(Routes.VEHICLE) { VehicleInfoScreen(vm, back) }
        composable(Routes.MODE06) { Mode06Screen(vm, back) }
        composable(Routes.FUEL) { FuelScreen(vm, back) }
        composable(Routes.ACCEL) { PerformanceScreen(vm, back) }
        composable(Routes.SENSORS) { AllSensorsScreen(vm, back) }
        composable(Routes.CUSTOM_PIDS) { CustomPidsScreen(vm, back) }
        composable(Routes.ALERTS) { AlertsScreen(vm, back) }
        composable(Routes.HUD) { HudScreen(vm, back) }
        composable(Routes.BATTERY) { BatteryTestScreen(vm, back) }
        composable(Routes.DTC_SEARCH) { DtcSearchScreen(vm, back, nav) }
        composable(Routes.MAINTENANCE) { MaintenanceScreen(vm, back) }
        composable(Routes.GLOSSARY) { com.diagnosepro.app.ui.screens.GlossaryScreen(back) }
        composable(Routes.FULLSCAN) { com.diagnosepro.app.ui.screens.FullScanScreen(vm, back, nav) }
        composable(Routes.SELFTEST) { com.diagnosepro.app.ui.screens.SelfTestScreen(vm, back) }
        composable(Routes.CONSOLE) { ConsoleScreen(vm, back) }
        composable(Routes.ABOUT) { AboutScreen(back) }
        composable(
            "code/{code}?ecu={ecu}&kind={kind}",
            arguments = listOf(
                navArgument("code") { type = NavType.StringType },
                navArgument("ecu") { type = NavType.StringType; defaultValue = "" },
                navArgument("kind") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { e ->
            CodeDetailScreen(vm, e.arguments?.getString("code").orEmpty(), e.arguments?.getString("ecu").orEmpty(), e.arguments?.getString("kind").orEmpty(), back)
        }
        composable("scan/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            ScanDetailScreen(vm, e.arguments?.getLong("id") ?: 0, back, nav)
        }
        composable("session/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
            SessionDetailScreen(vm, e.arguments?.getLong("id") ?: 0, back)
        }
    }
}
