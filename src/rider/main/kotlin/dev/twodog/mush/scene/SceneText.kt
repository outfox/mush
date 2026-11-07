package dev.twodog.mush.scene

/** Reads just enough `.tscn` text to link document lines to scene nodes and Godot's errors, without a parser. */
internal object SceneText {
    private val SECTION = Regex("""^\[(gd_scene|gd_resource|ext_resource|sub_resource|resource|node|connection|editable)\b""")
    private val NODE_NAME = Regex("""\bname="([^"]*)"""")
    private val NODE_PARENT = Regex("""\bparent="([^"]*)"""")
    private val TEMP_SCENE = Regex("""res://\.godot/mush/[^:\s'\]]+\.tscn""")
    private val TEMP_SCENE_LINE = Regex("""res://\.godot/mush/[^:\s'\]]+\.tscn:(\d+)""")

    /** The node path, relative to the scene root, of the `[node]` section holding [line]; null in any other section. */
    fun nodePathAt(text: CharSequence, line: Int): String? {
        var path: String? = null
        text.lineSequence().take(line + 1).forEach { current ->
            val section = SECTION.find(current)?.groupValues?.get(1) ?: return@forEach
            path = if (section == "node") nodePath(current) else null
        }
        return path
    }

    /**
     * The line of the `[node]` section for [path]. Nodes from an instanced scene or created by a script have no
     * section of their own, so this falls back to their nearest ancestor that does.
     */
    fun lineOfNode(text: CharSequence, path: String): Int? {
        val lines = HashMap<String, Int>()
        text.lineSequence().forEachIndexed { index, current ->
            if (current.startsWith("[node ")) nodePath(current)?.let { lines.putIfAbsent(it, index) }
        }
        return generateSequence(path) { if ('/' in it) it.substringBeforeLast('/') else null }
            .firstNotNullOfOrNull(lines::get) ?: lines["."]
    }

    private fun nodePath(header: String): String? {
        val name = NODE_NAME.find(header)?.groupValues?.get(1) ?: return null
        return when (val parent = NODE_PARENT.find(header)?.groupValues?.get(1)) {
            null -> "."
            "." -> name
            else -> "$parent/$name"
        }
    }

    /** The zero-based document line of an error Godot reports in its temp copy of this document. */
    fun problemLine(message: String): Int? =
        TEMP_SCENE_LINE.find(message)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.coerceAtLeast(0)

    /** Godot reports errors against its temp copy; show them against the real file. */
    fun problemText(message: String, resPath: String): String = TEMP_SCENE.replace(message) { resPath }
}
