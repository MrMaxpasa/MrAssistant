package com.example.mrassistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mrassistant.ui.theme.DotoFamily
import com.example.mrassistant.ui.theme.SpaceMonoFamily

// ── Paleta oscura (igual que la pantalla principal) ───────────────────────
private val BgColor      = Color(0xFF0D0D0D)
private val SurfaceColor = Color(0xFF161616)
private val TextColor    = Color(0xFFE2E2E2)
private val DimColor     = Color(0xFF444444)
private val DimText      = Color(0xFF777777)

@Composable
fun VoiceSettingsScreen(vm: VoiceSettingsViewModel, onBack: () -> Unit) {
    val voices by vm.voices.collectAsState()
    val selectedVoiceName by vm.selectedVoiceName.collectAsState()
    val pitch by vm.pitch.collectAsState()
    val speechRate by vm.speechRate.collectAsState()

    BackHandler { vm.saveSettings(); onBack() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {

        // ── Cabecera ──────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { vm.saveSettings(); onBack() },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextColor)
                ) {
                    Text(
                        "← GUARDAR",
                        fontFamily   = DotoFamily,
                        fontWeight   = FontWeight.Medium,
                        letterSpacing = 2.sp,
                        fontSize     = 11.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "VOZ DE ALFRED",
                    color        = TextColor,
                    fontSize     = 13.sp,
                    fontWeight   = FontWeight.Bold,
                    letterSpacing = 5.sp,
                    fontFamily   = DotoFamily
                )
            }
        }

        // ── Deslizadores ─────────────────────────────────────────────────
        item {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .background(SurfaceColor, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                SliderRow(
                    label = "TONO",
                    value = pitch,
                    formatted = "%.2f".format(pitch),
                    onValueChange = vm::onPitchChanged
                )
                Spacer(Modifier.height(8.dp))
                SliderRow(
                    label = "VELOCIDAD",
                    value = speechRate,
                    formatted = "%.2f".format(speechRate),
                    onValueChange = vm::onSpeechRateChanged
                )
            }
        }

        // ── Separador y etiqueta de sección ───────────────────────────────
        item {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DimColor)
            )
            Text(
                text         = "VOCES DISPONIBLES — ESPAÑOL",
                modifier     = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color        = DimText,
                fontSize     = 9.sp,
                letterSpacing = 2.sp,
                fontFamily   = DotoFamily
            )
        }

        // ── Lista vacía ───────────────────────────────────────────────────
        if (voices.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = TextColor, strokeWidth = 2.dp)
                }
            }
        }

        // ── Filas de voces ────────────────────────────────────────────────
        items(voices, key = { it.name }) { voice ->
            VoiceRow(
                item       = voice,
                isSelected = voice.name == selectedVoiceName,
                onSelect   = { vm.onVoiceSelected(voice.name) },
                onTest     = { vm.testVoice(voice) }
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 16.dp)
                    .background(DimColor)
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    formatted: String,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text         = label,
            color        = DimText,
            fontSize     = 9.sp,
            letterSpacing = 2.sp,
            fontFamily   = DotoFamily,
            modifier     = Modifier.width(80.dp)
        )
        Slider(
            value         = value,
            onValueChange = onValueChange,
            valueRange    = 0.5f..1.5f,
            modifier      = Modifier.weight(1f),
            colors        = SliderDefaults.colors(
                thumbColor         = Color.White,
                activeTrackColor   = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.18f)
            )
        )
        Text(
            text       = formatted,
            color      = TextColor,
            fontSize   = 11.sp,
            fontFamily = SpaceMonoFamily,
            modifier   = Modifier
                .width(40.dp)
                .padding(start = 8.dp)
        )
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
        else                -> "★"
    }
    val netLabel = if (item.isNetworkRequired) "red" else "sin red"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication        = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick           = onSelect
            )
            .background(if (isSelected) SurfaceColor else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Indicador de selección
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(6.dp)
                .background(
                    if (isSelected) Color.White else Color.Transparent,
                    CircleShape
                )
                .border(
                    width = 1.dp,
                    color = if (isSelected) Color.White else DimColor,
                    shape = CircleShape
                )
        )

        Column(Modifier.weight(1f)) {
            Text(
                text       = item.name,
                color      = if (isSelected) Color.White else TextColor,
                fontSize   = 12.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = SpaceMonoFamily
            )
            Text(
                text       = "${item.locale}  $stars  $netLabel",
                color      = DimText,
                fontSize   = 10.sp,
                fontFamily = SpaceMonoFamily
            )
        }

        OutlinedButton(
            onClick        = onTest,
            border         = androidx.compose.foundation.BorderStroke(
                1.dp, Color.White.copy(alpha = 0.30f)
            ),
            colors         = ButtonDefaults.outlinedButtonColors(
                contentColor = TextColor
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier       = Modifier.height(32.dp)
        ) {
            Text(
                "PROBAR",
                fontSize     = 9.sp,
                letterSpacing = 1.5.sp,
                fontFamily   = DotoFamily
            )
        }
    }
}

