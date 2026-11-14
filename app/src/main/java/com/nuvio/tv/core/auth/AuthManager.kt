package com.nuvio.tv.core.auth

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.logging.diagnosticSummary
import com.nuvio.tv.core.util.StartupLatencyTrace
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.remote.supabase.TvLoginExchangeResult
import com.nuvio.tv.data.remote.supabase.TvLoginPollResult
import com.nuvio.tv.data.remote.supabase.TvLoginStartResult
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.ServerConfiguration
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ServerResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import com.nuvio.tv.core.di.BackendAuth
import javax.inject.Singleton
import javax.net.ssl.SSLException

private const val TAG = "AuthManager"
private const val AUTH_ENDPOINT_PASSWORD = "/auth/v1/token?grant_type=password"
private const val AUTH_ENDPOINT_REFRESH = "/auth/v1/token?grant_type=refresh_token"
private const val AUTH_ENDPOINT_START_TV_LOGIN = "/rest/v1/rpc/start_tv_login_session"
private const val AUTH_ENDPOINT_POLL_TV_LOGIN = "/rest/v1/rpc/poll_tv_login_session"
private const val AUTH_ENDPOINT_EXCHANGE_TV_LOGIN = "/functions/v1/tv-logins-exchange"
private val tvLoginRequestCounter = AtomicLong(0L)
private val authJsonMediaType = "application/json".toMediaType()

private enum class SessionRefreshResult {
    REFRESHED,
    INVALID_SESSION,
    TRANSIENT_FAILURE
}

private data class SessionRefreshOutcome(
    val result: SessionRefreshResult,
    val error: Throwable? = null
)

@Singleton
class AuthManager @Inject constructor(
    private val auth: Auth,
    private val postgrest: Postgrest,
    // Validated TLS for default and custom backends alike: passwords and tokens ride these calls.
    @param:BackendAuth private val authHttpClient: OkHttpClient,
    private val authSessionNoticeDataStore: AuthSessionNoticeDataStore,
    private val accountLocalDataResetService: AccountLocalDataResetService,
    private val serverConfiguration: ServerConfiguration
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val sessionValidator = AuthSessionValidator(auth)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    @Volatile
    private var cachedEffectiveUserId: String? = null
    @Volatile
    private var cachedEffectiveUserSourceUserId: String? = null

    init {
        observeSessionStatus()
    }

    private fun observeSessionStatus() {
        scope.launch {
            auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.Authenticated -> {
                        // Spec 69 M1: restored session in hand; gate_auth_resolved follows the validation.
                        // On a signed-out start this first fires at sign-in instead (large t).
                        StartupLatencyTrace.markOnce("auth_session_restored")
                        val user = auth.currentUserOrNull()
                        val tokenPresent = auth.currentAccessTokenOrNull()?.isNotBlank() == true
                        Log.d(TAG, "SessionStatus.Authenticated userPresent=${user != null} emailPresent=${!user?.email.isNullOrBlank()} tokenPresent=$tokenPresent")
                        if (user != null) {
                            if (cachedEffectiveUserSourceUserId != user.id) {
                                cachedEffectiveUserId = null
                                cachedEffectiveUserSourceUserId = null
                            }
                            if (user.email.isNullOrBlank()) {
                                clearInvalidRemoteSession()
                            } else if (validateAuthenticatedSession(force = false)) {
                                _authState.value = AuthState.FullAccount(userId = user.id, email = user.email!!)
                                authSessionNoticeDataStore.markNuvioAuthenticated()
                            }
                        } else {
                            clearInvalidRemoteSession()
                        }
                    }
                    is SessionStatus.NotAuthenticated -> {
                        val session = auth.currentSessionOrNull()
                        val refreshToken = session?.refreshToken?.takeIf { it.isNotBlank() }
                        Log.d(TAG, "SessionStatus.NotAuthenticated refreshTokenPresent=${refreshToken != null} authState=${_authState.value.nameForLog()}")
                        if (refreshToken != null) {
                            scope.launch {
                                val outcome = refreshCurrentSessionSerialized(
                                    observedRefreshToken = refreshToken,
                                    reason = "Session became unauthenticated"
                                )
                                when (outcome.result) {
                                    SessionRefreshResult.REFRESHED -> Unit
                                    SessionRefreshResult.INVALID_SESSION -> {
                                        clearInvalidRemoteSession(outcome.error)
                                    }
                                    SessionRefreshResult.TRANSIENT_FAILURE -> {
                                        Log.w(TAG, "Session refresh failed transiently; keeping current auth state")
                                    }
                                }
                            }
                        } else {
                            handleUnexpectedSignedOut()
                        }
                    }
                    is SessionStatus.Initializing -> {
                        Log.d(TAG, "SessionStatus.Initializing")
                        _authState.value = AuthState.Loading
                    }
                    else -> Unit
                }
            }
        }
    }

    val isAuthenticated: Boolean
        get() = _authState.value is AuthState.FullAccount

    val currentUserId: String?
        get() = when (val state = _authState.value) {
            is AuthState.FullAccount -> state.userId
            else -> null
        }

    /**
     * Returns the effective user ID for data operations.
     * For sync-linked devices, this returns the sync owner's user ID.
     * For direct users, returns their own user ID.
     */
    suspend fun getEffectiveUserId(fallbackToOwnIdOnFailure: Boolean = true): String? {
        val userId = currentUserId ?: return null
        if (cachedEffectiveUserSourceUserId != userId) {
            cachedEffectiveUserId = null
            cachedEffectiveUserSourceUserId = null
        }
        cachedEffectiveUserId?.let { return it }

        suspend fun resolveAndCache(): String {
            val result = postgrest.rpc("get_sync_owner")
            val effectiveId = result.decodeAs<String>()
            cachedEffectiveUserId = effectiveId
            cachedEffectiveUserSourceUserId = userId
            return effectiveId
        }

        return try {
            resolveAndCache()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (refreshSessionIfJwtExpired(e)) {
                return try {
                    resolveAndCache()
                } catch (retryCancellation: CancellationException) {
                    throw retryCancellation
                } catch (retryError: Exception) {
                    if (fallbackToOwnIdOnFailure) {
                        Log.e(TAG, "Failed to get effective user ID after refresh; falling back to own ID", retryError)
                        userId
                    } else {
                        Log.e(TAG, "Failed to get effective user ID after refresh", retryError)
                        null
                    }
                }
            }

            if (fallbackToOwnIdOnFailure) {
                Log.e(TAG, "Failed to get effective user ID, falling back to own ID", e)
                userId
            } else {
                Log.e(TAG, "Failed to get effective user ID", e)
                null
            }
        }
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> {
        return try {
            val payload = buildJsonObject {
                put("email", email)
                put("password", password)
            }.toString()
            val body = executeSupabaseJsonRequest(
                endpoint = AUTH_ENDPOINT_PASSWORD,
                url = supabaseUrl(AUTH_ENDPOINT_PASSWORD),
                headers = supabaseHeaders(),
                body = payload
            ).body
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            Log.d(TAG, "Sign in token response tokenType=${result.tokenType ?: "-"} expiresIn=${result.expiresIn ?: "-"} accessTokenPresent=${result.accessToken.isNotBlank()} refreshTokenPresent=${result.refreshToken.isNotBlank()}")
            auth.importTokenResponse(result)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed", e)
            Result.failure(e)
        }
    }

    suspend fun signOut(explicit: Boolean = true) {
        sessionValidator.reset()
        if (explicit) {
            authSessionNoticeDataStore.markNuvioExplicitLogout()
        } else {
            authSessionNoticeDataStore.markUnexpectedNuvioLogoutIfNeeded()
        }
        try {
            auth.signOut()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Log.e(TAG, "Sign out failed", e)
        }
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
        accountLocalDataResetService.clearAfterSignOut()
    }

    suspend fun prepareForServerSwitch(): Result<Unit> {
        val hadSession = auth.currentSessionOrNull() != null || _authState.value is AuthState.FullAccount
        sessionValidator.reset()
        val sessionClearResult = try {
            auth.clearSession()
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (clearError: Exception) {
            Result.failure(clearError)
        }
        if (sessionClearResult.isFailure) return sessionClearResult
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
        authSessionNoticeDataStore.markNuvioExplicitLogout()
        if (hadSession) accountLocalDataResetService.clearAfterSignOut()
        return Result.success(Unit)
    }

    private suspend fun handleUnexpectedSignedOut() {
        sessionValidator.reset()
        cachedEffectiveUserId = null
        cachedEffectiveUserSourceUserId = null
        _authState.value = AuthState.SignedOut
        if (authSessionNoticeDataStore.markUnexpectedNuvioLogoutIfNeeded()) {
            accountLocalDataResetService.clearAfterSignOut()
        }
    }

    suspend fun refreshSessionIfJwtExpired(error: Throwable): Boolean {
        if (!error.isJwtExpiredAuthError()) return false
        val refreshToken = auth.currentSessionOrNull()?.refreshToken?.takeIf { it.isNotBlank() }
            ?: run {
                Log.w(TAG, "JWT expired but no refresh token available; cannot refresh session")
                clearInvalidRemoteSession(error)
                return false
            }
        val outcome = refreshCurrentSessionSerialized(
            observedRefreshToken = refreshToken,
            reason = "JWT expired"
        )
        if (outcome.result == SessionRefreshResult.INVALID_SESSION) {
            clearInvalidRemoteSession(outcome.error)
        }
        return outcome.result == SessionRefreshResult.REFRESHED
    }

    private suspend fun validateAuthenticatedSession(force: Boolean): Boolean =
        sessionValidator.validate(force).let { outcome ->
            when (outcome.result) {
                AuthSessionValidationResult.VALID -> true
                AuthSessionValidationResult.EXPIRED_ACCESS_TOKEN -> {
                    val error = outcome.error
                    if (error == null) {
                        clearInvalidRemoteSession()
                        false
                    } else {
                        val refreshed = refreshSessionIfJwtExpired(error)
                        if (refreshed) {
                            sessionValidator.markCurrentSessionValidated()
                            true
                        } else {
                            _authState.value !is AuthState.SignedOut
                        }
                    }
                }
                AuthSessionValidationResult.INVALID_SESSION -> {
                    outcome.error?.let { error ->
                        Log.w(TAG, "Supabase session is no longer active; clearing local authentication", error)
                    }
                    clearInvalidRemoteSession(outcome.error)
                    false
                }
                AuthSessionValidationResult.TRANSIENT_FAILURE -> {
                    Log.w(TAG, "Unable to validate Supabase session; keeping cached authentication", outcome.error)
                    true
                }
            }
        }

    private suspend fun clearInvalidRemoteSession(error: Throwable? = null) {
        sessionValidator.reset()
        try {
            auth.clearSession()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (clearError: Throwable) {
            if (error != null) clearError.addSuppressed(error)
            Log.w(TAG, "Failed to clear invalid Supabase session", clearError)
        }
        handleUnexpectedSignedOut()
    }

    private suspend fun refreshCurrentSessionSerialized(
        observedRefreshToken: String?,
        reason: String
    ): SessionRefreshOutcome = refreshMutex.withLock {
        val currentRefreshToken = auth.currentSessionOrNull()?.refreshToken?.takeIf { it.isNotBlank() }
        if (currentRefreshToken == null) {
            Log.w(TAG, "$reason but no refresh token available; cannot refresh session")
            return@withLock SessionRefreshOutcome(SessionRefreshResult.INVALID_SESSION)
        }
        if (observedRefreshToken != null && currentRefreshToken != observedRefreshToken) {
            Log.d(TAG, "$reason; session was already refreshed by another request")
            return@withLock SessionRefreshOutcome(SessionRefreshResult.REFRESHED)
        }
        return@withLock try {
            Log.w(TAG, "$reason; refreshing Supabase session")
            val payload = buildJsonObject {
                put("refresh_token", currentRefreshToken)
            }.toString()
            val body = executeSupabaseJsonRequest(
                endpoint = AUTH_ENDPOINT_REFRESH,
                url = supabaseUrl(AUTH_ENDPOINT_REFRESH),
                headers = supabaseHeaders(),
                body = payload
            ).body
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            Log.d(TAG, "Supabase session refresh token response tokenType=${result.tokenType ?: "-"} expiresIn=${result.expiresIn ?: "-"} accessTokenPresent=${result.accessToken.isNotBlank()} refreshTokenPresent=${result.refreshToken.isNotBlank()}")
            auth.importTokenResponse(result)
            SessionRefreshOutcome(SessionRefreshResult.REFRESHED)
        } catch (refreshCancellation: CancellationException) {
            throw refreshCancellation
        } catch (refreshError: Exception) {
            val result = refreshError.toSessionRefreshResult()
            if (result == SessionRefreshResult.INVALID_SESSION) {
                Log.e(TAG, "Supabase session refresh failed with invalid session", refreshError)
            } else {
                Log.w(TAG, "Supabase session refresh failed transiently", refreshError)
            }
            SessionRefreshOutcome(result, refreshError)
        }
    }

    suspend fun startTvLoginSession(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String,
        traceId: Long? = null
    ): Result<TvLoginStartResult> {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        Log.d(TAG, "$trace startTvLoginSession begin deviceNamePresent=${!deviceName.isNullOrBlank()} redirectPresent=${redirectBaseUrl.isNotBlank()}")
        return try {
            val result = startTvLoginSessionRpc(
                deviceNonce = deviceNonce,
                deviceName = deviceName,
                redirectBaseUrl = redirectBaseUrl,
                traceId = traceId
            )
            Log.d(TAG, "$trace startTvLoginSession ok elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} codePresent=${result.code.isNotBlank()} urlLength=${result.webUrl.length} expiresAt=${result.expiresAt} pollInterval=${result.pollIntervalSeconds}")
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.markerText().orEmpty().lowercase()
            val shouldRetryLegacySignature = !deviceName.isNullOrBlank() &&
                message.contains("could not find the function") &&
                message.contains("start_tv_login_session") &&
                message.contains("p_device_name")

            if (shouldRetryLegacySignature) {
                return try {
                    Log.w(TAG, "$trace start_tv_login_session legacy signature detected; retrying without p_device_name elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}")
                    val result = startTvLoginSessionRpc(
                        deviceNonce = deviceNonce,
                        deviceName = null,
                        redirectBaseUrl = redirectBaseUrl,
                        traceId = traceId
                    )
                    Log.d(TAG, "$trace startTvLoginSession legacy retry ok elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} codePresent=${result.code.isNotBlank()} urlLength=${result.webUrl.length} expiresAt=${result.expiresAt} pollInterval=${result.pollIntervalSeconds}")
                    Result.success(result)
                } catch (retryCancellation: CancellationException) {
                    throw retryCancellation
                } catch (retryError: Exception) {
                    Log.e(TAG, "$trace startTvLoginSession legacy retry failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${retryError.diagnosticSummary()}", retryError)
                    Result.failure(retryError)
                }
            }

            Log.e(TAG, "$trace startTvLoginSession failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            Result.failure(e)
        }
    }

    private suspend fun startTvLoginSessionRpc(
        deviceNonce: String,
        deviceName: String?,
        redirectBaseUrl: String,
        traceId: Long?
    ): TvLoginStartResult {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        val params = buildJsonObject {
            put("p_device_nonce", deviceNonce)
            put("p_redirect_base_url", redirectBaseUrl)
            if (!deviceName.isNullOrBlank()) put("p_device_name", deviceName)
        }
        Log.d(TAG, "$trace rpc start_tv_login_session request deviceNamePresent=${!deviceName.isNullOrBlank()}")
        val body = executeSupabaseJsonRequest(
            endpoint = AUTH_ENDPOINT_START_TV_LOGIN,
            url = supabaseUrl(AUTH_ENDPOINT_START_TV_LOGIN),
            headers = supabaseHeaders(),
            body = params.toString()
        ).body
        val results = json.decodeFromString<List<TvLoginStartResult>>(body)
        Log.d(TAG, "$trace rpc start_tv_login_session response rows=${results.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
        return results.firstOrNull()
            ?: throw Exception("Empty response from start_tv_login_session")
    }

    suspend fun pollTvLoginSession(
        code: String,
        deviceNonce: String,
        traceId: Long? = null,
        attempt: Int? = null
    ): Result<TvLoginPollResult> {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        return try {
            Log.d(TAG, "$trace pollTvLoginSession begin attempt=${attempt ?: "-"}")
            val params = buildJsonObject {
                put("p_code", code)
                put("p_device_nonce", deviceNonce)
            }
            val body = executeSupabaseJsonRequest(
                endpoint = AUTH_ENDPOINT_POLL_TV_LOGIN,
                url = supabaseUrl(AUTH_ENDPOINT_POLL_TV_LOGIN),
                headers = supabaseHeaders(),
                body = params.toString()
            ).body
            val results = json.decodeFromString<List<TvLoginPollResult>>(body)
            val result = results.firstOrNull()
                ?: throw Exception("Empty response from poll_tv_login_session")
            Log.d(TAG, "$trace pollTvLoginSession ok attempt=${attempt ?: "-"} rows=${results.size} status=${result.status} expiresAt=${result.expiresAt ?: "-"} pollInterval=${result.pollIntervalSeconds ?: "-"} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$trace pollTvLoginSession failed attempt=${attempt ?: "-"} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            Result.failure(e)
        }
    }

    suspend fun exchangeTvLoginSession(
        code: String,
        deviceNonce: String,
        traceId: Long? = null
    ): Result<Unit> {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = qrTrace(traceId)
        return try {
            val payload = buildJsonObject {
                put("code", code)
                put("device_nonce", deviceNonce)
            }.toString()
            val url = supabaseUrl(AUTH_ENDPOINT_EXCHANGE_TV_LOGIN)
            Log.d(TAG, "$trace exchangeTvLoginSession request payloadBytes=${payload.length}")
            val body = executeSupabaseJsonRequest(
                endpoint = AUTH_ENDPOINT_EXCHANGE_TV_LOGIN,
                url = url,
                headers = supabaseHeaders(),
                body = payload
            ).body
            val result = json.decodeFromString<TvLoginExchangeResult>(body)
            Log.d(TAG, "$trace exchangeTvLoginSession decoded tokenType=${result.tokenType ?: "-"} expiresIn=${result.expiresIn ?: "-"} accessTokenPresent=${result.accessToken.isNotBlank()} refreshTokenPresent=${result.refreshToken.isNotBlank()}")
            auth.importTokenResponse(result)
            Log.d(TAG, "$trace exchangeTvLoginSession imported auth token elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "$trace exchangeTvLoginSession failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            Result.failure(e)
        }
    }

    private suspend fun executeSupabaseJsonRequest(
        endpoint: String,
        url: String,
        headers: Map<String, String>,
        body: String,
        method: String = "POST"
    ): AuthHttpResponse {
        val requestId = tvLoginRequestCounter.incrementAndGet()
        return try {
            executeSupabaseJsonRequestAttempt(
                requestId = requestId,
                attempt = "primary",
                endpoint = endpoint,
                url = url,
                headers = headers,
                body = body,
                method = method
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (primaryError: Exception) {
            val fallbackUrl = supabaseFallbackUrl(endpoint)
            if (fallbackUrl == null || !primaryError.shouldRetryWithAuthFallback()) {
                throw primaryError
            }
            Log.w(TAG, "auth request #$requestId endpoint=$endpoint retrying origin fallback reason=${primaryError.diagnosticSummary()}")
            try {
                executeSupabaseJsonRequestAttempt(
                    requestId = requestId,
                    attempt = "origin_fallback",
                    endpoint = endpoint,
                    url = fallbackUrl,
                    headers = headers,
                    body = body,
                    method = method
                )
            } catch (fallbackError: Exception) {
                fallbackError.addSuppressed(primaryError)
                throw fallbackError
            }
        }
    }

    private suspend fun executeSupabaseJsonRequestAttempt(
        requestId: Long,
        attempt: String,
        endpoint: String,
        url: String,
        headers: Map<String, String>,
        body: String,
        method: String
    ): AuthHttpResponse {
        val startedAtMs = SystemClock.elapsedRealtime()
        Log.d(TAG, "auth request #$requestId attempt=$attempt method=$method endpoint=$endpoint payloadBytes=${body.length}")
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        when (method.uppercase()) {
            "POST" -> requestBuilder.post(body.toRequestBody(authJsonMediaType))
            else -> error("Unsupported auth request method: $method")
        }
        val request = requestBuilder.build()
        return try {
            withContext(Dispatchers.IO) {
                authHttpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body.string()
                    Log.d(TAG, "auth response #$requestId attempt=$attempt endpoint=$endpoint http=${response.code} success=${response.isSuccessful} bodyBytes=${responseBody.length} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}")
                    if (!response.isSuccessful) {
                        // Error bodies only: success bodies carry tokens and the TV-login
                        // code/web URL, which the credential-key redaction does not catch.
                        Log.d(TAG, "auth response #$requestId error body=${redactAuthCredentials(responseBody).orEmpty()}")
                        throw AuthHttpException(endpoint, response.code, responseBody)
                    }
                    AuthHttpResponse(response.code, responseBody)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "auth request #$requestId attempt=$attempt endpoint=$endpoint failed elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} error=${e.diagnosticSummary()}", e)
            throw e
        }
    }

    private fun supabaseUrl(endpoint: String): String =
        "${serverConfiguration.backendUrl.trimEnd('/')}$endpoint"

    private fun supabaseFallbackUrl(endpoint: String): String? {
        val primary = serverConfiguration.backendUrl.trimEnd('/')
        val fallback = serverConfiguration.fallbackBackendUrl.orEmpty().trim().trimEnd('/')
        if (fallback.isBlank()) return null
        if (primary.equals(fallback, ignoreCase = true)) return null
        return "$fallback$endpoint"
    }

    private fun supabaseHeaders(accessToken: String? = null): Map<String, String> =
        buildMap {
            put("apikey", serverConfiguration.publishableKey)
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) put("Authorization", "Bearer $accessToken")
        }
}

private data class AuthHttpResponse(
    val statusCode: Int,
    val body: String
)

// The body stays out of the message: messages reach logs (diagnosticSummary, stack traces)
// unredacted. Classification reads it through markerText() / responseBody.
internal class AuthHttpException(
    val endpoint: String,
    val statusCode: Int,
    val responseBody: String
) : Exception("Auth request failed endpoint=$endpoint status=$statusCode")

private fun qrTrace(traceId: Long?): String =
    if (traceId == null) "QR_LOGIN" else "QR_LOGIN[$traceId]"

internal fun AuthState.nameForLog(): String =
    when (this) {
        is AuthState.FullAccount -> "FullAccount"
        AuthState.Loading -> "Loading"
        AuthState.SignedOut -> "SignedOut"
    }

private fun Throwable.toSessionRefreshResult(): SessionRefreshResult {
    findCause<AuthHttpException>()?.let { error ->
        return if (isInvalidAuthRefreshResponse(error.statusCode, error.responseBody)) {
            SessionRefreshResult.INVALID_SESSION
        } else {
            SessionRefreshResult.TRANSIENT_FAILURE
        }
    }

    if (hasCause<HttpRequestTimeoutException>() ||
        hasCause<ServerResponseException>() ||
        hasCause<UnknownHostException>() ||
        hasCause<SocketTimeoutException>() ||
        hasCause<ConnectException>() ||
        hasCause<NoRouteToHostException>() ||
        hasCause<SSLException>() ||
        hasCause<IOException>()
    ) {
        return SessionRefreshResult.TRANSIENT_FAILURE
    }

    findCause<ClientRequestException>()?.let { error ->
        val status = error.response.status.value
        return if (isInvalidAuthRefreshResponse(status, error.message.orEmpty())) {
            SessionRefreshResult.INVALID_SESSION
        } else {
            SessionRefreshResult.TRANSIENT_FAILURE
        }
    }

    val message = causeMessages().lowercase()
    if (isInvalidAuthSessionMessage(message)) {
        return SessionRefreshResult.INVALID_SESSION
    }

    return SessionRefreshResult.TRANSIENT_FAILURE
}

private fun Throwable.shouldRetryWithAuthFallback(): Boolean {
    findCause<AuthHttpException>()?.let { error ->
        return error.statusCode in setOf(408, 500, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 530) ||
            error.responseBody.contains("cloudflare", ignoreCase = true) ||
            error.responseBody.contains("cf-error-code", ignoreCase = true)
    }

    return hasCause<UnknownHostException>() ||
        hasCause<ConnectException>() ||
        hasCause<NoRouteToHostException>() ||
        hasCause<SSLException>()
}
