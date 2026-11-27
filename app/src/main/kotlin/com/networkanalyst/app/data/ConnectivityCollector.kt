package com.networkanalyst.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.PowerManager
import com.networkanalyst.core.model.ConnectionInfo
import com.networkanalyst.core.model.Transport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.Inet6Address

/**
 * Tracks how the phone is connected.
 *
 * Two networks matter. The *default* network is what apps use, and it is the VPN when one is
 * active. The *Wi-Fi* network is the physical link underneath, which is what the router and
 * the other devices in the home are reached through. Both are followed with callbacks so the
 * current state is always at hand without polling.
 */
class ConnectivityCollector(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val power = appContext.getSystemService(PowerManager::class.java)

    private class Tracked(val network: Network, val capabilities: NetworkCapabilities?, val link: LinkProperties?)

    @Volatile private var defaultNetwork: Tracked? = null
    @Volatile private var wifi: Tracked? = null

    private val _connection = MutableStateFlow(ConnectionInfo())
    /** Current connection, updated whenever the network changes. */
    val connection: StateFlow<ConnectionInfo> = _connection.asStateFlow()

    private val defaultCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            defaultNetwork = Tracked(network, capabilities, defaultNetwork?.takeIf { it.network == network }?.link)
            publish()
        }

        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
            defaultNetwork = Tracked(network, defaultNetwork?.takeIf { it.network == network }?.capabilities, link)
            publish()
        }

        override fun onLost(network: Network) {
            if (defaultNetwork?.network == network) defaultNetwork = null
            publish()
        }
    }

    private val wifiCallback: ConnectivityManager.NetworkCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Asking for location info makes the Wi-Fi name and access point address visible in
        // the delivered capabilities when the app holds the location permission.
        object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = wifiCapabilities(network, capabilities)
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = wifiLink(network, link)
            override fun onLost(network: Network) = wifiLost(network)
        }
    } else {
        object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = wifiCapabilities(network, capabilities)
            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = wifiLink(network, link)
            override fun onLost(network: Network) = wifiLost(network)
        }
    }

    private fun wifiCapabilities(network: Network, capabilities: NetworkCapabilities) {
        wifi = Tracked(network, capabilities, wifi?.takeIf { it.network == network }?.link)
        publish()
    }

    private fun wifiLink(network: Network, link: LinkProperties) {
        wifi = Tracked(network, wifi?.takeIf { it.network == network }?.capabilities, link)
        publish()
    }

    private fun wifiLost(network: Network) {
        if (wifi?.network == network) wifi = null
        publish()
    }

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        try {
            connectivity.registerDefaultNetworkCallback(defaultCallback)
            // The default request excludes VPNs, so this follows the real Wi-Fi network even
            // while a VPN is the default route.
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity.registerNetworkCallback(request, wifiCallback)
        } catch (_: RuntimeException) {
            // Too many callbacks registered or a vendor quirk: fall back to on-demand reads.
        }
        publish()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        runCatching { connectivity.unregisterNetworkCallback(defaultCallback) }
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
    }

    /** The network apps currently use (the VPN, if one is up). */
    fun defaultNetwork(): Network? = defaultNetwork?.network ?: connectivity.activeNetwork

    /** The physical Wi-Fi network, or null when not on Wi-Fi. */
    fun wifiNetwork(): Network? {
        wifi?.network?.let { return it }
        val active = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return null
        val plainWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return active.takeIf { plainWifi }
    }

    /**
     * Wi-Fi details as last delivered with the network's capabilities. On Android 12+ this
     * copy carries the network name and access point address (given location permission).
     */
    fun wifiInfoFromCapabilities(): WifiInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return wifi?.capabilities?.transportInfo as? WifiInfo
    }

    /** Builds a fresh description of the connection from the latest known state. */
    fun snapshot(): ConnectionInfo {
        val default = defaultNetwork ?: connectivity.activeNetwork?.let { network ->
            Tracked(network, connectivity.getNetworkCapabilities(network), connectivity.getLinkProperties(network))
        }
        val defaultCaps = default?.capabilities ?: return ConnectionInfo(batterySaverOn = power?.isPowerSaveMode == true)

        val isVpn = defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val wifiNetwork = wifi ?: wifiNetwork()?.let { network ->
            Tracked(network, connectivity.getNetworkCapabilities(network), connectivity.getLinkProperties(network))
        }
        val onWifi = wifiNetwork != null && (defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || !isVpn && default.network == wifiNetwork.network)
        val transport = when {
            onWifi -> Transport.WIFI
            defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            defaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            else -> Transport.OTHER
        }
        // Addresses, gateway and DNS describe the physical link when there is one, because
        // that is what reaches the router; a VPN's own addresses are of no use for that.
        val physical = if (onWifi) wifiNetwork else default
        val link = physical.link ?: connectivity.getLinkProperties(physical.network)
        val physicalCaps = physical.capabilities ?: defaultCaps

        val addresses = link?.linkAddresses.orEmpty()
        val defaultRoutes = link?.routes.orEmpty().filter { it.isDefaultRoute }
        return ConnectionInfo(
            transport = transport,
            isVpn = isVpn,
            isMetered = !defaultCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isValidated = defaultCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            hasCaptivePortal = defaultCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            interfaceName = link?.interfaceName,
            ipv4Addresses = addresses.filter { it.address is Inet4Address }.map { "${it.address.hostAddress}/${it.prefixLength}" },
            ipv6Addresses = addresses.filter { it.address is Inet6Address }.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" },
            gatewayV4 = defaultRoutes.firstNotNullOfOrNull { (it.gateway as? Inet4Address)?.takeIf { g -> !g.isAnyLocalAddress }?.hostAddress },
            gatewayV6 = defaultRoutes.firstNotNullOfOrNull { (it.gateway as? Inet6Address)?.takeIf { g -> !g.isAnyLocalAddress }?.hostAddress?.substringBefore('%') },
            dnsServers = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') },
            privateDnsActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && link?.isPrivateDnsActive == true,
            privateDnsServerName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) link?.privateDnsServerName else null,
            mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) link?.mtu?.takeIf { it > 0 } else null,
            httpProxy = link?.httpProxy?.let { proxy -> proxy.host?.takeIf { it.isNotBlank() }?.let { "$it:${proxy.port}" } },
            downstreamKbps = physicalCaps.linkDownstreamBandwidthKbps.takeIf { it > 0 },
            upstreamKbps = physicalCaps.linkUpstreamBandwidthKbps.takeIf { it > 0 },
            dataSaverOn = connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            batterySaverOn = power?.isPowerSaveMode == true,
        )
    }

    private fun publish() {
        _connection.value = runCatching { snapshot() }.getOrDefault(ConnectionInfo())
    }
}
