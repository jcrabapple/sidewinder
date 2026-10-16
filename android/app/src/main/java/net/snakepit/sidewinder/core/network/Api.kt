package net.snakepit.sidewinder.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cloudflare in front of GTS 401s default non-browser user agents (error 1010). */
private const val USER_AGENT = "Sidewinder-Android/0.1"

/** Port of web/src/api/client.ts's ApiError: HTTP failure with status and parsed body. */
class ApiError(val status: Int, message: String, val body: JsonElement? = null) : Exception(message)

/** Port of NetworkError: request never got a usable HTTP response. */
class NetworkError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Port of Page<T>: items plus the Link-header max_id for the next page. */
data class Page<T>(val items: List<T>, val nextMaxId: String?)

/** Matches the `rel=next` marker inside one Link-header entry. */
private val NEXT_REL = Regex("""rel\s*=\s*"?next"?""")

/** Extracts the URL between angle brackets of a Link-header entry. */
private val LINK_URL = Regex("<([^>]+)>")

/**
 * Pull max_id out of a Link header's `next` entry. [root] resolves
 * relative links; a malformed URL yields null instead of throwing. Pure
 * so the parsing rules are unit-testable.
 */
internal fun nextMaxIdFromLink(root: HttpUrl, link: String?): String? {
    val url = link
        ?.split(',')
        ?.firstOrNull { it.contains(NEXT_REL) }
        ?.let { LINK_URL.find(it)?.groupValues?.get(1) }
        ?: return null
    return try {
        root.resolve(url)?.queryParameter("max_id")
    } catch (_: Exception) {
        null
    }
}

/**
 * Thin OkHttp wrapper for the Mastodon-compatible API. Port of
 * web/src/api/client.ts `Api`: auth header, JSON errors, Link-header
 * pagination, 30s timeouts, custom user agent.
 */
class Api(base: String, @Volatile var token: String? = null) {

    private val root: HttpUrl = base.trimEnd('/').toHttpUrl()

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private class Raw(val code: Int, val text: String, val link: String?)

    /**
     * Build the request synchronously (bad paths and body encoding fail
     * fast on the caller's dispatcher); the network exchange itself runs
     * through [await].
     */
    private fun buildRequest(
        path: String,
        method: String,
        body: JsonElement?,
        query: Map<String, List<String>>,
        headers: Map<String, String>,
        form: FormBody?,
        multipart: MultipartBody? = null,
    ): Request {
        val url = root.resolve(path)?.newBuilder()?.apply {
            query.forEach { (k, vs) -> vs.forEach { addQueryParameter(k, it) } }
        }?.build() ?: throw NetworkError("Bad request path: $path")

        val reqBody = when {
            multipart != null -> multipart
            form != null -> form
            body != null -> json.encodeToString(JsonElement.serializer(), body).toRequestBody(jsonMedia)
            // OkHttp throws on bodyless POST/PUT/PATCH, and GTS rejects them
            // server-side; send an empty body so all such calls work.
            method.uppercase() in setOf("POST", "PUT", "PATCH") -> "".toRequestBody(jsonMedia)
            else -> null
        }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        token?.let { builder.header("Authorization", "Bearer $it") }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> if (reqBody != null) builder.delete(reqBody) else builder.delete()
            else -> builder.method(method.uppercase(), reqBody)
        }
        return builder.build()
    }

    /**
     * Enqueue the call and suspend until the response arrives. The call is
     * cancelled when the awaiting coroutine is cancelled, so a left screen
     * no longer holds an IO thread for the full timeout; a cancelled call
     * surfaces CancellationException, not NetworkError.
     */
    private suspend fun await(request: Request): Raw = suspendCancellableCoroutine { cont ->
        val call = Api.client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val raw = try {
                    response.use { resp -> Raw(resp.code, resp.body?.string().orEmpty(), resp.header("Link")) }
                } catch (e: IOException) {
                    null
                }
                if (raw != null) {
                    cont.resume(raw)
                } else if (call.isCanceled()) {
                    cont.cancel(IOException("call cancelled"))
                } else {
                    cont.resumeWithException(NetworkError("could not read the response body", null))
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    // Coroutine cancellation, not a network failure.
                    cont.cancel(e)
                } else {
                    cont.resumeWithException(NetworkError(e.message ?: "network request failed", e))
                }
            }
        })
    }

    private suspend fun send(
        path: String,
        method: String,
        body: JsonElement?,
        query: Map<String, List<String>>,
        headers: Map<String, String>,
        form: FormBody?,
        multipart: MultipartBody? = null,
    ): Raw = await(buildRequest(path, method, body, query, headers, form, multipart))

    private fun decodeError(raw: Raw): Nothing {
        val bodyJson = runCatching { json.parseToJsonElement(raw.text) }.getOrNull()
        throw ApiError(raw.code, raw.text.take(200).ifBlank { "HTTP ${raw.code}" }, bodyJson)
    }

    /**
     * Decode a response body off the caller's (often Main) dispatcher.
     * A blank 2xx body with a [JsonElement] serializer is [JsonNull] —
     * kotlinx throws on an empty stream, and several endpoints answer
     * 200 with no body.
     */
    private suspend fun <T> decode(text: String, serializer: KSerializer<T>): T =
        withContext(Dispatchers.Default) {
            @Suppress("UNCHECKED_CAST")
            if (text.isBlank() && serializer == JsonElement.serializer()) {
                JsonNull as T
            } else {
                json.decodeFromString(serializer, text)
            }
        }

    suspend fun <T> request(
        path: String,
        serializer: KSerializer<T>,
        method: String = "GET",
        body: JsonElement? = null,
        query: Map<String, List<String>> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): T {
        val raw = send(path, method, body, query, headers, form = null)
        if (raw.code !in 200..299) decodeError(raw)
        return decode(raw.text, serializer)
    }

    /** Form-encoded POST (OAuth token exchange). Port of postForm(). */
    suspend fun <T> postForm(
        path: String,
        form: Map<String, String>,
        serializer: KSerializer<T>,
        headers: Map<String, String> = emptyMap(),
    ): T {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val raw = send(path, "POST", body = null, query = emptyMap(), headers = headers, form = body)
        if (raw.code !in 200..299) decodeError(raw)
        return decode(raw.text, serializer)
    }

    /** Form-encoded PATCH (update_credentials; the endpoint is formData, not JSON). */
    suspend fun <T> patchForm(
        path: String,
        form: Map<String, String>,
        serializer: KSerializer<T>,
    ): T {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val raw = send(path, "PATCH", body = null, query = emptyMap(), headers = emptyMap(), form = body)
        if (raw.code !in 200..299) decodeError(raw)
        return decode(raw.text, serializer)
    }

    /**
     * Multipart file upload (media attachments). [fields] carries extra
     * form parts such as `description` (alt text) or `focus`.
     */
    suspend fun <T> upload(
        path: String,
        serializer: KSerializer<T>,
        fileField: String = "file",
        filename: String,
        mediaType: String,
        bytes: ByteArray,
        fields: Map<String, String> = emptyMap(),
        method: String = "POST",
    ): T {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fileField,
                filename,
                bytes.toRequestBody(mediaType.toMediaType()),
            )
        fields.forEach { (k, v) -> multipart.addFormDataPart(k, v) }
        val raw = send(path, method, body = null, query = emptyMap(), headers = emptyMap(), form = null, multipart = multipart.build())
        if (raw.code !in 200..299) decodeError(raw)
        return decode(raw.text, serializer)
    }

    /**
     * One page of a cursor-paginated collection. Pass a previous
     * [Page.nextMaxId] as [maxId]; repeated query values (id[]=a&id[]=b)
     * are supplied as list entries.
     */
    suspend fun <T> page(
        path: String,
        serializer: KSerializer<List<T>>,
        query: Map<String, List<String>> = emptyMap(),
        maxId: String? = null,
    ): Page<T> {
        val q = if (maxId != null) query + mapOf("max_id" to listOf(maxId)) else query
        val raw = send(path, "GET", body = null, query = q, headers = emptyMap(), form = null)
        if (raw.code !in 200..299) decodeError(raw)
        val items = decode(raw.text, serializer)
        return Page(items, nextMaxIdFromLink(root, raw.link))
    }

    private companion object {
        /**
         * One process-wide client: every Api instance used to build its
         * own connection pool and thread pool. Per-instance state stays
         * `root` + `token`.
         */
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
