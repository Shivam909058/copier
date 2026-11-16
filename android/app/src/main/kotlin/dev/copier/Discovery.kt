package dev.copier

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import dev.copier.proto.Proto
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executor

/** Finds Macs advertising `_copier._tcp` through the platform mDNS stack. Callbacks run on NSD's thread. */
class Discovery(
    context: Context,
    private val onFound: (MacService) -> Unit,
    private val onLost: (serviceName: String) -> Unit = {},
) {
    class MacService(val name: String, val id: String?, val host: String, val port: Int)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null

    // Before API 34, resolveService fails with FAILURE_ALREADY_ACTIVE if called concurrently.
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) = onLost(info.serviceName)
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start: $errorCode")
                synchronized(this@Discovery) { listener = null }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = l
        nsd.discoverServices(Proto.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    fun stop() {
        listener?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "discovery was not running", e)
            }
        }
        listener = null
        pending.clear()
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            resolveModern(info)
            return
        }
        pending.addLast(info)
        if (!resolving) resolveNextLegacy()
    }

    private fun resolveModern(info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT < 34) return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(resolved: NsdServiceInfo) {
                nsd.unregisterServiceInfoCallback(this)
                val host = resolved.hostAddresses.firstOrNull { it is Inet4Address } ?: resolved.hostAddresses.firstOrNull()
                if (host != null) report(resolved, host)
            }
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.w(TAG, "mDNS resolve failed: $errorCode")
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        nsd.registerServiceInfoCallback(info, Executor(Runnable::run), callback)
    }

    @Synchronized
    private fun resolveNextLegacy() {
        val info = pending.removeFirstOrNull()
        resolving = info != null
        if (info == null) return
        @Suppress("DEPRECATION") // Replaced by registerServiceInfoCallback on API 34+ (see resolveModern).
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS resolve failed: $errorCode")
                    resolveNextLegacy()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    serviceInfo.host?.let { report(serviceInfo, it) }
                    resolveNextLegacy()
                }
            },
        )
    }

    private fun report(info: NsdServiceInfo, host: InetAddress) {
        val address = host.hostAddress ?: return
        onFound(MacService(info.serviceName, info.attributes["id"]?.toString(Charsets.UTF_8), address, info.port))
    }
}
