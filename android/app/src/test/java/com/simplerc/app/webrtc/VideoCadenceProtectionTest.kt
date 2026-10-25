package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class VideoCadenceProtectionTest {
    private fun low() = VideoCadenceProtection().also {
        it.observe(0, "编码耗时偏高", false, false)
        assertTrue(it.observe(1000, "编码耗时偏高", false, false))
    }

    @Test fun sustainedDevicePressureLowersCadenceWithoutWaitingForNetworkLoss() {
        val p = VideoCadenceProtection()
        assertFalse(p.observe(0, "解码耗时偏高", false, false))
        assertTrue(p.observe(1000, "解码耗时偏高", false, false))
        assertEquals(24, p.fps)
        assertEquals("解码耗时偏高", p.reason)
    }
    @Test fun oneTransientSpikeDoesNotSacrificeCadence() {
        val p = VideoCadenceProtection()
        p.observe(0, "编码耗时偏高", false, false)
        p.observe(1000, null, false, true)
        p.observe(2000, "编码耗时偏高", false, false)
        assertEquals(30, p.fps)
    }
    @Test fun severeMeasuredPressureCanReactImmediately() {
        val p = VideoCadenceProtection()
        assertTrue(p.observe(0, "显示处理排队偏高", true, false))
        assertEquals(24, p.fps)
    }
    @Test fun severeFlagWithoutAPressureReasonCannotInventOverload() {
        val p = VideoCadenceProtection()
        repeat(20) { p.observe(it * 1000L, null, true, false) }
        assertEquals(30, p.fps)
    }
    @Test fun duplicateOlderAndRapidCallbacksCannotManufacturePressure() {
        val p = VideoCadenceProtection()
        p.observe(1000, "编码耗时偏高", false, false)
        for (now in listOf(1000L, 500L, 1100L, 1400L, 1749L))
            assertFalse(p.observe(now, "编码耗时偏高", false, false))
        assertEquals(30, p.fps)
        assertTrue(p.observe(1750, "编码耗时偏高", false, false))
    }
    @Test fun longSamplingGapsBreakConsecutivePressure() {
        val p = VideoCadenceProtection()
        p.observe(0, "编码耗时偏高", false, false)
        p.observe(2000, "编码耗时偏高", false, false)
        assertEquals(30, p.fps)
        assertTrue(p.observe(3000, "编码耗时偏高", false, false))
    }
    @Test fun recoveryRequiresEightSecondsOfContinuousMeasuredHealth() {
        val p = low()
        for (now in 2000L..9000L step 1000) assertFalse(p.observe(now, null, false, true))
        assertEquals(24, p.fps)
        assertTrue(p.observe(10000, null, false, true))
        assertEquals(30, p.fps)
        assertNull(p.reason)
    }
    @Test fun missingMeasurementsCannotBeTreatedAsRecovery() {
        val p = low()
        for (now in 2000L..60000L step 1000) p.observe(now, null, false, false)
        assertEquals(24, p.fps)
    }
    @Test fun networkOnlyPressureDoesNotLowerDeviceCadence() {
        val p = VideoCadenceProtection()
        repeat(30) { p.observe(it * 1000L, null, false, false) }
        assertEquals(30, p.fps)
    }
    @Test fun freshDevicePressureInterruptsRecoveryAndUpdatesItsCause() {
        val p = low()
        for (now in 2000L..8000L step 1000) p.observe(now, null, false, true)
        p.observe(9000, "显示吞吐跟不上", false, false)
        assertEquals("显示吞吐跟不上", p.reason)
        for (now in 10000L..17000L step 1000) p.observe(now, null, false, true)
        assertEquals(24, p.fps)
        assertTrue(p.observe(18000, null, false, true))
    }
    @Test fun aPauseCannotCountTowardContinuousRecovery() {
        val p = low()
        for (now in 2000L..8000L step 1000) p.observe(now, null, false, true)
        p.observe(20000, null, false, true)
        assertEquals(24, p.fps)
        for (now in 21000L..27000L step 1000) p.observe(now, null, false, true)
        assertEquals(24, p.fps)
        assertTrue(p.observe(28000, null, false, true))
    }
    @Test fun briefRenewedPressureAfterRecoveryRespectsMinimumResidence() {
        val p = low()
        for (now in 2000L..10000L step 1000) p.observe(now, null, false, true)
        p.observe(11000, "编码耗时偏高", false, false)
        p.observe(12000, "编码耗时偏高", false, false)
        assertEquals(30, p.fps)
        assertTrue(p.observe(13000, "编码耗时偏高", false, false))
    }
    @Test fun severeOverloadOverridesResidenceAfterARecovery() {
        val p = low()
        for (now in 2000L..10000L step 1000) p.observe(now, null, false, true)
        assertTrue(p.observe(11000, "显示处理排队偏高", true, false))
        assertEquals(24, p.fps)
    }
    @Test fun changedRouteDiscardsOldHealthWithoutBurstingBackToThirty() {
        val p = low()
        for (now in 2000L..9000L step 1000) p.observe(now, null, false, true)
        p.clearEvidence()
        p.observe(10000, null, false, true)
        assertEquals(24, p.fps)
    }
    @Test fun persistentOverloadNeverCreatesMoreDegradationTiers() {
        val p = low()
        for (now in 2000L..120000L step 1000) p.observe(now, "编码耗时偏高", true, false)
        assertEquals(24, p.fps)
    }
    @Test fun aNewSessionStartsAtThirtyWithoutOldReasonsOrCooldowns() {
        val p = low()
        p.reset()
        assertEquals(30, p.fps)
        assertNull(p.reason)
        p.observe(0, "编码耗时偏高", false, false)
        assertTrue(p.observe(1000, "编码耗时偏高", false, false))
    }
    @Test fun invalidClockCannotStartPressureOrRecovery() {
        val p = VideoCadenceProtection()
        assertFalse(p.observe(-1, "编码耗时偏高", true, false))
        assertEquals(30, p.fps)
    }
}
