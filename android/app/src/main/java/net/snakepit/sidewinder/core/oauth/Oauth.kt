package net.snakepit.sidewinder.core.oauth

import android.content.Context
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.UUID

/** OAuth scopes for ordinary sessions. */
const val SCOPES = "read write follow push"

/** Admin scopes; only used when the login-screen admin toggle is on. */
const val ADMIN_SCOPES = "read write follow push admin"

/** The redirect URI registered with the instance and declared in the manifest. */
const val REDIRECT_URI = "sidewinder://oauth"

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

/**
 * Per-base OAuth client credentials, persisted at filesDir/sidewinder/clients.json.
 * Port of web src/api/oauth.ts localStorage store. Keys are "base::scopes" so
 * admin tools use a separate registration and ordinary sessions never hold an
 * admin-scoped token.
 */
private fun clientKey(base: String, scopes: String): String = "$base::$scopes"

private fun clientsFile(context: Context): File =
    File(context.filesDir, "sidewinder/clients.json")

internal fun loadClients(context: Context): Map<String, ClientCredentials> {
    val f = clientsFile(context)
    if (!f.exists()) return emptyMap()
    return try {
        clientsJson.decodeFromString(clientsCodec, f.readText())
    } catch (_: Exception) {
        emptyMap()
    }
}

internal fun saveClients(context: Context, clients: Map<String, ClientCredentials>) {
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

internal fun deleteClient(context: Context, base: String, scopes: String) {
    val clients = loadClients(context).toMutableMap()
    clients.remove(clientKey(base, scopes))
    if (clients.isEmpty()) clientsFile(context).delete() else saveClients(context, clients)
}

/**
 * Return the stored client for base+scopes, verifying it still exists on the
 * server; otherwise register a new app via POST /api/v1/apps. A stored client
 * whose server-side row vanished is deleted and re-registered.
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
                "/api/v1/apps/verify_credentials",
                mapOf("client_id" to existing.client_id, "client_secret" to existing.client_secret),
                ClientCredentials.serializer(),
            )
            return existing
        } catch (e: ApiError) {
            // Only a stale/unknown client row is worth discarding. A
            // server error must not destroy good credentials (an offline
            // blip would otherwise delete the registration every time).
            if (e.status == 401 || e.status == 404) {
                deleteClient(context, base, scopes)
            } else {
                throw OauthException("Could not verify the stored sign-in client: ${e.message} Try again later.")
            }
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
    saveClients(context, loadClients(context) + (key to creds))
    return creds
}

/** Everything needed between "open browser" and the redirect. */
data class AuthorizeRequest(
    val base: String,
    val scopes: String,
    val credentials: ClientCredentials,
    val url: String,
    val state: String,
)

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

/** Exchange the authorization code for a token via POST /oauth/token. */
suspend fun exchangeToken(
    base: String,
    code: String,
    credentials: ClientCredentials,
    scopes: String,
): String {
    val token = Api(base).postForm(
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
    return token.access_token
}

/** Fetch the account snapshot for a fresh token via verify_credentials. */
suspend fun fetchAccount(base: String, token: String): AccountCredentials {
    val api = Api(base, token)
    return api.request("/api/v1/accounts/verify_credentials", AccountCredentials.serializer())
}

/** Build a Session (id = random UUID) from a freshly exchanged token. */
suspend fun buildSession(base: String, token: String, scopes: String): Session =
    Session(
        id = UUID.randomUUID().toString(),
        base = base,
        token = token,
        account = fetchAccount(base, token),
        scopes = scopes,
    )

/** Highest-value error message to show the user during login. Port of loginErrorMessage. */
fun loginErrorMessage(e: Throwable): String = when (e) {
    is OauthException -> e.message ?: "Sign-in failed. Start again."
    is ApiError -> "${e.message} Check the instance address and try again."
    is net.snakepit.sidewinder.core.network.NetworkError ->
        "Could not reach the server. Check the address and your connection."
    else -> "${e.message ?: "Sign-in failed."} Check the instance address and try again."
}
