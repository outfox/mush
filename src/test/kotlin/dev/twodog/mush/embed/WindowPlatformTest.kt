package dev.twodog.mush.embed

import org.junit.Assert.*
import org.junit.Test

class WindowPlatformTest {
    @Test fun `Windows keeps its native backend and host arguments`() {
        val platform = WindowPlatform.detect("Windows 11", "sun.awt.windows.WToolkit")
        assertEquals(WindowPlatform.WINDOWS, platform)
        assertTrue(platform.hostArguments.isEmpty())
    }

    @Test fun `Linux X11 forces Godot to use the same window system`() {
        val platform = WindowPlatform.detect("Linux", "sun.awt.X11.XToolkit")
        assertEquals(WindowPlatform.X11, platform)
        assertEquals(listOf("--display-driver", "x11"), platform.hostArguments)
    }

    @Test fun `native Wayland is rejected even if XWayland is available in the session`() {
        assertEquals(WindowPlatform.UNSUPPORTED, WindowPlatform.detect("Linux", "sun.awt.wl.WLToolkit"))
    }

    @Test fun `unsupported and headless toolkits cannot launch an embedded host`() {
        assertEquals(WindowPlatform.UNSUPPORTED, WindowPlatform.detect("Mac OS X", "sun.lwawt.macosx.LWCToolkit"))
        assertEquals(WindowPlatform.UNSUPPORTED, WindowPlatform.detect("Linux", "sun.awt.HeadlessToolkit"))
    }
}
