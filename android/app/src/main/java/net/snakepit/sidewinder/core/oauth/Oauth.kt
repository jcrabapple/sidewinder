package net.snakepit.sidewinder.core.oauth

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.snakepit.sidewinder.core.model.AccountCredentials
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.model.TokenResponse
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.network.NetworkError
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.UUID

/** OAuth scopes for ordinary sessions. */
const val SCOPES = "read write follow push"

/** Admin scopes; only used when the login-screen admin toggle is on. */
const val ADMIN_SCOPES = "read write follow push admin"

/** The redirect URI registered with the instance and declared in the manifest. */
const val REDIRECT_URI = "sidewinder://oauth"

/** How long a persisted pending sign-in stays usable after a process death. */
internal const val PENDING_LOGIN_TTL_MS = 30 * 60_000L

/** Outcome of verifying a stored OAuth client against the server. */
internal enum class ClientCheck {
    /** The server accepted the client-credentials grant; keep the client. */
    Valid,
    /** The server does not know this client (400/401); delete and re-register. */
    Stale,
    /** Anything else (server error, outage); keep credentials, fail the sign-in. */
    Transient,
}

/**
 * Classify an HTTP status from the client-credentials check. Pure so the
 * stale-vs-outage decision is unit-testable: GTS answers 405 on
 * POST /api/v1/apps/verify_credentials and 400 for an unknown client id on
 * POST /oauth/token, so 400/401 mean a stale registration and every other
 * failure must NOT destroy good credentials.
 */
internal fun classifyClientCheck(status: Int): ClientCheck = when {
    status in 200..299 -> ClientCheck.Valid
    status == 400 || status == 401 -> ClientCheck.Stale
    else -> ClientCheck.Transient
}

/** App-registered OAuth client for one base+scope pair. Port of web ClientCredentials. */
@Serializable
data class ClientCredentials(
    val client_id: String,
    val client_secret: String,
)

/** Raised when the OAuth flow cannot continue and the user must start over. */
class OauthException(message: String) : Exception(message)

private val clientsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

private val clientsCodec = MapSerializer(String.serializer(), ClientCredentials.serializer())

/** Process-wide protection for client-store read-modify-write cycles. */
private val clientsMutex = Mutex()

/**
 * Per-base OAuth client credentials, persisted at filesDir/sidewinder/clients.json.
 * Port of web src/api/oauth.ts localStorage store. Keys are "base::scopes" so
 * admin tools use a separate registration and ordinary sessions never hold an
 * admin-scoped token.
 */
private fun clientKey(base: String, scopes: String): String = "$base::$scopes"

private fun clientsFile(context: Context): File =
    File(context.filesDir, "sidewinder/clients.json")

internal suspend fun loadClients(context: Context): Map<String, ClientCredentials> = withContext(Dispatchers.IO) {
    val f = clientsFile(context)
    if (!f.exists()) return@withContext emptyMap()
    try {
        clientsJson.decodeFromString(clientsCodec, f.readText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A corrupt or unreadable client store must not be silently
        // replaced: re-registering would overwrite every other stored
        // client. Surface the failure instead.
        throw OauthException(
            "Stored sign-in data is unreadable (${e.message ?: "corrupt file"}). " +
                "Clear the app's storage and sign in again.",
        )
    }
}

internal suspend fun saveClients(context: Context, clients: Map<String, ClientCredentials>) = withContext(Dispatchers.IO) {
    val dir = File(context.filesDir, "sidewinder")
    dir.mkdirs()
    val tmp = File(dir, "clients.json.tmp")
    tmp.writeText(clientsJson.encodeToString(clientsCodec, clients))
    if (!tmp.renameTo(clientsFile(context))) {
        clientsFile(context).delete()
        if (!tmp.renameTo(clientsFile(context))) {
            tmp.delete()
            throw OauthException("Could not save the sign-in client. Free up storage and try again.")
        }
    }
}

internal suspend fun deleteClient(context: Context, base: String, scopes: String): Unit = clientsMutex.withLock {
    val clients = loadClients(context).toMutableMap()
    clients.remove(clientKey(base, scopes))
    if (clients.isEmpty()) clientsFile(context).delete() else saveClients(context, clients)
}

/**
 * Return the stored client for base+scopes, verifying it still exists on the
 * server; otherwise register a new app via POST /api/v1/apps. A stored client
 * whose server-side row vanished is deleted and re-registered.
 *
 * The stored client is verified with the client-credentials grant
 * (POST /oauth/token, grant_type=client_credentials) — the
 * verify_credentials endpoint answers 405 on GTS, which cannot distinguish
 * a stale client from an outage. The app token issued by the grant is
 * discarded immediately.
 */
suspend fun getOrCreateClient(
    context: Context,
    base: String,
    scopes: String = SCOPES,
): ClientCredentials {
    val key = clientKey(base, scopes)
    val existing = loadClients(context)[key]
    if (existing != null) {
        try {
            Api(base).postForm(
                "/oauth/token",
                mapOf(
                    "grant_type" to "client_credentials",
                    "client_id" to existing.client_id,
                    "client_secret" to existing.client_secret,
                    "redirect_uri" to REDIRECT_URI,
                    "scope" to scopes,
                ),
                TokenResponse.serializer(),
            )
            return existing
        } catch (e: ApiError) {
            // Only a stale/unknown client row is worth discarding. A
            // server error must not destroy good credentials (an offline
            // blip would otherwise delete the registration every time).
            when (classifyClientCheck(e.status)) {
                ClientCheck.Valid -> return existing
                ClientCheck.Stale -> deleteClient(context, base, scopes)
                ClientCheck.Transient ->
                    throw OauthException("Could not verify the stored sign-in client: ${e.message} Try again later.")
            }
        } catch (e: NetworkError) {
            throw OauthException(
                "Could not reach the server to verify the stored sign-in client. " +
                    "Check your connection and try again.",
            )
        }
    }
    val created = try {
        Api(base).request(
            "/api/v1/apps",
            ClientCredentials.serializer(),
            method = "POST",
            body = buildJsonObject {
                put("client_name", "Sidewinder Android")
                put("redirect_uris", REDIRECT_URI)
                put("scopes", scopes)
                put("website", base)
            },
        )
    } catch (e: ApiError) {
        throw OauthException("The server refused app registration: ${e.message} Check the instance address and try again.")
    }
    val creds = ClientCredentials(created.client_id, created.client_secret)
    clientsMutex.withLock {
        saveClients(context, loadClients(context) + (key to creds))
    }
    return creds
}

/** Everything needed between "open browser" and the redirect. */
@Serializable
data class AuthorizeRequest(
    val base: String,
    val scopes: String,
    val credentials: ClientCredentials,
    val url: String,
    val state: String,
)

private val pendingLoginJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private fun pendingLoginFile(context: Context): File =
    File(context.filesDir, "sidewinder/pending-login.json")

/**
 * Persist the in-flight sign-in so a process death while the user is in the
 * Custom Tab does not kill the flow: the redirect handler falls back to this
 * file when the in-memory copy is gone. Atomic temp+rename.
 */
internal suspend fun savePendingLogin(context: Context, request: AuthorizeRequest) =
    withContext(Dispatchers.IO) {
        val f = pendingLoginFile(context)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(
            pendingLoginJson.encodeToString(
                StoredAuthorizeRequest.serializer(),
                StoredAuthorizeRequest(
                    base = request.base,
                    scopes = request.scopes,
                    credentials = request.credentials,
                    url = request.url,
                    state = request.state,
                    savedAtMs = System.currentTimeMillis(),
                ),
            ),
        )
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                throw OauthException("Could not save the sign-in state. Free up storage and try again.")
            }
        }
    }

/**
 * The persisted pending sign-in, or null when none exists or it is older
 * than [PENDING_LOGIN_TTL_MS] (stale entries are removed).
 */
internal suspend fun loadPendingLogin(context: Context): AuthorizeRequest? = withContext(Dispatchers.IO) {
    val f = pendingLoginFile(context)
    if (!f.exists()) return@withContext null
    try {
        val stored = pendingLoginJson.decodeFromString(StoredAuthorizeRequest.serializer(), f.readText())
        if (pendingLoginExpired(stored.savedAtMs, System.currentTimeMillis())) {
            f.delete()
            null
        } else {
            stored.toRequest()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Unreadable state is the same as no state: start the sign-in over.
        f.delete()
        null
    }
}

/** Pure expiry rule for a persisted pending sign-in (30 minutes). */
internal fun pendingLoginExpired(savedAtMs: Long, nowMs: Long): Boolean =
    savedAtMs <= 0 || nowMs - savedAtMs > PENDING_LOGIN_TTL_MS

internal suspend fun clearPendingLogin(context: Context) = withContext(Dispatchers.IO) {
    pendingLoginFile(context).delete()
}

@Serializable
private data class StoredAuthorizeRequest(
    val base: String,
    val scopes: String,
    val credentials: ClientCredentials,
    val url: String,
    val state: String,
    val savedAtMs: Long = 0,
) {
    fun toRequest() = AuthorizeRequest(base, scopes, credentials, url, state)
}

/** Build the /oauth/authorize URL the user opens in a browser (Custom Tab). */
fun authorizeUrl(base: String, credentials: ClientCredentials, scopes: String = SCOPES): AuthorizeRequest {
    val state = UUID.randomUUID().toString()
    val url = (base.trimEnd('/') + "/oauth/authorize").toHttpUrl().newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", credentials.client_id)
        .addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("scope", scopes)
        .addQueryParameter("state", state)
        .build()
        .toString()
    return AuthorizeRequest(base, scopes, credentials, url, state)
}

/** Use the server's granted scope unless it omits or blanks that field. */
internal fun effectiveTokenScopes(granted: String, requested: String): String =
    granted.ifBlank { requested }

/** Exchange the authorization code for a token via POST /oauth/token. */
suspend fun exchangeToken(
    base: String,
    code: String,
    credentials: ClientCredentials,
    scopes: String,
): TokenResponse {
    return Api(base).postForm(
        "/oauth/token",
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to credentials.client_id,
            "client_secret" to credentials.client_secret,
            "scope" to scopes,
        ),
        TokenResponse.serializer(),
    )
}

/** Fetch the account snapshot for a fresh token via verify_credentials. */
suspend fun fetchAccount(base: String, token: String): AccountCredentials {
    val api = Api(base, token)
    return api.request("/api/v1/accounts/verify_credentials", AccountCredentials.serializer())
}

/** Build a Session (id = random UUID) from a freshly exchanged token. */
suspend fun buildSession(base: String, token: TokenResponse, requestedScopes: String): Session =
    Session(
        id = UUID.randomUUID().toString(),
        base = base,
        token = token.access_token,
        account = fetchAccount(base, token.access_token),
        scopes = effectiveTokenScopes(token.scope, requestedScopes),
    )

/** Highest-value error message to show the user during login. Port of loginErrorMessage. */
fun loginErrorMessage(e: Throwable): String = when (e) {
    is OauthException -> e.message ?: "Sign-in failed. Start again."
    is ApiError -> "${e.message} Check the instance address and try again."
    is net.snakepit.sidewinder.core.network.NetworkError ->
        "Could not reach the server. Check the address and your connection."
    else -> "${e.message ?: "Sign-in failed."} Check the instance address and try again."
}
