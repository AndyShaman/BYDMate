package com.bydmate.app.data.autoservice

/** [RestoreFakeSystem] plus the members the ADB restore rework adds to [AdbRestoreSystem]. */
class RestoreFakeSystemV2 : RestoreFakeSystem() {
    var adbEnabled = 0
    var inProcessResult = true
    /** BYD system properties as they read now; absent key reads as null. */
    val props = mutableMapOf<String, String>()
    var propWriteAccepted = true

    override fun enableAccessibilityServiceInProcess(): Boolean {
        calls += "a11y-inprocess"
        if (inProcessResult) a11yBound = true
        return inProcessResult
    }
    override fun readAdbEnabled(): Int {
        calls += "read adb_enabled"
        return adbEnabled
    }
    override fun writeAdbEnabled(value: Int): Boolean {
        calls += "adb_enabled=$value"
        adbEnabled = value
        return true
    }
    override fun readBydAdbProp(key: String): String? = props[key]
    override fun writeBydAdbProp(key: String, value: String): Boolean {
        calls += "prop $key=$value"
        if (propWriteAccepted) props[key] = value
        return propWriteAccepted
    }

    companion object {
        const val PROP_ENABLE = "persist.sys.adb.wiress.enable"
        const val PROP_CONNECT = "sys.connect.adb.wiress"
    }
}
