package app.onetouch

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * mDNS via Android's NsdManager. Discovery runs only on demand (a few
 * seconds when we need a desktop) to save battery; registration is passive:
 * the system responder answers queries for us.
 */
class Discovery(private val ctx: Context) {
    private val mgr = ctx.getSystemService(NsdManager::class.java)
    private val resolveLock = Mutex()
    private var registration: NsdManager.RegistrationListener? = null

    /** Announces this phone so desktops can send it offers. */
    fun register(port: Int, id: String, name: String) {
        unregister()
        val info = NsdServiceInfo().apply {
            serviceName = "$name-${id.take(6)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("v", "2")
            setAttribute("id", id)
            setAttribute("name", name)
            setAttribute("os", "android")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {}
        }
        runCatching { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); registration = l }
    }

    fun unregister() {
        registration?.let { runCatching { mgr.unregisterService(it) } }
        registration = null
    }

    /**
     * Looks for desktop nodes for up to [timeoutMs]. Returns early once the
     * desktop with [preferId] (or, if null, any desktop) has been resolved.
     */
    suspend fun findDesktops(selfId: String, timeoutMs: Long, preferId: String?): List<Peer> {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi.createMulticastLock("onetouch").apply { setReferenceCounted(false); acquire() }
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, err: Int) { found.close() }
            override fun onStopDiscoveryFailed(type: String, err: Int) {}
        }
        val peers = mutableListOf<Peer>()
        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) {
                for (s in found) {
                    val p = resolve(s)?.let(::toPeer) ?: continue
                    if (p.id == selfId || p.fp.isEmpty() || peers.any { it.id == p.id }) continue
                    peers += p
                    if (preferId == null || p.id == preferId) break
                }
            }
        } finally {
            runCatching { mgr.stopServiceDiscovery(listener) }
            lock.release()
        }
        return peers
    }

    @Suppress("DEPRECATION") // resolveService works on all versions we support
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = resolveLock.withLock {
        withTimeoutOrNull(3000) {
            suspendCancellableCoroutine { cont ->
                mgr.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(s: NsdServiceInfo) { if (cont.isActive) cont.resume(s) }
                    override fun onResolveFailed(s: NsdServiceInfo, err: Int) { if (cont.isActive) cont.resume(null) }
                })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun toPeer(s: NsdServiceInfo): Peer? {
        val attrs = s.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) ?: "" }
        val id = attrs["id"] ?: return null
        val addr = if (Build.VERSION.SDK_INT >= 34) {
            s.hostAddresses.firstOrNull { it is Inet4Address } ?: s.hostAddresses.firstOrNull()
        } else {
            s.host
        } ?: return null
        return Peer(
            id = id,
            name = attrs["name"] ?: s.serviceName,
            os = attrs["os"] ?: "",
            fp = attrs["fp"] ?: "",
            host = addr.hostAddress ?: return null,
            port = s.port,
        )
    }
}
