package com.noisemachine.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.isActive
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private var activeTypes by mutableStateOf(setOf<NoiseType>())
    private var volume by mutableFloatStateOf(0.7f)
    private var timerEndMillis by mutableStateOf(0L)
    private var selectedTimerMinutes by mutableStateOf(0)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == NoiseService.ACTION_STATE) {
                val names = intent.getStringArrayListExtra(NoiseService.EXTRA_ACTIVE)
                    ?: arrayListOf()
                activeTypes = names
                    .mapNotNull { runCatching { NoiseType.valueOf(it) }.getOrNull() }
                    .toSet()
                timerEndMillis = intent.getLongExtra(NoiseService.EXTRA_TIMER_END, 0L)
                if (timerEndMillis == 0L) selectedTimerMinutes = 0
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NoiseMachineScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(NoiseService.ACTION_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startService(
            Intent(this, NoiseService::class.java)
                .setAction(NoiseService.ACTION_QUERY_STATE)
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(stateReceiver)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }
    }

    private fun toggle(type: NoiseType) {
        val intent = Intent(this, NoiseService::class.java)
            .setAction(NoiseService.ACTION_TOGGLE)
            .putExtra(NoiseService.EXTRA_TYPE, type.name)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun setVolumeValue(v: Float) {
        volume = v
        startService(
            Intent(this, NoiseService::class.java)
                .setAction(NoiseService.ACTION_SET_VOLUME)
                .putExtra(NoiseService.EXTRA_VOLUME, v)
        )
    }

    private fun setTimer(minutes: Int) {
        // Tapping the active pill disables the timer.
        val newMinutes = if (minutes == selectedTimerMinutes) -1 else minutes
        selectedTimerMinutes = maxOf(0, newMinutes)
        startService(
            Intent(this, NoiseService::class.java)
                .setAction(NoiseService.ACTION_SET_TIMER)
                .putExtra(NoiseService.EXTRA_TIMER_MINUTES, newMinutes)
        )
    }

    private fun formatDuration(ms: Long): String {
        val s = (ms / 1000).toInt().coerceAtLeast(0)
        val m = s / 60
        val h = m / 60
        fun p(n: Int) = if (n < 10) "0$n" else "$n"
        return if (h > 0) "$h:${p(m % 60)}:${p(s % 60)}" else "$m:${p(s % 60)}"
    }

    @Composable
    private fun NoiseMachineScreen() {
        var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                nowMillis = System.currentTimeMillis()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF14243D),
                            Color(0xFF0D1830),
                            Color(0xFF0A1224)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Noise Machine",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEEF2F8),
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            NoiseCell(NoiseType.WHITE)
                            NoiseCell(NoiseType.PINK)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            NoiseCell(NoiseType.BROWN)
                            NoiseCell(NoiseType.GREEN)
                        }
                    }
                }
                Text(
                    text = "VOLUME",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = Color(0xFFEEF2F8).copy(alpha = 0.55f)
                )
                Slider(
                    value = volume,
                    onValueChange = ::setVolumeValue,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF7FA8D8),
                        activeTrackColor = Color(0xFF7FA8D8),
                        inactiveTrackColor = Color(0xFF7FA8D8).copy(alpha = 0.3f)
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "SLEEP TIMER",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    color = Color(0xFFEEF2F8).copy(alpha = 0.55f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TimerPill(minutes = 30, label = "30m")
                    TimerPill(minutes = 90, label = "90m")
                    TimerPill(minutes = 360, label = "6h")
                    TimerPill(minutes = 480, label = "8h")
                }
                val status = buildString {
                    if (activeTypes.isNotEmpty()) {
                        append(
                            "Playing: " + activeTypes.sortedBy { it.ordinal }
                                .joinToString(" + ") { it.displayName }
                        )
                    }
                    val left = timerEndMillis - nowMillis
                    if (timerEndMillis > 0 && left > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("Timer " + formatDuration(left))
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = status,
                        fontSize = 12.sp,
                        color = Color(0xFFEEF2F8).copy(alpha = 0.55f)
                    )
                }
            }
        }
    }

    @Composable
    private fun RowScope.TimerPill(minutes: Int, label: String) {
        val active = selectedTimerMinutes == minutes
        val glow = Color(0xFF7FA8D8)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .graphicsLayer {
                    shadowElevation = if (active) 14.dp.toPx() else 0.dp.toPx()
                    ambientShadowColor = glow
                    spotShadowColor = glow
                    shape = CircleShape
                    clip = true
                }
                .background(
                    if (active) glow.copy(alpha = 0.22f)
                    else Color.White.copy(alpha = 0.06f),
                    CircleShape
                )
                .border(
                    1.dp,
                    if (active) glow.copy(alpha = 0.65f)
                    else Color.White.copy(alpha = 0.18f),
                    CircleShape
                )
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { setTimer(minutes) }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEEF2F8)
            )
        }
    }

    @Composable
    private fun NoiseCell(type: NoiseType) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            NoiseButton(
                type = type,
                active = activeTypes.contains(type),
                onToggle = { toggle(type) }
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = type.displayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEEF2F8).copy(alpha = 0.78f)
            )
        }
    }

    @Composable
    private fun NoiseButton(
        type: NoiseType,
        active: Boolean,
        onToggle: () -> Unit
    ) {
        val glow = type.glowColor
        Box(
            modifier = Modifier
                .sizeIn(maxWidth = 128.dp, maxHeight = 128.dp)
                .fillMaxWidth(0.42f)
                .aspectRatio(1f)
                .graphicsLayer {
                    shadowElevation = 18.dp.toPx()
                    ambientShadowColor = if (active) glow else Color.Black
                    spotShadowColor = if (active) glow else Color.Black
                    shape = CircleShape
                    clip = true
                }
                .background(
                    Brush.verticalGradient(listOf(type.topColor, type.bottomColor)),
                    CircleShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle
                ),
            contentAlignment = Alignment.Center
        ) {
            // Soft top light for a hint of dimensionality
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.08f),
                            0.35f to Color.Transparent
                        ),
                        CircleShape
                    )
            )
            if (active) {
                WaveCanvas(type)
            }
        }
    }

    @Composable
    private fun WaveCanvas(type: NoiseType) {
        var phase by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(type) {
            var last = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { now ->
                    val dt = (now - last) / 1e9f
                    last = now
                    phase += dt * type.waveSpeed * 60f
                }
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        ) {
            val w = size.width
            val h = size.height
            val scale = w / 248f
            val freq = type.waveFreq / scale
            for (layer in 0..2) {
                val baseY = h * (0.52f + layer * 0.13f)
                val amp = type.waveAmp * scale * (1 - layer * 0.28f)
                val path = Path().apply {
                    moveTo(0f, h)
                    var x = 0f
                    while (x <= w) {
                        val y = baseY +
                            sin(x * freq + phase * (1 + layer * 0.3f) + layer * 2.1f) * amp +
                            sin(x * freq * 2.7f - phase * 1.6f + layer * 1.3f) * amp * 0.35f
                        lineTo(x, y)
                        x += 4f * scale
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(path, type.waveColor.copy(alpha = 0.16f + layer * 0.11f))
            }
        }
    }
}
