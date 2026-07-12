package com.bydmate.app.voice

enum class VoiceLang { RU, EN }

/** Surface words per slot, per language. This is the single place where
 *  phrasing flexibility lives: add a synonym here and the parser picks it
 *  up. Words are lowercase; matching is stem-based (see NluParser), so
 *  listing one form per inflection family is usually enough. Qualifier
 *  words (driver/passenger/front/rear/all) are separate device-distinguishers,
 *  combined by the parser. */
object VoiceLexicon {

    private val ACTION_RU: Map<ActionSlot, List<String>> = mapOf(
        ActionSlot.OPEN to listOf("открой", "открыть", "открывай", "опусти", "опустить"),
        ActionSlot.CLOSE to listOf("закрой", "закрыть", "закрывай", "подними", "поднять"),
        ActionSlot.ON to listOf("включи", "включить", "вкл", "запусти", "заблокируй", "запри"),
        ActionSlot.OFF to listOf("выключи", "выключить", "выкл", "отключи", "разблокируй", "отопри"),
        ActionSlot.SET to listOf("поставь", "установи", "сделай", "выстави"),
        ActionSlot.VENT to listOf("проветри", "проветрить", "проветривание"),
        ActionSlot.HEAT_1 to listOf("подогрев"),  // level disambiguated by number/strength below
        ActionSlot.HEAT_2 to emptyList(),
        ActionSlot.VENT_1 to listOf("вентиляция", "обдув"),
        ActionSlot.VENT_2 to emptyList(),
        ActionSlot.WARMER to listOf("теплее", "потеплее", "теплей", "погорячее"),
        ActionSlot.COOLER to listOf("холоднее", "похолоднее", "прохладнее", "попрохладнее"),
        ActionSlot.HALF to listOf("приоткрой", "приоткрыть", "приспусти", "наполовину"),
        ActionSlot.LOUDER to listOf("громче"),
        ActionSlot.QUIETER to listOf("тише"),
    )

    private val DEVICE_RU: Map<DeviceSlot, List<String>> = mapOf(
        DeviceSlot.WINDOW_ALL to listOf("окно", "окна", "стекло", "форточка", "стёкла"),
        DeviceSlot.WINDOW_FRONT to listOf("окно", "стекло"),
        DeviceSlot.WINDOW_REAR to listOf("окно", "стекло"),
        DeviceSlot.WINDOW_DRIVER to listOf("окно", "стекло", "форточка"),
        DeviceSlot.WINDOW_PASSENGER to listOf("окно", "стекло", "форточка"),
        DeviceSlot.WINDOW_REAR_LEFT to listOf("окно", "стекло"),
        DeviceSlot.WINDOW_REAR_RIGHT to listOf("окно", "стекло"),
        DeviceSlot.AC_AUTO to listOf("климат", "кондиционер", "кондей"),
        DeviceSlot.AC_FLOW to listOf("обдув", "вентиляция"),
        DeviceSlot.AC_TEMP to listOf("температура", "градус", "градусов"),
        DeviceSlot.AC_RECIRC_INNER to listOf("рециркуляция", "внутренний"),
        DeviceSlot.AC_RECIRC_OUTER to listOf("забор", "внешний"),
        DeviceSlot.DEFROST_FRONT to listOf("лобовое", "обдув"),
        DeviceSlot.SEAT_DRIVER_HEAT to listOf("сиденье", "кресло"),
        DeviceSlot.SEAT_PASSENGER_HEAT to listOf("сиденье", "кресло"),
        DeviceSlot.SEAT_DRIVER_VENT to listOf("сиденье", "кресло"),
        DeviceSlot.SEAT_PASSENGER_VENT to listOf("сиденье", "кресло"),
        DeviceSlot.MIRROR_HEAT to listOf("зеркало", "зеркала"),
        DeviceSlot.LIGHT_AMBIENT to listOf("амбиент", "подсветка"),
        DeviceSlot.LIGHT_DRL to listOf("дхо", "ходовые"),
        DeviceSlot.LIGHT_INTERIOR to listOf("салон", "свет"),
        DeviceSlot.LOCK to listOf("замок", "двери"),
        // CAR is deliberately separate from LOCK: "машина" is the only surface that
        // fast-paths open/close to lock/unlock — "закрой дверь" (cabin chatter, not
        // addressed to the car) must fall through to the agent, not silently unlock.
        DeviceSlot.CAR to listOf("машина"),
        DeviceSlot.SUNROOF to listOf("люк", "крыша"),
        DeviceSlot.SUNSHADE to listOf("шторка"),
        DeviceSlot.TRUNK to listOf("багажник"),
        DeviceSlot.VOLUME to listOf("громкость", "звук"),
    )

    private val ACTION_EN: Map<ActionSlot, List<String>> = mapOf(
        ActionSlot.OPEN to listOf("open", "lower", "roll", "down"),
        ActionSlot.CLOSE to listOf("close", "shut", "raise", "up"),
        ActionSlot.ON to listOf("on", "enable", "turn", "start", "lock"),
        ActionSlot.OFF to listOf("off", "disable", "stop", "unlock"),
        ActionSlot.SET to listOf("set", "make"),
        ActionSlot.VENT to listOf("vent", "ventilate"),
        ActionSlot.HEAT_1 to listOf("heat", "heating"),
        ActionSlot.HEAT_2 to emptyList(),
        ActionSlot.VENT_1 to listOf("ventilation", "cooling"),
        ActionSlot.VENT_2 to emptyList(),
        ActionSlot.WARMER to listOf("warmer", "hotter"),
        ActionSlot.COOLER to listOf("cooler", "colder"),
        ActionSlot.HALF to listOf("half", "tilt"),
        ActionSlot.LOUDER to listOf("louder"),
        ActionSlot.QUIETER to listOf("quieter"),
    )

    private val DEVICE_EN: Map<DeviceSlot, List<String>> = mapOf(
        DeviceSlot.WINDOW_ALL to listOf("window", "windows"),
        DeviceSlot.WINDOW_FRONT to listOf("window"),
        DeviceSlot.WINDOW_REAR to listOf("window"),
        DeviceSlot.WINDOW_DRIVER to listOf("window"),
        DeviceSlot.WINDOW_PASSENGER to listOf("window"),
        DeviceSlot.WINDOW_REAR_LEFT to listOf("window"),
        DeviceSlot.WINDOW_REAR_RIGHT to listOf("window"),
        DeviceSlot.AC_AUTO to listOf("climate", "ac", "conditioner"),
        DeviceSlot.AC_FLOW to listOf("airflow", "fan"),
        DeviceSlot.AC_TEMP to listOf("temperature", "degrees", "temp"),
        DeviceSlot.AC_RECIRC_INNER to listOf("recirculation", "recirculate"),
        DeviceSlot.AC_RECIRC_OUTER to listOf("fresh"),
        DeviceSlot.DEFROST_FRONT to listOf("windshield", "defog", "defrost"),
        DeviceSlot.SEAT_DRIVER_HEAT to listOf("seat"),
        DeviceSlot.SEAT_PASSENGER_HEAT to listOf("seat"),
        DeviceSlot.SEAT_DRIVER_VENT to listOf("seat"),
        DeviceSlot.SEAT_PASSENGER_VENT to listOf("seat"),
        DeviceSlot.MIRROR_HEAT to listOf("mirror", "mirrors"),
        DeviceSlot.LIGHT_AMBIENT to listOf("ambient"),
        DeviceSlot.LIGHT_DRL to listOf("drl", "daytime"),
        DeviceSlot.LIGHT_INTERIOR to listOf("interior", "cabin", "light"),
        DeviceSlot.LOCK to listOf("lock", "doors"),
        DeviceSlot.CAR to listOf("car"),
        DeviceSlot.SUNROOF to listOf("sunroof", "roof"),
        DeviceSlot.SUNSHADE to listOf("sunshade", "shade"),
        DeviceSlot.TRUNK to listOf("trunk", "boot"),
        DeviceSlot.VOLUME to listOf("volume", "sound"),
    )

    private val NUM_RU: Map<String, Int> = buildNumberWordsRu()
    private val NUM_EN: Map<String, Int> = buildNumberWordsEn()

    fun actionWords(lang: VoiceLang) = if (lang == VoiceLang.RU) ACTION_RU else ACTION_EN
    fun deviceWords(lang: VoiceLang) = if (lang == VoiceLang.RU) DEVICE_RU else DEVICE_EN
    fun numberWords(lang: VoiceLang) = if (lang == VoiceLang.RU) NUM_RU else NUM_EN

    private fun buildNumberWordsRu(): Map<String, Int> = mapOf(
        "ноль" to 0, "один" to 1, "два" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9,
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13,
        "четырнадцать" to 14, "пятнадцать" to 15,
        "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19,
        "двадцать" to 20, "двадцать один" to 21, "двадцать два" to 22, "двадцать три" to 23,
        "двадцать четыре" to 24, "двадцать пять" to 25, "двадцать шесть" to 26,
        "двадцать семь" to 27, "двадцать восемь" to 28, "двадцать девять" to 29, "тридцать" to 30,
    )

    private fun buildNumberWordsEn(): Map<String, Int> = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13,
        "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "twenty one" to 21, "twenty two" to 22, "twenty three" to 23,
        "twenty four" to 24, "twenty five" to 25, "twenty six" to 26,
        "twenty seven" to 27, "twenty eight" to 28, "twenty nine" to 29, "thirty" to 30,
    )
}
