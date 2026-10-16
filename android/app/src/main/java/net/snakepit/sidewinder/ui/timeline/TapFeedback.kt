package net.snakepit.sidewinder.ui.timeline

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import net.snakepit.sidewinder.ui.theme.LocalReduceMotion

/**
 * Subtle press feedback for the post action buttons (boost, favourite,
 * bookmark, reply): the icon squishes slightly while pressed and settles
 * back on release. Honors Reduce motion (no scale change at all).
 *
 * Use INSIDE an IconButton on the icon, passing the SAME
 * [InteractionSource] the IconButton (or clickable) uses, so the press
 * events actually reach this modifier:
 * `val src = remember { MutableInteractionSource() }` then
 * `IconButton(interactionSource = src) { Icon(..., Modifier.bouncing(src)) }`.
 */
fun Modifier.bouncing(interactionSource: InteractionSource): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isPressed, reduceMotion) {
        if (reduceMotion) {
            scale.snapTo(1f)
        } else {
            scale.animateTo(
                if (isPressed) 0.86f else 1f,
                tween(durationMillis = if (isPressed) 90 else 180, easing = FastOutSlowInEasing),
            )
        }
    }
    this.scale(scale.value)
}

/**
 * One-shot pop for the moment a toggle turns ON (boost lands, star lands):
 * a quick 1.0 -> 1.2 -> 1.0 pulse driven by the state change itself.
 * Reduce motion keeps it static.
 */
@Composable
fun Modifier.pulseOn(active: Boolean): Modifier {
    val reduceMotion = LocalReduceMotion.current
    val scale = remember { Animatable(1f) }
    // Fire only on a false -> true transition: rows that are already
    // active at first composition must not replay the pop (K10/G2-8).
    var previous by remember { mutableStateOf(active) }
    LaunchedEffect(active, reduceMotion) {
        val newlyActive = active && !previous
        previous = active
        if (!newlyActive || reduceMotion) {
            scale.snapTo(1f)
            return@LaunchedEffect
        }
        scale.snapTo(1f)
        scale.animateTo(1.2f, tween(110, easing = FastOutSlowInEasing))
        scale.animateTo(1f, tween(170, easing = FastOutSlowInEasing))
    }
    return this.scale(scale.value)
}

/**
 * Press + long-press + scale in one modifier for elements that live
 * outside an IconButton (the long-press boost row). Ripple comes from
 * [indication] when provided.
 */
fun Modifier.bounceClick(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = composed {
    val reduceMotion = LocalReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(isPressed, reduceMotion) {
        if (reduceMotion) {
            scale.snapTo(1f)
        } else {
            scale.animateTo(
                if (isPressed) 0.94f else 1f,
                tween(durationMillis = if (isPressed) 90 else 180, easing = FastOutSlowInEasing),
            )
        }
    }
    this
        .combinedClickable(
            interactionSource = interactionSource,
            indication = androidx.compose.material3.ripple(),
            onClick = onClick,
            onLongClick = onLongClick,
        )
        .scale(scale.value)
}