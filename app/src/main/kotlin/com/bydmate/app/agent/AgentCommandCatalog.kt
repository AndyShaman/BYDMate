package com.bydmate.app.agent

/**
 * Readable command-id catalog for the vehicle_control agent tool. The LLM only
 * ever sees these ids and their Russian descriptions (in the tool schema and
 * in error messages); the internal Chinese protocol strings dispatched to
 * ActionDispatcher never reach the model or the user. Ids are hand-written and
 * stable (not generated from VoiceCatalog at runtime) so the tool contract
 * does not shift if VoiceCatalog changes; coverage against VoiceCatalog is
 * enforced by AgentCommandCatalogTest.
 */
object AgentCommandCatalog {
    data class Cmd(
        val id: String,
        val ru: String,
        val chinese: (Int?) -> String,
        val value: IntRange? = null,
    )

    val ALL: List<Cmd> = listOf(
        // Windows — composites
        Cmd("windows_open_all", "открыть все окна", { "车窗全开" }),
        Cmd("windows_close_all", "закрыть все окна", { "车窗关闭" }),
        Cmd("windows_vent_all", "все окна на проветривание", { "车窗通风" }),
        Cmd("windows_half_all", "все окна наполовину", { "车窗半开" }),
        Cmd("windows_open_front", "открыть передние окна", { "前排车窗全开" }),
        Cmd("windows_close_front", "закрыть передние окна", { "前排车窗关闭" }),
        Cmd("windows_open_rear", "открыть задние окна", { "后排车窗全开" }),
        Cmd("windows_close_rear", "закрыть задние окна", { "后排车窗关闭" }),
        // Windows — individual (open=100 / close=0)
        Cmd("window_driver_open", "открыть окно водителя", { "主驾打开100" }),
        Cmd("window_driver_close", "закрыть окно водителя", { "主驾打开0" }),
        Cmd("window_passenger_open", "открыть окно переднего пассажира", { "副驾打开100" }),
        Cmd("window_passenger_close", "закрыть окно переднего пассажира", { "副驾打开0" }),
        Cmd("window_rear_left_open", "открыть заднее левое окно", { "后左打开100" }),
        Cmd("window_rear_left_close", "закрыть заднее левое окно", { "后左打开0" }),
        Cmd("window_rear_right_open", "открыть заднее правое окно", { "后右打开100" }),
        Cmd("window_rear_right_close", "закрыть заднее правое окно", { "后右打开0" }),
        // Windows — individual vent (crack one window for fresh air)
        Cmd("window_driver_vent", "проветривание окна водителя", { "主驾通风" }),
        Cmd("window_passenger_vent", "проветривание окна переднего пассажира", { "副驾通风" }),
        Cmd("window_rear_left_vent", "проветривание заднего левого окна", { "后左通风" }),
        Cmd("window_rear_right_vent", "проветривание заднего правого окна", { "后右通风" }),
        // Climate
        Cmd("ac_on", "включить климат-контроль (авто)", { "自动空调" }),
        Cmd("ac_off", "выключить климат-контроль", { "关闭空调" }),
        Cmd("ac_flow_on", "включить вентиляцию климата (обдув без охлаждения)", { "打开空调通风" }),
        Cmd("ac_set_temp", "температура климата N°C", { n -> "设置温度$n" }, 16..30),
        Cmd("ac_recirc_inner", "внутренняя рециркуляция воздуха", { "内循环" }),
        Cmd("ac_recirc_outer", "внешняя циркуляция воздуха", { "外循环" }),
        Cmd("defrost_front_on", "включить обдув лобового стекла", { "吹前挡" }),
        Cmd("defrost_front_off", "выключить обдув лобового стекла", { "关闭吹前挡" }),
        Cmd("ac_auto_on", "включить авторежим климата (автоматический режим)", { "空调自动" }),
        Cmd("ac_auto_off", "выключить авторежим климата (ручной режим)", { "空调手动" }),
        // Seats — heat
        Cmd("seat_heat_driver_1", "подогрев сиденья водителя 1 уровень", { "主驾座椅加热1档" }),
        Cmd("seat_heat_driver_2", "подогрев сиденья водителя 2 уровень", { "主驾座椅加热2档" }),
        Cmd("seat_heat_driver_3", "подогрев сиденья водителя 3 уровень", { "主驾座椅加热3档" }),
        Cmd("seat_heat_driver_4", "подогрев сиденья водителя 4 уровень", { "主驾座椅加热4档" }),
        Cmd("seat_heat_driver_5", "подогрев сиденья водителя 5 уровень (максимум)", { "主驾座椅加热5档" }),
        Cmd("seat_heat_driver_off", "выключить подогрев сиденья водителя", { "主驾座椅加热关闭" }),
        Cmd("seat_heat_passenger_1", "подогрев сиденья пассажира 1 уровень", { "副驾座椅加热1档" }),
        Cmd("seat_heat_passenger_2", "подогрев сиденья пассажира 2 уровень", { "副驾座椅加热2档" }),
        Cmd("seat_heat_passenger_3", "подогрев сиденья пассажира 3 уровень", { "副驾座椅加热3档" }),
        Cmd("seat_heat_passenger_4", "подогрев сиденья пассажира 4 уровень", { "副驾座椅加热4档" }),
        Cmd("seat_heat_passenger_5", "подогрев сиденья пассажира 5 уровень (максимум)", { "副驾座椅加热5档" }),
        Cmd("seat_heat_passenger_off", "выключить подогрев сиденья пассажира", { "副驾座椅加热关闭" }),
        // Seats — vent
        Cmd("seat_vent_driver_1", "вентиляция сиденья водителя 1 уровень", { "主驾座椅通风1档" }),
        Cmd("seat_vent_driver_2", "вентиляция сиденья водителя 2 уровень", { "主驾座椅通风2档" }),
        Cmd("seat_vent_driver_3", "вентиляция сиденья водителя 3 уровень", { "主驾座椅通风3档" }),
        Cmd("seat_vent_driver_4", "вентиляция сиденья водителя 4 уровень", { "主驾座椅通风4档" }),
        Cmd("seat_vent_driver_5", "вентиляция сиденья водителя 5 уровень (максимум)", { "主驾座椅通风5档" }),
        Cmd("seat_vent_driver_off", "выключить вентиляцию сиденья водителя", { "主驾座椅通风关闭" }),
        Cmd("seat_vent_passenger_1", "вентиляция сиденья пассажира 1 уровень", { "副驾座椅通风1档" }),
        Cmd("seat_vent_passenger_2", "вентиляция сиденья пассажира 2 уровень", { "副驾座椅通风2档" }),
        Cmd("seat_vent_passenger_3", "вентиляция сиденья пассажира 3 уровень", { "副驾座椅通风3档" }),
        Cmd("seat_vent_passenger_4", "вентиляция сиденья пассажира 4 уровень", { "副驾座椅通风4档" }),
        Cmd("seat_vent_passenger_5", "вентиляция сиденья пассажира 5 уровень (максимум)", { "副驾座椅通风5档" }),
        Cmd("seat_vent_passenger_off", "выключить вентиляцию сиденья пассажира", { "副驾座椅通风关闭" }),
        // Mirrors
        Cmd("mirror_heat_on", "включить обогрев зеркал и заднего стекла", { "后视镜加热" }),
        Cmd("mirror_heat_off", "выключить обогрев зеркал и заднего стекла", { "关闭后视镜加热" }),
        // Lights
        Cmd("light_ambient_on", "включить атмосферную подсветку салона (амбиент)", { "氛围灯打开" }),
        Cmd("light_ambient_off", "выключить атмосферную подсветку салона (амбиент)", { "氛围灯关闭" }),
        Cmd("light_drl_on", "включить дневные ходовые огни", { "打开日行灯" }),
        Cmd("light_drl_off", "выключить дневные ходовые огни", { "关闭日行灯" }),
        Cmd("light_interior_on", "включить плафон света в салоне", { "打开车内灯" }),
        Cmd("light_interior_off", "выключить плафон света в салоне", { "关闭车内灯" }),
        // Locks
        Cmd("doors_lock", "запереть двери", { "车门上锁" }),
        Cmd("doors_unlock", "отпереть двери", { "车门解锁" }),
        // Sunroof / sunshade
        Cmd("sunroof_open", "открыть люк", { "天窗打开100" }),
        Cmd("sunroof_half", "приоткрыть люк наполовину", { "天窗打开50" }),
        Cmd("sunroof_close", "закрыть люк", { "天窗打开0" }),
        Cmd("sunroof_stop", "остановить движение люка", { "天窗停止" }),
        Cmd("sunroof_updip", "приподнять люк для проветривания (наклон)", { "天窗通风" }),
        Cmd("sunroof_comfort", "открыть люк в комфортное положение (без сквозняка)", { "天窗舒适打开" }),
        Cmd("sunshade_open", "открыть шторку люка", { "遮阳帘打开" }),
        Cmd("sunshade_close", "закрыть шторку люка", { "遮阳帘关闭" }),
        // Trunk (rear tailgate)
        Cmd("trunk_open", "открыть задний багажник", { "开后备箱" }),
        Cmd("trunk_close", "закрыть задний багажник", { "关后备箱" }),
        // Front trunk (powered front storage; open is speed-0 gated by the dispatcher)
        Cmd("front_trunk_open", "открыть передний багажник (только на стоянке)", { "前备箱打开" }),
        Cmd("front_trunk_close", "закрыть передний багажник", { "前备箱关闭" }),
        // Fridge (center-console icebox)
        Cmd("fridge_cool", "холодильник: охлаждение до N градусов", { n -> "冰箱制冷${n}度" }, -6..6),
        Cmd("fridge_heat", "холодильник: подогрев до N градусов", { n -> "冰箱制热${n}度" }, 35..50),
        Cmd("fridge_off", "выключить холодильник", { "冰箱关闭" }),
    )

    private val byId = ALL.associateBy { it.id }

    /** Resolve a readable id (+ optional value) to the internal Chinese
     *  dispatch string, or null if the id is unknown, or the value is missing
     *  / out of range for a ranged command. */
    fun resolve(id: String, value: Int?): String? {
        val cmd = byId[id] ?: return null
        val range = cmd.value
        if (range != null) {
            val v = value ?: return null
            if (v !in range) return null
            return cmd.chinese(v)
        }
        return cmd.chinese(null)
    }

    /** id — русское описание, one per line, for the vehicle_control tool description. */
    fun idsDoc(): String = ALL.joinToString("\n") { c ->
        if (c.value == null) "${c.id} — ${c.ru}"
        else "${c.id} — ${c.ru} (value=${c.value.first}..${c.value.last})"
    }
}
