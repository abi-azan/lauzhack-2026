package railstatus.mobile.screens.easyride

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T18 #5: the card hides a passed "decide by" and options that have left. */
class NextMoveClockTest {
    private fun at(h: Int, m: Int) = h * 60 + m

    @Test fun aTimeAMinuteBehindHasPassed() = assertTrue(isPassed("07:38", at(7, 39)))
    @Test fun theCurrentMinuteHasNotPassed() = assertFalse(isPassed("07:38", at(7, 38)))
    @Test fun aLaterTimeHasNotPassed() = assertFalse(isPassed("07:40", at(7, 38)))
    @Test fun lastNightsDeadlineHasPassedJustAfterMidnight() = assertTrue(isPassed("23:50", at(0, 5)))
    @Test fun anEarlyMorningTimeIsAheadLateAtNight() = assertFalse(isPassed("00:10", at(23, 55)))
    @Test fun withoutAClockNothingHasPassed() = assertFalse(isPassed("07:38", null))
    @Test fun aMalformedTimeNeverHides() = assertFalse(isPassed("soon", at(7, 39)))
}
