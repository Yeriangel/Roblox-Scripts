package com.diagnosepro.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diagnosepro.app.data.settings.ThemeMode
import com.diagnosepro.app.ui.MainViewModel
import com.diagnosepro.app.ui.nav.DiagnoseProApp
import com.diagnosepro.app.ui.theme.DiagnoseProTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity. Extends AppCompatActivity so AppCompatDelegate.setApplicationLocales
 * switches Hebrew/English instantly (the activity is recreated with the new locale and RTL/LTR).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { vm.settings.value == null }
        enableEdgeToEdge()
        setContent {
            val settings by vm.settings.collectAsStateWithLifecycle()
            val state by vm.obd.state.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            val dark = when (s.theme) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
            val keepOn = s.keepScreenOn && state.isConnected
            DisposableEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                onDispose { }
            }
            DiagnoseProTheme(dark) { DiagnoseProApp(vm, s) }
        }
    }
}
