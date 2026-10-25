package com.simplerc.app.session

import org.junit.Assert.*
import org.junit.Test

class CaptureConsentTest {
    @Test fun resultBelongsOnlyToTheRequestThatOpenedThePrompt() {
        val consent = CaptureConsent()
        assertTrue(consent.begin("first"))
        assertFalse(consent.isCurrent("second"))
        assertNull(consent.finish("second"))
        assertTrue(consent.begin("second"))
        assertEquals("second", consent.finish("second"))
    }

    @Test fun timeoutOrStopInvalidatesThePermissionResult() {
        val consent = CaptureConsent()
        assertTrue(consent.begin("session"))
        assertNull(consent.finish(null))
        assertNull(consent.finish("session"))
    }

    @Test fun repeatedTapsCannotReplaceAnOutstandingPermissionPrompt() {
        val consent = CaptureConsent()
        assertFalse(consent.begin(null))
        assertTrue(consent.begin("first"))
        assertFalse(consent.begin("first"))
        assertFalse(consent.begin("second"))
        assertTrue(consent.isCurrent("first"))
        assertEquals("first", consent.finish("first"))
        assertNull(consent.finish("first"))
    }
}
