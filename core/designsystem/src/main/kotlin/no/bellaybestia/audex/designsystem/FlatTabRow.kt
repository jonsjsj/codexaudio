package no.bellaybestia.audex.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The flat underlined tab bar mandated by the Codex design rules: a full-width
 * row sitting on a hairline baseline; each tab underlines 2dp in the accent
 * color when active. Tabs spread across the available width (weight 1 each).
 * Never replace this with pill chips or filled segmented buttons.
 *
 * Pass [icons] (one per tab) to stack an icon above each label — used by the
 * bottom navigation bar so Home / Library / Downloads / Settings read at a
 * glance. Omit it for the plain text sub-tab rows (Settings, Library).
 */
@Composable
fun FlatTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icons: List<ImageVector>? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val baseline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(baseline, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            },
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
            val icon = icons?.getOrNull(index)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(index) }
                    .drawBehind {
                        if (selected) {
                            val y = size.height - 1.dp.toPx()
                            drawLine(accent, Offset(0f, y), Offset(size.width, y), 2.dp.toPx())
                        }
                    }
                    .padding(vertical = if (icon != null) 8.dp else 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = label,
                            color = tint,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                } else {
                    Text(
                        text = label,
                        color = tint,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}
