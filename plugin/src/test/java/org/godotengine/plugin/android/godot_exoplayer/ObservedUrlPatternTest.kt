package org.godotengine.plugin.android.godot_exoplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservedUrlPatternTest {
    @Test
    fun `matches only URLs selected by the supplied regular expression`() {
        val pattern = ObservedUrlPattern.from("/watch/([0-9]+)/manifest\\.m3u8")

        assertTrue(pattern.matches("https://media.example/watch/42/manifest.m3u8?token=secret"))
        assertFalse(pattern.matches("https://media.example/watch/42/00001.ts?token=secret"))
    }

    @Test
    fun `rejects an invalid regular expression`() {
        try {
            ObservedUrlPattern.from("[")
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("invalid patterns must be rejected")
    }
}
