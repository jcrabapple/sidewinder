package net.snakepit.sidewinder.ui.login

import android.content.Context
import androidx.compose.ui.semantics.clearAndSetSemantics
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.snakepit.sidewinder.core.datastore.SessionStore
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.oauth.ADMIN_SCOPES
import net.snakepit.sidewinder.core.oauth.AuthorizeRequest
import net.snakepit.sidewinder.core.oauth.OauthException
import net.snakepit.sidewinder.core.oauth.SCOPES
import net.snakepit.sidewinder.core.oauth.authorizeUrl
import net.snakepit.sidewinder.core.oauth.buildSession
import net.snakepit.sidewinder.core.oauth.clearPendingLogin
import net.snakepit.sidewinder.core.oauth.exchangeToken
import net.snakepit.sidewinder.core.oauth.getOrCreateClient
import net.snakepit.sidewinder.core.oauth.loadPendingLogin
import net.snakepit.sidewinder.core.oauth.loginErrorMessage
import net.snakepit.sidewinder.R
import androidx.compose.ui.res.painterResource
import net.snakepit.sidewinder.core.oauth.savePendingLogin

/** The default server suggested on the sign-in screen. Same default as the web app. */
private const val DEFAULT_BASE = "https://gts.example.org"

/** Normalize user input into a server base: add scheme, strip trailing slash. */
internal fun normalizeInstanceUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
    val trimmedPath = withScheme.trimEnd('/')
    // Reject anything that is not scheme + host (no spaces, has a dot or is localhost).
    val afterScheme = trimmedPath.substringAfter("://")
    val host = afterScheme.substringBefore('/')
    val valid = host.isNotBlank() && !host.contains(' ') &&
        (host.contains('.') || host == "localhost" || host.endsWith(".local"))
    return if (valid) trimmedPath else null
}

/**
 * State carried between "open browser" and the redirect back into the app.
 * Single-activity app, so object state is sufficient. Port of the web app's
 * sessionStorage sidewinder.oauth_state record.
 */
internal object PendingLogin {
    /** Application context captured at composition time for file access. */
    var appContext: Context? = null
    var request: AuthorizeRequest? = null

    fun clear() {
        request = null
        appContext = null
    }
}

/**
 * Entry point MainActivity calls from the sidewinder://oauth intent.
 * Validates the redirect, exchanges the code, and stores the new session
 * as active. Throws [OauthException] (or ApiError) when sign-in fails;
 * the caller should surface [loginErrorMessage].
 */
suspend fun handleOauthRedirect(activityContext: Context, uri: Uri): Session {
    // The redirect can arrive in a fresh process (killed while the user was
    // in the browser): PendingLogin is empty then, so the caller supplies the
    // context and the persisted pending-login file is the fallback.
    val context = activityContext.applicationContext
    val pending = PendingLogin.request
        ?: loadPendingLogin(context)
        ?: throw OauthException("No sign-in attempt in progress. Start again from the sign-in screen.")

    if (uri.scheme != "sidewinder" || uri.host != "oauth") {
        throw OauthException("Unexpected sign-in redirect. Start again from the sign-in screen.")
    }
    uri.getQueryParameter("error")?.let { error ->
        val description = uri.getQueryParameter("error_description")
        clearPendingLogin(context)
        throw OauthException(
            "The server denied sign-in" +
                (description?.let { ": $it" } ?: ": $error") +
                ". Start again from the sign-in screen.",
        )
    }
    val code = uri.getQueryParameter("code")
        ?: throw OauthException("The sign-in redirect had no authorization code. Start again from the sign-in screen.")
    if (uri.getQueryParameter("state") != pending.state) {
        PendingLogin.clear()
        clearPendingLogin(context)
        throw OauthException("Sign-in state mismatch. Start again from the sign-in screen.")
    }

    try {
        val token = exchangeToken(pending.base, code, pending.credentials, pending.scopes)
        val session = buildSession(pending.base, token, pending.scopes)
        SessionStore(context).add(session)
        PendingLogin.clear()
        clearPendingLogin(context)
        return session
    } catch (e: CancellationException) {
        // The caller was cancelled; keep the pending sign-in so a retry
        // (or the redirect still in flight) can complete.
        throw e
    } catch (e: Exception) {
        PendingLogin.clear()
        clearPendingLogin(context)
        throw e
    }
}

private enum class LoginPhase { Idle, Checking, Waiting, Error }

/**
 * Sign-in screen: instance address, optional admin scopes, Custom Tab
 * authorization. [onLoggedIn] runs after [handleOauthRedirect] has stored a
 * session; [onCancel] is present when sign-in is optional (an account exists).
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onCancel: (() -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var instanceInput by rememberSaveable { mutableStateOf(DEFAULT_BASE) }
    var phase by remember { mutableStateOf(LoginPhase.Idle) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var adminScopes by rememberSaveable { mutableStateOf(false) }

    SideEffect { PendingLogin.appContext = context.applicationContext }

    fun startSignIn(appScope: CoroutineScope) {
        val base = normalizeInstanceUrl(instanceInput)
        if (base == null) {
            phase = LoginPhase.Error
            errorMessage =
                "That does not look like a server address. Use a host such as gts.example.org."
            return
        }
        phase = LoginPhase.Checking
        errorMessage = null
        val useAdminScopes = adminScopes
        appScope.launch {
            try {
                val scopes = if (useAdminScopes) ADMIN_SCOPES else SCOPES
                val credentials = getOrCreateClient(context.applicationContext, base, scopes)
                val request = authorizeUrl(base, credentials, scopes)
                PendingLogin.request = request
                savePendingLogin(context.applicationContext, request)
                val intent = CustomTabsIntent.Builder().build()
                intent.launchUrl(context, Uri.parse(request.url))
                phase = LoginPhase.Waiting
            } catch (e: CancellationException) {
                // Cancelled mid sign-in: keep any persisted pending state
                // (the redirect may still arrive) and stop composing.
                throw e
            } catch (e: Exception) {
                PendingLogin.clear()
                clearPendingLogin(context.applicationContext)
                phase = LoginPhase.Error
                errorMessage = loginErrorMessage(e)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.sidewinder_logo),
            contentDescription = "Sidewinder logo",
            modifier = Modifier.size(96.dp),
        )
        Text("Sign in", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Enter your GoToSocial or Mastodon server address. Your browser opens to authorize Sidewinder.",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = instanceInput,
            onValueChange = {
                instanceInput = it
                if (phase == LoginPhase.Error) {
                    phase = LoginPhase.Idle
                    errorMessage = null
                }
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Server address") },
            placeholder = { Text("gts.example.org") },
            isError = phase == LoginPhase.Error,
            supportingText = {
                when (phase) {
                    LoginPhase.Checking -> Text("Checking the server…")
                    LoginPhase.Waiting -> Text(
                        "Waiting for your browser sign-in to finish. " +
                            "If nothing happens, return here and sign in again.",
                    )
                    LoginPhase.Error -> Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
                    else -> Text("Example: $DEFAULT_BASE")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = adminScopes,
                    role = Role.Switch,
                    onValueChange = { adminScopes = it },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = adminScopes,
                onCheckedChange = null,
                // The row is the toggle; keep the Switch out of the
                // semantics tree so the state is announced once.
                modifier = Modifier.clearAndSetSemantics { },
            )
            Text(
                "Request admin scopes",
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (adminScopes) {
            Text(
                "Admin scopes give the token extra access on your server. Only enable this for an admin account.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = { startSignIn(scope) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            enabled = phase != LoginPhase.Checking,
        ) {
            if (phase == LoginPhase.Checking) {
                CircularProgressIndicator(modifier = Modifier.heightIn(min = 24.dp))
            } else if (phase == LoginPhase.Waiting) {
                // Deliberate restart of a stuck attempt (per the field
                // hint above); labelled so it cannot read as a stray
                // double-tap of the original action.
                Text("Sign in again")
            } else {
                Text("Continue in browser")
            }
        }

        onCancel?.let {
            TextButton(
                onClick = it,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("Cancel")
            }
        }
    }
}
