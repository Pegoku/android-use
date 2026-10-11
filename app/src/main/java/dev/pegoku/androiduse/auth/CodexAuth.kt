package dev.pegoku.androiduse.auth

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.pegoku.androiduse.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class CodexTokens(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String,
    val accountId: String,
) {
    val email: String? get() = CodexAuth.claims(idToken)["email"]?.jsonPrimitive?.content
    val plan: String? get() = CodexAuth.authClaims(idToken)?.get("chatgpt_plan_type")?.jsonPrimitive?.content
}

/**
 * ChatGPT-subscription auth, mirroring `codex login`: OAuth authorization code + PKCE against
 * auth.openai.com with the Codex CLI client id and its registered http://localhost:1455 callback.
 */
object CodexAuth {
    private const val ISSUER = "https://auth.openai.com"
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val PORT = 1455
    private const val REDIRECT_URI = "http://localhost:$PORT/auth/callback"
    private const val KEY_TOKENS = "codex_tokens"

    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()
    private lateinit var prefs: SharedPreferences

    private val _tokens = MutableStateFlow<CodexTokens?>(null)
    val tokens: StateFlow<CodexTokens?> = _tokens.asStateFlow()

    @Suppress("DEPRECATION")
    fun init(context: Context) {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            context, "codex_auth", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        _tokens.value = prefs.getString(KEY_TOKENS, null)?.let { runCatching { json.decodeFromString<CodexTokens>(it) }.getOrNull() }
    }

    private fun save(tokens: CodexTokens?) {
        prefs.edit().apply { if (tokens == null) remove(KEY_TOKENS) else putString(KEY_TOKENS, json.encodeToString(tokens)) }.apply()
        _tokens.value = tokens
    }

    fun signOut() = save(null)

    /** A pending browser login: open [url], then await [complete]. */
    class Login internal constructor(val url: String, private val verifier: String, private val state: String) {
        private val server = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
        @Volatile private var cancelled = false

        fun cancel() {
            cancelled = true
            runCatching { server.close() }
        }

        suspend fun complete(): CodexTokens = withContext(Dispatchers.IO) {
            try {
                val code = awaitCode()
                val body = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("client_id", CLIENT_ID)
                    .add("code_verifier", verifier)
                    .build()
                val result = tokenRequest(Request.Builder().url("$ISSUER/oauth/token").post(body).build())
                val idToken = result.string("id_token") ?: error("No id_token in response")
                val tokens = CodexTokens(
                    idToken = idToken,
                    accessToken = result.string("access_token") ?: error("No access_token in response"),
                    refreshToken = result.string("refresh_token") ?: error("No refresh_token in response"),
                    accountId = accountId(idToken) ?: error("No ChatGPT account in id_token"),
                )
                save(tokens)
                tokens
            } finally {
                runCatching { server.close() }
            }
        }

        private fun awaitCode(): String {
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (!cancelled && System.currentTimeMillis() < deadline) {
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    if (cancelled) break else throw e
                }
                socket.use { s ->
                    val requestLine = s.getInputStream().bufferedReader().readLine().orEmpty()
                    val target = requestLine.split(' ').getOrNull(1).orEmpty()
                    val uri = Uri.parse("http://localhost$target")
                    fun respond(status: String, message: String) {
                        val html = "<html><body style=\"font-family:sans-serif;padding:2em\"><h2>$message</h2></body></html>"
                        val bytes = html.toByteArray()
                        s.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                    if (uri.path != "/auth/callback") {
                        respond("404 Not Found", "Not found")
                        return@use
                    }
                    uri.getQueryParameter("error")?.let { err ->
                        respond("400 Bad Request", "Sign-in failed: $err")
                        error("Sign-in failed: ${uri.getQueryParameter("error_description") ?: err}")
                    }
                    if (uri.getQueryParameter("state") != state) {
                        respond("400 Bad Request", "State mismatch, please retry.")
                        error("OAuth state mismatch")
                    }
                    val code = uri.getQueryParameter("code") ?: error("Callback without code")
                    respond("200 OK", "Signed in to Codex. You can return to Android Use.")
                    return code
                }
            }
            error(if (cancelled) "Sign-in cancelled" else "Sign-in timed out")
        }
    }

    fun startLogin(): Login {
        val random = SecureRandom()
        val verifier = base64Url(ByteArray(64).also(random::nextBytes))
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = base64Url(ByteArray(32).also(random::nextBytes))
        val url = Uri.parse("$ISSUER/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "openid profile email offline_access")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("originator", "codex_cli_rs")
            .build().toString()
        return Login(url, verifier, state)
    }

    /** Imports the contents of a desktop `~/.codex/auth.json`. */
    fun importAuthJson(text: String): CodexTokens {
        val root = json.parseToJsonElement(text).jsonObject
        val t = root["tokens"]?.jsonObject ?: error("auth.json has no \"tokens\" (is it an API-key login?)")
        val idToken = t.string("id_token") ?: error("Missing id_token")
        val tokens = CodexTokens(
            idToken = idToken,
            accessToken = t.string("access_token") ?: error("Missing access_token"),
            refreshToken = t.string("refresh_token") ?: error("Missing refresh_token"),
            accountId = t.string("account_id") ?: accountId(idToken) ?: error("Missing account_id"),
        )
        save(tokens)
        return tokens
    }

    /** Returns tokens whose access token is valid for at least a few more minutes. */
    suspend fun fresh(force: Boolean = false): CodexTokens = refreshLock.withLock {
        val current = _tokens.value ?: error("Not signed in to Codex")
        val exp = claims(current.accessToken)["exp"]?.jsonPrimitive?.longOrNull
        if (!force && exp != null && exp * 1000 > System.currentTimeMillis() + 5 * 60_000) return current
        val body = buildJsonObject {
            put("client_id", CLIENT_ID)
            put("grant_type", "refresh_token")
            put("refresh_token", current.refreshToken)
            put("scope", "openid profile email")
        }.toString().toRequestBody("application/json".toMediaType())
        val result = withContext(Dispatchers.IO) {
            tokenRequest(Request.Builder().url("$ISSUER/oauth/token").post(body).build())
        }
        val idToken = result.string("id_token") ?: current.idToken
        val updated = current.copy(
            idToken = idToken,
            accessToken = result.string("access_token") ?: current.accessToken,
            refreshToken = result.string("refresh_token") ?: current.refreshToken,
            accountId = accountId(idToken) ?: current.accountId,
        )
        save(updated)
        updated
    }

    private fun tokenRequest(request: Request): JsonObject =
        Http.client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 400 || response.code == 401) {
                    error("Codex token request failed (${response.code}). Sign in again. ${text.take(300)}")
                }
                throw IOException("Codex token request failed (${response.code}): ${text.take(300)}")
            }
            json.parseToJsonElement(text).jsonObject
        }

    internal fun claims(jwt: String): JsonObject = runCatching {
        val payload = jwt.split('.')[1]
        json.parseToJsonElement(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    internal fun authClaims(jwt: String): JsonObject? =
        claims(jwt)["https://api.openai.com/auth"] as? JsonObject

    private fun accountId(idToken: String): String? =
        authClaims(idToken)?.get("chatgpt_account_id")?.jsonPrimitive?.content

    private fun base64Url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
