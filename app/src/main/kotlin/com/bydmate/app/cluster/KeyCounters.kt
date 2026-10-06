package com.bydmate.app.cluster

/**
 * What the steering-wheel key filter received since the process started, for the dump: whether
 * the keys reach us at all (#291, DiLink 4), which codes the car sends and how many went to a
 * rule or passed through. Counters instead of a trace line per press: the volume keys alone
 * wrote hundreds of `action=pass` lines a day. A key that types text is counted, never by code.
 */
class KeyCounters(private val maxCodes: Int = MAX_CODES) {

    private var received = 0
    private var matched = 0
    private var passed = 0
    private var text = 0
    private var otherCodes = 0
    private val byCode = LinkedHashMap<Int, Int>()

    /** The first DOWN of a press, whatever the filter decides about it. */
    @Synchronized
    fun onPress(keyCode: Int) {
        received++
        when {
            !isTraceableKey(keyCode) -> text++
            keyCode in byCode || byCode.size < maxCodes -> byCode[keyCode] = (byCode[keyCode] ?: 0) + 1
            else -> otherCodes++
        }
    }

    /** A press that went to the automation rules bound to its key. */
    @Synchronized
    fun onMatched() {
        matched++
    }

    /** A press nothing of ours took: it went on to its native function. */
    @Synchronized
    fun onPassed() {
        passed++
    }

    @Synchronized
    fun line(): String {
        val codes = byCode.entries.map { "${it.key}:${it.value}" }.toMutableList()
        if (text > 0) codes += "text:$text"
        if (otherCodes > 0) codes += "other:$otherCodes"
        return "keys: rx=$received matched=$matched passed=$passed by_code={${codes.joinToString(",")}}"
    }

    companion object {
        /** A head unit sends a dozen distinct codes at most; the rest is lumped together. */
        const val MAX_CODES = 16
    }
}
