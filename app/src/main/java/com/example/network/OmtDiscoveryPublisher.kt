package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * Publishes the OMT stream on the local network using mDNS / DNS-SD (Bonjour).
 * Compatible with OBS OMT plugin, vMix, and desktop OMT discovery tools.
 */
class OmtDiscoveryPublisher(private val context: Context) {
    companion object {
        private const val TAG = "OmtDiscoveryPublisher"
        // Standard OMT service type
        const val SERVICE_TYPE_OMT = "_omt._tcp."
    }

    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredServiceName: String? = null

    fun registerService(serviceName: String, port: Int) {
        unregisterService()

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
                Log.i(TAG, "OMT mDNS Service registered successfully: ${info.serviceName} on port $port")
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
        val listener = registrationListener ?: return
        try {
            nsdManager?.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering service: ${e.message}")
        } finally {
            registrationListener = null
            registeredServiceName = null
        }
    }
}
