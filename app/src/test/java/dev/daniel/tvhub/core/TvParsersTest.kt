package dev.daniel.tvhub.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TvParsersTest {
    @Test fun parsesNetflixStatus() {
        val out = "  mWakefulness=Awake\n" + TvParsers.SEP +
            "\n  mCurrentFocus=Window{4a1b2c u0 com.netflix.ninja/com.netflix.ninja.MainActivity}\n" + TvParsers.SEP + """
      package=com.netflix.ninja
      active=true
      state=PlaybackState {state=3, position=12345}
      metadata: size=5, description=Stranger Things, S4:E1, null
"""
        val (power, now) = TvParsers.parseStatus(out)
        assertEquals(Power.ON, power)
        assertEquals("Netflix", now?.appName)
        assertEquals("Stranger Things · S4:E1", now?.title)
        assertEquals("Playing", now?.playback)
    }

    @Test fun parsesLaunchables() {
        val apps = TvParsers.parseLaunchables("priority=0\n  com.netflix.ninja/.MainActivity\n")
        assertEquals(listOf("com.netflix.ninja" to "com.netflix.ninja/.MainActivity"), apps)
    }

    @Test fun quotesText() {
        assertEquals("'hi%sthere'", TvParsers.inputText("hi there"))
        assertEquals("'it'\\''s'", TvParsers.q("it's"))
    }
}
