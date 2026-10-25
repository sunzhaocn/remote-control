package com.simplerc.app.control

import com.simplerc.app.geometry.RectPx
import org.junit.Assert.*
import org.junit.Test

class LocalTouchInputGateTest {
    private val screen = RectPx(0f, 0f, 400f, 800f)
    private val navigation = RectPx(200f, 500f, 150f, 100f)
    private fun gate() = LocalTouchInputGate().apply { update(resumed = true, focused = true) }

    @Test fun downMustStartInsideVideoAndOutsideNavigation() {
        val gate = gate()
        assertNotNull(gate.begin(100f, 400f, screen, navigation))
        assertNull(gate.begin(250f, 550f, screen, navigation))
        assertNull(gate.begin(-1f, 400f, screen, navigation))
        assertNull(gate.begin(Float.NaN, 400f, screen, navigation))
    }

    @Test fun existingFingerKeepsOwnershipWhenItCrossesNavigation() {
        val gate = gate()
        val token = gate.begin(100f, 550f, screen, navigation)!!
        // The next MOVE is over the menu, where a new DOWN would be rejected.
        assertNull(gate.begin(250f, 550f, screen, navigation))
        assertTrue(gate.accepts(token))
    }

    @Test fun navigationRelayoutOnlyChangesWhichNewDownsAreAccepted() {
        val gate = gate()
        val token = gate.begin(100f, 550f, screen, navigation)!!
        val movedNavigation = RectPx(50f, 500f, 150f, 100f)
        assertNull(gate.begin(100f, 550f, screen, movedNavigation))
        assertNotNull(gate.begin(250f, 550f, screen, movedNavigation))
        assertTrue(gate.accepts(token))
    }

    @Test fun pausingCancelsImmediatelyAndResumeCannotReviveTheOldFinger() {
        val gate = gate()
        val old = gate.begin(100f, 400f, screen, navigation)!!
        assertTrue(gate.update(resumed = false, focused = true))
        assertFalse(gate.accepts(old))
        assertNull(gate.begin(100f, 400f, screen, navigation))
        assertFalse(gate.update(resumed = true, focused = true))
        assertFalse(gate.accepts(old))
        val fresh = gate.begin(100f, 400f, screen, navigation)!!
        assertNotEquals(old, fresh)
        assertTrue(gate.accepts(fresh))
    }

    @Test fun fastFocusLossAndRegainStillInvalidatesTheSuspendedFinger() {
        val gate = gate()
        val old = gate.begin(100f, 400f, screen, navigation)!!
        // Both notifications may arrive before the next Compose frame.
        assertTrue(gate.update(resumed = true, focused = false))
        assertFalse(gate.update(resumed = true, focused = true))
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(gate.begin(100f, 400f, screen, navigation)!!))
    }

    @Test fun repeatedLifecycleEventsDoNotCancelANewForegroundFinger() {
        val gate = gate()
        assertTrue(gate.update(resumed = false, focused = false))
        assertFalse(gate.update(resumed = false, focused = false))
        assertFalse(gate.update(resumed = true, focused = false))
        assertNull(gate.begin(100f, 400f, screen, navigation))
        assertFalse(gate.update(resumed = true, focused = true))
        val token = gate.begin(100f, 400f, screen, navigation)!!
        assertFalse(gate.update(resumed = true, focused = true))
        assertTrue(gate.accepts(token))
    }
}
