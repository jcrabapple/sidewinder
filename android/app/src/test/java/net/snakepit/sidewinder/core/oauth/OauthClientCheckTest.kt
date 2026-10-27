package net.snakepit.sidewinder.core.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the OAuth client-verification classification (the fix for
 * the 405-on-verify_credentials failure: verification now runs through
 * POST /oauth/token, where GTS answers 400 for an unknown client id).
 */
class OauthClientCheckTest {

    @Test
    fun successStatusIsValid() {
        assertEquals(ClientCheck.Valid, classifyClientCheck(200))
        assertEquals(ClientCheck.Valid, classifyClientCheck(204))
    }

    @Test
    fun unknownClientStatusesAreStale() {
        // Verified live: GTS answers 400 ("no application found with client
        // id ...") on POST /oauth/token for an unknown client_id.
        assertEquals(ClientCheck.Stale, classifyClientCheck(400))
        assertEquals(ClientCheck.Stale, classifyClientCheck(401))
    }

    @Test
    fun verifyCredentialsMethodNotAllowedIsTransient() {
        // Verified live: POST /api/v1/apps/verify_credentials answers 405.
        // A stored client must NOT be deleted because of this status.
        assertEquals(ClientCheck.Transient, classifyClientCheck(405))
    }

    @Test
    fun serverErrorsAreTransient() {
        for (status in listOf(403, 404, 429, 500, 502, 503)) {
            assertEquals("HTTP $status must be transient", ClientCheck.Transient, classifyClientCheck(status))
        }
    }

    @Test
    fun pendingLoginExpiryWindow() {
        // 30 minutes: fresh entries survive, older ones expire; a missing
        // timestamp (0) is treated as stale.
        assertFalse(pendingLoginExpired(savedAtMs = 1_000L, nowMs = 1_000L))
        assertFalse(pendingLoginExpired(savedAtMs = 1_000L, nowMs = 1_000L + 29 * 60_000L))
        assertTrue(pendingLoginExpired(savedAtMs = 1_000L, nowMs = 1_000L + 31 * 60_000L))
        assertTrue(pendingLoginExpired(savedAtMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun grantedTokenScopeIsPreferred() {
        assertEquals("read write", effectiveTokenScopes("read write", ADMIN_SCOPES))
    }

    @Test
    fun blankGrantedTokenScopeFallsBackToRequestedScope() {
        assertEquals(ADMIN_SCOPES, effectiveTokenScopes("", ADMIN_SCOPES))
        assertEquals(SCOPES, effectiveTokenScopes("   ", SCOPES))
    }
}
