package dev.ytplus

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.ytplus.data.auth.TokenStore
import dev.ytplus.data.auth.TvOAuth
import dev.ytplus.data.innertube.InnerTube
import dev.ytplus.data.sponsorblock.SponsorBlock
import dev.ytplus.data.stats.WatchStats
import dev.ytplus.player.PlayerController
import dev.ytplus.streams.NewPipeDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        NewPipe.init(
            NewPipeDownloader(Graph.http),
            Localization.fromLocale(Locale.getDefault()),
            ContentCountry(Locale.getDefault().country.ifEmpty { "US" }),
        )
        PlayerController.init(this)
    }
}

/** Tiny manual DI container. */
object Graph {
    lateinit var prefs: Prefs
        private set

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val auth by lazy { TvOAuth(http, prefs) }
    val api by lazy { InnerTube(http, auth) }
    val stats by lazy { WatchStats(http, auth) }
    val sponsorBlock by lazy { SponsorBlock(http) }

    /** Observable sign-in state for the UI. */
    val signedIn = MutableStateFlow(false)

    fun init(context: Context) {
        prefs = Prefs(context.getSharedPreferences("ytplus", Context.MODE_PRIVATE))
        signedIn.value = auth.isSignedIn
    }

    fun signOut() {
        auth.signOut()
        signedIn.value = false
    }
}

/** App settings plus OAuth tokens, kept in app-private storage. */
class Prefs(private val sp: SharedPreferences) : TokenStore {
    override var refreshToken: String?
        get() = sp.getString("refresh_token", null)
        set(v) = sp.edit().putString("refresh_token", v).apply()
    override var accessToken: String?
        get() = sp.getString("access_token", null)
        set(v) = sp.edit().putString("access_token", v).apply()
    override var accessTokenExpiry: Long
        get() = sp.getLong("access_token_expiry", 0)
        set(v) = sp.edit().putLong("access_token_expiry", v).apply()

    var maxQuality: Int
        get() = sp.getInt("max_quality", 1080)
        set(v) = sp.edit().putInt("max_quality", v).apply()
    var sponsorBlockEnabled: Boolean
        get() = sp.getBoolean("sb_enabled", true)
        set(v) = sp.edit().putBoolean("sb_enabled", v).apply()
    var sponsorBlockCategories: Set<String>
        get() = sp.getStringSet("sb_categories", null) ?: SponsorBlock.DEFAULT_CATEGORIES
        set(v) = sp.edit().putStringSet("sb_categories", v).apply()
    var backgroundPlay: Boolean
        get() = sp.getBoolean("background_play", true)
        set(v) = sp.edit().putBoolean("background_play", v).apply()
    var autoPip: Boolean
        get() = sp.getBoolean("auto_pip", true)
        set(v) = sp.edit().putBoolean("auto_pip", v).apply()
    var skippedSignIn: Boolean
        get() = sp.getBoolean("skipped_sign_in", false)
        set(v) = sp.edit().putBoolean("skipped_sign_in", v).apply()
}
