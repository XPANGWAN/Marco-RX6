package com.marcorx6.library

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.Configuration
import com.marcorx6.library.data.DownloadWorkScheduler
import java.util.concurrent.Executors

/** Gives WorkManager enough bounded worker threads for the user's download concurrency setting. */
class MarcoApplication : Application(), Configuration.Provider {
    private val workExecutor by lazy { Executors.newFixedThreadPool(5) }
    private var downloadNetworkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.install(this)
        AppDiagnostics.log("INFO", "Application", "Process started")
        registerDownloadNetworkCallback()
        // Do not initialize WebView/WorkManager filter components during process startup.
        // The browser initializes the filter lazily when the user actually opens it.
    }

    private fun registerDownloadNetworkCallback() {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    DownloadWorkScheduler.schedule(this@MarcoApplication)
                }
            }
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(callback)
            downloadNetworkCallback = callback
        }.onFailure {
            AppDiagnostics.log("WARN", "DownloadNetwork", "Could not register Wi-Fi availability callback")
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(workExecutor)
            .setMaxSchedulerLimit(20)
            .build()
}
