package com.bydmate.app.voice

import com.bydmate.app.agent.AgentCommandCatalog

/** Readable names of dispatch strings for the voice journal: the Chinese protocol string
 *  never reaches the journal screen, the readable catalog id does («windows_open_all»,
 *  «ac_set_temp=22»). */
object VoiceCommandLabels {
    private val byCommand: Map<String, String> by lazy {
        buildMap {
            for (cmd in AgentCommandCatalog.ALL) {
                val range = cmd.value
                if (range == null) putIfAbsent(cmd.chinese(null), cmd.id)
                else for (v in range) putIfAbsent(cmd.chinese(v), "${cmd.id}=$v")
            }
        }
    }

    fun of(command: String): String = byCommand[command] ?: command

    fun of(commands: List<String>): String = commands.joinToString("+") { of(it) }
}
