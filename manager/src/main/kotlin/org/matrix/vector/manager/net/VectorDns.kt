package org.matrix.vector.manager.net
import org.matrix.vector.manager.Constants

import android.util.Log
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.matrix.vector.manager.data.repository.SettingsRepository

/**
 * Name resolution: DNS over HTTPS when it helps, the system resolver when it does not.
 *
 * DoH exists here for users whose network will not resolve the module repository or GitHub over
 * plain DNS. But it used to be **all or nothing** — with the setting on, every lookup went through
 * `cloudflare-dns.com` and a failure was final. On a network where Cloudflare itself is blocked,
 * which is precisely the kind of network the setting is meant for, that meant the module list, the
 * activity feed and every avatar failed together and the app was unusable until the switch was
 * found and turned off by hand. This is the single largest cause of an empty Store.
 *
 * So DoH is now **best-effort**:
 * - a failed DoH lookup falls through to the system resolver rather than failing the request;
 * - the first failure latches for the session, so the timeout is paid once and not per lookup;
 * - the DoH client's own timeouts are short, so that one payment is a few seconds, not fifteen;
 * - a configured HTTP proxy disables DoH entirely, because the proxy is doing the resolving and
 *   bootstrap IPs are meaningless to it.
 *
 * The setting is read **per lookup** rather than baked into the client at construction. OkHttp
 * cannot have its DNS swapped on a live client, and rebuilding the shared client would drop the
 * connection pool and orphan the disk cache — so the switch has to be readable from in here to take
 * effect at all. Previously it only applied to clients built after it was toggled, which in
 * practice meant "after the next process start".
 */
class VectorDns(private val settings: SettingsRepository, bootstrapClient: OkHttpClient) : Dns {

    private val endpoint = "https://cloudflare-dns.com/dns-query".toHttpUrl()

    /**
     * Latched once the DoH endpoint proves unreachable.
     *
     * Volatile rather than synchronised: two threads racing to set it to true is harmless, and
     * lookups happen on every OkHttp dispatcher thread.
     */
    @Volatile private var dohUnavailable = false

    private val doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(
                bootstrapClient
                    .newBuilder()
                    // Fail fast. The default connect timeout is long enough that a blocked
                    // endpoint reads as a hung app rather than as a fallback about to happen.
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .callTimeout(5, TimeUnit.SECONDS)
                    .build()
            )
            .url(endpoint)
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("2606:4700:4700::1001"),
            )
            .includeIPv6(true)
            .build()
    }

    /** True when nothing is proxying our traffic, which is the only case where DoH is ours to do. */
    private val direct: Boolean by lazy {
        runCatching {
                ProxySelector.getDefault().select(endpoint.toUri()).firstOrNull() == Proxy.NO_PROXY
            }
            .getOrDefault(true)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (settings.dohEnabled.value && direct && !dohUnavailable) {
            try {
                return doh.lookup(hostname)
            } catch (e: UnknownHostException) {
                dohUnavailable = true
                Log.w(
                    Constants.TAG,
                    "dns: DoH lookup of $hostname failed, using the system resolver for this session",
                    e,
                )
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

}
