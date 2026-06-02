package com.example.mrassistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VoiceSettingsScreen(vm: VoiceSettingsViewModel, onBack: () -> Unit) {
    val voices by vm.voices.collectAsState()
    val selectedVoiceName by vm.selectedVoiceName.collectAsState()
    val pitch by vm.pitch.collectAsState()
    val speechRate by vm.speechRate.collectAsState()

    // El botón físico de atrás también guarda y vuelve
    BackHandler { vm.saveSettings(); onBack() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {

        // ── Cabecera ──────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { vm.saveSettings(); onBack() }) {
                    Text("← Guardar", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.weight(1f))
                Text("Voz de Alfred", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ── Sliders ───────────────────────────────────────────────────────
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    "Tono: ${"%.2f".format(pitch)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = pitch,
                    onValueChange = vm::onPitchChanged,
                    valueRange = 0.5f..1.5f
                )
                Text(
                    "Velocidad: ${"%.2f".format(speechRate)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Slider(
                    value = speechRate,
                    onValueChange = vm::onSpeechRateChanged,
                    valueRange = 0.5f..1.5f
                )
            }
        }

        // ── Separador y título de lista ───────────────────────────────────
        item {
            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            Text(
                "Voces disponibles (español)",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // ── Lista vacía = cargando ─────────────────────────────────────────
        if (voices.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        }

        // ── Filas de voces ────────────────────────────────────────────────
        items(voices, key = { it.name }) { voice ->
            VoiceRow(
                item = voice,
                isSelected = voice.name == selectedVoiceName,
                onSelect = { vm.onVoiceSelected(voice.name) },
                onTest = { vm.testVoice(voice) }
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun VoiceRow(
    item: VoiceItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit
) {
    val stars = when {
        item.quality >= 500 -> "★★★"
        item.quality >= 400 -> "★★"
        else -> "★"
    }
    val netLabel = if (item.isNetworkRequired) "· red" else "· sin red"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = "${item.locale}  $stars  $netLabel",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (isSelected) {
            Text(
                "✓",
                modifier = Modifier.padding(horizontal = 8.dp),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        } else {
            Spacer(Modifier.width(28.dp))
        }
        OutlinedButton(
            onClick = onTest,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
        ) {
            Text("Probar", fontSize = 12.sp)
        }
    }
}
