package com.simplerc.app.credentials

import org.junit.Assert.*
import org.junit.Test

class CredentialRefreshScheduleTest {
    @Test fun renewsBeforeTheServerCredentialExpires() {
        assertEquals(285_000L, CredentialRefreshSchedule.delayMillis(1_300, 1_000_000))
    }

    @Test fun rejectedRequestResumesTheOriginalDeadline() {
        assertEquals(5_000L, CredentialRefreshSchedule.delayMillis(1_300, 1_280_000))
    }

    @Test fun overdueCredentialRefreshesImmediatelyAndFixedCredentialHasNoTimer() {
        assertEquals(0L, CredentialRefreshSchedule.delayMillis(1_300, 1_310_000))
        assertNull(CredentialRefreshSchedule.delayMillis(null, 1_310_000))
    }
}
