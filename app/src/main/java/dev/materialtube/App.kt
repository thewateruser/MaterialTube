package dev.materialtube

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.materialtube.data.net.FallbackDns
import android.content.Context
import android.content.SharedPreferences
import dev.materialtube.data.auth.TokenStore
import dev.materialtube.data.auth.TvOAuth
import dev.materialtube.data.innertube.InnerTube
import dev.materialtube.data.sponsorblock.SponsorBlock
import dev.materialtube.data.stats.WatchStats
import dev.materialtube.player.PlayerController
import dev.materialtube.streams.NewPipeDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

class App : Application(), ImageLoaderFactory {
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

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient { Graph.http }.crossfade(true).build()
}

/** Tiny manual DI container. */
object Graph {
    lateinit var prefs: Prefs
        private set

    /** Shared by the API, NewPipe, the player and image loading, so all get the DNS fallback. */
    val http: OkHttpClient by lazy { FallbackDns.client() }
    val auth by lazy { TvOAuth(http, prefs) }
    val api by lazy { InnerTube(http, auth) }
    val music by lazy { dev.materialtube.data.innertube.MusicApi(api) }
    val stats by lazy { WatchStats(http, auth) }
    val sponsorBlock by lazy { SponsorBlock(http) }

    /** Observable sign-in state for the UI. */
    val signedIn = MutableStateFlow(false)

    fun init(context: Context) {
        prefs = Prefs(context.getSharedPreferences("materialtube", Context.MODE_PRIVATE))
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
