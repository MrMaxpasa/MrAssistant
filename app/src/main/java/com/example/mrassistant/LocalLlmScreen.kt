package com.example.mrassistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mrassistant.ui.theme.DotoFamily
import com.example.mrassistant.ui.theme.SpaceMonoFamily

// ── Paleta ────────────────────────────────────────────────────────────────────
private val LlmBg      = Color(0xFF0D0D0D)
private val LlmSurface = Color(0xFF161616)
private val LlmText    = Color(0xFFF5F5F5)
private val LlmDim     = Color(0xFF555555)
private val LlmError   = Color(0xFFFF453A)
private val LlmGreen   = Color(0xFF30D158)

@Composable
fun LocalLlmScreen(vm: LocalLlmViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()

    // Lanzador SAF — el sistema gestiona los permisos de acceso al archivo
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { vm.onUriSelected(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LlmBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {

        // ── Cabecera ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text          = "← VOLVER",
                color         = LlmText,
                fontSize      = 11.sp,
                letterSpacing = 2.sp,
                fontFamily    = DotoFamily,
                modifier      = Modifier.clickable(
                    indication        = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick           = onBack
                )
            )
            Spacer(Modifier.weight(1f))
            Text(
                text          = "MODELO LOCAL",
                color         = LlmText,
                fontSize      = 13.sp,
                fontWeight    = FontWeight.Bold,
                letterSpacing = 5.sp,
                fontFamily    = DotoFamily
            )
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(60.dp))
        }

        // ── Info ruta interna ─────────────────────────────────────────────
        SectionLabel("RUTA INTERNA DEL MODELO")
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(LlmSurface, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Text(
                text      = vm.internalModelPath,
                color     = LlmDim,
                fontSize  = 10.sp,
                fontFamily = SpaceMonoFamily,
                lineHeight = 15.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Botón principal ───────────────────────────────────────────────
        val isIdle = state.loadState == LlmLoadState.IDLE ||
                     state.loadState == LlmLoadState.ERROR

        Button(
            onClick  = {
                if (state.modelExistsInternally) vm.loadExistingModel()
                else filePicker.launch(arrayOf("*/*"))
            },
            enabled  = isIdle,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(
                containerColor         = Color(0xFF2A2A2A),
                contentColor           = LlmText,
                disabledContainerColor = Color(0xFF1A1A1A),
                disabledContentColor   = LlmDim
            )
        ) {
            when (state.loadState) {
                LlmLoadState.COPYING -> {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp),
                        color       = LlmGreen,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    val pct = (state.copyProgress * 100).toInt()
                    Text(
                        "COPIANDO… $pct%",
                        fontFamily = DotoFamily, letterSpacing = 2.sp, fontSize = 11.sp
                    )
                }
                LlmLoadState.LOADING -> {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp),
                        color       = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "CARGANDO MODELO…",
                        fontFamily = DotoFamily, letterSpacing = 2.sp, fontSize = 11.sp
                    )
                }
                LlmLoadState.READY -> Text(
                    "✓ MODELO LISTO",
                    color = LlmGreen,
                    fontFamily = DotoFamily, letterSpacing = 2.sp, fontSize = 11.sp
                )
                else -> {
                    val label = if (state.modelExistsInternally) "CARGAR MODELO"
                                else "SELECCIONAR MODELO…"
                    Text(label, fontFamily = DotoFamily, letterSpacing = 2.sp, fontSize = 11.sp)
                }
            }
        }

        // Si el modelo ya existe pero quiere reemplazarlo
        if (state.modelExistsInternally && isIdle) {
            Spacer(Modifier.height(6.dp))
            Text(
                text      = "Seleccionar otro archivo…",
                color     = LlmDim,
                fontSize  = 10.sp,
                fontFamily = SpaceMonoFamily,
                modifier  = Modifier.clickable(
                    indication        = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { filePicker.launch(arrayOf("*/*")) }
            )
        }

        // ── Barra de progreso de copia ────────────────────────────────────
        if (state.loadState == LlmLoadState.COPYING) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress   = { state.copyProgress },
                modifier   = Modifier.fillMaxWidth(),
                color      = LlmGreen,
                trackColor = Color(0xFF2A2A2A)
            )
            if (state.copyBytesLabel.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text      = state.copyBytesLabel,
                    color     = LlmDim,
                    fontSize  = 10.sp,
                    fontFamily = SpaceMonoFamily
                )
            }
        }

        // ── Error ─────────────────────────────────────────────────────────
        state.errorMessage?.let { msg ->
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A0A0A), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text      = msg,
                    color     = LlmError,
                    fontSize  = 12.sp,
                    fontFamily = SpaceMonoFamily,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(20.dp))

        // ── Área de prompt ────────────────────────────────────────────────
        SectionLabel("MENSAJE AL MODELO")
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value         = state.prompt,
            onValueChange = vm::onPromptChanged,
            enabled       = state.loadState == LlmLoadState.READY && !state.isGenerating,
            textStyle     = TextStyle(
                color      = LlmText, fontSize = 13.sp,
                fontFamily = SpaceMonoFamily, lineHeight = 20.sp
            ),
            cursorBrush   = SolidColor(Color.White),
            modifier      = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(LlmSurface, RoundedCornerShape(8.dp))
                .padding(12.dp),
            decorationBox = { inner ->
                if (state.prompt.isEmpty()) {
                    Text(
                        "Escribe algo para enviar al modelo…",
                        color = LlmDim, fontSize = 13.sp, fontFamily = SpaceMonoFamily
                    )
                }
                inner()
            }
        )

        Spacer(Modifier.height(12.dp))

        // ── Botón enviar ──────────────────────────────────────────────────
        Button(
            onClick  = vm::generate,
            enabled  = state.loadState == LlmLoadState.READY &&
                       state.prompt.isNotBlank() && !state.isGenerating,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(
                containerColor         = Color(0xFF2A2A2A),
                contentColor           = LlmText,
                disabledContainerColor = Color(0xFF1A1A1A),
                disabledContentColor   = LlmDim
            )
        ) {
            if (state.isGenerating) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(14.dp),
                    color       = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text("GENERANDO…", fontFamily = DotoFamily, letterSpacing = 2.sp, fontSize = 11.sp)
            } else {
                Text("ENVIAR →", fontFamily = DotoFamily, letterSpacing = 2.sp, fontSize = 11.sp)
            }
        }

        // ── Respuesta ─────────────────────────────────────────────────────
        if (state.response.isNotBlank()) {
            Spacer(Modifier.height(20.dp))
            SectionLabel("RESPUESTA")
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LlmSurface, RoundedCornerShape(8.dp))
                    .padding(14.dp)
            ) {
                Text(
                    text      = state.response,
                    color     = LlmText,
                    fontSize  = 13.sp,
                    fontFamily = SpaceMonoFamily,
                    lineHeight = 20.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text          = text,
        color         = LlmDim,
        fontSize      = 9.sp,
        letterSpacing = 2.sp,
        fontFamily    = DotoFamily
    )
}
