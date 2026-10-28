package app.privacy.miuixmailtm.data

import app.privacy.miuixmailtm.util.ClipboardHelper
import app.privacy.miuixmailtm.util.OtpExtractor
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class MailUiState(
    val currentEmail: String = "Generating ephemeral address...",
    val accountId: String? = null,
    val bearerToken: String? = null,
    val isProvisioning: Boolean = true,
    val statusLine: String = "Initializing secure RAM session...",
    val latestOtp: String? = null,
    val messages: List<MessageDetail> = emptyList(),
    val autoCopyMail: Boolean = true,
    val autoCopyOtp: Boolean = true,
    val secureScreen: Boolean = false,
    val lastCopyToast: String? = null
)

object MailTmRepository {
    private const val BASE_URL = "https://api.mail.tm"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val secureRandom = SecureRandom()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _state = MutableStateFlow(MailUiState())
    val state: StateFlow<MailUiState> = _state.asStateFlow()

    @Volatile
    private var cachedDomains: List<String> = emptyList()
    private val processedMessageIds = HashSet<String>()
    private var hasInitializedRun = false

    fun ensureNewRunIdentity(context: Context) {
        synchronized(this) {
            if (hasInitializedRun) return
            hasInitializedRun = true
        }
        scope.launch {
            rotateIdentity(context.applicationContext, reason = "New App Run")
        }
    }

    suspend fun shutdownAndClear(context: Context) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val oldId = _state.value.accountId
            val oldToken = _state.value.bearerToken
            if (oldId != null && oldToken != null) {
                deleteAccountQuietly(oldId, oldToken)
            }
            processedMessageIds.clear()
            _state.value = MailUiState(
                currentEmail = "App stopped",
                statusLine = "Session terminated",
                isProvisioning = false
            )
            hasInitializedRun = false
        }
    }

    fun setAutoCopyMail(enabled: Boolean) {
        _state.update { it.copy(autoCopyMail = enabled) }
    }

    fun setAutoCopyOtp(enabled: Boolean) {
        _state.update { it.copy(autoCopyOtp = enabled) }
    }

    fun setSecureScreen(enabled: Boolean) {
        _state.update { it.copy(secureScreen = enabled) }
    }

    suspend fun rotateIdentity(
        context: Context,
        reason: String = "Manual Rotation"
    ): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val oldId = _state.value.accountId
                val oldToken = _state.value.bearerToken
                if (oldId != null && oldToken != null) {
                    scope.launch { deleteAccountQuietly(oldId, oldToken) }
                }

                processedMessageIds.clear()
                _state.update {
                    it.copy(
                        isProvisioning = true,
                        statusLine = "Creating fresh disposable inbox ($reason)...",
                        latestOtp = null,
                        messages = emptyList()
                    )
                }

                val domains = fetchActiveDomains()
                if (domains.isEmpty()) {
                    _state.update {
                        it.copy(
                            isProvisioning = false,
                            statusLine = "Error: No active mail.tm domains available"
                        )
                    }
                    return@withContext null
                }

                val chosenDomain = domains[secureRandom.nextInt(domains.size)]
                val username = generateRandomString(10)
                val password = generateRandomString(24)
                val emailAddress = "$username@$chosenDomain"

                val credentialsJson = json.encodeToString(
                    AccountCredentials(address = emailAddress, password = password)
                )
                val createReq = Request.Builder()
                    .url("$BASE_URL/accounts")
                    .post(credentialsJson.toRequestBody(JSON_MEDIA))
                    .header("Accept", "application/ld+json")
                    .build()

                httpClient.newCall(createReq).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Create account failed: HTTP ${resp.code}")
                    }
                }

                val tokenReq = Request.Builder()
                    .url("$BASE_URL/token")
                    .post(credentialsJson.toRequestBody(JSON_MEDIA))
                    .header("Accept", "application/json")
                    .build()

                val tokenResp = httpClient.newCall(tokenReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Auth failed: HTTP ${resp.code}")
                    }
                    json.decodeFromString<TokenResponse>(body)
                }

                val autoCopied = if (_state.value.autoCopyMail) {
                    ClipboardHelper.copyText(context, "Mail.tm Address", emailAddress)
                } else false

                _state.update {
                    it.copy(
                        currentEmail = emailAddress,
                        accountId = tokenResp.id,
                        bearerToken = tokenResp.token,
                        isProvisioning = false,
                        statusLine = if (autoCopied) {
                            "Active & Auto-Copied to Clipboard"
                        } else {
                            "Active • Listening in background (2.5s poll)"
                        },
                        lastCopyToast = if (autoCopied) "Copied mail: $emailAddress" else it.lastCopyToast
                    )
                }

                return@withContext emailAddress
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isProvisioning = false,
                        statusLine = "Network retry scheduled: ${e.localizedMessage ?: "Error"}"
                    )
                }
                return@withContext null
            }
        }
    }

    suspend fun pollMessagesOnce(
        context: Context
    ): List<Pair<MessageDetail, String?>> = withContext(Dispatchers.IO) {
        val token = _state.value.bearerToken ?: return@withContext emptyList()
        val newlyArrived = mutableListOf<Pair<MessageDetail, String?>>()

        try {
            val listReq = Request.Builder()
                .url("$BASE_URL/messages?page=1")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/ld+json")
                .get()
                .build()

            val summaries = httpClient.newCall(listReq).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<MessageListResponse>(body).messages
            }

            for (summary in summaries) {
                if (processedMessageIds.contains(summary.id)) continue

                val detailReq = Request.Builder()
                    .url("$BASE_URL/messages/${summary.id}")
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/ld+json")
                    .get()
                    .build()

                val detail = httpClient.newCall(detailReq).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else {
                        val body = resp.body?.string().orEmpty()
                        json.decodeFromString<MessageDetail>(body)
                    }
                } ?: continue

                processedMessageIds.add(summary.id)

                val cleanBody = when {
                    !detail.text.isNullOrBlank() -> detail.text
                    !detail.html.isNullOrEmpty() -> OtpExtractor.stripHtml(detail.html.joinToString("\n"))
                    else -> detail.intro
                }

                val normalizedDetail = detail.copy(text = cleanBody)
                val otp = OtpExtractor.extractOtp(detail.subject, cleanBody)

                if (otp != null && _state.value.autoCopyOtp) {
                    ClipboardHelper.copyText(context, "Mail.tm OTP", otp)
                }

                newlyArrived.add(normalizedDetail to otp)

                _state.update { current ->
                    current.copy(
                        latestOtp = otp ?: current.latestOtp,
                        messages = listOf(normalizedDetail) + current.messages,
                        statusLine = if (otp != null && current.autoCopyOtp) {
                            "OTP $otp Auto-Copied to Clipboard!"
                        } else {
                            "New message received (${current.messages.size + 1} total)"
                        },
                        lastCopyToast = if (otp != null && current.autoCopyOtp) {
                            "Auto-Copied OTP: $otp"
                        } else current.lastCopyToast
                    )
                }
            }
        } catch (_: Exception) {}

        return@withContext newlyArrived
    }

    private fun fetchActiveDomains(): List<String> {
        if (cachedDomains.isNotEmpty()) return cachedDomains
        val req = Request.Builder()
            .url("$BASE_URL/domains?page=1")
            .header("Accept", "application/ld+json")
            .get()
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string().orEmpty()
            val parsed = json.decodeFromString<DomainListResponse>(body)
            val active = parsed.domains
                .filter { it.isActive && !it.isPrivate }
                .map { it.domain }
            if (active.isNotEmpty()) {
                cachedDomains = active
            }
            return active
        }
    }

    private fun deleteAccountQuietly(accountId: String, token: String) {
        try {
            val req = Request.Builder()
                .url("$BASE_URL/accounts/$accountId")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()
            httpClient.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    private fun generateRandomString(length: Int): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val sb = StringBuilder(length)
        for (i in 0 until length) {
            sb.append(alphabet[secureRandom.nextInt(alphabet.length)])
        }
        return sb.toString()
    }
}
