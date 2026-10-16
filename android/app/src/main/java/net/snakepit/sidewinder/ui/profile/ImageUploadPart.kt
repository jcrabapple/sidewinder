package net.snakepit.sidewinder.ui.profile

/**
 * MIME type and matching file extension for an avatar/header upload,
 * taken from the content resolver's real type for the picked image
 * (K44) instead of assuming JPEG. Falls back to JPEG when the resolver
 * reports no type (some document providers return null).
 */
internal fun imageUploadPart(contentType: String?, baseName: String): Pair<String, String> =
    when (contentType?.lowercase()) {
        "image/png" -> "image/png" to "$baseName.png"
        "image/webp" -> "image/webp" to "$baseName.webp"
        "image/gif" -> "image/gif" to "$baseName.gif"
        else -> "image/jpeg" to "$baseName.jpg"
    }
