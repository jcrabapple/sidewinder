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
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    internal data class SessionFile(
        val sessions: List<StoredSession> = emptyList(),
        val activeId: String? = null,
    )

    private val dir = File(context.applicationContext.filesDir, "sidewinder")
    private val file = File(dir, "sessions.json")

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
        decodeSessionFile(if (file.exists()) file.readText() else null)
    }

    private suspend fun write(data: SessionFile) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "sessions.json.tmp")
        tmp.writeText(sessionsJson.encodeToString(SessionFile.serializer(), data))
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            // REPLACE_EXISTING swaps the directory entry without deleting
            // the last good session file first.
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
        val updated = activateSession(data, id)
        if (updated != data) write(updated)
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

private val sessionsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** A null document represents a missing file; every present document must decode. */
internal fun decodeSessionFile(contents: String?): SessionStore.SessionFile =
    if (contents == null) {
        SessionStore.SessionFile()
    } else {
        sessionsJson.decodeFromString(SessionStore.SessionFile.serializer(), contents)
    }

/** Unknown ids leave the complete persisted state untouched. */
internal fun activateSession(data: SessionStore.SessionFile, id: String): SessionStore.SessionFile =
    if (data.sessions.any { it.id == id }) data.copy(activeId = id) else data

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
