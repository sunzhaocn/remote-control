package com.simplerc.app.webrtc

import org.webrtc.RTCStats

/** A nominated successful pair is not proof that this transport selected it. */
data class SelectedIcePair(val pair: RTCStats, val source: String)

object IcePairSelection {
    const val TRANSPORT = "transport.selectedCandidatePairId"
    const val LEGACY_SELECTED = "candidate-pair.selected"

    private fun RTCStats.reference(key: String): String? =
        (members[key] as? String)?.takeIf { it.isNotBlank() }

    private fun usablePair(stat: RTCStats?): Boolean = stat?.type == "candidate-pair" &&
        (stat.members["state"] == null || stat.members["state"] == "succeeded")

    fun select(all: Map<String, RTCStats>): SelectedIcePair? {
        val videoTransports = all.values.filter {
            it.type in setOf("inbound-rtp", "outbound-rtp") &&
                (it.members["kind"] ?: it.members["mediaType"]) == "video" && it.members["active"] != false
        }.mapNotNull { it.reference("transportId") }.distinct()
        val transports = if (videoTransports.isNotEmpty()) {
            videoTransports.map { id -> all[id]?.takeIf { it.type == "transport" } ?: return null }
        } else all.values.filter { it.type == "transport" }
        val active = transports.filter { it.members["dtlsState"] !in setOf("closed", "failed") }
        if (transports.isNotEmpty() && active.isEmpty()) return null
        if (videoTransports.isNotEmpty() && active.size != transports.size) return null

        if (active.any { it.members["selectedCandidatePairId"] != null }) {
            // An explicit but dangling/ambiguous ID must never fall back to another pair.
            val pairs = active.map { transport ->
                val pair = transport.reference("selectedCandidatePairId")?.let(all::get)
                if (!usablePair(pair)) return null
                val owner = pair!!.reference("transportId")
                if (owner != null && owner != transport.id) return null
                pair
            }.distinctBy { it.id }
            return pairs.singleOrNull()?.let { SelectedIcePair(it, TRANSPORT) }
        }

        // This field is a legacy implementation extension, not a standard stats field.
        // One selected pair cannot establish the path of another relevant transport.
        if (active.size > 1) return null
        val candidates = all.values.filter { usablePair(it) && it.members["selected"] == true }
        val pair = candidates.singleOrNull() ?: return null
        val owner = pair.reference("transportId")
        if (owner != null && active.none { it.id == owner }) return null
        return SelectedIcePair(pair, LEGACY_SELECTED)
    }
}
