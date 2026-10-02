package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maneuver dictionaries of Kom-BYDMate (English, exits in words) and OpenBYD 2.5
 * (YandexManager.getTurnIconFromManeuverText), mapped onto our numbering, read only what our own
 * parser leaves at 0: every maneuver read before reads the same.
 */
class NavManeuverCompetitorsTest {

    private fun gaode(text: String?) = NavManeuverCodes.fromA11yDescription(text)

    private fun resource(name: String): List<List<String>> =
        requireNotNull(javaClass.classLoader?.getResource(name)).readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("\t") }

    /** The corpus: the phrases of the current tests, our tables, the competitors' dictionaries and
     *  their variants, with what the parser gave for each before this change. */
    @Test fun `every phrase read before reads the same`() {
        val rows = resource("navdata/maneuver-corpus-golden.tsv")
        assertTrue(rows.size > 1_000)
        var known = 0
        rows.forEach { (raw, a11y, res) ->
            val text = raw.replace("\\u00A0", " ")
            if (a11y != "0") {
                known++
                assertEquals(raw, a11y.toInt(), gaode(text))
            }
            if (res != "0") assertEquals(raw, res.toInt(), NavManeuverCodes.fromNotificationRes(text))
        }
        assertTrue(known > 300)
    }

    @Test fun `the English navigator's maneuvers are read`() {
        resource("navdata/navigator-english-maneuvers.tsv").forEach { (desc, code) ->
            assertEquals(desc, code.toInt(), gaode(desc))
        }
    }

    @Test fun `English turns as Kom reads them`() {
        assertEquals(1, gaode("Turn left"))
        assertEquals(2, gaode("Turn right"))
        assertEquals(11, gaode("Continue"))
        assertEquals(11, gaode("Straight ahead"))
    }

    @Test fun `English slight, bear, keep, fork and veer are the slight turns`() {
        assertEquals(3, gaode("Slight left"))
        assertEquals(4, gaode("Bear right"))
        assertEquals(3, gaode("Keep left"))
        assertEquals(4, gaode("Fork right"))
        assertEquals(3, gaode("Veer left"))   // OpenBYD
        assertEquals(4, gaode("slight_right"))
    }

    @Test fun `English sharp and hard are the sharp turns`() {
        assertEquals(7, gaode("Sharp left"))
        assertEquals(8, gaode("Sharp right"))
        assertEquals(7, gaode("hard_left"))   // OpenBYD
        assertEquals(8, gaode("hard_turn_right"))
    }

    @Test fun `English u-turns`() {
        assertEquals(9, gaode("Make a U-turn"))
        assertEquals(9, gaode("Turn around"))
        assertEquals(9, gaode("Turn back"))   // OpenBYD
        assertEquals(10, gaode("Turn around to the right"))
    }

    @Test fun `English roundabout, ferry, tunnel, waypoint and arrival`() {
        assertEquals(13, gaode("Enter the roundabout"))
        assertEquals(13, gaode("Traffic circle"))
        assertEquals(24, gaode("Exit the roundabout"))
        assertEquals(46, gaode("Board the ferry"))
        assertEquals(49, gaode("Tunnel"))
        assertEquals(45, gaode("Via point"))
        assertEquals(45, gaode("Intermediate point"))   // OpenBYD
        assertEquals(48, gaode("You have arrived"))
        assertEquals(48, gaode("Destination"))
        assertEquals(48, gaode("Finish"))   // OpenBYD
        assertEquals(48, gaode("End of route"))   // OpenBYD
    }

    @Test fun `exits in words and in English ordinals`() {
        assertEquals(26, gaode("Второй съезд"))
        assertEquals(27, gaode("Третий съезд"))
        assertEquals(26, gaode("Second exit"))
        assertEquals(26, gaode("2nd exit"))
        assertEquals(26, gaode("Take the 2nd exit"))
        assertEquals(25, gaode("1st exit"))
        assertEquals(34, gaode("Take the 10th exit"))
        assertEquals(34, gaode("tenth exit"))
        assertEquals(26, gaode("Exit 2"))   // OpenBYD
        assertEquals(26, gaode("Съезд 2"))   // OpenBYD
        // Out of the 1..10 icons: the plain roundabout exit.
        assertEquals(24, gaode("Take the 11th exit"))
    }

    @Test fun `the exit number comes from the exit itself, not from a distance before it`() {
        assertEquals(26, gaode("Через 5 км второй съезд"))
        assertEquals(29, gaode("через 2 км пятый съезд"))
        assertEquals(26, gaode("In 300 m take the 2nd exit"))
        assertEquals(27, gaode("In 2 km take the third exit"))
        assertEquals(26, gaode("In 5 km take exit 2"))
        // A distance and no exit: what it read before.
        assertEquals(0, gaode("Через 5 км держитесь середины"))
        assertEquals(11, gaode("In 500 m continue straight"))
        assertEquals(0, gaode("Через 3 км съезд"))
    }

    @Test fun `stems inside other words are not maneuvers`() {
        assertEquals(0, gaode("Улица Кольцова"))
        assertEquals(0, gaode("Резкое исправление маршрута"))
        assertEquals(0, gaode("Улица Левашова"))
        assertEquals(0, gaode("Проспект Правды"))
        assertEquals(0, gaode("Плавникова улица"))
        assertEquals(0, gaode("Крутоярская улица"))
        assertEquals(0, gaode("Съездовская линия"))
        assertEquals(0, gaode("Улица Кругова"))
        assertEquals(0, gaode("Bearing Street"))
        assertEquals(0, gaode("Copyright"))
        assertEquals(0, gaode("Leftover lane"))
        assertEquals(0, gaode("Brighton Road"))
        assertEquals(0, gaode("Hardware Street"))
        assertEquals(0, gaode("Exiting soon"))
        assertEquals(0, gaode("Unfinished business"))
    }

    @Test fun `Russian variants OpenBYD reads and our tables did not`() {
        assertEquals(7, gaode("Круто влево"))
        assertEquals(8, gaode("Резко вправо"))
        assertEquals(3, gaode("Плавно влево"))
        assertEquals(4, gaode("Плавно вправо"))
        assertEquals(13, gaode("Кольцевая развязка"))
        assertEquals(13, gaode("Круг"))
        assertEquals(9, gaode("Разворачивайтесь"))
    }

    @Test fun `OpenBYD's transliterated and icon names`() {
        assertEquals(11, gaode("pryamo"))
        assertEquals(11, gaode("vpered"))
        assertEquals(1, gaode("levo"))
        assertEquals(2, gaode("pravyj"))
        assertEquals(3, gaode("polu_levo"))
        assertEquals(4, gaode("vetvlenie_pravo"))
        assertEquals(7, gaode("kruto_levo"))
        assertEquals(9, gaode("razvorot"))
        assertEquals(13, gaode("kolco"))
        assertEquals(48, gaode("konec"))
        assertEquals(48, gaode("pribytie"))
        assertEquals(11, gaode("notification_go_ahead_sdl"))
        assertEquals(8, gaode("notification_exit_right_sdl"))   // our own icon table
    }

    @Test fun `notification icons OpenBYD knows and our table did not`() {
        assertEquals(11, NavManeuverCodes.fromNotificationRes("notification_go_ahead_sdl"))
        assertEquals(48, NavManeuverCodes.fromNotificationRes("notification_arrive_sdl"))
        assertEquals(11, NavManeuverCodes.fromNotificationRes("notification_leave_ferry_sdl"))
        assertEquals(9, NavManeuverCodes.fromNotificationRes("notification_uturn_sdl"))
    }

    @Test fun `a maneuver nobody reads stays unknown, not straight`() {
        assertEquals(0, gaode("Rechts abbiegen"))
        assertEquals(0, gaode("Держитесь середины"))
        assertEquals(0, gaode("2-й поворот"))
        assertEquals(0, gaode("x"))
    }

    @Test fun `what our parser read first is not overridden`() {
        // Ours gives plain left here; OpenBYD reads a sharp left. Ours stays.
        assertEquals(1, gaode("Круто поверните налево"))
        // Ours sees the roundabout before the exit word.
        assertEquals(13, gaode("Кольцевое движение, второй съезд"))
        assertEquals(46, gaode("Пересадка на паром"))
        // Ours reads the roundabout exit without its number.
        assertEquals(24, gaode("Третий съезд с кольца"))
        // Ours reads any «u-turn» as the left-hand one; Kom would read the right-hand one here.
        assertEquals(9, gaode("U-turn right"))
    }
}
