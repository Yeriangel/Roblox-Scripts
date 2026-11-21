package com.diagnosepro.app.ui

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diagnosepro.app.MainActivity
import com.diagnosepro.app.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Drives the real app (Hilt, Room, DataStore, ELM327 stack) in Demo mode under Robolectric
 * and saves a screenshot of every main screen, in English (LTR) and Hebrew (RTL).
 * Screenshots are written to app/build/screenshots/(lang)/
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AppUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun s(@StringRes id: Int) = rule.activity.getString(id)

    /**
     * Draws every window (activity + dialogs, in z-order) into one bitmap.
     * Uses View.draw with Robolectric native graphics; [dialog] = only the topmost window.
     */
    private fun shot(lang: String, name: String, dialog: Boolean = false) {
        rule.waitForIdle()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val inst = wmg.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val names = (wmg.getMethod("getViewRootNames").invoke(inst) as Array<String>).toList()
        val views = names.mapNotNull { wmg.getMethod("getRootView", String::class.java).invoke(inst, it) as? android.view.View }
            .filter { it.width > 0 && it.height > 0 }
        val decor = rule.activity.window.decorView
        val w = decor.width; val h = decor.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val toDraw = if (dialog) listOf(decor) + views.filter { it !== decor }.takeLast(1) else listOf(decor) + views.filter { it !== decor }
        for ((i, v) in toDraw.withIndex()) {
            c.save()
            if (i > 0) { c.drawColor(0x99000000.toInt()); c.translate(((w - v.width) / 2).toFloat(), ((h - v.height) / 2).toFloat()) }
            v.draw(c)
            c.restore()
        }
        val dir = File("build/screenshots/$lang").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitText(text: String, timeout: Long = 20_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun clickText(text: String) = rule.onAllNodesWithText(text).onFirst().performClick()

    private fun tab(@StringRes id: Int) { clickText(s(id)); rule.waitForIdle() }

    private fun runFlow(lang: String) {
        try { flow(lang) } catch (e: Throwable) {
            val vm = androidx.lifecycle.ViewModelProvider(rule.activity)[MainViewModel::class.java]
            println("STATE=" + vm.obd.state.value + " LIVE=" + vm.obd.live.value.keys)
            println(vm.rawLog.asText().lines().takeLast(30).joinToString("\n"))
            runCatching { shot(lang, "zz_failure") }
            throw e
        }
    }

    private fun flow(lang: String) {
        // Onboarding (first launch only; DataStore may persist between tests in one JVM)
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(s(R.string.onb_title_1)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText(s(R.string.nav_codes)).fetchSemanticsNodes().isNotEmpty()
        }
        shot(lang, "01_onboarding")
        if (rule.onAllNodesWithText(s(R.string.skip)).fetchSemanticsNodes().isNotEmpty()) clickText(s(R.string.skip))
        rule.activity.runOnUiThread { }
        androidx.lifecycle.ViewModelProvider(rule.activity)[MainViewModel::class.java].disconnect()
        waitText(s(R.string.dash_not_connected_title))
        shot(lang, "02_dashboard_disconnected")

        // Connect screen -> Demo mode
        clickText(s(R.string.connect))
        waitText(s(R.string.connect_title))
        shot(lang, "03_connect")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(s(R.string.demo_mode_desc)))
        clickText(s(R.string.demo_mode_desc))
        waitText(s(R.string.disconnect), 30_000)
        rule.activity.onBackPressedDispatcher.onBackPressed()

        // Dashboard with live demo data: wait until the slow PIDs have been read once
        rule.waitUntil(30_000) {
            val live = androidx.lifecycle.ViewModelProvider(rule.activity)[MainViewModel::class.java].obd.live.value
            "coolant_temp" in live && "run_time" in live
        }
        rule.waitForIdle()
        shot(lang, "04_dashboard_live")

        // Codes: read, detail, clear dialogs
        tab(R.string.nav_codes)
        clickText(s(R.string.codes_read))
        waitText("P0301", 30_000)
        shot(lang, "05_codes")
        clickText(s(R.string.codes_clear))
        waitText(s(R.string.clear_title))
        shot(lang, "06_clear_dialog", dialog = true)
        clickText(s(R.string.clear_continue))
        waitText(s(R.string.clear_confirm_button))
        shot(lang, "07_clear_confirm", dialog = true)
        clickText(s(R.string.clear_confirm_button))
        waitText(s(R.string.clear_success), 40_000)
        shot(lang, "08_clear_result", dialog = true)
        clickText(s(R.string.ok))
        rule.waitForIdle()
        shot(lang, "09_codes_after_clear")

        // History contains the pre-clear scan
        tab(R.string.nav_history)
        waitText(s(R.string.hist_before_clear))
        shot(lang, "10_history")
        clickText("P0301")
        waitText(s(R.string.scan_title))
        waitText(s(R.string.codes_mil_on)) // scan details are loaded asynchronously
        waitText("P0301")
        clickText("P0301")
        waitText(s(R.string.detail_causes))
        shot(lang, "11_code_detail")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()

        // Live graph
        tab(R.string.nav_graph)
        Thread.sleep(3_000)
        shot(lang, "12_graph")

        // Tools: readiness, vehicle info, freeze frame (erased after clear), Mode 06
        tab(R.string.nav_dashboard)
        rule.onNode(androidx.compose.ui.test.hasContentDescription(s(R.string.dash_tools))).performClick()
        waitText(s(R.string.tools_title))
        shot(lang, "13_tools")
        clickText(s(R.string.tool_readiness_desc))
        waitText(s(R.string.rd_since_clear), 30_000)
        shot(lang, "14_readiness")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        clickText(s(R.string.tool_vehicle_desc))
        waitText(s(R.string.vi_manufacturer), 30_000)
        shot(lang, "15_vehicle_info")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        waitText(s(R.string.tool_mode06_desc))
        clickText(s(R.string.tool_mode06_desc))
        waitText(s(R.string.m06_read))
        clickText(s(R.string.m06_read))
        waitText(s(R.string.m06_pass), 30_000)
        shot(lang, "16_mode06")
        rule.activity.onBackPressedDispatcher.onBackPressed()

        // New tools (all reached from the Tools list, scrolling as needed)
        fun openTool(desc: Int, waitFor: Int) {
            waitText(s(R.string.tools_title))
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(s(desc)))
            clickText(s(desc))
            waitText(s(waitFor))
        }
        openTool(R.string.tool_fullscan_desc, R.string.fs_run)
        clickText(s(R.string.fs_run))
        waitText(s(R.string.mod_abs), 120_000)
        rule.waitUntil(60_000) { rule.onAllNodesWithText(s(R.string.fs_run)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(s(R.string.fs_details)).onFirst().performClick()
        waitText(s(R.string.did_system), 30_000)
        shot(lang, "27_full_scan")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_selftest_desc, R.string.st_run)
        clickText(s(R.string.st_run))
        waitText(s(R.string.st_live), 60_000)
        rule.waitUntil(60_000) { rule.onAllNodesWithText(s(R.string.st_run)).fetchSemanticsNodes().isNotEmpty() }
        shot(lang, "26_selftest")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_sensors_desc, R.string.pid_rpm)
        rule.waitUntil(30_000) {
            val live = androidx.lifecycle.ViewModelProvider(rule.activity)[MainViewModel::class.java].obd.live.value
            "boost" in live && "cat_temp_b1s1" in live
        }
        shot(lang, "18_all_sensors")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_perf_desc, R.string.perf_0_100)
        shot(lang, "19_performance")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_alerts_desc, R.string.alerts_sound)
        shot(lang, "20_alarms")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_battery_desc, R.string.battery_measure_rest)
        shot(lang, "21_battery")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_dtc_search_desc, R.string.dtc_search_hint)
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("P042")
        waitText("P0420")
        shot(lang, "22_code_lookup")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_maintenance_desc, R.string.maint_empty)
        shot(lang, "23_maintenance")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_custom_desc, R.string.custom_empty)
        shot(lang, "24_custom_pids")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        openTool(R.string.tool_hud_desc, R.string.hud_mirror)
        shot(lang, "25_hud")
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.activity.onBackPressedDispatcher.onBackPressed()

        // Settings
        tab(R.string.nav_settings)
        shot(lang, "17_settings")
    }

    @Test @Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
    fun englishFlow() {
        runFlow("en")
        assertEquals(android.util.LayoutDirection.LTR, rule.activity.resources.configuration.layoutDirection)
    }

    @Test @Config(sdk = [34], qualifiers = "iw-rIL-w411dp-h891dp-xhdpi")
    fun hebrewFlow() {
        runFlow("he")
        assertEquals(android.util.LayoutDirection.RTL, rule.activity.resources.configuration.layoutDirection)
    }
}
