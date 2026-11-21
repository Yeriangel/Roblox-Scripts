package com.diagnosepro.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diagnosepro.app.R
import com.diagnosepro.app.ui.components.BackTopBar
import com.diagnosepro.app.ui.components.InfoCard
import com.diagnosepro.app.ui.components.LtrText

/** Plain-language explanation of common control-module abbreviations. */
@Composable
fun GlossaryScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar(stringResource(R.string.tool_glossary), onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(GLOSSARY, key = { it.first }) { (abbr, text) ->
                InfoCard {
                    LtrText(abbr, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}
