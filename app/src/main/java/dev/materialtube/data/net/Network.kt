package dev.materialtube.data.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * System DNS first, then DNS-over-HTTPS (Cloudflare, then Google) when the device's resolver
 * can't resolve a host. Fixes "Unable to resolve host www.youtube.com" on networks or Private DNS
 * setups that block or break YouTube's domains.
 */
class FallbackDns(bootstrap: OkHttpClient) : Dns {

    private val resolvers: List<Dns> = listOf(
        doh(bootstrap, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"),
        doh(bootstrap, "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888"),
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val systemError = try {
            val result = Dns.SYSTEM.lookup(hostname)
            if (result.isNotEmpty()) return result
            null
        } catch (e: UnknownHostException) {
            e
        }
        for (resolver in resolvers) {
            try {
                val result = resolver.lookup(hostname)
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {
                // try the next resolver
            }
        }
        throw systemError ?: UnknownHostException(hostname)
    }

    private fun doh(client: OkHttpClient, url: String, vararg bootstrapIps: String): Dns =
        DnsOverHttps.Builder()
            .client(client)
            .url(url.toHttpUrl())
            .bootstrapDnsHosts(bootstrapIps.map { InetAddress.getByName(it) })
            .includeIPv6(true)
            .build()

    companion object {
        /** The app-wide HTTP client: browser-like timeouts plus the DNS fallback. */
        fun client(): OkHttpClient {
            val bootstrap = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            return bootstrap.newBuilder()
                .dns(FallbackDns(bootstrap))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}

/** True for the "can't reach this host at all" class of failures, where trying another host helps. */
internal fun Throwable.isUnreachable(): Boolean =
    this is UnknownHostException || this is java.net.ConnectException || this is java.net.NoRouteToHostException ||
        this is javax.net.ssl.SSLHandshakeException || (this is java.net.SocketTimeoutException && message?.contains("connect") == true)
