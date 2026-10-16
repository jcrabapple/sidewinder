package net.snakepit.sidewinder.ui.account

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import net.snakepit.sidewinder.core.model.Session

/**
 * Account avatar button + session dropdown for the top app bar.
 * [active] is highlighted; [onSelect] switches the active session and
 * [onAddAccount] opens the sign-in flow for a new one.
 */
@Composable
fun AccountSwitcher(
    sessions: List<Session>,
    active: Session?,
    onSelect: (Session) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenMyProfile: (() -> Unit)? = null,
    onOpenBookmarks: (() -> Unit)? = null,
    onOpenFavourites: (() -> Unit)? = null,
    onOpenFollowRequests: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    TextButton(
        onClick = { expanded = true },
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = active?.account?.avatar_static?.ifEmpty { active.account.avatar }
                    ?: active?.account?.avatar,
                contentDescription = "Switch account; signed in as ${active?.account?.acct ?: "nobody"}",
                modifier = Modifier.size(28.dp),
            )
            Text(
                "@${active?.account?.acct ?: ""}",
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 1,
            )
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        // Personal destinations first (drawer-style shortcuts).
        val shortcuts = listOfNotNull(
            onOpenMyProfile?.let { Triple("My profile", Icons.Filled.Person, it) },
            onOpenBookmarks?.let { Triple("Bookmarks", Icons.Filled.Bookmark, it) },
            onOpenFavourites?.let { Triple("Favourites", Icons.Filled.Star, it) },
            onOpenFollowRequests?.let { Triple("Follow requests", Icons.Filled.PersonAdd, it) },
        )
        shortcuts.forEach { (label, icon, action) ->
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = {
                    expanded = false
                    action()
                },
            )
        }
        if (shortcuts.isNotEmpty()) HorizontalDivider()
        sessions.forEach { candidate ->
            DropdownMenuItem(
                text = { Text("@${candidate.account.acct}") },
                leadingIcon = {
                    AsyncImage(
                        model = candidate.account.avatar_static?.ifEmpty { candidate.account.avatar }
                            ?: candidate.account.avatar,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                },
                trailingIcon = {
                    if (candidate.id == active?.id) {
                        Icon(Icons.Filled.Check, contentDescription = "Active account")
                    }
                },
                onClick = {
                    expanded = false
                    if (candidate.id != active?.id) onSelect(candidate)
                },
            )
        }
        DropdownMenuItem(
            text = { Text("Add account") },
            onClick = {
                expanded = false
                onAddAccount()
            },
        )
    }
}
