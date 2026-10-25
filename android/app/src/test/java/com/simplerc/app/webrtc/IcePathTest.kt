package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class IcePathTest {
    private fun host(address:String,protocol:String="udp")=Candidate("host",protocol,null,address)
    private fun relay(protocol:String?)=Candidate("relay","udp",protocol,"198.51.100.1")
    @Test fun localSubnetRequiresTheActualInterfaceAndPrefix() {
        assertTrue(LocalNetworkScope.matchesPrefix("192.168.4.10","192.168.5.20","192.168.4.10",23))
        assertFalse(LocalNetworkScope.matchesPrefix("192.168.4.10","192.168.5.20","192.168.4.10",24))
        assertFalse(LocalNetworkScope.matchesPrefix("192.168.4.10","192.168.4.20","192.168.4.11",24))
        assertFalse(LocalNetworkScope.matchesPrefix("192.168.4.10","10.0.0.20","192.168.4.10",24))
        assertFalse(LocalNetworkScope.matchesPrefix("192.168.4.10","192.168.4.20","192.168.4.10",0))
    }
    @Test fun mdnsNamesAndLoopbackCannotBecomeLanProof() {
        assertFalse(LocalNetworkScope.matchesPrefix("peer.local","192.168.4.20","192.168.4.10",24))
        assertFalse(LocalNetworkScope.matchesPrefix("face.cafe","192.168.4.20","192.168.4.10",24))
        assertFalse(LocalNetworkScope.matchesPrefix("256.168.4.10","192.168.4.20","192.168.4.10",24))
        assertFalse(LocalNetworkScope.matchesPrefix("127.0.0.1","127.0.0.2","127.0.0.1",8))
        assertFalse(LocalNetworkScope.matchesPrefix("0.0.0.0","192.168.4.20","0.0.0.0",24))
    }
    @Test fun ipv6PrefixesAndScopedLiteralsAreMatchedWithoutDns() {
        assertTrue(LocalNetworkScope.matchesPrefix("fe80::10%wlan0","fe80::20","fe80::10%wlan0",64))
        assertFalse(LocalNetworkScope.matchesPrefix("fd01:2::10","fd01:3::20","fd01:2::10",64))
        assertFalse(LocalNetworkScope.matchesPrefix("fd01::10","192.168.1.20","fd01::10",64))
    }
    @Test fun onlyProvenHostUdpPairGetsLanPolicy() {
        assertEquals(VideoRoute.LAN,IcePathClassifier.describe(host("192.168.1.10"),host("192.168.1.20"),true).policyRoute())
        assertEquals(VideoRoute.DIRECT_UDP,IcePathClassifier.describe(host("192.168.1.10"),host("10.1.1.20"),false).policyRoute())
        assertEquals(VideoRoute.DIRECT_TCP,IcePathClassifier.describe(host("192.168.1.10","tcp"),host("192.168.1.20","tcp"),true).policyRoute())
    }
    @Test fun remoteCandidateUdpDoesNotProveRemoteTurnUdp() {
        val route=IcePathClassifier.describe(host("192.168.1.10"),relay(null),false)
        assertEquals(VideoRoute.RELAY_UNKNOWN,route.policyRoute())
        assertEquals(VideoRoute.RELAY_UDP,route.policyRoute("udp"))
        assertEquals(VideoRoute.RELAY_TCP,route.policyRoute("tls"))
        assertTrue(route.label().contains("待确认"))
    }
    @Test fun slowerTurnLegWinsEvenWhenTheOtherUsesUdp() {
        val route=IcePathClassifier.describe(relay("udp"),relay(null),false)
        assertEquals(VideoRoute.RELAY_UNKNOWN,route.policyRoute())
        assertEquals(VideoRoute.RELAY_TCP,route.policyRoute("tcp"))
        assertEquals(VideoRoute.RELAY_UDP,route.policyRoute("udp"))
        assertEquals(VideoRoute.RELAY_TCP,IcePathClassifier.describe(relay("tls"),host("192.168.1.10"),false).policyRoute())
    }
    @Test fun missingCandidateTypeNeverBecomesP2pOrLan() {
        val known=host("192.168.1.10")
        for(type in listOf("","unknown","future-type")) {
            val missing=Candidate(type,"udp",null,null)
            for((local,remote) in listOf(missing to known,known to missing,missing to missing)) {
                val route=IcePathClassifier.describe(local,remote,true)
                assertEquals(VideoRoute.UNKNOWN,route.policyRoute())
                assertEquals("路径未知",route.label())
                assertEquals(ConnectionPath.UNKNOWN,IcePathClassifier.classify(local,remote,true))
            }
        }
    }
    @Test fun unknownOrConflictingProtocolDoesNotProveDirectOrLan() {
        for(protocol in listOf(null,"","quic")) {
            val local=Candidate("host",protocol,null,"192.168.1.10")
            val remote=Candidate("host",protocol,null,"192.168.1.20")
            assertEquals("路径未知",IcePathClassifier.describe(local,remote,true).label())
            assertEquals(ConnectionPath.UNKNOWN,IcePathClassifier.classify(local,remote,true))
        }
        val route=IcePathClassifier.describe(host("192.168.1.10","tcp"),host("192.168.1.20","udp"),true)
        assertEquals(VideoRoute.UNKNOWN,route.policyRoute())
        assertEquals("路径未知",route.label())
    }
    @Test fun peerReflexiveRemoteNeedsThePeersOwnCandidateEvidence() {
        val local=host("192.168.1.10")
        val remote=Candidate("prflx","udp",null,"198.51.100.10")
        val route=IcePathClassifier.describe(local,remote,false)
        assertEquals(VideoRoute.UNKNOWN,route.policyRoute())
        assertEquals("路径未知（对端候选待确认）",route.label())
        assertEquals(ConnectionPath.UNKNOWN,IcePathClassifier.classify(local,remote,false))
        // A relay protocol alone is not proof that this pair uses a TURN allocation.
        assertEquals(VideoRoute.UNKNOWN,route.policyRoute("tls"))
        assertEquals(VideoRoute.DIRECT_UDP,route.policyRoute(peerLocalCandidateType="srflx"))
        assertEquals("P2P 直连/UDP",route.label(peerLocalCandidateType="host"))
    }
    @Test fun peerReportCanRevealTurnHiddenBehindPeerReflexiveRemote() {
        val local=host("192.168.1.10")
        val remote=Candidate("prflx","udp",null,"198.51.100.10")
        val route=IcePathClassifier.describe(local,remote,false)
        assertEquals(VideoRoute.RELAY_UNKNOWN,route.policyRoute(peerLocalCandidateType="relay"))
        assertTrue(route.label(peerLocalCandidateType="relay").contains("对端报告"))
        assertTrue(route.label(peerLocalCandidateType="relay").contains("接入协议待确认"))
        assertEquals(VideoRoute.RELAY_UDP,route.policyRoute("udp","relay"))
        assertEquals(VideoRoute.RELAY_TCP,route.policyRoute("tls","relay"))
        assertEquals(ConnectionPath.TURN_TLS,IcePathClassifier.classify(local,remote,false,"tls","relay"))
    }
    @Test fun observedRelayWinsOverPeerReportingDirect() {
        val route=IcePathClassifier.describe(host("192.168.1.10"),relay(null),false)
        assertEquals(VideoRoute.RELAY_UNKNOWN,route.policyRoute(peerLocalCandidateType="host"))
        assertTrue(route.label(peerLocalCandidateType="host").startsWith("TURN 中继"))
        assertFalse(route.label(peerLocalCandidateType="host").contains("对端报告"))
    }
    @Test fun observedRelaySurvivesMissingOtherCandidateButTransportStaysUnconfirmed() {
        val missing=Candidate("","udp",null,null)
        for((local,remote) in listOf(relay("udp") to missing,missing to relay(null))) {
            val route=IcePathClassifier.describe(local,remote,false)
            assertEquals(VideoRoute.RELAY_UNKNOWN,route.policyRoute("udp"))
            assertTrue(route.label("udp").startsWith("TURN 中继"))
            assertTrue(route.label("udp").contains("接入协议待确认"))
            assertFalse(route.label("udp").contains("P2P"))
        }
        val tcp=IcePathClassifier.describe(relay("tcp"),missing,false)
        assertEquals(VideoRoute.RELAY_TCP,tcp.policyRoute())
        assertEquals(ConnectionPath.TURN_TCP,tcp.connectionPath())
    }
    @Test fun localRelayUdpCannotRuleOutAnUnconfirmedSecondRelayLeg() {
        val route=IcePathClassifier.describe(relay("udp"),Candidate("prflx","udp",null,"198.51.100.10"),false)
        assertEquals(VideoRoute.RELAY_UNKNOWN,route.policyRoute())
        assertEquals(VideoRoute.RELAY_UNKNOWN,route.policyRoute("udp"))
        assertEquals(VideoRoute.RELAY_UDP,route.policyRoute("udp","relay"))
        assertEquals(VideoRoute.RELAY_UDP,route.policyRoute(peerLocalCandidateType="host"))
        assertEquals(VideoRoute.RELAY_TCP,route.policyRoute("tcp","relay"))
    }
    @Test fun classifierUsesTheSameProtocolAndCertaintyAsDetailedRoutes() {
        val local=host("192.168.1.10","tcp")
        val remote=host("192.168.1.20","tcp")
        val route=IcePathClassifier.describe(local,remote,true)
        assertEquals(VideoRoute.DIRECT_TCP,route.policyRoute())
        assertEquals("P2P 直连/TCP",route.label())
        assertEquals(ConnectionPath.P2P,IcePathClassifier.classify(local,remote,true))
        assertEquals(ConnectionPath.TURN_TLS,IcePathClassifier.classify(relay("tls"),host("192.168.1.10"),false))
        assertEquals(ConnectionPath.UNKNOWN,IcePathClassifier.classify(host("192.168.1.10"),relay(null),false))
    }
    @Test fun candidateTypesAndKnownProtocolsAreNormalized() {
        val local=Candidate(" HOST "," UDP ",null,"192.168.1.10")
        val remote=Candidate(" SRFLX ","udp",null,"198.51.100.10")
        assertEquals(ConnectionPath.P2P,IcePathClassifier.classify(local,remote,false))
        assertEquals("P2P 直连/UDP",IcePathClassifier.describe(local,remote,false).label())
        assertEquals(ConnectionPath.TURN_TLS,IcePathClassifier.classify(Candidate(" RELAY ","udp"," TLS ",null),remote,false))
        assertEquals(ConnectionPath.TURN_TLS,IcePathClassifier.classify(local,remote,false," TLS "," RELAY "))
    }
}
