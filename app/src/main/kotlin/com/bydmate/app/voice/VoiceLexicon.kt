package com.bydmate.app.voice

/** Offline commands are Russian only; the value is kept for callers that still pass it. */
enum class VoiceLang { RU }

/** Surface words per slot. This is the single place where phrasing flexibility
 *  lives: add a synonym here and the parser picks it up. Words are lowercase with
 *  "е" for "ё" (VoiceNormalizer folds it); matching is stem-based (see NluParser), so
 *  listing one form per inflection family is usually enough. Qualifier words
 *  (driver/passenger/front/rear/all) and measure words (half, percent, levels) live
 *  in NluParser and VoiceNormalizer. Offline commands are Russian only. */
object VoiceLexicon {

    private val ACTIONS: Map<ActionSlot, List<String>> = mapOf(
        // "приоткрой"/"приспусти" open towards the vent detent (VoiceNormalizer.softVent).
        ActionSlot.OPEN to listOf(
            "открой", "открыть", "открывай", "откройте", "опусти", "опустить",
            "приоткрой", "приоткрыть", "приспусти", "приспустить",
        ),
        ActionSlot.CLOSE to listOf("закрой", "закрыть", "закрывай", "закройте", "подними", "поднять"),
        ActionSlot.ON to listOf("включи", "включить", "включите", "вкл", "запусти", "заблокируй", "запри"),
        ActionSlot.OFF to listOf(
            "выключи", "выключить", "выключите", "выкл", "отключи", "отключить", "разблокируй", "отопри",
        ),
        ActionSlot.SET to listOf("поставь", "установи", "сделай", "выстави"),
        ActionSlot.VENT to listOf("проветри", "проветрить", "проветривание", "проветривания"),
        ActionSlot.HEAT_1 to listOf("подогрев", "обогрев"),  // level disambiguated by number/strength below
        ActionSlot.HEAT_2 to emptyList(),
        ActionSlot.VENT_1 to listOf("вентиляция", "обдув"),
        ActionSlot.VENT_2 to emptyList(),
        ActionSlot.WARMER to listOf("теплее", "потеплее", "теплей", "погорячее"),
        ActionSlot.COOLER to listOf("холоднее", "похолоднее", "прохладнее", "попрохладнее"),
        // Half is a measure ("наполовину", "на половину", "пятьдесят процентов"), read by VoiceNormalizer.
        ActionSlot.HALF to emptyList(),
        ActionSlot.LOUDER to listOf("громче", "погромче"),
        ActionSlot.QUIETER to listOf("тише", "потише"),
    )

    private val DEVICES: Map<DeviceSlot, List<String>> = mapOf(
        DeviceSlot.WINDOW_ALL to listOf("окно", "окна", "стекло", "форточка", "окошко"),
        DeviceSlot.WINDOW_FRONT to listOf("окно", "стекло", "окошко"),
        DeviceSlot.WINDOW_REAR to listOf("окно", "стекло", "окошко"),
        DeviceSlot.WINDOW_DRIVER to listOf("окно", "стекло", "форточка", "окошко"),
        DeviceSlot.WINDOW_PASSENGER to listOf("окно", "стекло", "форточка", "окошко"),
        DeviceSlot.WINDOW_REAR_LEFT to listOf("окно", "стекло", "окошко"),
        DeviceSlot.WINDOW_REAR_RIGHT to listOf("окно", "стекло", "окошко"),
        DeviceSlot.AC_AUTO to listOf("климат", "кондиционер", "кондей"),
        DeviceSlot.AC_FLOW to listOf("обдув", "вентиляция"),
        DeviceSlot.AC_FAN to listOf("вентилятор"),
        DeviceSlot.AC_TEMP to listOf("температура", "градус", "градусов"),
        DeviceSlot.AC_RECIRC_INNER to listOf("рециркуляция", "внутренний"),
        DeviceSlot.AC_RECIRC_OUTER to listOf("забор", "внешний"),
        DeviceSlot.DEFROST_FRONT to listOf("лобовое", "обдув"),
        // "сидения" covers the plural family ("сидений"/"сидения"): it stems to
        // "сидени" while "сиденье"/"сиденья" stem to "сидень" — two distinct stems,
        // both must be listed or plural phrases miss the seat slot entirely and
        // "вентиляция сидений" falls through to cabin airflow (issue #98).
        DeviceSlot.SEAT_DRIVER_HEAT to listOf("сиденье", "сидения", "кресло"),
        DeviceSlot.SEAT_PASSENGER_HEAT to listOf("сиденье", "сидения", "кресло"),
        DeviceSlot.SEAT_DRIVER_VENT to listOf("сиденье", "сидения", "кресло"),
        DeviceSlot.SEAT_PASSENGER_VENT to listOf("сиденье", "сидения", "кресло"),
        DeviceSlot.MIRROR_HEAT to listOf("зеркало", "зеркала"),
        DeviceSlot.STEERING_HEAT to listOf("руль", "руля", "баранка"),
        DeviceSlot.LIGHT_AMBIENT to listOf("амбиент", "подсветка"),
        DeviceSlot.LIGHT_DRL to listOf("дхо", "ходовые"),
        DeviceSlot.LIGHT_INTERIOR to listOf("салон", "свет"),
        DeviceSlot.LOCK to listOf("замок", "двери"),
        // CAR is deliberately separate from LOCK: "машина" is the only surface that
        // fast-paths open/close to lock/unlock — "закрой дверь" (cabin chatter, not
        // addressed to the car) must fall through to the agent, not silently unlock.
        DeviceSlot.CAR to listOf("машина"),
        DeviceSlot.SUNROOF to listOf("люк", "крыша"),
        DeviceSlot.SUNSHADE to listOf("шторка", "штора"),
        DeviceSlot.TRUNK to listOf("багажник"),
        DeviceSlot.VOLUME to listOf("громкость", "звук"),
    )

    fun actionWords(): Map<ActionSlot, List<String>> = ACTIONS
    fun deviceWords(): Map<DeviceSlot, List<String>> = DEVICES
}
