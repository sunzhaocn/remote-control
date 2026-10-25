package com.simplerc.app.webrtc

/** A bounded burst gives a pipelined codec enough inputs; never synthesizes a continuous stream. */
class ClearFrameRefresh {
    private var attempts = 0
    private var nextMs = 0L
    private var request = 0L
    private var limit=12
    private var interval=150L
    private var minimum=0
    private var quietOnly=false
    fun start(epoch: Long, nowMs: Long, maxAttempts:Int=12, intervalMs:Long=150,minAttempts:Int=0,
        quietOnly:Boolean=minAttempts>0) {
        request = epoch; attempts = 0; nextMs = nowMs
        limit=maxAttempts.coerceIn(0,24);interval=intervalMs.coerceAtLeast(150)
        minimum=minAttempts.coerceIn(0,limit)
        this.quietOnly=quietOnly
    }
    fun poll(nowMs: Long, completedEpoch: Long): Boolean {
        if (request == 0L || completedEpoch >= request && attempts>=minimum || attempts >= limit) return false
        if (nowMs < nextMs) return false
        attempts++; nextMs = nowMs + interval
        return true
    }
    fun cancelForActivity(quiet:Boolean):Boolean {
        if(quiet || request==0L || !quietOnly)return false
        cancel();return true
    }
    fun cancel() { request = 0L; attempts = 0;minimum=0;quietOnly=false }
}
