package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.TriggerDef

/** The «BYDMate started» condition, as the editor, the starter templates and the PowerState migration write it. */
internal fun serviceStartTrigger(displayName: String): TriggerDef = TriggerDef(
    param = "ServiceStart", chineseName = "服务启动", operator = "==", value = "true",
    displayName = displayName, kind = "service_start",
)
