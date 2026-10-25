package com.simplerc.app.service

import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ForegroundResourcePolicyTest {
    @Test fun standbyKeepsSignalingAwakeWithoutDisplayOrWifiPerformanceLocks() {
        val state = AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,sessionState=SessionState.STANDBY)
        assertEquals(ForegroundResources(true,false,false), resources(ForegroundMode.STANDBY,state))
        assertEquals(ForegroundResources(false,false,false), resources(ForegroundMode.STANDBY,state.copy(standbyServiceRunning=false)))
    }

    @Test fun controllerSessionKeepsCpuWifiAndDisplayWhileConnected() {
        val state = AppState(role=DeviceRole.CONTROLLER,activeSessionId="live",sessionState=SessionState.CONNECTED)
        assertEquals(ForegroundResources(true,true,true), resources(ForegroundMode.CONTROLLER,state))
        assertEquals(ForegroundResources(true,false,true), resources(ForegroundMode.CONTROLLER,state,wifi=false))
    }

    @Test fun connectingAndRecoveringControllerRemainAwakeUntilTheyEnd() {
        for (phase in listOf(SessionState.CONNECTING,SessionState.WAITING_CONFIRMATION,SessionState.RECONNECTING)) {
            assertTrue(resources(ForegroundMode.CONTROLLER,AppState(sessionState=phase)).cpu)
            assertTrue(resources(ForegroundMode.CONTROLLER,AppState(sessionState=phase)).display)
        }
        for (phase in listOf(SessionState.IDLE,SessionState.STOPPED,SessionState.ERROR)) {
            assertEquals(ForegroundResources(false,false,false),resources(ForegroundMode.CONTROLLER,AppState(sessionState=phase)))
        }
    }

    @Test fun captureServiceOnlyKeepsTheScreenOnForItsCurrentSession() {
        val state = AppState(role=DeviceRole.CONTROLLED,activeSessionId="live",sessionState=SessionState.CONNECTED)
        assertEquals(ForegroundResources(true,true,true),resources(ForegroundMode.SCREEN_SHARE,state,"live"))
        assertEquals(ForegroundResources(false,false,false),resources(ForegroundMode.SCREEN_SHARE,state,"old"))
        assertEquals(ForegroundResources(false,false,false),resources(ForegroundMode.SCREEN_SHARE,state,null))
        assertEquals(ForegroundResources(false,false,false),resources(ForegroundMode.SCREEN_SHARE,state.copy(activeSessionId=null),"live"))
    }

    @Test fun controlledSetupAndConnectionRemainVisibleWithoutKeepingIdleStandbyLit() {
        val standby = AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true)
        for(phase in listOf(SessionState.REGISTERING,SessionState.WAITING_CONFIRMATION,SessionState.CONNECTING)) {
            assertTrue(phase.name,resources(ForegroundMode.STANDBY,standby.copy(sessionState=phase)).display)
        }
        assertTrue(resources(ForegroundMode.STANDBY,standby.copy(activeSessionId="live",sessionState=SessionState.CONNECTED)).display)
        assertTrue(resources(ForegroundMode.STANDBY,standby.copy(activeSessionId="live",sessionState=SessionState.RECONNECTING)).display)
        assertFalse(resources(ForegroundMode.STANDBY,standby.copy(sessionState=SessionState.STANDBY)).display)
        assertFalse(resources(ForegroundMode.STANDBY,standby.copy(sessionState=SessionState.RECONNECTING)).display)
    }

    @Test fun aManuallyTurnedOffOrLockedScreenIsNeverHeldAwake() {
        val states = listOf(
            ForegroundMode.CONTROLLER to AppState(role=DeviceRole.CONTROLLER,activeSessionId="live"),
            ForegroundMode.STANDBY to AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,activeSessionId="live"),
            ForegroundMode.SCREEN_SHARE to AppState(role=DeviceRole.CONTROLLED,activeSessionId="live"),
        )
        for((mode,state) in states) {
            val off=ForegroundResourcePolicy.forState(mode,"live",state,true,screenInteractive=false,screenLocked=false)
            val locked=ForegroundResourcePolicy.forState(mode,"live",state,true,screenInteractive=true,screenLocked=true)
            assertFalse(mode.name,off.display)
            assertFalse(mode.name,locked.display)
            assertTrue(mode.name,off.cpu)
            assertTrue(mode.name,locked.cpu)
            assertTrue(mode.name,ForegroundResourcePolicy.forState(mode,"live",state,true).display)
        }
    }

    @Test fun changingRoleReleasesTheOldServicesResources() {
        val controlled = AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,activeSessionId="live")
        assertEquals(ForegroundResources(false,false,false),resources(ForegroundMode.CONTROLLER,controlled))
        val controller = controlled.copy(role=DeviceRole.CONTROLLER)
        assertEquals(ForegroundResources(false,false,false),resources(ForegroundMode.STANDBY,controller))
        assertEquals(ForegroundResources(false,false,false),resources(ForegroundMode.SCREEN_SHARE,controller,"live"))
    }

    @Test fun lanWifiKeepsItsLockWhenMobileDataOrVpnIsTheDefaultNetwork() {
        val networks=ConnectedWifiNetworks<String>()
        val session=AppState(role=DeviceRole.CONTROLLER,activeSessionId="live",sessionState=SessionState.CONNECTED)
        // The observed LAN has no internet/default-network requirement.
        networks.update("local-wifi",true)
        networks.update("mobile-default",false)
        networks.update("vpn-default",false)
        assertTrue(resources(ForegroundMode.CONTROLLER,session,wifi=networks.available).wifi)
        networks.lost("mobile-default")
        assertTrue(resources(ForegroundMode.CONTROLLER,session,wifi=networks.available).wifi)
        networks.lost("local-wifi")
        assertFalse(resources(ForegroundMode.CONTROLLER,session,wifi=networks.available).wifi)
    }

    @Test fun wifiHandoverDoesNotReleaseTheLockWhileAnotherWifiNetworkRemains() {
        val networks=ConnectedWifiNetworks<String>()
        networks.update("old-wifi",true)
        networks.update("new-wifi",true)
        networks.lost("old-wifi")
        networks.lost("old-wifi")
        assertTrue(networks.available)
        networks.update("new-wifi",false)
        assertFalse(networks.available)
        networks.update("restored-wifi",true)
        assertTrue(networks.available)
        networks.clear()
        assertFalse(networks.available)
    }

    @Test fun inputAcksAndVideoStatsDoNotRefreshSystemResourcesButSessionEndDoes() = runBlocking {
        val connected = AppState(role=DeviceRole.CONTROLLER,activeSessionId="live",sessionState=SessionState.CONNECTED)
        val states = buildList {
            add(connected)
            repeat(1000) { index ->
                add(connected.copy(inputAckMs=index.toLong(),controlStatus="ACK $index",
                    rttMs=(index % 100).toLong(),remoteFrameWidth=720 + index % 2))
            }
            add(connected.copy(activeSessionId=null,sessionState=SessionState.STOPPED))
        }
        assertEquals(listOf(ForegroundResources(true,true,true),ForegroundResources(false,false,false)),
            states.asFlow().foregroundResourceChanges(ForegroundMode.CONTROLLER,null).toList())
    }

    @Test fun captureOwnershipChangesReleaseResourcesEvenWhenBothSessionsAreConnected() = runBlocking {
        val connected = AppState(role=DeviceRole.CONTROLLED,activeSessionId="old",sessionState=SessionState.CONNECTED)
        val states = listOf(connected,connected.copy(activeSessionId="new"))
        assertEquals(listOf(ForegroundResources(true,true,true),ForegroundResources(false,false,false)),
            states.asFlow().foregroundResourceChanges(ForegroundMode.SCREEN_SHARE,"old").toList())
    }

    @Test fun standbyDisplayChangesAndRoleSwitchAreNotHiddenByDiagnosticFiltering() = runBlocking {
        val standby = AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,sessionState=SessionState.STANDBY)
        val states = listOf(standby,
            standby.copy(incomingSessionId="pending",sessionState=SessionState.WAITING_CONFIRMATION),
            standby,
            standby.copy(role=DeviceRole.CONTROLLER))
        assertEquals(listOf(ForegroundResources(true,false,false),ForegroundResources(true,false,true),
            ForegroundResources(true,false,false),ForegroundResources(false,false,false)),
            states.asFlow().foregroundResourceChanges(ForegroundMode.STANDBY,null).toList())
    }

    private fun resources(mode:ForegroundMode,state:AppState,session:String?=null,wifi:Boolean=true) =
        ForegroundResourcePolicy.forState(mode,session,state,wifi)
}
