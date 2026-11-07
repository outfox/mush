package dev.twodog.mush.host

/** Access under the launcher's lock. Replaced state moves behind earlier control commands. */
internal class PendingCommands(private val capacity: Int = 32) {
    private val commands = ArrayDeque<String>()
    val size: Int get() = commands.size

    fun offer(command: String): Boolean {
        val verb = command.substringBefore(' ')
        if (verb in LATEST_ONLY) commands.removeAll { it.substringBefore(' ') == verb }
        if (commands.size >= capacity) return false
        commands.addLast(command)
        return true
    }

    fun poll(): String? = commands.removeFirstOrNull()
    fun clear() = commands.clear()

    private companion object {
        val LATEST_ONLY = setOf("text", "view", "stats", "select", "visible", "ping")
    }
}
