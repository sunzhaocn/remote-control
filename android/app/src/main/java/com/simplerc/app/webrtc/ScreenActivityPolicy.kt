package com.simplerc.app.webrtc

/** Producer frames, never retransmissions or forced refreshes, describe screen activity. */
enum class ScreenActivity(val label: String) { INTERACTION("交互"), MEDIA("媒体"), STILL("静止") }

/** Shared by the capture thread and main thread; no pixels or application-name heuristics. */
class ScreenActivityPolicy {
    private var lastFrameMs: Long? = null
    private var producerTimestamp: Long? = null
    private var bucketStartMs: Long? = null
    private var bucketFrames = 0
    private var fastMs = 0L
    private var mediaEvidence = false
    private var gestureId = 0L
    private var gestureSequence = 0L
    private var closedGestureId = 0L
    private var motionDeadlineMs = Long.MIN_VALUE
    private var actionDeadlineMs = Long.MIN_VALUE
    private var actionSequence = 0L
    private var enteredMs = 0L
    private var quietRefreshed = false
    private var current = ScreenActivity.STILL

    data class Reading(val activity: ScreenActivity, val refresh: Boolean, val quiet:Boolean=false)

    @Synchronized fun frame(nowMs: Long, timestampNs: Long): Boolean {
        if (producerTimestamp == timestampNs) return false
        producerTimestamp = timestampNs
        // A gap breaks the one-second continuous-media evidence, even if polling was delayed.
        if (lastFrameMs?.let { nowMs - it >= 300 } == true) {
            bucketStartMs = null; bucketFrames = 0; fastMs = 0; mediaEvidence = false
        }
        lastFrameMs = nowMs
        quietRefreshed = false
        if (bucketStartMs == null) bucketStartMs = nowMs
        bucketFrames++
        val elapsed = nowMs - checkNotNull(bucketStartMs)
        if (elapsed >= 500) {
            fastMs = if (elapsed <= 750 && bucketFrames * 1000.0 / elapsed >= 20.0) fastMs + elapsed else 0
            if (fastMs >= 1000) mediaEvidence = true
            bucketStartMs = nowMs; bucketFrames = 0
        }
        return true
    }

    /** Movement hints have their own ordering, independent of touch injection and heartbeats. */
    @Synchronized fun motion(id: Long, sequence: Long, moving: Boolean, nowMs: Long, expiresAtMs: Long) {
        if (id <= 0 || id < gestureId || id == gestureId && sequence <= gestureSequence) return
        if (moving && (id <= closedGestureId || expiresAtMs <= nowMs)) return
        gestureId = id; gestureSequence = sequence
        if (moving) {
            quietRefreshed = false
            motionDeadlineMs = minOf(expiresAtMs, nowMs + 220)
        } else {
            closedGestureId = maxOf(closedGestureId, id)
            // Even if END overtakes all moves, late packets cannot reopen this gesture.
            motionDeadlineMs = minOf(motionDeadlineMs, nowMs + 80)
        }
    }

    /** Controller-origin action hint; never renew it when input is executed or acknowledged. */
    @Synchronized fun action(sequence: Long, nowMs: Long, expiresAtMs: Long) {
        if(sequence<=actionSequence || expiresAtMs<=nowMs)return
        actionSequence=sequence
        quietRefreshed=false
        actionDeadlineMs = minOf(expiresAtMs,nowMs+350)
    }

    @Synchronized fun releaseMotion() {
        closedGestureId = maxOf(closedGestureId, gestureId)
        motionDeadlineMs = Long.MIN_VALUE; actionDeadlineMs = Long.MIN_VALUE
    }

    @Synchronized fun isQuiet(nowMs:Long):Boolean =
        lastFrameMs?.let {nowMs-it>=300}==true && nowMs>=maxOf(motionDeadlineMs,actionDeadlineMs)

    @Synchronized fun read(nowMs: Long): Reading {
        val touching = nowMs < maxOf(motionDeadlineMs, actionDeadlineMs)
        val quiet = lastFrameMs?.let { nowMs - it >= 300 } == true
        if (quiet) {
            mediaEvidence = false; fastMs = 0; bucketStartMs = null; bucketFrames = 0
        }
        val desired = when {
            touching -> ScreenActivity.INTERACTION
            mediaEvidence && !quiet -> ScreenActivity.MEDIA
            else -> ScreenActivity.STILL // Unclassified short animations retain full detail.
        }
        // No minimum blur dwell: an expired motion lease must restore detail immediately.
        if (desired != current && (desired == ScreenActivity.INTERACTION || current == ScreenActivity.INTERACTION || nowMs - enteredMs >= 500)) {
            current = desired; enteredMs = nowMs
        }
        val refresh = current == ScreenActivity.STILL && !touching && quiet && !quietRefreshed
        if (refresh) quietRefreshed = true
        return Reading(current, refresh,quiet && !touching)
    }

    @Synchronized fun reset() {
        lastFrameMs = null; producerTimestamp = null; bucketStartMs = null; bucketFrames = 0
        fastMs = 0; mediaEvidence = false; gestureId = 0; gestureSequence = 0
        closedGestureId = 0; motionDeadlineMs = Long.MIN_VALUE; actionDeadlineMs = Long.MIN_VALUE; actionSequence = 0; enteredMs = 0
        quietRefreshed = false; current = ScreenActivity.STILL
    }
}
