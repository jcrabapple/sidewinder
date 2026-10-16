package net.snakepit.sidewinder.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.snakepit.sidewinder.core.model.Account
import net.snakepit.sidewinder.core.model.Session
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.ApiError
import net.snakepit.sidewinder.core.profile.ProfileRepository
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

private sealed interface EditState {
    data object Loading : EditState
    data class Error(val message: String) : EditState
    data class Content(val account: Account) : EditState
}

/**
 * Edit own profile: display name, bio (markdown/plain text as the server
 * accepts), avatar + header images with alt descriptions, locked-account
 * switch, and the discoverable/indexable toggles. Saves via PATCH
 * /api/v1/accounts/update_credentials (formData; images ride as multipart
 * file parts).
 */
@Composable
fun EditProfileScreen(api: Api, session: Session, onSaved: () -> Unit) {
    var state by remember { mutableStateOf<EditState>(EditState.Loading) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        state = EditState.Loading
        state = try {
            EditState.Content(ProfileRepository.account(api, session.account.id))
        } catch (e: ApiError) {
            EditState.Error("Could not load your profile: the server returned error ${e.status}. Check your connection, then retry.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            EditState.Error("Could not load your profile: ${e.message ?: "network error"}. Check your connection, then retry.")
        }
    }

    LaunchedEffect(api, session.id) { load() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val s = state) {
            EditState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading your profile" },
                )
            }

            is EditState.Error -> Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(
                        onClick = { scope.launch { load() } },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = "Retry loading your profile" },
                    ) {
                        Text("Retry")
                    }
                }
            }

            is EditState.Content -> EditForm(
                account = s.account,
                api = api,
                session = session,
                onSaved = onSaved,
                onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun EditForm(
    account: Account,
    api: Api,
    session: Session,
    onSaved: () -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayName by rememberSaveable { mutableStateOf(account.display_name.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(account.note) }
    var locked by rememberSaveable { mutableStateOf(account.locked) }
    var discoverable by rememberSaveable { mutableStateOf(account.discoverable ?: true) }
    var indexable by rememberSaveable { mutableStateOf(account.indexable ?: true) }
    var avatarUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var avatarDescription by rememberSaveable { mutableStateOf("") }
    var headerUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var headerDescription by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) avatarUri = uri
    }
    val headerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) headerUri = uri
    }

    val dirty = displayName != account.display_name.orEmpty() ||
        note != account.note ||
        locked != account.locked ||
        avatarUri != null ||
        headerUri != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Edit profile", style = MaterialTheme.typography.headlineSmall)

        // Header preview + picker.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(MaterialTheme.shapes.medium),
        ) {
            AsyncImage(
                model = headerUri ?: account.header,
                contentDescription = if (headerUri != null) "New header image selected" else null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    headerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Choose a new header image" },
            ) { Text(if (headerUri != null) "Change header" else "Choose header") }
        }
        if (headerUri != null) {
            OutlinedTextField(
                value = headerDescription,
                onValueChange = { headerDescription = it },
                label = { Text("Header description (alt text)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Describe the header image" },
            )
        }

        // Avatar preview + picker.
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = avatarUri ?: account.avatar_static?.ifEmpty { account.avatar } ?: account.avatar,
                contentDescription = if (avatarUri != null) "New avatar image selected" else "Your avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = {
                    avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "Choose a new avatar image" },
            ) { Text(if (avatarUri != null) "Change avatar" else "Choose avatar") }
        }
        if (avatarUri != null) {
            OutlinedTextField(
                value = avatarDescription,
                onValueChange = { avatarDescription = it },
                label = { Text("Avatar description (alt text)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Describe the avatar image" },
            )
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display name") },
            singleLine = true,
            supportingText = { Text("Up to 30 characters") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Your display name" },
        )

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Bio") },
            minLines = 4,
            supportingText = { Text("Plain text; the server renders it") },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Your bio" },
        )

        ToggleRowEdit(
            title = "Locked account",
            body = "New followers must be approved by you.",
            checked = locked,
            onChecked = { locked = it },
        )
        ToggleRowEdit(
            title = "Listed on the profile directory",
            body = "Let others find you in the server's account directory.",
            checked = discoverable,
            onChecked = { discoverable = it },
        )
        ToggleRowEdit(
            title = "Profile and posts indexed by search engines",
            body = "Off keeps your pages out of external search results.",
            checked = indexable,
            onChecked = { indexable = it },
        )

        saveError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Button(
            onClick = {
                saving = true
                saveError = null
                scope.launch {
                    try {
                        // Text fields first: a failed image upload must not
                        // leave the bio/name half-applied the other way round.
                        ProfileRepository.updateCredentials(
                            api = api,
                            displayName = displayName,
                            note = note,
                            locked = locked,
                            discoverable = discoverable,
                            indexable = indexable,
                        )
                        // Image parts ride as multipart PATCH (avatar/header
                        // are type:file on update_credentials).
                        val headerBytes = withContext(Dispatchers.IO) {
                            headerUri?.let { uri ->
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }
                        }
                        val avatarBytes = withContext(Dispatchers.IO) {
                            avatarUri?.let { uri ->
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }
                        }
                        if (headerBytes != null) {
                            api.upload(
                                path = "/api/v1/accounts/update_credentials",
                                serializer = Account.serializer(),
                                fileField = "header",
                                filename = "header.jpg",
                                mediaType = "image/jpeg",
                                bytes = headerBytes,
                                fields = if (headerDescription.isNotBlank()) {
                                    mapOf("header_description" to headerDescription)
                                } else {
                                    emptyMap()
                                },
                                method = "PATCH",
                            )
                        }
                        if (avatarBytes != null) {
                            api.upload(
                                path = "/api/v1/accounts/update_credentials",
                                serializer = Account.serializer(),
                                fileField = "avatar",
                                filename = "avatar.jpg",
                                mediaType = "image/jpeg",
                                bytes = avatarBytes,
                                fields = if (avatarDescription.isNotBlank()) {
                                    mapOf("avatar_description" to avatarDescription)
                                } else {
                                    emptyMap()
                                },
                                method = "PATCH",
                            )
                        }
                        onNotice("Profile saved.")
                        onSaved()
                    } catch (e: ApiError) {
                        saveError = "Could not save: the server returned error ${e.status}. ${if (e.status == 422) "Check field limits and try again." else "Try again."}"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        saveError = "Could not save: ${e.message ?: "network error"}. Check your connection and try again."
                    } finally {
                        saving = false
                    }
                }
            },
            enabled = !saving && dirty,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Save profile changes" },
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (saving) "Saving…" else "Save")
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Settings-style labeled switch row reused inside the editor. */
@Composable
private fun ToggleRowEdit(title: String, body: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { role = Role.Switch },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
