package com.marcorx6.library.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNetworkPolicyTest {
    @Test
    fun allNetworkAllowsAnyConnectedTransport() {
        assertTrue(DownloadNetworkPolicy.canStart(wifiOnly = false, activeNetworkIsWifi = false))
        assertTrue(DownloadNetworkPolicy.canStart(wifiOnly = false, activeNetworkIsWifi = true))
    }

    @Test
    fun wifiOnlyRejectsMobileAndAllowsWifi() {
        assertFalse(DownloadNetworkPolicy.canStart(wifiOnly = true, activeNetworkIsWifi = false))
        assertTrue(DownloadNetworkPolicy.canStart(wifiOnly = true, activeNetworkIsWifi = true))
    }
}
