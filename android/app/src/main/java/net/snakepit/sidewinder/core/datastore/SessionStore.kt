package net.snakepit.sidewinder.core.datastore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import net.snakepit.sidewinder.core.model.AccountCredentials
import net.snakepit.sidewinder.core.model.Session
import java.io.File

/**
 * Signed-in sessions, persisted at filesDir/sidewinder/sessions.json together
 * with the active session id. Port of web src/state/auth.ts localStorage store.
 * Writes are atomic: temp file + rename.
 */
class SessionStore(context: Context) {

    /** Storage shape; [Session] itself is not @Serializable in Models.kt. */
    @Serializable
    internal data class StoredSession(
        val id: String,
        val base: String,
        val token: String,
        val account: AccountCredentials,
        val scopes: String? = null,
    )

    @Serializable
    private data class SessionFile(
        val sessions: List<StoredSession> = emptyList(),
        val activeId: String? = null,
    )

    private val dir = File(context.applicationContext.filesDir, "sidewinder")
    private val file = File(dir, "sessions.json")
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Guards every read-modify-write cycle so concurrent callers cannot
     * drop a write. Process-wide: three SessionStore instances exist
     * (MainActivity, LoginScreen, NotifyWorker) and must not interleave.
     */
    private companion object {
        val mutex = Mutex()
    }

    private fun StoredSession.toSession() = Session(id, base, token, account, scopes)
    private fun Session.toStored() = StoredSession(id, base, token, account, scopes)

    private suspend fun read(): SessionFile = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext SessionFile()
        try {
            json.decodeFromString(SessionFile.serializer(), file.readText())
        } catch (_: Exception) {
            // Corrupt or unreadable store: start over rather than fail sign-in.
            SessionFile()
        }
    }

    private suspend fun write(data: SessionFile) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "sessions.json.tmp")
        tmp.writeText(json.encodeToString(SessionFile.serializer(), data))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IllegalStateException("Could not save sessions. Free up storage and try again.")
            }
        }
    }

    suspend fun list(): List<Session> = read().sessions.map { it.toSession() }

    /** The active session, or the first one, or null. Mirrors web activeSession. */
    suspend fun active(): Session? {
        val data = read()
        if (data.sessions.isEmpty()) return null
        return (data.sessions.firstOrNull { it.id == data.activeId } ?: data.sessions.first())
            .toSession()
    }

    /** Insert or replace a session and make it active. */
    suspend fun add(session: Session) = mutex.withLock {
        val data = read()
        val sessions = mergeSessions(data.sessions, session.toStored())
        write(SessionFile(sessions, session.id))
    }

    suspend fun setActive(id: String) = mutex.withLock {
        val data = read()
        require(data.sessions.any { it.id == id }) { "Unknown session id: $id" }
        write(data.copy(activeId = id))
    }

    suspend fun remove(id: String) = mutex.withLock {
        val data = read()
        val sessions = data.sessions.filterNot { it.id == id }
        val activeId = when {
            data.activeId != id -> data.activeId
            sessions.isNotEmpty() -> sessions.first().id
            else -> null
        }
        write(SessionFile(sessions, activeId))
    }
}

/**
 * Merge [incoming] into [existing]: replace by id, and replace a stored
 * session with the same base AND account id (a re-login on the same account
 * must replace, not duplicate). Pure so the dedupe rule is unit-testable.
 */
internal fun mergeSessions(
    existing: List<SessionStore.StoredSession>,
    incoming: SessionStore.StoredSession,
): List<SessionStore.StoredSession> =
    existing.filterNot {
        it.id == incoming.id ||
            (it.base == incoming.base && it.account.id == incoming.account.id)
    } + incoming
