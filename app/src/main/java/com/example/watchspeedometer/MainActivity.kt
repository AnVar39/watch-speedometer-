package com.example.watchspeedometer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Text
import com.google.android.gms.location.*
import kotlinx.coroutines.launch

val Context.dataStore by preferencesDataStore(name = "settings")

enum class SpeedUnit(val label: String, val factor: Float) {
    KMH("км/ч", 3.6f),
    MPH("миль/ч", 2.23694f),
    KNOTS("узлов", 1.94384f)
}

class MainActivity : ComponentActivity() {

    private val KEY_UNIT = stringPreferencesKey("speed_unit")
    private lateinit var fusedClient: FusedLocationProviderClient

    private val speedState = mutableStateOf(0f)
    private val unitState = mutableStateOf(SpeedUnit.KMH)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val s = result.lastLocation?.speed ?: 0f
            speedState.value = if (s >= 0f) s else 0f
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startGps()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        lifecycleScope.launch {
            dataStore.data.collect { prefs ->
                val name = prefs[KEY_UNIT] ?: SpeedUnit.KMH.name
                unitState.value = SpeedUnit.valueOf(name)
            }
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) startGps()
        else permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)

        setContent {
            val speed = speedState.value
            val unit = unitState.value
            val context = LocalContext.current

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (unit == SpeedUnit.KNOTS)
                            "%.1f".format(speed * unit.factor)
                        else "%.0f".format(speed * unit.factor),
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = unit.label,
                        fontSize = 16.sp,
                        color = Color.LightGray,
                        modifier = Modifier
                            .clickable {
                                val next = when (unit) {
                                    SpeedUnit.KMH -> SpeedUnit.MPH
                                    SpeedUnit.MPH -> SpeedUnit.KNOTS
                                    SpeedUnit.KNOTS -> SpeedUnit.KMH
                                }
                                unitState.value = next
                                lifecycleScope.launch {
                                    context.dataStore.edit { it[KEY_UNIT] = next.name }
                                }
                            }
                            .padding(8.dp)
                    )
                }
            }
        }
    }

    private fun startGps() {
        val request = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 2000L
        ).build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // нет разрешения
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fusedClient.removeLocationUpdates(locationCallback)
    }
}
