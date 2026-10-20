package dev.twodog.mush.embed

import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * JAWT is the only way to reach an AWT component's Core Animation layer, and JNA cannot call it: JAWT's entry
 * points take a `JNIEnv` and a `jobject`, neither of which JNA can produce. Windows and X11 avoid this because
 * their components own a real native window whose handle JNA can read; macOS AWT is lightweight, so a Canvas has
 * no view of its own and JAWT has to hand one out. Hence one small Objective-C library.
 *
 * The native methods are deliberately public. Kotlin mangles the JVM names of `internal` functions, which would
 * leave JNI unable to bind them.
 */
internal object MushNative {
    /** Why the library is unavailable, phrased for the editor's status line, or null once it has loaded. */
    val failure: String? by lazy { load() }

    val isAvailable: Boolean get() = failure == null

    external fun attachLayerHost(component: Component, contextId: Int, scale: Double): Boolean

    external fun setLayerState(component: Component, visible: Boolean, scale: Double): Boolean

    external fun detachLayer(component: Component): Boolean

    /** Rectangles, as a flat x/y/width/height sequence in the component's own coordinates, to clip out. */
    external fun setLayerCutouts(component: Component, rects: IntArray): Boolean

    /** The hosted container's size in points, packed width:height, or -1 when nothing is hosted. */
    external fun layerSize(component: Component): Long

    /** The layer tree's real geometry, for diagnostics. */
    external fun layerState(component: Component): String

    /** Resolves `JAWT_GetAWT` out of the running JVM, so the library carries no path to a build-time JDK. */
    private external fun initJawt(libjawtPath: String): Boolean

    /** Returns null when the library is ready to use, or the reason it is not. */
    private fun load(): String? {
        try {
            val library = libraryPath()
            if (library == null || !Files.isRegularFile(library)) {
                LOG.warn("no viewport library at $library")
                return "The viewport's native library is missing from the plugin. Build it on macOS with gradlew buildPlugin."
            }
            System.load(library.toString())

            val libjawt = "${System.getProperty("java.home")}/lib/libjawt.dylib"
            if (!initJawt(libjawt)) {
                LOG.warn("could not load $libjawt")
                return "The viewport could not load $libjawt."
            }
            LOG.info("loaded $library against $libjawt")
            return null
        } catch (e: Throwable) {
            LOG.warn("native viewport support failed to load", e)
            return "The viewport's native library failed to load: ${e.message}"
        }
    }

    /** Beside the plugin's own jars, where `prepareSandbox` installs it, as with the backend's DLLs. */
    private fun libraryPath(): Path? =
        PluginManager.getPluginByClass(MushNative::class.java)?.pluginPath?.resolve(LIBRARY_PATH)

    private const val LIBRARY_PATH = "native/libmush_embed.dylib"
    private val LOG = logger<MushNative>()
}
