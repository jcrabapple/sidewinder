package net.snakepit.sidewinder.core.moderation

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import net.snakepit.sidewinder.core.network.Api
import net.snakepit.sidewinder.core.network.Page

/**
 * Moderation helpers for admins: pending signups, reports, account
 * search/suspend, and domain blocks. Port of web `src/state/admin.ts`.
 *
 * GoToSocial has no API to confirm a new account's email address and no API
 * to undo a suspend; the UI carries the caveat copy from the web client for
 * both. The web client's second-step confirm helper (gts-admin-helper) is a
 * localhost endpoint and is not called from the app.
 */
object ModerationRepository {

    // ---- Payload shapes (subsets of the GTS admin models) ----

    /** Embedded account card in admin payloads. */
    @Serializable
    data class AdminAccountInfo(
        val id: String,
        val username: String,
        val acct: String,
        val domain: String? = null,
        val display_name: String = "",
        val avatar: String = "",
        val avatar_static: String? = null,
        val suspended: Boolean? = null,
    )

    /** Admin account row (v1 signup shape). */
    @Serializable
    data class AdminAccount(
        val id: String,
        val username: String,
        val domain: String? = null,
        val email: String? = null,
        val reason: String? = null,
        val invite_request: String? = null,
        val created_at: String = "",
        val confirmed: Boolean = false,
        val approved: Boolean = false,
        val account: AdminAccountInfo,
    )

    /** GTS AdminReport. */
    @Serializable
    data class AdminReport(
        val id: String,
        val action_taken: Boolean = false,
        val action_taken_at: String? = null,
        val action_taken_comment: String? = null,
        val category: String = "",
        val comment: String = "",
        val forwarded: Boolean = false,
        val created_at: String = "",
        val account: AdminAccountInfo,
        val target_account: AdminAccountInfo,
        val statuses: List<ReportedStatus> = emptyList(),
        val rules: List<ReportedRule> = emptyList(),
    )

    @Serializable
    data class ReportedStatus(
        val id: String,
        val url: String? = null,
        val content: String? = null,
    )

    @Serializable
    data class ReportedRule(
        val id: String,
        val text: String? = null,
        val description: String? = null,
    )

    /** Domain permission row from /api/v1/admin/domain_blocks. */
    @Serializable
    data class AdminDomainBlock(
        val id: String,
        val domain: String,
        val obfuscate: Boolean = false,
        val public_comment: String? = null,
        val private_comment: String? = null,
        val created_at: String = "",
    )

    /** The signup reason shown to the admin: invite note, else account reason. */
    fun signupReason(a: AdminAccount): String =
        (a.invite_request ?: a.reason ?: "").trim()

    // ---- Signups ----

    /**
     * Accounts waiting on approval. The GTS `status=pending` filter returns
     * some internal rows (confirmed:false/approved:false), so filter to real
     * humans: local, unconfirmed, unapproved, and not the instance actor.
     */
    suspend fun fetchPendingSignups(api: Api, base: String): List<AdminAccount> {
        val list = api.page(
            path = "/api/v1/admin/accounts",
            serializer = ListSerializer(AdminAccount.serializer()),
            query = mapOf("status" to listOf("pending")),
        )
        val host = runCatching { java.net.URI(base.trim()).host?.lowercase().orEmpty() }
            .getOrDefault("")
        return list.items.filter {
            it.domain == null && !it.approved && !it.confirmed &&
                !it.username.equals(host, ignoreCase = true)
        }
    }

    /**
     * Approve a pending signup. GTS has no email-confirm API: after this
     * succeeds the account still cannot log in until its email is confirmed
     * on the server (`gotosocial admin account confirm --username <u>`),
     * which the UI must say.
     */
    suspend fun approveSignup(api: Api, id: String) {
        api.postForm(
            path = "/api/v1/admin/accounts/$id/approve",
            form = emptyMap(),
            serializer = JsonElement.serializer(),
        )
    }

    /** Deny a pending signup. Rejecting removes the pending account. */
    suspend fun denySignup(api: Api, id: String) {
        api.postForm(
            path = "/api/v1/admin/accounts/$id/reject",
            form = emptyMap(),
            serializer = JsonElement.serializer(),
        )
    }

    // ---- Reports ----

    /**
     * One page of reports. [resolved] = true returns resolved reports;
     * false returns open ones. Paged via the Link header.
     */
    suspend fun fetchReports(api: Api, resolved: Boolean, maxId: String? = null): Page<AdminReport> =
        api.page(
            path = "/api/v1/admin/reports",
            serializer = ListSerializer(AdminReport.serializer()),
            query = mapOf("resolved" to listOf(resolved.toString()), "limit" to listOf("40")),
            maxId = maxId,
        )


    /** Mark a report resolved. The comment is shown to the account that
     *  filed it, so it should be factual. */
    suspend fun resolveReport(api: Api, id: String, comment: String? = null) {
        val trimmed = comment?.trim().orEmpty()
        api.request(
            path = "/api/v1/admin/reports/$id/resolve",
            serializer = JsonElement.serializer(),
            method = "POST",
            body = if (trimmed.isEmpty()) {
                buildJsonObject { }
            } else {
                buildJsonObject {
                    put("action_taken_comment", JsonPrimitive(trimmed))
                }
            },
        )
    }

    // ---- Accounts ----

    /**
     * Suspend an account. GTS supports `type=suspend` only on this endpoint,
     * and there is no API to undo a suspend — that takes the server CLI,
     * which the confirm dialog must state before anyone taps through.
     */
    suspend fun suspendAccount(api: Api, id: String, text: String? = null) {
        val trimmed = text?.trim().orEmpty()
        api.postForm(
            path = "/api/v1/admin/accounts/$id/action",
            form = buildMap {
                put("type", "suspend")
                if (trimmed.isNotEmpty()) put("text", trimmed)
            },
            serializer = JsonElement.serializer(),
        )
    }

    /**
     * Admin account search: by username substring, optionally narrowed to
     * remote or suspended accounts.
     */
    suspend fun searchAdminAccounts(
        api: Api,
        username: String? = null,
        remote: Boolean = false,
        suspended: Boolean = false,
        maxId: String? = null,
    ): Page<AdminAccount> {
        val query = buildMap {
            username?.trim()?.takeIf { it.isNotEmpty() }?.let { put("username", listOf(it)) }
            if (remote) put("remote", listOf("true"))
            if (suspended) put("suspended", listOf("true"))
            put("limit", listOf("40"))
        }
        return api.page(
            path = "/api/v1/admin/accounts",
            serializer = ListSerializer(AdminAccount.serializer()),
            query = query,
            maxId = maxId,
        )
    }

    // ---- Domain blocks ----

    suspend fun fetchDomainBlocks(api: Api, maxId: String? = null): Page<AdminDomainBlock> =
        api.page(
            path = "/api/v1/admin/domain_blocks",
            serializer = ListSerializer(AdminDomainBlock.serializer()),
            query = mapOf("limit" to listOf("100")),
            maxId = maxId,
        )

    suspend fun createDomainBlock(
        api: Api,
        domain: String,
        publicComment: String? = null,
        obfuscate: Boolean = false,
    ) {
        api.postForm(
            path = "/api/v1/admin/domain_blocks",
            form = buildMap {
                put("domain", domain.trim().lowercase())
                publicComment?.trim()?.takeIf { it.isNotEmpty() }?.let { put("public_comment", it) }
                if (obfuscate) put("obfuscate", "true")
            },
            serializer = JsonElement.serializer(),
        )
    }

    /** Unblock a domain. Confirm in the UI: federation resumes immediately. */
    suspend fun deleteDomainBlock(api: Api, id: String) {
        api.request(
            path = "/api/v1/admin/domain_blocks/$id",
            serializer = JsonElement.serializer(),
            method = "DELETE",
        )
    }
}
