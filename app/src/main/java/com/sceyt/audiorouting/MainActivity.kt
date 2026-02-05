package com.sceyt.audiorouting

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.sceyt.audiorouting.ui.theme.AudioRoutingTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private lateinit var audioRouter: AudioRouter
    private var melodicSoundPlayer: MelodicSoundPlayer? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Permission granted, refresh devices to detect Bluetooth
            audioRouter.refreshDevices()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Create the audio router with logging enabled for demo
        audioRouter = AudioRouter.create(
            context = this,
            config = AudioRouterConfig(loggingEnabled = true)
        )

        melodicSoundPlayer = MelodicSoundPlayer()

        setContent {
            AudioRoutingTheme {
                AudioRoutingDemo(
                    audioRouter = audioRouter,
                    soundPlayer = melodicSoundPlayer!!,
                    onRequestPermission = { requestBluetoothPermission() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        checkAndRequestBluetoothPermission()
        audioRouter.start()
    }

    override fun onStop() {
        super.onStop()
        melodicSoundPlayer?.stop()
        audioRouter.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        melodicSoundPlayer?.release()
    }

    private fun checkAndRequestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestBluetoothPermission()
            }
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
}

/**
 * Plays melodic tones using AudioTrack for testing audio routing.
 * Generates a pleasant melody pattern using synthesized sine waves.
 */
@Stable
class MelodicSoundPlayer {
    private val sampleRate = 44100
    private var mediaPlayer: MediaPlayer? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var isPlaying: Boolean = false
        private set

    fun play(context: Context) {
        if (isPlaying) return
        isPlaying = true

        playbackJob = scope.launch {
            // Create and configure MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .build()
                )
                isLooping = true
                setDataSource(
                    context,
                    "android.resource://${context.packageName}/${R.raw.song}".toUri()
                )

                // Set up listeners
                setOnPreparedListener {
                    it.start()
                }

                // Prepare asynchronously
                prepareAsync()
            }
        }
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
    }

    fun release() {
        stop()
    }

    @Suppress("SameParameterValue")
    private fun generateNoteWithEnvelope(frequency: Double, durationMs: Int): ShortArray {
        val numSamples = (sampleRate * durationMs / 1000.0).toInt()
        val samples = ShortArray(numSamples)

        val attackSamples = (numSamples * 0.1).toInt()
        val decaySamples = (numSamples * 0.1).toInt()
        val releaseSamples = (numSamples * 0.3).toInt()
        val sustainSamples = numSamples - attackSamples - decaySamples - releaseSamples

        for (i in 0 until numSamples) {
            // Generate sine wave with harmonics for richer sound
            val time = i.toDouble() / sampleRate
            val fundamental = sin(2 * PI * frequency * time)
            val harmonic2 = 0.5 * sin(2 * PI * frequency * 2 * time)
            val harmonic3 = 0.25 * sin(2 * PI * frequency * 3 * time)
            var sample = (fundamental + harmonic2 + harmonic3) / 1.75

            // Apply ADSR envelope
            val envelope = when {
                i < attackSamples -> i.toDouble() / attackSamples // Attack
                i < attackSamples + decaySamples -> {
                    val decayProgress = (i - attackSamples).toDouble() / decaySamples
                    1.0 - (0.3 * decayProgress) // Decay to 0.7
                }

                i < attackSamples + decaySamples + sustainSamples -> 0.7 // Sustain
                else -> {
                    val releaseProgress =
                        (i - attackSamples - decaySamples - sustainSamples).toDouble() / releaseSamples
                    0.7 * (1.0 - releaseProgress) // Release
                }
            }

            sample *= envelope
            samples[i] = (sample * Short.MAX_VALUE * 0.8).toInt().toShort()
        }

        return samples
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioRoutingDemo(
    audioRouter: AudioRouter,
    soundPlayer: MelodicSoundPlayer,
    onRequestPermission: () -> Unit
) {
    val availableDevices by audioRouter.availableDevices.collectAsState()
    val selectedDevice by audioRouter.selectedDevice.collectAsState()
    val routingState by audioRouter.routingState.collectAsState()
    val isManualSelection by audioRouter.isManualSelection.collectAsState()

    var isSoundPlaying by remember { mutableStateOf(false) }

    // Cleanup sound when leaving composition
    DisposableEffect(Unit) {
        onDispose {
            soundPlayer.stop()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Routing Demo") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Status Card
            StatusCard(
                routingState = routingState,
                isManualSelection = isManualSelection,
                selectedDevice = selectedDevice,
                isSoundPlaying = isSoundPlaying
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Control Buttons
            ControlButtons(
                routingState = routingState,
                isManualSelection = isManualSelection,
                onActivate = { audioRouter.activate() },
                onDeactivate = { audioRouter.deactivate() },
                onClearManualSelection = { audioRouter.clearManualSelection() },
                onRequestPermission = onRequestPermission
            )

            Spacer(modifier = Modifier.height(8.dp))
            val context = LocalContext.current

            // Sound Control Button
            SoundControlButton(
                isPlaying = isSoundPlaying,
                isActivated = routingState == RoutingState.ACTIVATED,
                onToggle = {
                    if (isSoundPlaying) {
                        soundPlayer.stop()
                        isSoundPlaying = false
                    } else {
                        soundPlayer.play(context)
                        isSoundPlaying = true
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Available Devices
            Text(
                text = "Available Devices",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (availableDevices.isEmpty()) {
                Text(
                    text = "No devices available. Make sure Bluetooth permission is granted.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(availableDevices) { device ->
                        DeviceCard(
                            device = device,
                            isSelected = device.id == selectedDevice?.id,
                            onClick = { audioRouter.selectDevice(device) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SoundControlButton(
    isPlaying: Boolean,
    isActivated: Boolean,
    onToggle: () -> Unit
) {
    Button(
        onClick = onToggle,
        enabled = isActivated,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isPlaying) Color(0xFFE91E63) else Color(0xFF4CAF50)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isPlaying) {
                // Animated sound wave indicator
                SoundWaveIndicator()
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop Melody")
            } else {
                Text("Play Melody")
                Spacer(modifier = Modifier.width(4.dp))
                Text("(Tests audio routing)")
            }
        }
    }
}

@Composable
fun SoundWaveIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "sound_wave")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((12 + index * 4).dp * scale)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White)
            )
        }
    }
}

@Composable
fun StatusCard(
    routingState: RoutingState,
    isManualSelection: Boolean,
    selectedDevice: AudioDevice?,
    isSoundPlaying: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Status:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusBadge(routingState)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Active Device:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = selectedDevice?.name ?: "None",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Selection Mode:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isManualSelection) "Manual (locked)" else "Automatic",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isManualSelection)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sound:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isSoundPlaying) {
                    SoundPlayingBadge()
                } else {
                    Text(
                        text = "Not playing",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SoundPlayingBadge() {
    val infiniteTransition = rememberInfiniteTransition(label = "playing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = Color(0xFF4CAF50).copy(alpha = alpha * 0.3f)
    ) {
        Text(
            text = "Playing melody",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF4CAF50)
        )
    }
}

@Composable
fun StatusBadge(routingState: RoutingState) {
    val (text, color) = when (routingState) {
        RoutingState.IDLE -> "Idle" to Color.Gray
        RoutingState.STARTED -> "Started" to Color(0xFF2196F3)
        RoutingState.ACTIVATED -> "Activated" to Color(0xFF4CAF50)
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
fun ControlButtons(
    routingState: RoutingState,
    isManualSelection: Boolean,
    onActivate: () -> Unit,
    onDeactivate: () -> Unit,
    onClearManualSelection: () -> Unit,
    onRequestPermission: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onActivate,
            enabled = routingState == RoutingState.STARTED,
            modifier = Modifier.weight(1f)
        ) {
            Text("Activate")
        }

        Button(
            onClick = onDeactivate,
            enabled = routingState == RoutingState.ACTIVATED,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary
            )
        ) {
            Text("Deactivate")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onClearManualSelection,
            enabled = isManualSelection,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text("Clear Manual")
        }

        Button(
            onClick = onRequestPermission,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.outline
            )
        ) {
            Text("BT Permission")
        }
    }
}

@Composable
fun DeviceCard(
    device: AudioDevice,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Device icon
            DeviceIcon(device)

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
                Text(
                    text = getDeviceTypeLabel(device),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

@Composable
fun DeviceIcon(device: AudioDevice) {
    val (emoji, backgroundColor) = when (device) {
        is AudioDevice.BluetoothHeadset -> "🎧" to Color(0xFF2196F3)
        is AudioDevice.WiredHeadset -> "🎧" to Color(0xFF9C27B0)
        is AudioDevice.Earpiece -> "📱" to Color(0xFF607D8B)
        is AudioDevice.Speakerphone -> "🔊" to Color(0xFFFF9800)
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(backgroundColor.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = emoji,
            style = MaterialTheme.typography.headlineSmall
        )
    }
}

fun getDeviceTypeLabel(device: AudioDevice): String {
    return when (device) {
        is AudioDevice.BluetoothHeadset -> "Bluetooth"
        is AudioDevice.WiredHeadset -> "Wired"
        is AudioDevice.Earpiece -> "Built-in"
        is AudioDevice.Speakerphone -> "Speaker"
    }
}
