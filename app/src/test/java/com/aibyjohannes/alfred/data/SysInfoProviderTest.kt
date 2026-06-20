package com.aibyjohannes.alfred.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Locale

class SysInfoProviderTest {

    private val context = mockk<Context>()
    private val locationManager = mockk<LocationManager>()

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
        every { context.getSystemService(Context.LOCATION_SERVICE) } returns locationManager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `buildSysInfo without location permission returns only datetime`() {
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) } returns PackageManager.PERMISSION_DENIED

        val provider = SysInfoProvider(context)
        val info = provider.buildSysInfo()

        assertTrue(info.contains("Current datetime:"))
        assertFalse(info.contains("Approximate location:"))
    }

    @Test
    fun `buildSysInfo with permission but no location providers returns only datetime`() {
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { locationManager.getProviders(true) } returns emptyList()

        val provider = SysInfoProvider(context)
        val info = provider.buildSysInfo()

        assertTrue(info.contains("Current datetime:"))
        assertFalse(info.contains("Approximate location:"))
    }

    @Test
    fun `buildSysInfo with permission and location but geocoder fails returns only datetime`() {
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { locationManager.getProviders(true) } returns listOf("gps")
        val mockLocation = mockk<Location>()
        every { mockLocation.accuracy } returns 10f
        every { mockLocation.latitude } returns 37.7749
        every { mockLocation.longitude } returns -122.4194
        every { locationManager.getLastKnownLocation("gps") } returns mockLocation

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(any(), any(), any()) } throws RuntimeException("Geocoder failed")

        val provider = SysInfoProvider(context)
        val info = provider.buildSysInfo()

        assertTrue(info.contains("Current datetime:"))
        assertFalse(info.contains("Approximate location:"))
    }

    @Test
    fun `buildSysInfo with permission location and geocoder returns address locality and country`() {
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { locationManager.getProviders(true) } returns listOf("gps")
        val mockLocation = mockk<Location>()
        every { mockLocation.accuracy } returns 5f
        every { mockLocation.latitude } returns 37.7749
        every { mockLocation.longitude } returns -122.4194
        every { locationManager.getLastKnownLocation("gps") } returns mockLocation

        val mockAddress = mockk<Address>()
        every { mockAddress.locality } returns "San Francisco"
        every { mockAddress.countryName } returns "United States"

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(37.7749, -122.4194, 1) } returns listOf(mockAddress)

        val provider = SysInfoProvider(context)
        val info = provider.buildSysInfo()

        assertTrue(info.contains("Current datetime:"))
        assertTrue(info.contains("Approximate location: San Francisco, United States"))
    }

    @Test
    fun `buildSysInfo with permission location and geocoder returns only country if locality null`() {
        every { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) } returns PackageManager.PERMISSION_GRANTED
        every { locationManager.getProviders(true) } returns listOf("network")
        val mockLocation = mockk<Location>()
        every { mockLocation.accuracy } returns 100f
        every { mockLocation.latitude } returns 37.7749
        every { mockLocation.longitude } returns -122.4194
        every { locationManager.getLastKnownLocation("network") } returns mockLocation

        val mockAddress = mockk<Address>()
        every { mockAddress.locality } returns null
        every { mockAddress.countryName } returns "United States"

        mockkConstructor(Geocoder::class)
        every { anyConstructed<Geocoder>().getFromLocation(37.7749, -122.4194, 1) } returns listOf(mockAddress)

        val provider = SysInfoProvider(context)
        val info = provider.buildSysInfo()

        assertTrue(info.contains("Current datetime:"))
        assertTrue(info.contains("Approximate location: United States"))
    }
}
