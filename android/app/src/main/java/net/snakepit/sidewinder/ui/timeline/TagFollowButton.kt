package net.snakepit.sidewinder.ui.timeline

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.timeline.TimelineRepository

/**
 * Follow / Unfollow toggle for a hashtag (followed tags feed the home
 * timeline). Hidden until the current state is known; optimistic with
 * revert on failure, reporting through [onNotice].
 */
@Composable
fun TagFollowButton(api: Api, name: String, onNotice: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var following by remember(name) { mutableStateOf<Boolean?>(null) }
    var busy by remember(name) { mutableStateOf(false) }

    LaunchedEffect(api, name) {
        following = try {
            TimelineRepository.tagInfo(api, name).following
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // unknown state: hide the control rather than guess
        }
    }

    val current = following ?: return
    val toggle: () -> Unit = {
        if (!busy) {
            busy = true
            following = !current
            scope.launch {
                try {
                    following = TimelineRepository.setTagFollowed(api, name, follow = !current).following
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    following = current
                    onNotice(
                        "Could not ${if (current) "unfollow" else "follow"} #$name: " +
                            "${e.message ?: "network error"}. Try again.",
                    )
                }
                busy = false
            }
        }
    }
    val modifier = Modifier
        .heightIn(min = 48.dp)
        .semantics { contentDescription = if (current) "Unfollow hashtag $name" else "Follow hashtag $name" }
    if (current) {
        OutlinedButton(onClick = toggle, enabled = !busy, modifier = modifier) {
            // The spinner replaces the label while the toggle is in flight,
            // at the same size in both branches so the button never jumps.
            if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Following")
        }
    } else {
        FilledTonalButton(onClick = toggle, enabled = !busy, modifier = modifier) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp)) else Text("Follow")
        }
    }
}
