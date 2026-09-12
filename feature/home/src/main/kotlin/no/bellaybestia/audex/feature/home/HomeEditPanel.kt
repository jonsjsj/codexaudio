package no.bellaybestia.audex.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import no.bellaybestia.audex.domain.settings.HomeSection

private fun HomeSection.label(): String = when (this) {
    HomeSection.CONTINUE -> "Continue"
    HomeSection.RECENTLY_ADDED -> "Recently added"
    HomeSection.RECENTLY_RELEASED -> "Recently released"
    HomeSection.UPCOMING -> "Upcoming releases"
}

private val ROW_HEIGHT = 56.dp

/**
 * Home's inline Edit surface: visible sections up top, draggable by their
 * handle and removable with the × (which sinks them below); hidden ones
 * listed underneath, greyed out, with a + to bring them back. Replaces
 * HomeScreen's normal content while active — there's nothing to scroll past,
 * just the (at most 4) section rows, so this is a plain Column, not a list.
 *
 * The drag gesture keys off the SECTION itself, not a numeric index: an index
 * captured at gesture-start goes stale the moment a reorder happens mid-drag
 * (every row's index shifts), so [localOrder]`.indexOf(section)` is
 * recomputed fresh on every pointer move instead.
 */
@Composable
internal fun HomeEditPanel(
    order: List<HomeSection>,
    hidden: Set<HomeSection>,
    onReorder: (List<HomeSection>) -> Unit,
    onSetHidden: (HomeSection, Boolean) -> Unit,
) {
    var localOrder by remember(order) { mutableStateOf(order) }
    val visible = localOrder.filter { it !in hidden }
    val hiddenOnes = localOrder.filter { it in hidden }
    var draggingSection by remember { mutableStateOf<HomeSection?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = "Drag to reorder. Tap × to hide a section.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, bottom = 8.dp),
        )
        visible.forEach { section ->
            val isDragging = section == draggingSection
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                    .background(
                        if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.background,
                    )
                    .height(ROW_HEIGHT)
                    .padding(horizontal = 12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Drag to reorder ${section.label()}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(8.dp)
                        .pointerInput(section) {
                            detectDragGestures(
                                onDragStart = { draggingSection = section; dragOffsetY = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetY += dragAmount.y
                                    val from = localOrder.indexOf(section)
                                    val visibleNow = localOrder.filter { it !in hidden }
                                    val fromVisible = visibleNow.indexOf(section)
                                    val toVisible = (fromVisible + (dragOffsetY / rowHeightPx).roundToInt())
                                        .coerceIn(0, visibleNow.lastIndex)
                                    if (toVisible != fromVisible) {
                                        val target = visibleNow[toVisible]
                                        val to = localOrder.indexOf(target)
                                        localOrder = localOrder.toMutableList().apply {
                                            add(to, removeAt(from))
                                        }
                                        dragOffsetY -= (toVisible - fromVisible) * rowHeightPx
                                    }
                                },
                                onDragEnd = {
                                    draggingSection = null
                                    dragOffsetY = 0f
                                    onReorder(localOrder)
                                },
                                onDragCancel = { draggingSection = null; dragOffsetY = 0f },
                            )
                        },
                )
                Text(
                    text = section.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                IconButton(onClick = { onSetHidden(section, true) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Hide ${section.label()}")
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (hiddenOnes.isNotEmpty()) {
            Text(
                text = "HIDDEN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, top = 20.dp, bottom = 4.dp),
            )
            hiddenOnes.forEach { section ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0.5f)
                        .height(ROW_HEIGHT)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text = section.label(),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(start = 36.dp),
                    )
                    IconButton(onClick = { onSetHidden(section, false) }) {
                        Icon(Icons.Filled.Add, contentDescription = "Show ${section.label()} again")
                    }
                }
            }
        }
    }
}
