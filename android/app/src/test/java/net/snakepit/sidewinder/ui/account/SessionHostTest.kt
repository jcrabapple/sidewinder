package net.snakepit.sidewinder.ui.account

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the host shown next to duplicate accts in the switcher. */
class SessionHostTest {

    @Test
    fun extractsHostFromBase() {
        assertEquals("gts.example.org", sessionHost("https://gts.example.org"))
        assertEquals("gts.example.org", sessionHost("https://gts.example.org/"))
    }

    @Test
    fun fallsBackToBaseWhenUnparseable() {
        assertEquals("not-a-url", sessionHost("not-a-url"))
    }
}
