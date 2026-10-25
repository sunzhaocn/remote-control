package com.simplerc.app.webrtc

import java.util.ArrayDeque

/** Only app-requested refreshes are limited; codec startup and remote PLI/FIR stay native. */
class RefreshRequestBudget {
    private val requests=ArrayDeque<Long>()
    var admitted=0L
        private set
    fun waitMs(now:Long):Long {
        while(requests.isNotEmpty() && now-requests.first>=10_000)requests.removeFirst()
        return if(requests.size<3)0 else (requests.first+10_000-now).coerceAtLeast(0)
    }
    fun acquire(now:Long):Boolean {
        if(waitMs(now)>0)return false
        requests.addLast(now);admitted++;return true
    }
    fun reset(){requests.clear();admitted=0}
}
