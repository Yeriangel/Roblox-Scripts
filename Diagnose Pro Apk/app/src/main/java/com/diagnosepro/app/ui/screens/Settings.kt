package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.diagnosepro.app.BuildConfig
import com.diagnosepro.app.R
import com.diagnosepro.app.data.settings.PollingSpeed
import com.diagnosepro.app.data.settings.ThemeMode
import com.diagnosepro.app.domain.EconomyUnit
import com.diagnosepro.app.domain.PressureUnit
import com.diagnosepro.app.domain.SpeedUnit
import com.diagnosepro.app.domain.TempUnit
import com.diagnosepro.app.i18n.AppLanguage
import com.diagnosepro.app.i18n.Bidi
import com.diagnosepro.app.i18n.LocaleManager
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.Banner
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.SectionTitle
import com.diagnosepro.app.ui.nav.Routes

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (v, label) ->
            SegmentedButton(selected = v == selected, onClick = { onSelect(v) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))

@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    var lang by remember { mutableStateOf(LocaleManager.current()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        DrivingWarning(vm, Modifier.padding(top = 8.dp))
        SectionTitle(stringResource(R.string.set_language))
        Segmented(
            listOf(AppLanguage.HEBREW to stringResource(R.string.lang_hebrew), AppLanguage.ENGLISH to stringResource(R.string.lang_english), AppLanguage.SYSTEM to stringResource(R.string.lang_system)),
            lang,
        ) { lang = it; LocaleManager.set(it) }

        SectionTitle(stringResource(R.string.set_units))
        Label(stringResource(R.string.set_speed_unit))
        // Unit symbols are wrapped as LTR so "°C" never renders as "C°" in Hebrew.
        Segmented(listOf(SpeedUnit.KMH to Bidi.ltr("km/h"), SpeedUnit.MPH to Bidi.ltr("mph")), s.units.speed) { vm.setUnits(s.units.copy(speed = it)) }
        Label(stringResource(R.string.set_temp_unit))
        Segmented(listOf(TempUnit.C to Bidi.ltr("°C"), TempUnit.F to Bidi.ltr("°F")), s.units.temp) { vm.setUnits(s.units.copy(temp = it)) }
        Label(stringResource(R.string.set_pressure_unit))
        Segmented(listOf(PressureUnit.KPA to Bidi.ltr("kPa"), PressureUnit.BAR to Bidi.ltr("bar"), PressureUnit.PSI to Bidi.ltr("psi")), s.units.pressure) { vm.setUnits(s.units.copy(pressure = it)) }
        Label(stringResource(R.string.set_economy_unit))
        Segmented(listOf(EconomyUnit.L_PER_100KM to Bidi.ltr("L/100km"), EconomyUnit.KM_PER_L to Bidi.ltr("km/L"), EconomyUnit.MPG_US to Bidi.ltr("MPG")), s.units.economy) { vm.setUnits(s.units.copy(economy = it)) }

        SectionTitle(stringResource(R.string.set_connection))
        Label(stringResource(R.string.set_polling))
        Segmented(listOf(PollingSpeed.FAST to stringResource(R.string.polling_fast), PollingSpeed.NORMAL to stringResource(R.string.polling_normal), PollingSpeed.SLOW to stringResource(R.string.polling_slow)), s.polling) { vm.setPolling(it) }
        Text(stringResource(R.string.polling_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        SwitchRow(stringResource(R.string.set_auto_connect), stringResource(R.string.set_auto_connect_desc), s.autoConnect) { vm.setAutoConnect(it) }
        SwitchRow(stringResource(R.string.set_keep_screen), null, s.keepScreenOn) { vm.setKeepScreenOn(it) }
        if (s.lastAdapter != null) TextButton(onClick = { vm.forgetAdapter() }) { Text(stringResource(R.string.set_forget_adapter) + " (" + Bidi.ltr(s.lastAdapter.name) + ")") }

        SectionTitle(stringResource(R.string.set_appearance))
        Label(stringResource(R.string.set_theme))
        Segmented(listOf(ThemeMode.DARK to stringResource(R.string.theme_dark), ThemeMode.LIGHT to stringResource(R.string.theme_light), ThemeMode.SYSTEM to stringResource(R.string.theme_system)), s.theme) { vm.setTheme(it) }

        SectionTitle(stringResource(R.string.set_advanced))
        SwitchRow(stringResource(R.string.set_dev_console), stringResource(R.string.set_dev_console_desc), s.developerConsole) { vm.setDevConsole(it) }
        TextButton(onClick = { nav.navigate(Routes.VEHICLE) }) { Text(stringResource(R.string.set_vehicles)) }
        TextButton(onClick = { nav.navigate(Routes.ONBOARDING) }) { Text(stringResource(R.string.set_onboarding)) }
        TextButton(onClick = { nav.navigate(Routes.ABOUT) }) { Text(stringResource(R.string.set_about)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.set_about), onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.about_version, Bidi.ltr(BuildConfig.VERSION_NAME)))
            Text(stringResource(R.string.about_text))
            InfoCard {
                Text(stringResource(R.string.about_limits_title), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.limits_note))
            }
            Banner(stringResource(R.string.about_safety), color = MaterialTheme.colorScheme.secondary)
            Banner(stringResource(R.string.driving_warning))
        }
    }
}
