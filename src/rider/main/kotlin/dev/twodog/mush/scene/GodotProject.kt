package dev.twodog.mush.scene

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path

/** Maps files to their Godot project, the nearest ancestor directory holding `project.godot`. */
object GodotProject {
    /** A host boots here so the game's main scene never runs. Booting the opened scene would die on a broken file. */
    const val BLANK_SCENE = "res://.godot/mush/blank.tscn"

    private val SCENE_DEPENDENCIES = setOf("tscn", "tres", "gdshader", "gdshaderinc")

    fun root(file: VirtualFile): VirtualFile? {
        var dir = file.parent
        while (dir != null) {
            if (dir.findChild("project.godot") != null)
                return dir
            dir = dir.parent
        }
        return null
    }

    fun resPath(file: VirtualFile): String? {
        val root = root(file) ?: return null
        return VfsUtilCore.getRelativePath(file, root, '/')?.let { "res://$it" }
    }

    /** `.godot` holds mush's own temp scenes, so reloading on changes there would never stop. */
    fun isSceneDependency(path: String, root: String, scene: String): Boolean =
        path != scene && path.startsWith("$root/") && !path.startsWith("$root/.godot/") &&
            path.substringAfterLast('.').lowercase() in SCENE_DEPENDENCIES

    fun ensureBlankScene(root: String) {
        val dir = Path.of(root, ".godot", "mush")
        val blank = dir.resolve("blank.tscn")
        if (Files.exists(blank)) return
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, "blank", ".tmp")
        Files.writeString(temp, "[gd_scene format=3]\n\n[node name=\"MushBlank\" type=\"Node\"]\n")
        // A rename publishes the whole file at once and never replaces one: ATOMIC_MOVE fails on Windows while another
        // editor's host is booting from it.
        try {
            Files.move(temp, blank)
        } catch (_: FileAlreadyExistsException) {
            Files.delete(temp)
        }
    }
}
