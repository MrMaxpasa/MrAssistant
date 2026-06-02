package com.example.mrassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.mrassistant.ui.theme.MrAssistantTheme

class MainActivity : ComponentActivity() {

    private val vm: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MrAssistantTheme {
                AssistantScreen(vm = vm)
            }
        }
    }
}

@Composable
fun AssistantScreen(vm: AssistantViewModel) {
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) vm.setListening() else vm.onSpeechError()
    }

    val sttLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (result.resultCode == Activity.RESULT_OK && text.isNotBlank()) {
            vm.onSpeechResult(text)
        } else {
            vm.onSpeechError()
        }
    }

    // Cuando el estado cambia a LISTENING, lanzamos el diálogo de STT
    LaunchedEffect(uiState.status) {
        if (uiState.status == AssistantStatus.LISTENING) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla ahora…")
            }
            sttLauncher.launch(intent)
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("MrAssistant", fontSize = 28.sp, fontWeight = FontWeight.Bold)

        // Pastilla de estado con color dinámico
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

        // Botón circular grande — sólo activo en reposo
        Button(
            onClick = {
                val hasPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasPerm) vm.setListening()
                else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            enabled = uiState.status == AssistantStatus.IDLE,
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp)
        ) {
            Text("🎙️", fontSize = 40.sp)
        }

        Text("Hablar", style = MaterialTheme.typography.labelLarge)

        // Lo que el usuario dijo
        if (uiState.userText.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Tú dijiste:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(uiState.userText)
                }
            }
        }

        // Respuesta del asistente
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

        // Error
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
