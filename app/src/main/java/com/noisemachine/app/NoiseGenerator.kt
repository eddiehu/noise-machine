package com.noisemachine.app

import kotlin.math.sqrt
import kotlin.random.Random

object NoiseGenerator {
    const val SAMPLE_RATE = 44100
    private const val SECONDS = 4

    /** Generates a seamlessly-looping 16-bit mono PCM buffer for the given noise type. */
    fun generate(type: NoiseType): ShortArray {
        val len = SAMPLE_RATE * SECONDS
        val out = FloatArray(len)
        val r = Random.Default
        when (type) {
            NoiseType.WHITE -> {
                for (i in 0 until len) out[i] = (r.nextFloat() * 2f - 1f) * 0.5f
            }
            NoiseType.PINK -> {
                // Paul Kellet's refined pink noise filter
                var b0 = 0.0; var b1 = 0.0; var b2 = 0.0
                var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
                for (i in 0 until len) {
                    val w = r.nextDouble() * 2 - 1
                    b0 = 0.99886 * b0 + w * 0.0555179
                    b1 = 0.99332 * b1 + w * 0.0750759
                    b2 = 0.96900 * b2 + w * 0.1538520
                    b3 = 0.86650 * b3 + w * 0.3104856
                    b4 = 0.55000 * b4 + w * 0.5329522
                    b5 = -0.7616 * b5 - w * 0.0168980
                    out[i] = ((b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.055).toFloat()
                    b6 = w * 0.115926
                }
            }
            NoiseType.BROWN -> {
                var last = 0.0
                for (i in 0 until len) {
                    val w = r.nextDouble() * 2 - 1
                    last = (last + 0.02 * w) / 1.02
                    out[i] = (last * 1.75).toFloat()
                }
            }
            NoiseType.GREEN -> {
                // "Green noise": mid-frequency focused (~500 Hz), like a gentle
                // stream. White noise through a two-pole lowpass (~800 Hz)
                // followed by a highpass (~300 Hz).
                val lpA = 0.1077   // 1 - exp(-2π*800/44100)
                val hpB = 0.9582   // exp(-2π*300/44100)
                var s1 = 0.0
                var s2 = 0.0
                var hy = 0.0
                var xp = 0.0
                for (i in 0 until len) {
                    val w = r.nextDouble() * 2 - 1
                    s1 += lpA * (w - s1)
                    s2 += lpA * (s1 - s2)
                    val y = hpB * (hy + s2 - xp)
                    xp = s2
                    hy = y
                    out[i] = (y * 2.32).toFloat()
                }
            }
        }
        // Loop crossfade: blend the head into the tail so the loop point is
        // seamless. Equal-power (sqrt) weighting: a linear blend of
        // uncorrelated noise dips ~3 dB mid-fade, heard as a periodic gap.
        val fade = SAMPLE_RATE / 4
        val n = len - fade
        val pcm = ShortArray(n)
        for (i in 0 until n) {
            var s = out[i]
            if (i < fade) {
                val t = i / fade.toFloat()
                s = out[i] * sqrt(t) + out[n + i] * sqrt(1f - t)
            }
            pcm[i] = (s.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
        return pcm
    }
}
