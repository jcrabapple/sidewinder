package net.snakepit.sidewinder.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cloudflare in front of GTS 401s default non-browser user agents (error 1010). */
private const val USER_AGENT = "Sidewinder-Android/0.1"

/** Port of web/src/api/client.ts's ApiError: HTTP failure with status and parsed body. */
class ApiError(val status: Int, message: String, val body: JsonElement? = null) : Exception(message)

/** Port of NetworkError: request never got a usable HTTP response. */
class NetworkError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Port of Page<T>: items plus the Link-header max_id for the next page. */
data class Page<T>(val items: List<T>, val nextMaxId: String?)

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

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private class Raw(val code: Int, val text: String, val link: String?)

    private suspend fun send(
        path: String,
        method: String,
        body: JsonElement?,
        query: Map<String, List<String>>,
        headers: Map<String, String>,
        form: FormBody?,
        multipart: MultipartBody? = null,
    ): Raw = withContext(Dispatchers.IO) {
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

        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw NetworkError(e.message ?: "network request failed", e)
        }
        response.use { resp ->
            Raw(resp.code, resp.body?.string().orEmpty(), resp.header("Link"))
        }
    }

    private fun decodeError(raw: Raw): Nothing {
        val bodyJson = runCatching { json.parseToJsonElement(raw.text) }.getOrNull()
        throw ApiError(raw.code, raw.text.take(200).ifBlank { "HTTP ${raw.code}" }, bodyJson)
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
        return json.decodeFromString(serializer, raw.text)
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
        return json.decodeFromString(serializer, raw.text)
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
    ): T {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fileField,
                filename,
                bytes.toRequestBody(mediaType.toMediaType()),
            )
        fields.forEach { (k, v) -> multipart.addFormDataPart(k, v) }
        val raw = send(path, "POST", body = null, query = emptyMap(), headers = emptyMap(), form = null, multipart = multipart.build())
        if (raw.code !in 200..299) decodeError(raw)
        return json.decodeFromString(serializer, raw.text)
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
        return Page(json.decodeFromString(serializer, raw.text), nextMaxIdFromLink(raw.link))
    }

    private fun nextMaxIdFromLink(link: String?): String? {
        val url = link
            ?.split(',')
            ?.firstOrNull { it.contains(Regex("rel\\s*=\\s*\"?next\"?")) }
            ?.let { Regex("<([^>]+)>").find(it)?.groupValues?.get(1) }
            ?: return null
        return url.toHttpUrl().queryParameter("max_id")
    }
}
