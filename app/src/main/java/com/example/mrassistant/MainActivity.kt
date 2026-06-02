package com.example.mrassistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.mrassistant.ui.theme.MrAssistantTheme

private enum class Screen { MAIN, VOICE_SETTINGS }

class MainActivity : ComponentActivity() {

    private val assistantVm: AssistantViewModel by viewModels()
    private val voiceVm: VoiceSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MrAssistantTheme {
                var screen by remember { mutableStateOf(Screen.MAIN) }
                when (screen) {
                    Screen.MAIN -> AssistantScreen(
                        vm = assistantVm,
                        onOpenVoiceSettings = { screen = Screen.VOICE_SETTINGS }
                    )
                    Screen.VOICE_SETTINGS -> VoiceSettingsScreen(
                        vm = voiceVm,
                        onBack = {
                            voiceVm.saveSettings()
                            assistantVm.reloadTts()
                            screen = Screen.MAIN
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantScreen(vm: AssistantViewModel, onOpenVoiceSettings: () -> Unit) {
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    // Solo pedimos permiso; cuando se concede llamamos vm.startListening()
    // directamente sin pasar por ningún intent externo.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.startListening() else vm.onMicPermissionDenied()
    }

    // Launcher para el permiso de ubicación (pedido por la herramienta get_weather).
    // No hace falta hacer nada en el callback: si se concede, el usuario
    // puede repetir la pregunta y la herramienta ya tendrá el permiso.
    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Alfred ya habrá explicado en voz alta que necesitaba el permiso */ }

    // Observamos el SharedFlow: cuando la herramienta señala que necesita ubicación,
    // lanzamos el diálogo del sistema mientras Alfred habla.
    LaunchedEffect(Unit) {
        vm.locationPermRequest.collect {
            locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    val statusLabel = when (uiState.status) {
        AssistantStatus.IDLE -> "En reposo"
        AssistantStatus.LISTENING -> "Escuchando…"
        AssistantStatus.THINKING -> "Pensando…"
        AssistantStatus.SPEAKING -> "Hablando…"
    }
    val statusColor = when (uiState.status) {
        AssistantStatus.IDLE -> MaterialTheme.colorScheme.outline
        AssistantStatus.LISTENING -> Color(0xFF4CAF50)
        AssistantStatus.THINKING -> Color(0xFFFFC107)
        AssistantStatus.SPEAKING -> Color(0xFF2196F3)
    }

    // --- Animación del halo/círculo pulsante ---
    // El halo solo es visible en estado LISTENING.
    // Su escala sube con rmsLevel (0..1) para simular un "latido" de voz.
    // tween(80 ms) hace que reaccione rápido sin ser errático.
    val isListening = uiState.status == AssistantStatus.LISTENING
    val haloScale by animateFloatAsState(
        targetValue = if (isListening) 1f + uiState.rmsLevel * 0.65f else 1f,
        animationSpec = tween(durationMillis = 80),
        label = "halo_scale"
    )
    val haloAlpha by animateFloatAsState(
        targetValue = if (isListening) 0.18f + uiState.rmsLevel * 0.42f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "halo_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Alfred", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onOpenVoiceSettings) {
                Text("Voz ⚙", fontSize = 13.sp)
            }
        }

        Surface(
            shape = MaterialTheme.shapes.small,
            color = statusColor.copy(alpha = 0.15f)
        ) {
            Text(
                text = statusLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = statusColor,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(8.dp))

        // Box que contiene el halo (fondo) y el botón (frente).
        // El halo ocupa más espacio que el botón para que la escala
        // animada no quede cortada por los bordes del contenedor.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(170.dp)
        ) {
            // Halo: un círculo verde semitransparente que crece con la voz.
            // Modifier.scale() cambia el tamaño visual sin alterar el layout,
            // así el botón siempre queda centrado independientemente de la escala.
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(haloScale)
                    .background(
                        color = Color(0xFF4CAF50).copy(alpha = haloAlpha),
                        shape = CircleShape
                    )
            )

            // Botón mic: activo solo en IDLE para evitar dobles pulsaciones.
            Button(
                onClick = {
                    val hasPerm = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasPerm) vm.startListening()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = uiState.status == AssistantStatus.IDLE,
                modifier = Modifier.size(110.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("🎙️", fontSize = 38.sp)
            }
        }

        Text("Hablar", style = MaterialTheme.typography.labelLarge)

        // Área de transcripción: muestra el texto parcial mientras escucha
        // y el texto final en los demás estados.
        // Esto evita que el usuario vea "pantalla vacía" mientras habla.
        val shownText = if (isListening && uiState.partialText.isNotBlank())
            uiState.partialText
        else
            uiState.userText

        val shownLabel = if (isListening && uiState.partialText.isNotBlank())
            "Transcripción en vivo…"
        else
            "Tú dijiste:"

        if (shownText.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        shownLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        shownText,
                        // El texto parcial aparece en cursiva para indicar que
                        // todavía está siendo reconocido y puede cambiar.
                        fontStyle = if (isListening) FontStyle.Italic else FontStyle.Normal
                    )
                }
            }
        }

        // Respuesta del asistente — sin cambios respecto a antes.
        if (uiState.responseText.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Respuesta:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(uiState.responseText)
                }
            }
        }

        // Aviso de red: la escucha funcionó pero Gemini no tiene conexión.
        // Color tertiaryContainer (neutro/amber) para diferenciarlo del rojo de error.
        uiState.networkWarning?.let { warning ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Sin conexión",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(warning, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }

        // Error duro de escucha/reconocimiento.
        uiState.error?.let { err ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    err,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
