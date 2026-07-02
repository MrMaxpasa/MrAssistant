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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.mrassistant.ui.theme.DotoFamily
import com.example.mrassistant.ui.theme.MrAssistantTheme
import com.example.mrassistant.ui.theme.SpaceMonoFamily
import kotlinx.coroutines.delay

// ── Paleta Nothing ────────────────────────────────────────────────────────
private val BgColor        = Color(0xFF0D0D0D)
private val SurfaceColor   = Color(0xFF161616)
private val TextColor      = Color(0xFFF5F5F5)   // texto principal de burbujas y UI
private val DimColor       = Color(0xFF555555)
private val UserBubbleBg   = Color(0xFF2A2A2A)   // gris más claro  — ajusta aquí
private val AlfredBubbleBg = Color(0xFF161616)   // gris más oscuro — ajusta aquí
private val BubbleBorderColor = Color.White.copy(alpha = 0.22f) // borde punteado común

private enum class Screen { MAIN, VOICE_SETTINGS, LOCAL_LLM }

class MainActivity : ComponentActivity() {

    private val assistantVm: AssistantViewModel by viewModels()
    private val voiceVm: VoiceSettingsViewModel by viewModels()
    private val localLlmVm: LocalLlmViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MrAssistantTheme {
                var screen by remember { mutableStateOf(Screen.MAIN) }
                when (screen) {
                    Screen.MAIN -> AssistantScreen(
                        vm = assistantVm,
                        onOpenVoiceSettings = { screen = Screen.VOICE_SETTINGS },
                        onOpenLocalLlm      = { screen = Screen.LOCAL_LLM }
                    )
                    Screen.VOICE_SETTINGS -> VoiceSettingsScreen(
                        vm = voiceVm,
                        onBack = {
                            voiceVm.saveSettings()
                            assistantVm.reloadTts()
                            screen = Screen.MAIN
                        }
                    )
                    Screen.LOCAL_LLM -> LocalLlmScreen(
                        vm = localLlmVm,
                        onBack = { screen = Screen.MAIN }
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantScreen(
    vm: AssistantViewModel,
    onOpenVoiceSettings: () -> Unit,
    onOpenLocalLlm: () -> Unit = {}
) {
    val uiState by vm.uiState.collectAsState()
    val context = LocalContext.current

    // ── Launchers de permisos ─────────────────────────────────────────────
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.startListening() else vm.onMicPermissionDenied() }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    val contactsPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    LaunchedEffect(Unit) {
        vm.permissionRequest.collect { permission ->
            when (permission) {
                "location" -> locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                "contacts" -> contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
            }
        }
    }

    // ── Cuenta atrás del rate limit ───────────────────────────────────────
    var rateLimitDisplay by remember { mutableStateOf("") }
    LaunchedEffect(uiState.rateLimitUntilMs) {
        val until = uiState.rateLimitUntilMs
        if (until == null) { rateLimitDisplay = ""; return@LaunchedEffect }
        while (true) {
            val remaining = (until - System.currentTimeMillis()) / 1000
            if (remaining <= 0) { vm.clearRateLimit(); break }
            rateLimitDisplay = formatDurationForDisplay(remaining)
            delay(1_000L)
        }
    }

    val isListening = uiState.status == AssistantStatus.LISTENING
    val isIdle      = uiState.status == AssistantStatus.IDLE

    // Estado → color del indicador y texto
    val statusColor = when (uiState.status) {
        AssistantStatus.IDLE      -> DimColor
        AssistantStatus.LISTENING -> Color(0xFFD4221F)
        AssistantStatus.THINKING  -> Color(0xFFFFD60A)
        AssistantStatus.SPEAKING  -> Color(0xFF30D158)
    }
    val statusLabel = when (uiState.status) {
        AssistantStatus.IDLE      -> "EN REPOSO"
        AssistantStatus.LISTENING -> "ESCUCHANDO"
        AssistantStatus.THINKING  -> "PENSANDO"
        AssistantStatus.SPEAKING  -> "HABLANDO"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ── Cabecera ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "ALFRED",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 7.sp,
                fontFamily = DotoFamily
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Indicador de estado de Gemma (solo visible cuando el modelo existe)
                val gemmaColor = when (uiState.gemmaStatus) {
                    GemmaStatus.READY       -> Color(0xFF30D158)  // verde: listo
                    GemmaStatus.LOADING     -> DimColor           // gris: cargando
                    GemmaStatus.UNAVAILABLE -> Color.Transparent  // oculto
                }
                if (uiState.gemmaStatus != GemmaStatus.UNAVAILABLE) {
                    Box(Modifier.size(5.dp).background(gemmaColor, CircleShape))
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    text          = "◈",
                    color         = DimColor,
                    fontSize      = 16.sp,
                    modifier      = Modifier.clickable(
                        indication        = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick           = onOpenLocalLlm
                    )
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    text = "⚙",
                    color = DimColor,
                    fontSize = 18.sp,
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onOpenVoiceSettings
                    )
                )
            }
        }

        // ── Indicador de estado ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(modifier = Modifier.size(7.dp))
            Text(
                text = statusLabel,
                color = statusColor,
                fontSize = 10.sp,
                letterSpacing = 2.5.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = DotoFamily
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── Avatar ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AlfredFace(
                status   = uiState.status,
                rmsLevel = uiState.rmsLevel,
                modifier = Modifier.size(240.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Chat ──────────────────────────────────────────────────────────
        val scrollState = rememberScrollState()
        LaunchedEffect(uiState.userText, uiState.responseText) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            // Texto del usuario (parcial mientras escucha, final después)
            val shownUser = if (isListening && uiState.partialText.isNotBlank())
                uiState.partialText else uiState.userText
            if (shownUser.isNotBlank()) {
                UserBubble(
                    text      = shownUser,
                    isPartial = isListening && uiState.partialText.isNotBlank()
                )
            }

            // Respuesta de Alfred
            if (uiState.responseText.isNotBlank()) {
                AlfredBubble(uiState.responseText, uiState.brainUsed)
            }

            // Rate limit activo
            if (uiState.rateLimitUntilMs != null && rateLimitDisplay.isNotBlank()) {
                RateLimitBubble(rateLimitDisplay)
            }

            // Aviso de red
            uiState.networkWarning?.let {
                StatusNote(text = it, color = Color(0xFFFFD60A))
            }

            // Error duro
            uiState.error?.let {
                StatusNote(text = it, color = Color(0xFFFF453A))
            }

            Spacer(Modifier.height(2.dp))
        }

        // ── Botón de micrófono ─────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .nothingCircleButton(
                        bgColor     = SurfaceColor,
                        borderColor = if (isIdle) Color.White
                                      else Color.White.copy(alpha = 0.18f)
                    )
                    .clip(CircleShape)
                    .clickable(
                        enabled           = isIdle,
                        indication        = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        val hasPerm = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasPerm) vm.startListening()
                        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (uiState.status) {
                        AssistantStatus.IDLE      -> "◎"
                        AssistantStatus.LISTENING -> "◉"
                        AssistantStatus.THINKING  -> "⋯"
                        AssistantStatus.SPEAKING  -> "◈"
                    },
                    color     = if (isIdle) Color.White else Color.White.copy(alpha = 0.22f),
                    fontSize  = 24.sp
                )
            }

            Spacer(Modifier.height(7.dp))

            Text(
                text = when (uiState.status) {
                    AssistantStatus.IDLE      -> "PULSA PARA HABLAR"
                    AssistantStatus.LISTENING -> "ESCUCHANDO"
                    AssistantStatus.THINKING  -> "PENSANDO"
                    AssistantStatus.SPEAKING  -> "HABLANDO"
                },
                color        = Color.White.copy(alpha = 0.28f),
                fontSize     = 9.sp,
                letterSpacing = 2.sp,
                fontFamily   = DotoFamily
            )
        }
    }
}

// ── Burbuja de usuario ────────────────────────────────────────────────────
@Composable
private fun UserBubble(text: String, isPartial: Boolean = false) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(max = 280.dp)
                .nothingBubble(UserBubbleBg, BubbleBorderColor, 14.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text       = text,
                color      = if (isPartial) TextColor.copy(alpha = 0.55f) else TextColor,
                fontSize   = 13.sp,
                fontFamily = SpaceMonoFamily,
                fontStyle  = if (isPartial) FontStyle.Italic else FontStyle.Normal
            )
        }
    }
}

// ── Burbuja de Alfred ─────────────────────────────────────────────────────
@Composable
private fun AlfredBubble(text: String, brain: Brain? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        // Pequeño identificador "A" a la izquierda
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = 8.dp)
                .size(22.dp)
                .background(SurfaceColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text      = "A",
                color     = Color.White.copy(alpha = 0.6f),
                fontSize  = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        androidx.compose.foundation.layout.Column {
            Box(
                modifier = Modifier
                    .widthIn(max = 268.dp)
                    .nothingBubble(AlfredBubbleBg, BubbleBorderColor, 14.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(text = text, color = TextColor, fontSize = 13.sp, fontFamily = SpaceMonoFamily)
            }
            // Indicador de cerebro: ☁ = Gemini (nube), ⊕ = Gemma (local)
            if (brain != null) {
                Text(
                    text      = if (brain == Brain.GEMINI) "☁" else "⊕",
                    color     = DimColor,
                    fontSize  = 10.sp,
                    modifier  = Modifier.padding(start = 3.dp, top = 2.dp)
                )
            }
        }
    }
}

// ── Burbuja de rate limit ─────────────────────────────────────────────────
@Composable
private fun RateLimitBubble(countdown: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, end = 8.dp)
                .size(22.dp)
                .background(SurfaceColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Column {
            Text(
                text       = "EN ESPERA",
                color      = Color.White.copy(alpha = 0.35f),
                fontSize   = 9.sp,
                letterSpacing = 2.sp,
                fontFamily = DotoFamily
            )
            Text(
                text       = countdown,
                color      = Color.White.copy(alpha = 0.85f),
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                fontFamily = DotoFamily
            )
        }
    }
}

// ── Nota de estado (red / error) ──────────────────────────────────────────
@Composable
private fun StatusNote(text: String, color: Color) {
    Text(
        text      = text,
        color     = color.copy(alpha = 0.80f),
        fontSize  = 12.sp,
        textAlign = TextAlign.Center,
        modifier  = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

// ── Modificadores de estilo Nothing ──────────────────────────────────────

/** Fondo + borde punteado en una sola pasada DrawBehind. */
private fun Modifier.nothingBubble(
    bgColor: Color,
    borderColor: Color,
    cornerRadius: Dp
): Modifier = drawBehind {
    val cr = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
    drawRoundRect(color = bgColor, cornerRadius = cr)
    drawRoundRect(
        color       = borderColor,
        cornerRadius = cr,
        style       = Stroke(
            width      = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(3.dp.toPx(), 5.dp.toPx()), 0f
            )
        )
    )
}

/** Círculo relleno + borde punteado para el botón de micrófono. */
private fun Modifier.nothingCircleButton(
    bgColor: Color,
    borderColor: Color
): Modifier = drawBehind {
    val r = minOf(size.width, size.height) / 2f
    drawCircle(color = bgColor, radius = r)
    drawCircle(
        color  = borderColor,
        radius = r - 1.dp.toPx(),
        style  = Stroke(
            width      = 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(3.dp.toPx(), 5.dp.toPx()), 0f
            )
        )
    )
}

private fun formatDurationForDisplay(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "${h}h ${m}m" else "%02d:%02d".format(m, s)
}
