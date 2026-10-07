package com.noisemachine.app

import androidx.compose.ui.graphics.Color

enum class NoiseType(
    val displayName: String,
    val topColor: Color,
    val bottomColor: Color,
    val glowColor: Color,
    val waveColor: Color,
    val waveFreq: Float,
    val waveAmp: Float,
    val waveSpeed: Float,
    /** Dark ARGB tint for the colorized media notification background. */
    val notifTint: Int
) {
    WHITE(
        "White",
        Color(0xFFC9CFD8), Color(0xFFA8B0BC), Color(0xFFBEC6D4), Color(0xFF3B475C),
        waveFreq = 0.055f, waveAmp = 9f, waveSpeed = 0.16f,
        notifTint = 0xFF46536B.toInt()
    ),
    PINK(
        "Pink",
        Color(0xFFD693B1), Color(0xFFBD7A98), Color(0xFFD287A5), Color(0xFF7C2D4E),
        waveFreq = 0.035f, waveAmp = 15f, waveSpeed = 0.09f,
        notifTint = 0xFF8A3D5E.toInt()
    ),
    BROWN(
        "Brown",
        Color(0xFF5D3D22), Color(0xFF33200E), Color(0xFF785032), Color(0xFFB48259),
        waveFreq = 0.020f, waveAmp = 23f, waveSpeed = 0.05f,
        notifTint = 0xFF5D3D22.toInt()
    ),
    GREEN(
        "Green",
        Color(0xFF234A30), Color(0xFF102B1A), Color(0xFF326E4B), Color(0xFF649B73),
        waveFreq = 0.024f, waveAmp = 20f, waveSpeed = 0.065f,
        notifTint = 0xFF234A30.toInt()
    );
}
