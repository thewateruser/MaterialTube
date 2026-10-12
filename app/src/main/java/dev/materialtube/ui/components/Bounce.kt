package dev.materialtube.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's one press animation: a quick squish on press and a springy overshoot on release.
 *
 * It only *observes* touches (initial pass, nothing consumed), so it can be added to any Material
 * component's modifier without changing its behaviour. Drags/scrolls cancel it, so lists don't
 * wobble while you scroll through them.
 */
fun Modifier.pressBounce(pressedScale: Float = 0.92f): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }.pointerInput(pressedScale) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val press = scope.launch {
                delay(40)
                scale.animateTo(pressedScale, spring(stiffness = Spring.StiffnessHigh))
            }
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - down.position).getDistance() > slop) {
                    moved = true
                    break
                }
                if (!change.pressed) break
            }
            press.cancel()
            scope.launch {
                if (!moved && scale.value > pressedScale + 0.005f) {
                    scale.animateTo(pressedScale, tween(60)) // quick taps still get the squish
                }
                scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
}

/** `clickable` with ripple + [pressBounce]. For cards, rows and other custom tappable surfaces. */
fun Modifier.bounceClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier = composed {
    pressBounce(pressedScale).clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(),
        enabled = enabled,
        onClick = onClick,
    )
}
