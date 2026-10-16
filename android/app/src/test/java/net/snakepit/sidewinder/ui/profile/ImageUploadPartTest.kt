package net.snakepit.sidewinder.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for K44: avatar/header uploads must carry the real
 * MIME type reported by the content resolver, with the file extension
 * matching it, instead of always claiming image/jpeg.
 */
class ImageUploadPartTest {

    @Test
    fun `png picks the png media type and extension`() {
        assertEquals("image/png" to "avatar.png", imageUploadPart("image/png", "avatar"))
    }

    @Test
    fun `webp picks the webp media type and extension`() {
        assertEquals("image/webp" to "header.webp", imageUploadPart("image/webp", "header"))
    }

    @Test
    fun `gif picks the gif media type and extension`() {
        assertEquals("image/gif" to "avatar.gif", imageUploadPart("image/gif", "avatar"))
    }

    @Test
    fun `jpeg picks the jpeg media type and extension`() {
        assertEquals("image/jpeg" to "avatar.jpg", imageUploadPart("image/jpeg", "avatar"))
    }

    @Test
    fun `null content type falls back to jpeg`() {
        assertEquals("image/jpeg" to "header.jpg", imageUploadPart(null, "header"))
    }
}
