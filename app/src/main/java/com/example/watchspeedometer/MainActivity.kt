package com.example.watchspeedometer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch
import java.util.Locale

val Context.dataStore by preferencesDataStore(name = "settings")

enum class SpeedUnit(val label: String, val factor: Float) {
    KMH("км/ч", 3.6f),
    MPH("миль/ч", 2.23694f),
    KNOTS("узлов", 1.94384f)
}

/** Палитра цветов цифр. Тап по цифрам — следующий цвет. */
val DigitColors = listOf(
    Color.White,
    Color(0xFF00FF66), // зелёный
    Color(0xFFFFEB3B), // жёлтый
    Color(0xFFFF9800), // оранжевый
    Color(0xFFFF3B30), // красный
    Color(0xFF00E5FF), // голубой
    Color(0xFFFF4DFF)  // розовый
)

class MainActivity : ComponentActivity() {

    private val keyUnit = stringPreferencesKey("speed_unit")
    private val keyColor = intPreferencesKey("digit_color")

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startSpeedService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // экран не гаснет и скринсейвер не включается, пока приложение открыто
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (hasLocationPermission()) startSpeedService() else requestPermissions()

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val prefs by context.dataStore.data.collectAsState(initial = emptyPreferences())

            val unit = SpeedUnit.values().firstOrNull { it.name == prefs[keyUnit] } ?: SpeedUnit.KMH
            val colorIndex = (prefs[keyColor] ?: 0).coerceIn(0, DigitColors.lastIndex)
            val digitColor = DigitColors[colorIndex]

            val speed by SpeedState.speed.collectAsState()
            val value = speed * unit.factor
            val text = if (unit == SpeedUnit.KNOTS)
                String.format(Locale.US, "%.1f", value)
            else
                String.format(Locale.US, "%.0f", value)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    BigSpeed(
                        text = text,
                        color = digitColor,
                        onTap = {
                            scope.launch {
                                context.dataStore.edit {
                                    it[keyColor] = (colorIndex + 1) % DigitColors.size
                                }
                            }
                        },
                        onLongPress = { stopAndExit() }
                    )
                }
                Text(
                    text = unit.label,
                    fontSize = 18.sp,
                    color = Color.LightGray,
                    modifier = Modifier
                        .clickable {
                            val next = SpeedUnit.values()[(unit.ordinal + 1) % SpeedUnit.values().size]
                            scope.launch { context.dataStore.edit { it[keyUnit] = next.name } }
                        }
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }

    /** Долгое нажатие на цифры (или кнопка «Стоп» в уведомлении) — полный выход. */
    private fun stopAndExit() {
        stopService(Intent(this, SpeedService::class.java))
        finishAndRemoveTask()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun startSpeedService() {
        ContextCompat.startForegroundService(this, Intent(this, SpeedService::class.java))
    }
}

/** Цифры максимального размера: подгоняются под высоту и ширину экрана. */
@Composable
fun BigSpeed(
    text: String,
    color: Color,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLongPress)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { tap() }, onLongPress = { long() })
            },
        contentAlignment = Alignment.Center
    ) {
        val startSize = with(LocalDensity.current) { (maxHeight * 0.85f).toSp() }
        var size by remember(text.length, startSize) { mutableStateOf(startSize) }

        Text(
            text = text,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp),
            onTextLayout = { layout ->
                if (layout.didOverflowWidth && size.value > 12f) size = size * 0.92f
            }
        )
    }
}
