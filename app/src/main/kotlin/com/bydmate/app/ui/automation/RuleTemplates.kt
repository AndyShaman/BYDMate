package com.bydmate.app.ui.automation

import com.bydmate.app.data.automation.serviceStartTrigger
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND

/** Disabled starter rules a fresh install gets once, named in [lang]. */
@Suppress("LongMethod") // a data table: one entry per template
internal fun starterTemplates(lang: String): List<RuleEntity> {
    fun tName(zh: String, en: String, ru: String): String = when (lang) { "zh" -> zh; "ru", "be" -> ru; else -> en }

    return listOf(
        RuleEntity(
            name = tName("高速关窗", "Close windows on highway", "Закрыть окна на трассе"),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("Speed", "车速", ">", "100",
                    tName("车速 > 100 km/h", "Speed > 100 km/h", "Скорость > 100 км/ч"))
            )),
            actions = ActionDef.listToJson(listOf(
                ActionDef("车窗关闭",
                    tName("关闭所有车窗", "Close All Windows", "Закрыть все окна"))
            )),
            cooldownSeconds = 60
        ),
        RuleEntity(
            name = tName("冬季启动", "Winter start", "Зимний старт"),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("ExtTemp", "车外温度", "<", "0",
                    tName("车外温度 < 0°C", "Outside Temp < 0°C", "Темп. снаружи < 0°C")),
                serviceStartTrigger(tName("BYDMate 启动", "BYDMate startup", "Запуск BYDMate"))
            )),
            actions = ActionDef.listToJson(listOf(
                ActionDef("主驾座椅加热2档",
                    tName("主驾座椅加热2档", "Driver Heat 2", "Подогрев водителя 2")),
                ActionDef("后视镜加热",
                    tName("后视镜加热开", "Mirror Heat On", "Подогрев зеркал вкл"))
            )),
            cooldownSeconds = 600
        ),
        RuleEntity(
            name = tName("低电量ECO", "ECO at low SOC", "Эко при низком заряде"),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("SOC", "电量百分比", "<", "15", "SOC < 15%")
            )),
            actions = ActionDef.listToJson(listOf(
                ActionDef("ECO模式", tName("ECO 模式", "ECO Mode", "ECO режим"))
            )),
            cooldownSeconds = 300
        ),
        RuleEntity(
            name = tName("夏季制冷", "Summer cooling", "Летнее охлаждение"),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("InsideTemp", "车内温度", ">", "30",
                    tName("车内温度 > 30°C", "Cabin Temp > 30°C", "Темп. салона > 30°C")),
                serviceStartTrigger(tName("BYDMate 启动", "BYDMate startup", "Запуск BYDMate"))
            )),
            actions = ActionDef.listToJson(listOf(
                ActionDef("主驾座椅通风1档",
                    tName("主驾座椅通风1档", "Driver Vent 1", "Вентиляция водителя 1")),
                ActionDef("自动空调",
                    tName("自动空调", "Auto AC", "Авто AC"))
            )),
            cooldownSeconds = 600
        ),
        RuleEntity(
            name = tName("行驶开遮阳帘", "Sunshade while driving", "Шторка при движении"),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("Gear", "档位", "==", "4",
                    tName("档位 = D", "Gear = D", "Передача = D"))
            )),
            actions = ActionDef.listToJson(listOf(
                ActionDef("遮阳帘打开",
                    tName("遮阳帘打开", "Sunshade Open", "Открыть шторку"))
            )),
            cooldownSeconds = 600
        ),
        RuleEntity(
            name = tName("充电时空调", "Climate while charging", "Климат при зарядке"),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("ChargingStatus", "充电状态", "==", "2",
                    tName("充电状态 = 充电中", "Charging Status = Charging", "Зарядка = Начата")),
                TriggerDef("ExtTemp", "车外温度", "<", "5",
                    tName("车外温度 < 5°C", "Outside Temp < 5°C", "Темп. снаружи < 5°C"))
            )),
            actions = ActionDef.listToJson(listOf(
                ActionDef("自动空调",
                    tName("自动空调", "Auto AC", "Авто AC")),
                ActionDef("主驾座椅加热1档",
                    tName("主驾座椅加热1档", "Driver Heat 1", "Подогрев водителя 1"))
            )),
            cooldownSeconds = 600
        ),
    ) + telegramReportTemplates(lang)
}

/** The two Telegram report rules of 3.19, disabled: also added once to installs made before it. */
internal fun telegramReportTemplates(lang: String): List<RuleEntity> {
    // A local six-language helper: the shared tName above only knows zh/en/ru and falls back to
    // ru for be, en for pl/pt, which ends up in the sent report header (finding #4, 2026-09-27).
    fun tName(zh: String, en: String, ru: String, be: String, pl: String, pt: String): String = when (lang) {
        "zh" -> zh
        "ru" -> ru
        "be" -> be
        "pl" -> pl
        "pt" -> pt
        else -> en
    }
    val reportName = tName(
        "Telegram 报告", "Telegram report", "Отчёт в Telegram",
        "Справаздача ў Telegram", "Raport w Telegramie", "Relatório no Telegram",
    )
    fun report(vararg fields: ReportField) = ActionDef(
        command = "",
        displayName = reportName,
        kind = TELEGRAM_REPORT_KIND,
        payload = telegramReportPayload(fields.toSet(), ""),
    )
    return listOf(
        RuleEntity(
            name = tName(
                "车在哪里", "Where the car is", "Где машина",
                "Дзе машына", "Gdzie jest samochód", "Onde está o carro",
            ),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                TriggerDef("Gear", "档位", "==", "1", tName(
                    "档位 = P", "Gear = P", "Передача = P", "Перадача = P", "Bieg = P", "Marcha = P",
                ))
            )),
            actions = ActionDef.listToJson(listOf(report(ReportField.LOCATION, ReportField.SOC, ReportField.RANGE))),
            cooldownSeconds = 60
        ),
        RuleEntity(
            name = tName(
                "启动时状态", "Status at start", "Статус при запуске",
                "Стан пры запуску", "Stan przy uruchomieniu", "Status ao iniciar",
            ),
            enabled = false,
            triggerLogic = "AND",
            triggers = TriggerDef.listToJson(listOf(
                serviceStartTrigger(tName(
                    "BYDMate 启动", "BYDMate startup", "Запуск BYDMate",
                    "Запуск BYDMate", "Uruchomienie BYDMate", "Inicialização do BYDMate",
                ))
            )),
            actions = ActionDef.listToJson(listOf(report(ReportField.SOC, ReportField.RANGE, ReportField.TRIP))),
            cooldownSeconds = 60
        ),
    )
}
