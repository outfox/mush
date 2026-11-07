package dev.twodog.mush.scene

/** Reads just enough `.tscn` text to link document lines to scene nodes and Godot's errors, without a parser. */
internal object SceneText {
    private val SECTION = Regex("""^\[(gd_scene|gd_resource|ext_resource|sub_resource|resource|node|connection|editable)\b""")
    private val NODE_NAME = Regex("""\bname="((?:[^"\\]|\\.)*)"""")
    private val NODE_PARENT = Regex("""\bparent="((?:[^"\\]|\\.)*)"""")
    private val ESCAPE = Regex("""\\(.)""")
    private val TEMP_SCENE = Regex("""res://\.godot/mush/[^:\s'\]]+\.tscn""")
    private val TEMP_SCENE_LINE = Regex("""res://\.godot/mush/[^:\s'\]]+\.tscn:(\d+)""")

    /** The node path, relative to the scene root, of the `[node]` section holding [line]; null in any other section. */
    fun nodePathAt(text: CharSequence, line: Int): String? {
        val header = sectionHeaders(text).takeWhile { it.index <= line }.lastOrNull()?.value ?: return null
        return if (header.startsWith("[node ")) nodePath(header) else null
    }

    /**
     * The line of the `[node]` section for [path]. Nodes from an instanced scene or created by a script have no
     * section of their own, so this falls back to their nearest ancestor that does.
     */
    fun lineOfNode(text: CharSequence, path: String): Int? {
        val lines = HashMap<String, Int>()
        for ((index, header) in sectionHeaders(text)) {
            if (header.startsWith("[node ")) nodePath(header)?.let { lines.putIfAbsent(it, index) }
        }
        return generateSequence(path) { if ('/' in it) it.substringBeforeLast('/') else null }
            .firstNotNullOfOrNull(lines::get) ?: lines["."]
    }

    // A string value can run over lines and hold anything, including a line that looks just like a section.
    private fun sectionHeaders(text: CharSequence): Sequence<IndexedValue<String>> = sequence {
        var inString = false
        for ((index, line) in text.lineSequence().withIndex()) {
            if (!inString && SECTION.containsMatchIn(line)) yield(IndexedValue(index, line))
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '\\' -> i++
                    '"' -> inString = !inString
                }
                i++
            }
        }
    }

    private fun nodePath(header: String): String? {
        val name = NODE_NAME.find(header)?.let(::unescaped) ?: return null
        return when (val parent = NODE_PARENT.find(header)?.let(::unescaped)) {
            null -> "."
            "." -> name
            else -> "$parent/$name"
        }
    }

    // shortcut: decodes quotes and backslashes only, add the rest if a node name ever holds a control character.
    private fun unescaped(match: MatchResult): String = ESCAPE.replace(match.groupValues[1]) { it.groupValues[1] }

    /** The zero-based document line of an error Godot reports in its temp copy of this document. */
    fun problemLine(message: String): Int? =
        TEMP_SCENE_LINE.find(message)?.groupValues?.get(1)?.toIntOrNull()?.minus(1)?.coerceAtLeast(0)

    /** Godot reports errors against its temp copy; show them against the real file. */
    fun problemText(message: String, resPath: String): String = TEMP_SCENE.replace(message) { resPath }
}
