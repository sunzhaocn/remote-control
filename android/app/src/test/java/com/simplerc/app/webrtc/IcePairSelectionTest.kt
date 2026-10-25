package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class IcePairSelectionTest {
    private fun stat(type:String,id:String,vararg values:Pair<String,Any>)=
        RTCStats(1_000_000,type,id,values.toMap())
    private fun transport(id:String="transport",pair:String="pair")=
        stat("transport",id,"selectedCandidatePairId" to pair)
    private fun pair(id:String="pair",vararg extra:Pair<String,Any>)=
        stat("candidate-pair",id,"state" to "succeeded","localCandidateId" to "local",
            "remoteCandidateId" to "remote","currentRoundTripTime" to 0.02,
            "availableOutgoingBitrate" to 4_000_000.0,*extra)
    private fun candidates(local:String="host",remote:String="srflx")=listOf(
        stat("local-candidate","local","candidateType" to local,"protocol" to "udp"),
        stat("remote-candidate","remote","candidateType" to remote,"protocol" to "udp"))
    private fun selected(vararg values:RTCStats)=IcePairSelection.select(values.associateBy {it.id})
    private fun report(vararg values:RTCStats)=RTCStatsReport(1_000_000,values.associateBy {it.id})
    private fun read(vararg values:RTCStats)=QualityStats {_,_->false}.read(report(*values))

    @Test fun nominatedSuccessfulPairAloneIsUnknownAndCannotSupplyBandwidth() {
        val result=read(pair(extra=arrayOf("nominated" to true)),*candidates().toTypedArray())
        assertEquals("路径未知",result.path)
        assertNull(result.routeInfo);assertNull(result.rttMs);assertNull(result.video.availableKbps)
        assertNull(result.video.candidatePairId)
    }

    @Test fun transportReferenceWinsOverTheFirstSuccessfulNominatedPair() {
        val result=selected(pair("old","nominated" to true),pair("current"),transport(pair="current"))!!
        assertEquals("current",result.pair.id);assertEquals(IcePairSelection.TRANSPORT,result.source)
    }

    @Test fun danglingExplicitReferenceCannotUseAnotherLegacySelectedPair() {
        assertNull(selected(transport(pair="missing"),pair("old","selected" to true)))
    }

    @Test fun wrongReferencedStatsTypeIsNotACandidatePair() {
        assertNull(selected(transport(),stat("codec","pair","selected" to true)))
    }

    @Test fun explicitlyFailedPairCannotBePresentedAsTheCurrentPath() {
        assertNull(selected(transport(),pair(extra=arrayOf("state" to "failed"))))
    }

    @Test fun uniqueLegacySelectedFlagIsAnExplicitCompatibilityFallback() {
        val result=selected(stat("transport","transport"),pair(extra=arrayOf("selected" to true)))!!
        assertEquals("pair",result.pair.id);assertEquals(IcePairSelection.LEGACY_SELECTED,result.source)
    }

    @Test fun multipleLegacySelectedFlagsRemainUnknown() {
        assertNull(selected(pair("a","selected" to true),pair("b","selected" to true)))
    }

    @Test fun oneLegacyPairCannotResolveTwoVideoTransportsEvenWithAKnownOwner() {
        assertNull(selected(stat("transport","video-a"),stat("transport","video-b"),
            pair(extra=arrayOf("selected" to true,"transportId" to "video-a")),
            stat("outbound-rtp","video-rtp-a","kind" to "video","transportId" to "video-a"),
            stat("inbound-rtp","video-rtp-b","kind" to "video","transportId" to "video-b")))
    }

    @Test fun explicitTransportReferenceHasPriorityOverTheLegacyFlag() {
        assertEquals("pair",selected(transport(),pair(),pair("other","selected" to true))!!.pair.id)
    }

    @Test fun videoRtpTransportReferenceAvoidsAnUnrelatedTransport() {
        val result=selected(transport("other","other-pair"),pair("other-pair"),
            transport("video","video-pair"),pair("video-pair","transportId" to "video"),
            stat("outbound-rtp","video-rtp","kind" to "video","transportId" to "video"))!!
        assertEquals("video-pair",result.pair.id)
    }

    @Test fun multipleUnscopedTransportsWithDifferentPairsAreAmbiguous() {
        assertNull(selected(transport("a","a-pair"),pair("a-pair"),transport("b","b-pair"),pair("b-pair")))
    }

    @Test fun missingRtpTransportCannotFallBackToAnUnrelatedTransport() {
        assertNull(selected(transport(),pair(),stat("inbound-rtp","video","kind" to "video","transportId" to "absent")))
    }

    @Test fun pairOwnerMustMatchItsReferencingTransport() {
        assertNull(selected(transport(),pair(extra=arrayOf("transportId" to "different"))))
    }

    @Test fun aClosedTransportCannotBeResurrectedByALegacySelectedFlag() {
        assertNull(selected(stat("transport","transport","dtlsState" to "closed"),pair(extra=arrayOf("selected" to true))))
    }

    @Test fun missingCandidatesRemainUnknownEvenWithASelectedPair() {
        val result=read(transport(),pair())
        assertEquals("路径未知",result.path)
        assertEquals("?/?",result.video.candidateTypes)
        assertEquals("pair",result.video.candidatePairId)
        assertEquals(IcePairSelection.TRANSPORT,result.video.candidatePairSelection)
        assertEquals(VideoRoute.UNKNOWN,result.routeInfo!!.policyRoute())
    }

    @Test fun disappearingSelectionClearsTheOldDirectPathAndItsEvidence() {
        val reader=QualityStats {_,_->false}
        val direct=reader.read(report(transport(),pair(),*candidates().toTypedArray()))
        assertTrue(direct.path!!.startsWith("P2P"))
        val unknown=reader.read(report(pair(extra=arrayOf("nominated" to true)),*candidates().toTypedArray()))
        assertEquals("路径未知",unknown.path)
        assertNull(unknown.routeKey);assertNull(unknown.rttMs);assertNull(unknown.video.availableKbps)
        assertNull(unknown.video.candidatePairId);assertNull(unknown.video.candidateTypes)
    }

    @Test fun candidateEvidenceChangesInvalidateRouteIdentityEvenIfPairIdIsReused() {
        val reader=QualityStats {_,_->false}
        val first=reader.read(report(transport(),pair(),*candidates().toTypedArray()))
        val second=reader.read(report(transport(),pair(),*candidates(local="relay").toTypedArray()))
        assertNotEquals(first.routeKey,second.routeKey)
        assertTrue(second.path!!.startsWith("TURN"))
    }

    @Test fun aKnownLocalRelayDoesNotNeedTheMissingRemoteCandidateToBeCalledTurn() {
        val result=read(transport(),pair(),candidates(local="relay").first())
        assertTrue(result.path!!.startsWith("TURN"))
        assertEquals("relay/?",result.video.candidateTypes)
        assertEquals(VideoRoute.RELAY_UNKNOWN,result.routeInfo!!.policyRoute())
    }

    @Test fun legacyPairBelongingToAnotherTransportIsRejected() {
        assertNull(selected(stat("transport","transport"),pair(extra=arrayOf("selected" to true,"transportId" to "other"))))
    }

    @Test fun separateMediaAndControlSnapshotsCannotOverwriteEachOthersRoute() {
        val media=QualityStats {_,_->false}
        val control=QualityStats {_,_->false}
        val direct=media.read(report(transport(),pair(),*candidates().toTypedArray()))
        val turn=control.read(report(transport(),pair(),*candidates(local="relay",remote="host").toTypedArray()))
        assertTrue(direct.path!!.startsWith("P2P"));assertTrue(turn.path!!.startsWith("TURN"))
        assertEquals("host/srflx",direct.video.candidateTypes)
        assertEquals("relay/host",turn.video.candidateTypes)
    }
}
