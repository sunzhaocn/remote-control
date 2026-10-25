package com.simplerc.app.session

import org.junit.Assert.*
import org.junit.Test

class PendingInputAcksTest {
    @Test fun expiredReplyIsRejectedEvenWhenNoNewInputArrives() {
        val pending = PendingInputAcks()
        pending.remember(1, 100)
        assertNull(pending.acknowledge(1, 3101))
        pending.remember(2, 4000)
        assertEquals(30L, pending.acknowledge(2, 4030))
        assertNull(pending.acknowledge(2, 4031))
    }

    @Test fun fullQueueEvictsOldestAndSessionResetRejectsLateReplies() {
        val pending = PendingInputAcks(capacity = 2)
        pending.remember(1, 0); pending.remember(2, 10); pending.remember(3, 20)
        assertNull(pending.acknowledge(1, 30))
        assertEquals(20L, pending.acknowledge(2, 30))
        pending.clear()
        assertNull(pending.acknowledge(3, 40))
    }

    @Test fun clockReversalDoesNotProduceNegativeLatency() {
        val pending = PendingInputAcks()
        pending.remember(1, 100)
        assertNull(pending.acknowledge(1, 90))
    }
}
