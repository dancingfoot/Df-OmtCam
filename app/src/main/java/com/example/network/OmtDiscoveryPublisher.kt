package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Publishes the OMT stream on the local network using Android NsdManager (mDNS / DNS-SD).
 *
 * Strictly follows libomtnet / opencamera-omt discovery rules:
 * - Service type: `_omt._tcp`
 * - Instance name MUST contain parentheses in the format `HOST (SOURCE)`, e.g. `24117RN76E (Camera)`
 *   so conforming OMT receivers (OBS OMT plugin, vMix, OMT Viewer) parse it properly.
 * - Acquires WifiManager.MulticastLock while active.
 */
class OmtDiscoveryPublisher(private val context: Context) {
    companion object {
        private const val TAG = "OmtDiscoveryPublisher"
        const val SERVICE_TYPE_OMT = "_omt._tcp"
        private const val MAX_MDNS_INSTANCE_BYTES = 63
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredServiceName: String? = null

    fun buildOmtInstanceName(sourceName: String): String {
        val rawHost = (Build.MODEL ?: "Android")
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifEmpty { "Android" }
        val cleanSource = sourceName
            .replace("(", "")
            .replace(")", "")
            .trim()
            .ifEmpty { "Camera" }

        val candidate = "$rawHost ($cleanSource)"
        if (candidate.toByteArray(Charsets.UTF_8).size <= MAX_MDNS_INSTANCE_BYTES) {
            return candidate
        }
        val maxHostLen = (MAX_MDNS_INSTANCE_BYTES - cleanSource.length - 3).coerceAtLeast(8)
        val trimmedHost = rawHost.take(maxHostLen)
        return "$trimmedHost ($cleanSource)".take(MAX_MDNS_INSTANCE_BYTES)
    }

    fun getLocalWifiIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ""
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: ""
                    }
                }
            }
        } catch (_: Exception) {}
        return ""
    }

    fun registerService(sourceName: String, port: Int): String {
        unregisterService()

        val omtInstanceName = buildOmtInstanceName(sourceName)

        try {
            multicastLock = wifiManager?.createMulticastLock("OmtMdnsMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.i(TAG, "MulticastLock held=${multicastLock?.isHeld}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = omtInstanceName
            this.serviceType = SERVICE_TYPE_OMT
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.i(TAG, "OMT mDNS registered: '${info.serviceName}' ($SERVICE_TYPE_OMT) port=$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "OMT mDNS registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i(TAG, "OMT mDNS unregistered: ${arg0.serviceName}")
                registeredServiceName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "OMT mDNS unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register OMT mDNS service", e)
        }

        return omtInstanceName
    }

    fun unregisterService() {
        val listener = registrationListener
        if (listener != null) {
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering mDNS service: ${e.message}")
            } finally {
                registrationListener = null
                registeredServiceName = null
            }
        }

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null
    }
}
