package com.example.mrassistant

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val FaceWhite = Color.White
private val RecordRed  = Color(0xFFD4221F)

// Forma de la boca en reposo y al hablar.
// 0f = línea recta neutra · 1f = sonrisa pronunciada
private const val MOUTH_SMILE = 0.55f

@Composable
fun AlfredFace(
    status: AssistantStatus,
    rmsLevel: Float,
    modifier: Modifier = Modifier
) {
    // ── Blink ─────────────────────────────────────────────────────────────
    var eyesOpen by remember { mutableStateOf(true) }
    LaunchedEffect(status) {
        if (status != AssistantStatus.IDLE) { eyesOpen = true; return@LaunchedEffect }
        while (true) {
            delay(2200L + (0L..3000L).random())
            eyesOpen = false
            delay(110L)
            eyesOpen = true
        }
    }
    val eyeAlpha by animateFloatAsState(
        targetValue = if (eyesOpen || status != AssistantStatus.IDLE) 1f else 0f,
        animationSpec = tween(70),
        label = "blink"
    )

    // ── Looping animations ────────────────────────────────────────────────
    val inf = rememberInfiniteTransition(label = "alfred_face")

    val breathe by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val thinkPhase by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "think"
    )
    val speakPhase by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing)),
        label = "speak"
    )

    val smoothRms by animateFloatAsState(
        targetValue = rmsLevel, animationSpec = tween(70), label = "rms"
    )

    Canvas(modifier = modifier) {
        val w    = size.width
        val h    = size.height
        val unit = minOf(w, h)
        val dot  = unit * 0.032f    // puntos algo más grandes

        val cx      = w / 2f
        val eyeY    = h * 0.30f    // ojos más arriba
        val eyeGap  = unit * 0.22f
        val mouthY  = h * 0.70f    // boca más abajo → cara más abierta

        val isIdle      = status == AssistantStatus.IDLE
        val isListening = status == AssistantStatus.LISTENING
        val isThinking  = status == AssistantStatus.THINKING

        val breathScale = if (isIdle) 1f + breathe * 0.04f else 1f
        val vibe        = if (isListening) smoothRms * unit * 0.018f else 0f

        // Eyes — hidden during thinking (replaced by orbital ring)
        if (!isThinking) {
            drawEye(Offset(cx - eyeGap, eyeY), dot * breathScale, eyeAlpha, vibe, side = -1f)
            drawEye(Offset(cx + eyeGap, eyeY), dot * breathScale, eyeAlpha, vibe, side =  1f)
        }

        // Mouth
        drawMouth(cx, mouthY, dot, status, breathe, thinkPhase, speakPhase, smoothRms)

        // Thinking — orbital ring of 8 dots
        if (isThinking) {
            val orbitR  = unit * 0.16f
            val orbitCy = h * 0.48f
            val twoPI   = (2 * PI).toFloat()
            repeat(8) { i ->
                val angle    = (thinkPhase + i / 8f) * twoPI
                val dotAlpha = ((sin(thinkPhase * twoPI - i * PI.toFloat() / 2) + 1) / 2f)
                    .coerceIn(0.12f, 1f)
                drawCircle(
                    color  = FaceWhite.copy(alpha = dotAlpha),
                    radius = dot * 0.82f,
                    center = Offset(cx + cos(angle) * orbitR, orbitCy + sin(angle) * orbitR * 0.50f)
                )
            }
        }

        // Listening — red recording indicator
        if (isListening) {
            drawCircle(
                color  = RecordRed.copy(alpha = 0.85f + smoothRms * 0.15f),
                radius = dot * (0.9f + smoothRms * 0.55f),
                center = Offset(cx, h * 0.85f)
            )
        }
    }
}

// ── Eye: 3×3 grid of dots, corners smaller ────────────────────────────────
private fun DrawScope.drawEye(
    center: Offset,
    dotR: Float,
    alpha: Float,
    vibe: Float,
    side: Float          // −1 left, +1 right
) {
    val spacing = dotR * 2.7f
    for (row in -1..1) {
        for (col in -1..1) {
            val isCorner = abs(row) == 1 && abs(col) == 1
            val r  = if (isCorner) dotR * 0.55f else dotR
            val vx = vibe * col * 0.65f * side
            val vy = vibe * row * 0.65f
            drawCircle(
                color  = FaceWhite.copy(alpha = alpha),
                radius = r,
                center = Offset(center.x + col * spacing + vx, center.y + row * spacing + vy)
            )
        }
    }
}

// ── Mouth: 7 dots in a curve, animated per state ─────────────────────────
// sin(t*PI) da un arco positivo en el centro (t=0.5).
// cy + arco → centro hacia abajo en pantalla = SONRISA (Y crece hacia abajo).
// cy - arco → centro hacia arriba = tristeza (bug anterior).
private fun DrawScope.drawMouth(
    cx: Float, cy: Float, dotR: Float,
    status: AssistantStatus,
    breathe: Float, thinkPhase: Float, speakPhase: Float, rms: Float
) {
    val n     = 7
    val halfW = dotR * 7f
    val twoPI = (2 * PI).toFloat()

    for (i in 0 until n) {
        val t     = i / (n - 1f)
        val x     = cx - halfW + t * 2f * halfW
        // Arco de sonrisa: positivo en el centro, cero en los extremos
        val smile = sin(t * PI.toFloat()) * dotR * MOUTH_SMILE

        val yPos = when (status) {
            AssistantStatus.IDLE ->
                cy + smile * (0.5f + breathe * 0.5f)
            AssistantStatus.LISTENING ->
                cy + smile +
                sin(t * PI.toFloat() * 2 + speakPhase * twoPI) * dotR * rms * 3.5f
            AssistantStatus.THINKING ->
                cy + sin(t * PI.toFloat() * 3 + thinkPhase * twoPI) * dotR * 1.8f
            AssistantStatus.SPEAKING ->
                cy + smile +
                sin(t * PI.toFloat() * 2 + speakPhase * twoPI) * dotR * 2.8f
        }

        val dotAlpha = if (status == AssistantStatus.THINKING) {
            val wave = (sin((t + thinkPhase) * twoPI) + 1) / 2f
            0.25f + wave * 0.75f
        } else 1f

        drawCircle(
            color  = FaceWhite.copy(alpha = dotAlpha),
            radius = dotR,
            center = Offset(x, yPos)
        )
    }
}
