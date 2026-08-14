package org.godotengine.plugin.android.godot_exoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BufferDurationsTest {
    @Test
    fun `accepts a valid resilient streaming profile`() {
        val durations = BufferDurations.fromValues(30_000, 60_000, 10_000, 15_000)

        assertEquals(60_000, durations.maxBufferMs)
    }

    @Test
    fun `rejects playback duration above minimum buffer`() {
        assertThrows(IllegalArgumentException::class.java) {
            BufferDurations.fromValues(10_000, 60_000, 15_000, 5_000)
        }
    }
}
