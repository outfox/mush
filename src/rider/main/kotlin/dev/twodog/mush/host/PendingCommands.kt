package dev.twodog.mush.host

/** Access under the launcher's lock. Replaced state moves behind earlier control commands. */
internal class PendingCommands(private val capacity: Int = 32) {
    private val commands = ArrayDeque<String>()
    val size: Int get() = commands.size

    fun offer(command: String): Boolean {
        val key = when {
            command.startsWith("text ") -> "text "
            command.startsWith("view ") -> "view "
            command == "stats" -> "stats"
            else -> null
        }
        if (key != null) commands.removeAll { if (key == "stats") it == key else it.startsWith(key) }
        if (commands.size >= capacity) return false
        commands.addLast(command)
        return true
    }

    fun poll(): String? = commands.removeFirstOrNull()
    fun clear() = commands.clear()
}
