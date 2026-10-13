package dev.materialtube.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A non-modal bottom panel (comments), like the YouTube app's: it opens at [collapsedHeight]
 * with whatever is behind it (the video) still visible and playing, and its list scrolls at
 * that height. Drag the handle/header up for full height, down to shrink or close. Scrolling
 * down past the top of the list also pulls the panel down.
 *
 * [content] goes below the handle and should fill the remaining space (e.g. a LazyColumn with
 * `Modifier.weight(1f)`).
 */
@Composable
fun BottomPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    collapsedHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val full = constraints.maxHeight.toFloat()
        val half = with(density) { (collapsedHeight ?: (maxHeight * 0.55f)).toPx() }.coerceIn(full * 0.3f, full)
        val height = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val dismissing = remember { booleanArrayOf(false) }

        fun settle(velocity: Float) {
            val h = height.value
            val anchors = listOf(0f, half, full).distinct().sorted()
            val target = when {
                velocity > 1800f -> anchors.lastOrNull { it < h - 1f } ?: 0f // flung down
                velocity < -1800f -> anchors.firstOrNull { it > h + 1f } ?: full // flung up
                else -> anchors.minBy { abs(it - h) }
            }
            scope.launch {
                if (target == 0f) {
                    dismissing[0] = true
                    height.animateTo(0f, tween(180))
                    onDismiss()
                } else {
                    height.animateTo(target, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
                }
            }
        }

        LaunchedEffect(half) {
            if (!dismissing[0]) height.animateTo(half, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        }
        BackHandler { settle(Float.MAX_VALUE) }

        val dragState = rememberDraggableState { delta ->
            scope.launch { height.snapTo((height.value - delta).coerceIn(0f, full)) }
        }
        // The list scrolls on its own at any height; only leftover downward scrolling (already at
        // the top) moves the panel. Everything is consumed so nothing behind (e.g. the Shorts
        // pager) moves.
        val nested = remember(half, full) {
            object : NestedScrollConnection {
                var moved = false

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && (available.y > 0f || moved && available.y < 0f)) {
                        moved = true
                        scope.launch { height.snapTo((height.value - available.y).coerceIn(0f, full)) }
                    }
                    return available
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    if (moved) {
                        moved = false
                        settle(available.y)
                    }
                    return available
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { height.value.toDp() })
                .pointerInput(Unit) { detectTapGestures { } } // don't let taps reach the video
                // Dragging anywhere that isn't a scrolling list (handle, header) resizes the panel.
                .draggable(dragState, Orientation.Vertical, onDragStopped = { v -> settle(v) })
                .nestedScroll(nested),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                    )
                }
                content()
            }
        }
    }
}
