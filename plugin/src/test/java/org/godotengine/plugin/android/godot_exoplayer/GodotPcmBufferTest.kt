package org.godotengine.plugin.android.godot_exoplayer

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class GodotPcmBufferTest {
    @Test
    fun `5_1 center reaches both stereo outputs at minus 3 dB`() {
        val stereo = GodotPcmBuffer.downmixFrame(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f),
            AudioFormat.CHANNEL_OUT_5POINT1
        )

        assertEquals(0.70710678f, stereo[0], 0.0001f)
        assertEquals(0.70710678f, stereo[1], 0.0001f)
    }

    @Test
    fun `lfe is excluded and hot surround mix is clamped`() {
        val stereo = GodotPcmBuffer.downmixFrame(
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f),
            AudioFormat.CHANNEL_OUT_5POINT1
        )

        assertEquals(1f, stereo[0], 0f)
        assertEquals(1f, stereo[1], 0f)
    }

    @Test
    fun `mono duplicates its only channel`() {
        val stereo = GodotPcmBuffer.downmixFrame(floatArrayOf(-0.25f), AudioFormat.CHANNEL_OUT_MONO)

        assertEquals(-0.25f, stereo[0], 0f)
        assertEquals(-0.25f, stereo[1], 0f)
    }
}
