package com.networkanalyst.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.networkanalyst.app.ui.theme.LocalStatusColors
import com.networkanalyst.core.analysis.Finding
import com.networkanalyst.core.analysis.Severity

fun severityIcon(severity: Severity): ImageVector = when (severity) {
    Severity.CRITICAL -> Icons.Filled.Error
    Severity.WARNING -> Icons.Filled.Warning
    Severity.INFO -> Icons.Filled.Info
    Severity.GOOD -> Icons.Filled.CheckCircle
}

/** A titled card that groups related content. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    trailing?.invoke()
                }
                Spacer(Modifier.height(12.dp))
            }
            content()
        }
    }
}

/** Circular score indicator: an arc filled in proportion to [score] out of 100. */
@Composable
fun HealthRing(score: Int, modifier: Modifier = Modifier, diameter: Dp = 112.dp) {
    val color = LocalStatusColors.current.ofScore(score)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = modifier.size(diameter).semantics { contentDescription = "Network health $score out of 100" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter)) {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * (score.coerceIn(0, 100) / 100f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score.toString(), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = color)
            Text("health", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Half-circle gauge for a live speed reading. The scale is logarithmic so that both a 5 Mbps
 * and a 900 Mbps line give a readable needle position.
 */
@Composable
fun SpeedGauge(mbps: Double, label: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fraction = gaugeFraction(mbps)
    Box(modifier = modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(width = 260.dp, height = 150.dp)) {
            val stroke = 16.dp.toPx()
            val diameter = size.width - stroke
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(track, 180f, 180f, false, topLeft, Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0f) {
                drawArc(accent, 180f, 180f * fraction, false, topLeft, Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.speedValue(mbps), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(Fmt.speedUnit(mbps), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = accent)
        }
    }
}

/** Maps 0 to 1000+ Mbps onto 0..1 on a log scale (1 Mbps is about a fifth of the way). */
fun gaugeFraction(mbps: Double): Float {
    if (mbps <= 0.0) return 0f
    val value = kotlin.math.log10(1 + mbps) / kotlin.math.log10(1001.0)
    return value.toFloat().coerceIn(0f, 1f)
}

/** A simple line chart of recent values. [range] fixes the vertical scale when given. */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    range: ClosedFloatingPointRange<Double>? = null,
) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val low = range?.start ?: 0.0
        val high = (range?.endInclusive ?: values.max()).let { if (it <= low) low + 1 else it }
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value.coerceIn(low, high) - low) / (high - low)).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Horizontal meter. [fraction] is 0..1. */
@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = size.height / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))
        drawRoundRect(
            color,
            size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
        )
    }
}

/** One labelled number, e.g. "Download / 280 Mbps". */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface, caption: String? = null) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A row of "label    value" for detail lists. */
@Composable
fun DetailRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, modifier = Modifier.weight(0.58f))
    }
}

/** A finding from the analysis. Tapping it reveals the explanation and the fixes. */
@Composable
fun FindingCard(finding: Finding, modifier: Modifier = Modifier, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable(finding.id) { mutableStateOf(initiallyExpanded) }
    val color = LocalStatusColors.current.of(finding.severity)
    Card(
        modifier = modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(severityIcon(finding.severity), contentDescription = finding.severity.label, tint = color, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(finding.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${finding.severity.label} · ${finding.category.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = color,
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 10.dp)) {
                    Text(finding.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (finding.fixes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("What to do", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        finding.fixes.forEachIndexed { index, fix ->
                            Row(Modifier.padding(vertical = 3.dp)) {
                                Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(22.dp))
                                Text(fix, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A notice with an optional action, used for permission prompts and empty states. */
@Composable
fun Notice(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.secondary,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action != null) {
                    Spacer(Modifier.height(4.dp))
                    action()
                }
            }
        }
    }
}
