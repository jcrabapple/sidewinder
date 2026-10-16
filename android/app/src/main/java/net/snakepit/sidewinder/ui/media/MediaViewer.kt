package net.snakepit.sidewinder.ui.media

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import net.snakepit.sidewinder.core.model.MediaAttachment
import net.snakepit.sidewinder.ui.app.openInBrowser
import net.snakepit.sidewinder.ui.app.shareText

/*
 * Full-screen media viewer (lightbox). Design rules, shared with the web
 * client's MediaGrid lightbox:
 * - Full-bleed: the media fills the screen and EVERY control floats over
 *   it. Never add a bar above the media; on a phone it pushes the image
 *   off-screen.
 * - Alt text is opt-in: a pill toggles an overlay panel (capped height,
 *   scrollable) that resets to hidden whenever the page changes. With no
 *   description, a dashed non-button "No alt text" label shows instead.
 * The viewer is black in both themes on purpose (photo viewers are), so
 * it uses literal black/white rather than theme tokens.
 */

private val Scrim = Color.Black.copy(alpha = 0.55f)
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * Pager over [media] starting at [startIndex]. Images zoom (pinch,
 * double-tap) and pan; swiping pages is disabled while zoomed. Video,
 * gifv, and audio show their preview with a play button that hands the
 * file to the system player (no in-app player dependency).
 */
@Composable
fun MediaViewer(
    media: List<MediaAttachment>,
    startIndex: Int,
    onDismiss: () -> Unit,
) {
    if (media.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val context = LocalContext.current
        val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, media.lastIndex)) { media.size }
        var zoomed by remember { mutableStateOf(false) }
        var chromeVisible by remember { mutableStateOf(true) }
        var altOpen by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        // Sequence counter, not the string: two saves can produce the same
        // notice text, and keying on the string would not restart the timer.
        var noticeSeq by remember { mutableStateOf(0) }
        fun showNotice(message: String) {
            notice = message
            noticeSeq += 1
        }

        // Alt panel and zoom never carry over to another page.
        LaunchedEffect(pagerState.currentPage) {
            altOpen = false
            zoomed = false
        }
        LaunchedEffect(noticeSeq) {
            if (noticeSeq > 0) {
                kotlinx.coroutines.delay(2500)
                notice = null
            }
        }

        val current = media[pagerState.currentPage.coerceIn(0, media.lastIndex)]
        val currentUrl = current.url ?: current.preview_url

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .semantics { contentDescription = "Media viewer" },
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !zoomed,
                key = { media[it].id },
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                MediaPage(
                    attachment = media[page],
                    isCurrent = page == pagerState.currentPage,
                    onZoomChange = { isZoomed -> if (page == pagerState.currentPage) zoomed = isZoomed },
                    onTap = { chromeVisible = !chromeVisible },
                    onPlay = { attachment -> playExternally(context, attachment) },
                )
            }

            // ---- Top overlay: close, counter, actions ----
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayIconButton(onClick = onDismiss, description = "Close media viewer") {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (media.size > 1) {
                            Text(
                                text = "${pagerState.currentPage + 1} / ${media.size}",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Scrim)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (currentUrl != null) {
                        OverlayIconButton(
                            onClick = { shareText(context, currentUrl, "Share media link") },
                            description = "Share media link",
                        ) { Icon(Icons.Filled.Share, contentDescription = null, tint = Color.White) }
                        OverlayIconButton(
                            onClick = { showNotice(saveMedia(context, current)) },
                            description = "Save to device",
                        ) { Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White) }
                        OverlayIconButton(
                            onClick = { openInBrowser(context, current.remote_url ?: currentUrl) },
                            description = "Open original in browser",
                        ) { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Color.White) }
                    }
                }
            }

            // ---- Bottom overlay: alt text pill + panel, transient notice ----
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    notice?.let { message ->
                        Text(
                            text = message,
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Scrim)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    val description = current.description?.takeIf { it.isNotBlank() }
                    if (description != null) {
                        if (altOpen) {
                            Text(
                                text = description,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Scrim)
                                    .verticalScroll(rememberScrollState())
                                    .padding(12.dp),
                            )
                        }
                        TextButton(
                            onClick = { altOpen = !altOpen },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Scrim)
                                .semantics {
                                    contentDescription = if (altOpen) "Hide image description" else "Show image description"
                                },
                        ) {
                            Text(if (altOpen) "Hide alt text" else "ALT", color = Color.White)
                        }
                    } else {
                        // Not a button: nothing to reveal.
                        Text(
                            text = "No alt text",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .drawBehind {
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.6f),
                                        cornerRadius = CornerRadius(16.dp.toPx()),
                                        style = Stroke(
                                            width = 1.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                                        ),
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPage(
    attachment: MediaAttachment,
    isCurrent: Boolean,
    onZoomChange: (Boolean) -> Unit,
    onTap: () -> Unit,
    onPlay: (MediaAttachment) -> Unit,
) {
    val playable = attachment.type == "video" || attachment.type == "gifv" || attachment.type == "audio"
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }

    fun clamp(raw: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1f) / 2f
        val maxY = size.height * (s - 1f) / 2f
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        scale = next
        offset = if (next <= 1f) Offset.Zero else clamp(offset + panChange, next)
    }

    LaunchedEffect(isCurrent) {
        if (!isCurrent) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale) { onZoomChange(scale > 1f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .then(
                if (playable) {
                    Modifier
                } else {
                    // Single-finger drags pass through to the pager unless
                    // zoomed in, so swiping between items keeps working.
                    Modifier.transformable(state = transformState, canPan = { scale > 1f })
                },
            )
            .pointerInput(playable) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (playable) return@detectTapGestures
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val model = if (playable) attachment.preview_url ?: attachment.url else attachment.url ?: attachment.preview_url
        AsyncImage(
            model = model,
            contentDescription = attachment.description?.takeIf { it.isNotBlank() } ?: "Media without description",
            contentScale = ContentScale.Fit,
            onState = { state ->
                loading = state is AsyncImagePainter.State.Loading
                failed = state is AsyncImagePainter.State.Error
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        if (loading && !playable) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.semantics { contentDescription = "Loading media" },
            )
        }
        if (failed && !playable) {
            Text(
                "Could not load this image. Use Open original to view it in the browser.",
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )
        }
        if (playable) {
            FilledIconButton(
                onClick = { onPlay(attachment) },
                modifier = Modifier
                    .size(72.dp)
                    .semantics {
                        contentDescription = if (attachment.type == "audio") "Play audio" else "Play video"
                    },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Scrim),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }
    }
}

@Composable
private fun OverlayIconButton(onClick: () -> Unit, description: String, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(2.dp)
            .clip(CircleShape)
            .background(Scrim)
            .semantics { contentDescription = description },
    ) { content() }
}

/** Hand video/gifv/audio to whatever player the system has. */
private fun playExternally(context: Context, attachment: MediaAttachment) {
    val url = attachment.url ?: attachment.remote_url ?: return
    val mime = if (attachment.type == "audio") "audio/*" else "video/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        openInBrowser(context, url)
    }
}

/**
 * Queue a download into Pictures/Sidewinder (Movies/Music for other
 * types) via DownloadManager. Returns a user-facing status line.
 * Below Android 10 public-storage writes need a runtime permission the
 * app does not request, so those devices are pointed at the browser.
 */
private fun saveMedia(context: Context, attachment: MediaAttachment): String {
    val url = attachment.url ?: return "This item has no downloadable file."
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return "Saving needs Android 10 or newer. Use Open original and save from the browser."
    }
    val dir = when (attachment.type) {
        "video", "gifv" -> Environment.DIRECTORY_MOVIES
        "audio" -> Environment.DIRECTORY_MUSIC
        else -> Environment.DIRECTORY_PICTURES
    }
    val name = Uri.parse(url).lastPathSegment?.takeIf { it.isNotBlank() } ?: "${attachment.id}.bin"
    return try {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .addRequestHeader("User-Agent", "Sidewinder-Android/0.1")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(dir, "Sidewinder/$name")
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: return "Downloads are unavailable on this device."
        manager.enqueue(request)
        "Saving to your device. The download notification shows when it finishes."
    } catch (e: Exception) {
        "Could not start the download: ${e.message ?: "unknown error"}. Try Open original instead."
    }
}
