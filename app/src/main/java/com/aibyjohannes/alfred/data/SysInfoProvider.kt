package com.aibyjohannes.alfred.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Provides a system-info block (datetime + coarse location) for injection into the AI system prompt.
 * Location access is optional — degrades gracefully when permission is missing.
 */
class SysInfoProvider(private val context: Context) {

    fun buildSysInfo(): String {
        val datetime = currentDatetime()
        val location = coarseLocation()
        return buildString {
            appendLine("Current datetime: $datetime")
            if (location != null) {
                appendLine("Approximate location: $location")
            }
        }.trimEnd()
    }

    private fun currentDatetime(): String {
        val now = ZonedDateTime.now()
        val formatted = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val zone = now.zone.id
        return "$formatted ($zone)"
    }

    private fun coarseLocation(): String? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return null

        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: android.location.Location? = null
            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
            val loc = bestLocation ?: return null

            val geocoder = Geocoder(context, Locale.ENGLISH)
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
            val address = addresses?.firstOrNull()
            when {
                address?.locality != null && address.countryName != null ->
                    "${address.locality}, ${address.countryName}"
                address?.countryName != null -> address.countryName
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
