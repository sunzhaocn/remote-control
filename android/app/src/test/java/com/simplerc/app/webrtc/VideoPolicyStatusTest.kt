package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class VideoPolicyStatusTest {
    @Test fun bothEndsRetainAReasonAfterTheOldFiveHundredTwelveCharacterBoundary() {
        val status="前".repeat(600)+"连续两次失败，等待带宽翻倍"+"后".repeat(300)
        val sent=VideoPolicyStatus.bounded(status)
        val received=VideoPolicyStatus.bounded(sent)
        assertEquals(768,received.length);assertEquals(sent,received)
        assertTrue(received.contains("连续两次失败，等待带宽翻倍"))
    }
    @Test fun controlCharactersDoNotConsumeTheBudgetOrReachTheSingleLineStatus() {
        val status="\n\t\r".repeat(400)+"安全状态"+"后".repeat(1000)
        val result=VideoPolicyStatus.bounded(status)
        assertTrue(result.startsWith("安全状态"));assertEquals(768,result.length)
        assertTrue(result.all {it>=' '})
    }
}
