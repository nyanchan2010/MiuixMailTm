package app.privacy.miuixmailtm.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DomainListResponse(
    @SerialName("hydra:member") val domains: List<DomainItem> = emptyList()
)

@Serializable
data class DomainItem(
    val id: String,
    val domain: String,
    val isActive: Boolean = true,
    val isPrivate: Boolean = false
)

@Serializable
data class AccountCredentials(
    val address: String,
    val password: String
)

@Serializable
data class AccountResponse(
    val id: String,
    val address: String
)

@Serializable
data class TokenResponse(
    val id: String,
    val token: String
)

@Serializable
data class MessageListResponse(
    @SerialName("hydra:member") val messages: List<MessageSummary> = emptyList()
)

@Serializable
data class SenderInfo(
    val address: String = "",
    val name: String = ""
)

@Serializable
data class MessageSummary(
    val id: String,
    val from: SenderInfo = SenderInfo(),
    val subject: String = "(No Subject)",
    val intro: String = "",
    val seen: Boolean = false,
    val createdAt: String = ""
)

@Serializable
data class MessageDetail(
    val id: String,
    val from: SenderInfo = SenderInfo(),
    val subject: String = "(No Subject)",
    val intro: String = "",
    val text: String? = null,
    val html: List<String>? = null,
    val createdAt: String = ""
)
