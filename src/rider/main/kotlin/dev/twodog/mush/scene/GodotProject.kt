package dev.twodog.mush.scene

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore

/** Maps files to their Godot project, the nearest ancestor directory holding `project.godot`. */
object GodotProject {
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
}
