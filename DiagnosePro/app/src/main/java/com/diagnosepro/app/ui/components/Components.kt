package com.diagnosepro.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diagnosepro.app.R
import com.diagnosepro.app.ui.theme.Accent
import com.diagnosepro.app.ui.theme.Danger
import com.diagnosepro.app.ui.theme.GaugeValueStyle
import com.diagnosepro.app.ui.theme.Warn

/** Text that is always laid out left-to-right (codes, VINs, numbers with units). */
@Composable
fun LtrText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = color, style = style.copy(textDirection = TextDirection.Ltr), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        // AutoMirrored icon flips automatically in RTL.
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        actions = { actions() },
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
fun Banner(text: String, color: Color = Warn, icon: ImageVector = Icons.Filled.Warning, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Surface(
        color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val m = modifier.fillMaxWidth()
    if (onClick != null) {
        Card(onClick = onClick, modifier = m, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    } else {
        Card(modifier = m, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) { content() }
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, ltrValue: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        if (ltrValue) LtrText(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
        else Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium))
    }
}

@Composable
fun DemoBadge() {
    Surface(color = Accent, shape = RoundedCornerShape(6.dp)) {
        Text(stringResource(R.string.demo_badge), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = Color.Black, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
    }
}

/**
 * Round automotive gauge drawn on Canvas. 240° sweep starting bottom-start.
 * The arc direction is mirrored in RTL so the value grows from the start side.
 */
@Composable
fun ArcGauge(
    label: String, valueText: String, unit: String, fraction: Float, warning: Boolean,
    rtl: Boolean, modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.outline
    val color = if (warning) Danger else Accent
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.08f
                val d = size.minDimension - stroke
                val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
                drawArc(track, 150f, 240f, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = 240f * fraction.coerceIn(0f, 1f)
                if (rtl) drawArc(color, 30f, -sweep, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
                else drawArc(color, 150f, sweep, false, topLeft, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LtrText(valueText, style = GaugeValueStyle, color = if (warning) Danger else MaterialTheme.colorScheme.onSurface)
                LtrText(unit, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

/** Compact value tile for secondary gauges. */
@Composable
fun ValueTile(label: String, valueText: String, unit: String, warning: Boolean, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = if (warning) Danger.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                // Number + unit isolated together as LTR (e.g. "92 °C" never becomes "°C 92")
                LtrText(if (unit.isEmpty()) valueText else "$valueText $unit", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 26.sp),
                    color = if (warning) Danger else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            }
        }
    }
}

/**
 * Lightweight real-time line chart. Y-axis labels sit on the start side (right in Hebrew);
 * time always flows left → right, as on every automotive scope.
 */
@Composable
fun LineChart(
    points: List<Pair<Long, Double>>, windowMs: Long, endTime: Long, color: Color,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val visible = points.filter { it.first >= endTime - windowMs && it.first <= endTime }
    val minV = visible.minOfOrNull { it.second } ?: 0.0
    val maxV = visible.maxOfOrNull { it.second } ?: 1.0
    val pad = ((maxV - minV) * 0.1).coerceAtLeast(0.5)
    // Values that are never negative (speed, rpm, temps above 0) keep the axis at >= 0.
    val lo = if (minV >= 0) (minV - pad).coerceAtLeast(0.0) else minV - pad
    val hi = maxV + pad
    Row(modifier) {
        val labels: @Composable () -> Unit = {
            Column(Modifier.width(48.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                LtrText(fmtAxis(hi), style = MaterialTheme.typography.labelSmall, color = labelColor)
                LtrText(fmtAxis((hi + lo) / 2), style = MaterialTheme.typography.labelSmall, color = labelColor)
                LtrText(fmtAxis(lo), style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
        // In RTL the Row already places the first child on the right (start side).
        labels()
        Canvas(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))) {
            for (i in 0..4) {
                val y = size.height * i / 4f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            if (visible.size < 2) return@Canvas
            val path = Path()
            visible.forEachIndexed { i, (t, v) ->
                val x = ((t - (endTime - windowMs)).toFloat() / windowMs) * size.width
                val y = size.height - ((v - lo) / (hi - lo)).toFloat() * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
        }
    }
}


private fun fmtAxis(v: Double): String = when {
    kotlin.math.abs(v) >= 100 -> String.format(java.util.Locale.US, "%.0f", v)
    kotlin.math.abs(v) >= 10 -> String.format(java.util.Locale.US, "%.1f", v)
    else -> String.format(java.util.Locale.US, "%.2f", v)
}
