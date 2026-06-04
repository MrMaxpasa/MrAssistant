package com.example.mrassistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.mrassistant.R

// Fuente de puntos Nothing — archivo: app/src/main/res/font/doto_regular.ttf
val DotoFamily: FontFamily = FontFamily(
    Font(R.font.doto_regular, FontWeight.Normal),
    Font(R.font.doto_regular, FontWeight.Bold)
)

// Monoespaciada legible para el chat — archivo: app/src/main/res/font/space_mono_regular.ttf
val SpaceMonoFamily: FontFamily = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal)
)

val Typography = Typography(
    bodyLarge = TextStyle(
        fontFamily    = SpaceMonoFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 14.sp,
        lineHeight    = 22.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily    = DotoFamily,
        fontWeight    = FontWeight.Normal,
        fontSize      = 10.sp,
        lineHeight    = 14.sp,
        letterSpacing = 2.sp
    )
)
