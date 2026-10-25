package com.simplerc.app.model

import com.simplerc.app.service.controlQualityText
import com.simplerc.app.service.controlRouteDetailsText
import com.simplerc.app.service.qualityText
import org.junit.Assert.*
import org.junit.Test

class RouteDiagnosticsTest {
    @Test fun mediaSummaryUsesOnlyTheMediaRouteAndRoundTripTime() {
        val state=AppState(connectionPath="P2P 直连/UDP",rttMs=12,
            controlConnectionPath="TURN 中继/TCP",controlRttMs=345)

        val text=state.qualityText()
        assertTrue(text.contains("媒体路径（视频/音频）"))
        assertTrue(text.contains("P2P 直连/UDP"))
        assertTrue(text.contains("RTT 12ms"))
        assertFalse(text.contains("TURN 中继/TCP"))
        assertFalse(text.contains("345ms"))
        assertFalse(text.contains("独立控制路径"))
    }

    @Test fun controlSummaryUsesOnlyTheIndependentControlRouteAndRoundTripTime() {
        val state=AppState(connectionPath="TURN 中继/TLS",rttMs=678,
            controlConnectionPath="P2P 直连/UDP",controlRttMs=23)

        val text=state.controlQualityText()
        assertTrue(text.contains("独立控制路径"))
        assertTrue(text.contains("P2P 直连/UDP"))
        assertTrue(text.contains("RTT 23ms"))
        assertFalse(text.contains("TURN 中继/TLS"))
        assertFalse(text.contains("678ms"))
        assertFalse(text.contains("媒体路径"))
    }

    @Test fun controlDetailsShowLocalRemoteCandidatesPairAndSelectionSource() {
        val state=AppState(controlCandidateTypes="relay/prflx",
            controlCandidatePairId="control-pair-7",controlCandidatePairSelection="selected=true")

        val text=state.controlRouteDetailsText()
        assertTrue(text.contains("独立控制候选（本机/对端）：relay/prflx"))
        assertTrue(text.contains("独立控制候选对：control-pair-7"))
        assertTrue(text.contains("选择来源：selected=true"))
    }

    @Test fun missingControlEvidenceIsExplicitlyUnknown() {
        val state=AppState()

        assertTrue(state.controlQualityText().contains("独立控制路径：未知 · RTT 未知"))
        val text=state.controlRouteDetailsText()
        assertTrue(text.contains("候选（本机/对端）：未知"))
        assertTrue(text.contains("候选对：未知"))
        assertTrue(text.contains("选择来源：未知"))
        assertFalse(text.contains("null"))
    }

    @Test fun blankControlEvidenceIsAlsoUnknown() {
        val state=AppState(controlCandidateTypes="",controlCandidatePairId=" ",
            controlCandidatePairSelection="\t")

        val text=state.controlRouteDetailsText()
        assertTrue(text.contains("候选（本机/对端）：未知"))
        assertTrue(text.contains("候选对：未知"))
        assertTrue(text.contains("选择来源：未知"))
    }

    @Test fun controlledMediaDiagnosticsExposePairSelectionAndPeerLocalCandidate() {
        assertMediaRouteEvidence(DeviceRole.CONTROLLED)
    }

    @Test fun controllerMediaDiagnosticsExposePairSelectionAndPeerLocalCandidate() {
        assertMediaRouteEvidence(DeviceRole.CONTROLLER)
    }

    @Test fun independentlyConstructedMediaSnapshotDoesNotRetainPriorPairEvidence() {
        val previous=MediaDiagnostics(candidateTypes="host/relay",candidatePairId="old-media-pair",
            candidatePairSelection="old-selection",peerLocalCandidateType="relay")
        val current=MediaDiagnostics()

        DeviceRole.entries.forEach { role ->
            assertTrue(previous.text(role).contains("old-media-pair"))
            val text=current.text(role)
            assertTrue(text.contains("媒体候选（本机/对端）未知"))
            assertTrue(text.contains("媒体候选对 未知 · 选择来源 未知"))
            assertTrue(text.contains("对端自报媒体本地候选 未知"))
            assertFalse(text.contains("old-media-pair"))
            assertFalse(text.contains("old-selection"))
            assertFalse(text.contains("relay"))
        }
    }

    @Test fun independentlyConstructedControlStateDoesNotRetainPriorPairEvidence() {
        val previous=AppState(controlCandidateTypes="host/relay",
            controlCandidatePairId="old-control-pair",controlCandidatePairSelection="old-selection")
        val current=AppState()

        assertTrue(previous.controlRouteDetailsText().contains("old-control-pair"))
        val text=current.controlRouteDetailsText()
        assertTrue(text.contains("候选对：未知"))
        assertTrue(text.contains("选择来源：未知"))
        assertFalse(text.contains("old-control-pair"))
        assertFalse(text.contains("old-selection"))
        assertFalse(text.contains("host/relay"))
    }

    private fun assertMediaRouteEvidence(role:DeviceRole) {
        val diagnostics=MediaDiagnostics(candidateTypes="host/prflx",candidatePairId="media-pair-9",
            candidatePairSelection="transport.selectedCandidatePairId",peerLocalCandidateType="relay")

        val text=diagnostics.text(role)
        assertTrue(text.contains("媒体候选（本机/对端）host/prflx"))
        assertTrue(text.contains("媒体候选对 media-pair-9"))
        assertTrue(text.contains("选择来源 transport.selectedCandidatePairId"))
        assertTrue(text.contains("对端自报媒体本地候选 relay"))
        assertFalse(text.contains("独立控制候选"))
    }
}
