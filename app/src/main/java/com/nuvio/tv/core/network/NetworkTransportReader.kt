package com.nuvio.tv.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.nuvio.tv.core.assessment.NetworkTransport

/** The active network's link type, for the per-service network test (Spec 33 D3). */
object NetworkTransportReader {

    /** OTHER when there is no active network or the capabilities cannot be read. */
    fun current(context: Context): NetworkTransport {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkTransport.OTHER
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
            ?: return NetworkTransport.OTHER
        return transportOf(
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        )
    }

    /**
     * Spec 70 R3: the device has a network that claims internet (none = Wi-Fi down, waking from sleep).
     * Unreadable state counts as online: the addon guard then counts the failure, as before it asked.
     */
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return runCatching {
            val network = cm.activeNetwork ?: return@runCatching false
            cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
        }.getOrDefault(true)
    }

    /** Ethernet wins when both are reported (a box bridging Wi-Fi still streams over the cable). */
    internal fun transportOf(ethernet: Boolean, wifi: Boolean): NetworkTransport = when {
        ethernet -> NetworkTransport.ETHERNET
        wifi -> NetworkTransport.WIFI
        else -> NetworkTransport.OTHER
    }
}
