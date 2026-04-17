// app/src/test/kotlin/com/bydmate/app/data/automation/InsightToneLogicTest.kt
package com.bydmate.app.data.automation

import org.junit.Test
import org.junit.Assert.assertEquals

class InsightToneLogicTest {

    @Test fun consumption_flat_or_improving_is_good() {
        assertEquals("good", InsightToneLogic.consumptionTone(null))
        assertEquals("good", InsightToneLogic.consumptionTone(-10.0))
        assertEquals("good", InsightToneLogic.consumptionTone(0.0))
        assertEquals("good", InsightToneLogic.consumptionTone(5.0))
    }

    @Test fun consumption_slightly_worse_is_warning() {
        assertEquals("warning", InsightToneLogic.consumptionTone(5.01))
        assertEquals("warning", InsightToneLogic.consumptionTone(10.0))
        assertEquals("warning", InsightToneLogic.consumptionTone(15.0))
    }

    @Test fun consumption_much_worse_is_critical() {
        assertEquals("critical", InsightToneLogic.consumptionTone(15.01))
        assertEquals("critical", InsightToneLogic.consumptionTone(30.0))
    }

    @Test fun voltage12v_good_at_or_above_124() {
        assertEquals("good", InsightToneLogic.voltage12vTone(null))
        assertEquals("good", InsightToneLogic.voltage12vTone(12.4))
        assertEquals("good", InsightToneLogic.voltage12vTone(14.0))
    }

    @Test fun voltage12v_warning_118_to_124() {
        assertEquals("warning", InsightToneLogic.voltage12vTone(11.8))
        assertEquals("warning", InsightToneLogic.voltage12vTone(12.0))
        assertEquals("warning", InsightToneLogic.voltage12vTone(12.39))
    }

    @Test fun voltage12v_critical_below_118() {
        assertEquals("critical", InsightToneLogic.voltage12vTone(11.79))
        assertEquals("critical", InsightToneLogic.voltage12vTone(10.0))
    }

    @Test fun cellDelta_good_at_or_below_003() {
        assertEquals("good", InsightToneLogic.cellDeltaTone(null, null))
        assertEquals("good", InsightToneLogic.cellDeltaTone(3.35, 3.34))
        assertEquals("good", InsightToneLogic.cellDeltaTone(3.37, 3.34))
    }

    @Test fun cellDelta_warning_003_to_005() {
        assertEquals("warning", InsightToneLogic.cellDeltaTone(3.38, 3.34))
        assertEquals("warning", InsightToneLogic.cellDeltaTone(3.39, 3.34))
    }

    @Test fun cellDelta_critical_above_005() {
        assertEquals("critical", InsightToneLogic.cellDeltaTone(3.40, 3.34))
    }

    @Test fun worst_picks_highest_severity() {
        assertEquals("good", InsightToneLogic.worst("good", "good", "good"))
        assertEquals("warning", InsightToneLogic.worst("good", "warning", "good"))
        assertEquals("critical", InsightToneLogic.worst("warning", "good", "critical"))
        assertEquals("critical", InsightToneLogic.worst("critical", "good", "good"))
    }

    @Test fun worst_handles_unknown_as_good() {
        assertEquals("warning", InsightToneLogic.worst("unknown", "warning", "good"))
    }
}
