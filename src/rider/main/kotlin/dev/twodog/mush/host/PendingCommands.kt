package dev.twodog.mush.host

/** Access under the launcher's lock. Replaced state moves behind earlier control commands. */
internal class PendingCommands(private val capacity: Int = 32) {
    private val commands = ArrayDeque<String>()
    val size: Int get() = commands.size

    fun offer(command: String): Boolean {
        stateKey(command)?.let { key -> commands.removeAll { stateKey(it) == key } }
        if (commands.size >= capacity) return false
        commands.addLast(command)
        return true
    }

    fun poll(): String? = commands.removeFirstOrNull()
    fun clear() = commands.clear()

    private companion object {
        val STANDALONE = setOf("stats", "select", "ping")
        val NEEDS_PAYLOAD = setOf("text", "view", "visible")

        // Without a payload those are malformed, and must not evict a valid pending update.
        fun stateKey(command: String): String? {
            val verb = command.substringBefore(' ')
            return verb.takeIf { it in STANDALONE || (it in NEEDS_PAYLOAD && ' ' in command) }
        }
    }
}
