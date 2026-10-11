package dev.ytplus.data.auth

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Persists OAuth credentials. The Android implementation keeps them in app-private storage. */
interface TokenStore {
    var refreshToken: String?
    var accessToken: String?
    var accessTokenExpiry: Long
}

class InMemoryTokenStore : TokenStore {
    override var refreshToken: String? = null
    override var accessToken: String? = null
    override var accessTokenExpiry: Long = 0
}

/**
 * Google sign-in using the OAuth "device" flow of YouTube's own TV app.
 *
 * Why this and not Play Services: Play Services only hands out OAuth tokens that YouTube's
 * private API accepts to apps signed with Google's YouTube certificate. That's the whole reason
 * ReVanced needs MicroG (which fakes the signature). The TV flow below is the one smart TVs use:
 * the user approves the sign-in on Google's real page (on their phone, where they're usually
 * already signed in), and we get a refresh token that works with the TV InnerTube client,
 * personalised recommendations included. No MicroG, no signature spoofing, no root.
 */
class TvOAuth(private val http: OkHttpClient, private val store: TokenStore) {

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSeconds: Int,
        val expiresAtMillis: Long,
    )

    sealed interface PollResult {
        data object Pending : PollResult
        data object SlowDown : PollResult
        data object Success : PollResult
        data class Failed(val reason: String) : PollResult
    }

    private data class Client(val id: String, val secret: String)

    @Volatile private var client: Client? = null

    val isSignedIn: Boolean get() = store.refreshToken != null

    /** Step 1: ask Google for a short code the user types (or confirms) at google.com/device. */
    fun startDeviceFlow(): DeviceCode {
        val c = client()
        val body = FormBody.Builder()
            .add("client_id", c.id)
            .add("scope", SCOPE)
            .add("device_id", UUID.randomUUID().toString().replace("-", ""))
            .add("device_model", "ytlr::")
            .build()
        val json = post(DEVICE_CODE_URL, body)
        json.optString("error").takeIf { it.isNotEmpty() }?.let { throw IOException("Device code request failed: $it") }
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUrl = json.optString("verification_url").ifEmpty { "https://www.google.com/device" },
            intervalSeconds = json.optInt("interval", 5).coerceAtLeast(1),
            expiresAtMillis = System.currentTimeMillis() + json.optInt("expires_in", 1800) * 1000L,
        )
    }

    /** Step 2: poll until the user approved (or denied) the sign-in. */
    fun poll(code: DeviceCode): PollResult {
        if (System.currentTimeMillis() > code.expiresAtMillis) return PollResult.Failed("Code expired")
        val c = client()
        val body = FormBody.Builder()
            .add("client_id", c.id)
            .add("client_secret", c.secret)
            .add("code", code.deviceCode)
            .add("grant_type", "http://oauth.net/grant_type/device/1.0")
            .build()
        val json = post(TOKEN_URL, body)
        return when (val error = json.optString("error")) {
            "" -> {
                saveTokens(json)
                if (store.refreshToken == null) PollResult.Failed("No refresh token returned") else PollResult.Success
            }
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Failed("Sign-in was denied")
            "expired_token" -> PollResult.Failed("Code expired")
            else -> PollResult.Failed(error)
        }
    }

    /** A valid access token, refreshed when needed; null when signed out. */
    @Synchronized
    fun accessToken(forceRefresh: Boolean = false): String? {
        val refresh = store.refreshToken ?: return null
        val cached = store.accessToken
        if (!forceRefresh && cached != null && System.currentTimeMillis() < store.accessTokenExpiry - 60_000) {
            return cached
        }
        val c = client()
        val body = FormBody.Builder()
            .add("client_id", c.id)
            .add("client_secret", c.secret)
            .add("refresh_token", refresh)
            .add("grant_type", "refresh_token")
            .build()
        val json = post(TOKEN_URL, body)
        when (json.optString("error")) {
            "" -> saveTokens(json)
            // Token revoked (user removed access at myaccount.google.com) -> signed out.
            "invalid_grant", "unauthorized_client" -> { signOut(); return null }
            else -> throw IOException("Token refresh failed: ${json.optString("error")}")
        }
        return store.accessToken
    }

    fun signOut() {
        store.refreshToken = null
        store.accessToken = null
        store.accessTokenExpiry = 0
    }

    private fun saveTokens(json: JSONObject) {
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { store.refreshToken = it }
        json.optString("access_token").takeIf { it.isNotEmpty() }?.let { store.accessToken = it }
        store.accessTokenExpiry = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
    }

    private fun post(url: String, body: FormBody): JSONObject {
        val request = Request.Builder().url(url).post(body)
            .header("User-Agent", TV_USER_AGENT)
            .header("Origin", "https://www.youtube.com")
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return runCatching { JSONObject(text) }.getOrElse {
                throw IOException("HTTP ${response.code} from $url")
            }
        }
    }

    /**
     * The TV app's client id/secret. Read from the live TV web app when possible so a rotation
     * on Google's side doesn't break sign-in, with the long-standing values as fallback.
     */
    private fun client(): Client {
        client?.let { return it }
        val fetched = runCatching { fetchClientFromTvApp() }.getOrNull()
        return (fetched ?: Client(FALLBACK_CLIENT_ID, FALLBACK_CLIENT_SECRET)).also { client = it }
    }

    private fun fetchClientFromTvApp(): Client? {
        val html = get("https://www.youtube.com/tv") ?: return null
        val src = Regex("""<script[^>]+id="base-js"[^>]+src="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""src="([^"]*/base\.js)"""").find(html)?.groupValues?.get(1)
            ?: return null
        val js = get(if (src.startsWith("http")) src else "https://www.youtube.com$src") ?: return null
        val m = Regex("""clientId:"([^"]+\.apps\.googleusercontent\.com)",[^"]*?:"([^"]+)"""").find(js) ?: return null
        return Client(m.groupValues[1], m.groupValues[2])
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", TV_USER_AGENT).build()
        return http.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
    }

    companion object {
        const val SCOPE = "http://gdata.youtube.com https://www.googleapis.com/auth/youtube"
        const val DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code"
        const val TOKEN_URL = "https://www.youtube.com/o/oauth2/token"
        const val TV_USER_AGENT =
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private const val FALLBACK_CLIENT_ID =
            "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com"
        private const val FALLBACK_CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT"
    }
}
