package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Publishes the OMT stream on the local network using mDNS / DNS-SD (Bonjour).
 * Strictly adheres to `opencamera-omt` and libomt specification:
 * - Acquires Android WifiManager.MulticastLock so multicast packets pass through WiFi sleep/filtering
 * - Registers service type `_omt._tcp` (no trailing dot for Android NsdManager standard compliance)
 * - Publishes standard TXT records: version, codec, name
 */
class OmtDiscoveryPublisher(private val context: Context) {
    companion object {
        private const val TAG = "OmtDiscoveryPublisher"
        // Standard OMT service type expected by vMix, OBS OMT plugin, and OMT receiver
        const val SERVICE_TYPE_OMT = "_omt._tcp"
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

    fun registerService(serviceName: String, port: Int) {
        unregisterService()

        // Acquire MulticastLock to ensure mDNS broadcast packets are transmitted and received over WiFi
        try {
            multicastLock = wifiManager?.createMulticastLock("OmtMdnsMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.i(TAG, "MulticastLock acquired: ${multicastLock?.isHeld}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire MulticastLock: ${e.message}")
        }

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE_OMT
            this.port = port
            // Set attributes for OMT resolution
            setAttribute("version", "1.0")
            setAttribute("codec", "h264")
            setAttribute("name", serviceName)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                Log.i(TAG, "OMT mDNS Service registered successfully: ${info.serviceName} (${info.serviceType}) on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "OMT mDNS Registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i(TAG, "OMT mDNS Service unregistered: ${arg0.serviceName}")
                registeredServiceName = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "OMT mDNS Unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register OMT mDNS service", e)
        }
    }

    fun unregisterService() {
        val listener = registrationListener
        if (listener != null) {
            try {
                nsdManager?.unregisterService(listener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering service: ${e.message}")
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
