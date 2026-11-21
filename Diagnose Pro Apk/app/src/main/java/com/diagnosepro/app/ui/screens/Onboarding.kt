package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diagnosepro.app.R
import com.diagnosepro.app.ui.components.Banner
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pages = listOf(
        Triple(Icons.Filled.ElectricalServices, R.string.onb_title_1, R.string.onb_text_1),
        Triple(Icons.Filled.Bluetooth, R.string.onb_title_2, R.string.onb_text_2),
        Triple(Icons.Filled.PhonelinkRing, R.string.onb_title_3, R.string.onb_text_3),
    )
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.skip)) }
        }
        // HorizontalPager mirrors automatically in RTL (swipe direction follows the reading direction).
        HorizontalPager(pager, Modifier.weight(1f)) { i ->
            val (icon, title, text) = pages[i]
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(140.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(32.dp))
                Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                if (i == pages.lastIndex) {
                    Spacer(Modifier.height(24.dp))
                    Banner(stringResource(R.string.limits_note), color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(pages.size) { i ->
                Box(Modifier.padding(4.dp).size(if (pager.currentPage == i) 12.dp else 8.dp)
                    .background(if (pager.currentPage == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
        Button(
            onClick = { if (pager.currentPage < pages.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else onDone() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(stringResource(if (pager.currentPage < pages.lastIndex) R.string.next else R.string.onb_get_started)) }
    }
}
