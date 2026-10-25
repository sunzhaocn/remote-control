package com.simplerc.app.service

import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import org.junit.Assert.*
import org.junit.Test

class ScreenAwakePolicyTest {
    private fun plan(wanted:Boolean=true,foreground:Boolean=false,on:Boolean=true,locked:Boolean=false,
        off:Boolean=false,accessibility:Boolean=false,permission:Boolean=false)=
        ScreenAwakePolicy.plan(wanted,foreground,on,locked,off,accessibility,permission)
    @Test fun foregroundUsesItsVisibleWindowWithoutOverlayPermission() {
        assertEquals(ScreenAwakePlan(true,AwakeWindow.NONE,foregroundWindow=true),plan(foreground=true))
    }
    @Test fun controlledBackgroundUsesAnAccessibilityWindow() {
        assertEquals(ScreenAwakePlan(true,AwakeWindow.ACCESSIBILITY),plan(accessibility=true))
    }
    @Test fun controllerBackgroundUsesItsGrantedOverlayPermission() {
        assertEquals(ScreenAwakePlan(true,AwakeWindow.APPLICATION),plan(permission=true))
    }
    @Test fun aDisplayWakeLockAloneIsNotReportedAsBackgroundProtectionReady() {
        assertEquals(ScreenAwakePlan(true,AwakeWindow.NONE,true),plan())
    }
    @Test fun accessibilityDoesNotRequireASecondSpecialPermission() {
        assertEquals(plan(accessibility=true),plan(accessibility=true,permission=true))
    }
    @Test fun movingAwayFromTheActivityRequiresAnIndependentWindow() {
        assertEquals(AwakeWindow.NONE,plan(foreground=true,permission=true).window)
        assertEquals(AwakeWindow.APPLICATION,plan(foreground=false,permission=true).window)
    }
    @Test fun returningToTheActivityRemovesTheBackgroundWindow() {
        assertEquals(AwakeWindow.NONE,plan(foreground=true,accessibility=true).window)
    }
    @Test fun permissionGrantedWhileConnectedImmediatelyEnablesTheWindow() {
        assertTrue(plan().needsPermission)
        assertFalse(plan(permission=true).needsPermission)
        assertEquals(AwakeWindow.APPLICATION,plan(permission=true).window)
    }
    @Test fun revokedPermissionCannotLeaveTheOverlayRequested() {
        assertEquals(AwakeWindow.NONE,plan(permission=false).window)
        assertTrue(plan(permission=false).needsPermission)
    }
    @Test fun accessibilityRebindingCanFallBackToAnAlreadyGrantedApplicationWindow() {
        assertEquals(AwakeWindow.ACCESSIBILITY,plan(accessibility=true,permission=true).window)
        assertEquals(AwakeWindow.APPLICATION,plan(accessibility=false,permission=true).window)
    }
    @Test fun anOffBroadcastBlocksHoldingEvenIfThePowerQueryStillSaysOn() {
        assertEquals(ScreenAwakePlan(false,AwakeWindow.NONE),plan(off=true,on=true,permission=true))
    }
    @Test fun noWakeupIsRequestedWhileScreenIsOff() {
        for(foreground in listOf(true,false))for(accessibility in listOf(true,false))
            assertEquals(ScreenAwakePlan(false,AwakeWindow.NONE),plan(foreground=foreground,on=false,accessibility=accessibility,permission=true))
    }
    @Test fun aLockedScreenHasNeitherBrightLockNorWindow() {
        assertEquals(ScreenAwakePlan(false,AwakeWindow.NONE),plan(locked=true,accessibility=true,permission=true))
    }
    @Test fun userUnlockCanRestoreTheCurrentSessionsProtection() {
        assertFalse(plan(locked=true,permission=true).displayLock)
        assertEquals(AwakeWindow.APPLICATION,plan(locked=false,permission=true).window)
    }
    @Test fun endingTheLastLeaseRemovesBothLockAndWindow() {
        assertEquals(ScreenAwakePlan(false,AwakeWindow.NONE),plan(wanted=false,permission=true,accessibility=true))
    }
    @Test fun noPermissionWarningIsNeededForAnIdleAppOrLockedScreen() {
        assertFalse(plan(wanted=false).needsPermission)
        assertFalse(plan(locked=true).needsPermission)
    }
    @Test fun idleStandbyDoesNotKeepAnotherAppLit() {
        val leases=ScreenAwakeLeases();leases.add("standby",ForegroundMode.STANDBY,null)
        assertFalse(leases.wanted(AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,sessionState=SessionState.STANDBY)))
    }
    @Test fun aSessionIdWithoutARunningServiceCannotKeepTheDisplayOn() {
        assertFalse(ScreenAwakeLeases().wanted(AppState(activeSessionId="live",sessionState=SessionState.CONNECTED)))
    }
    @Test fun controllerServiceOwnsConnectedAndReconnectingProtection() {
        val leases=ScreenAwakeLeases();leases.add("controller",ForegroundMode.CONTROLLER,null)
        for(phase in listOf(SessionState.CONNECTING,SessionState.WAITING_CONFIRMATION,SessionState.CONNECTED,SessionState.RECONNECTING)) {
            assertTrue(leases.wanted(AppState(activeSessionId="live",sessionState=phase)))
        }
    }
    @Test fun captureProtectionCannotLeakIntoANewSession() {
        val leases=ScreenAwakeLeases();leases.add("capture",ForegroundMode.SCREEN_SHARE,"old")
        assertTrue(leases.wanted(AppState(role=DeviceRole.CONTROLLED,activeSessionId="old",sessionState=SessionState.CONNECTED)))
        assertFalse(leases.wanted(AppState(role=DeviceRole.CONTROLLED,activeSessionId="new",sessionState=SessionState.CONNECTED)))
    }
    @Test fun oldServiceDestructionCannotReleaseANewerServicesLease() {
        val leases=ScreenAwakeLeases()
        leases.add("old",ForegroundMode.SCREEN_SHARE,"old");leases.add("new",ForegroundMode.SCREEN_SHARE,"new")
        leases.remove("old")
        assertTrue(leases.wanted(AppState(role=DeviceRole.CONTROLLED,activeSessionId="new",sessionState=SessionState.CONNECTED)))
        leases.remove("new")
        assertFalse(leases.wanted(AppState(role=DeviceRole.CONTROLLED,activeSessionId="new",sessionState=SessionState.CONNECTED)))
    }
    @Test fun standbyAndCaptureCanReleaseInEitherOrder() {
        for(first in listOf("standby","capture")) {
            val leases=ScreenAwakeLeases()
            leases.add("standby",ForegroundMode.STANDBY,null);leases.add("capture",ForegroundMode.SCREEN_SHARE,"live")
            val state=AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,activeSessionId="live",sessionState=SessionState.CONNECTED)
            leases.remove(first);assertTrue(leases.wanted(state))
            leases.remove(if(first=="standby")"capture" else "standby");assertFalse(leases.wanted(state))
        }
    }
    @Test fun stoppingOrChangingRoleRevokesTheOldDisplayOwner() {
        val leases=ScreenAwakeLeases();leases.add("controller",ForegroundMode.CONTROLLER,null)
        val connected=AppState(activeSessionId="live",sessionState=SessionState.CONNECTED)
        assertTrue(leases.wanted(connected))
        assertFalse(leases.wanted(connected.copy(role=DeviceRole.CONTROLLED)))
        assertFalse(leases.wanted(connected.copy(activeSessionId=null,sessionState=SessionState.STOPPED)))
    }
    @Test fun videoStatisticsAndInputAcksDoNotChangeScreenOwnership() {
        val leases=ScreenAwakeLeases();leases.add("controller",ForegroundMode.CONTROLLER,null)
        val connected=AppState(activeSessionId="live",sessionState=SessionState.CONNECTED)
        repeat(1000){assertTrue(leases.wanted(connected.copy(rttMs=it.toLong(),inputAckMs=it.toLong(),remoteFrameWidth=it)))}
    }
    @Test fun disconnectedControlledRegistrationAndNetworkRetryDoNotOwnTheDisplay() {
        val leases=ScreenAwakeLeases();leases.add("standby",ForegroundMode.STANDBY,null)
        for(phase in listOf(SessionState.REGISTERING,SessionState.CONNECTING,SessionState.RECONNECTING)) {
            assertFalse(phase.name,leases.wanted(AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,sessionState=phase)))
        }
    }
    @Test fun aRealIncomingRequestStillKeepsTheConfirmationVisible() {
        val leases=ScreenAwakeLeases();leases.add("standby",ForegroundMode.STANDBY,null)
        assertTrue(leases.wanted(AppState(role=DeviceRole.CONTROLLED,standbyServiceRunning=true,
            sessionState=SessionState.WAITING_CONFIRMATION,incomingSessionId="pending")))
    }
    @Test fun staleSessionIdentifiersCannotKeepTerminalStatesBright() {
        val leases=ScreenAwakeLeases();leases.add("controller",ForegroundMode.CONTROLLER,null)
        for(phase in listOf(SessionState.IDLE,SessionState.STANDBY,SessionState.ERROR,SessionState.STOPPED))
            assertFalse(leases.wanted(AppState(sessionState=phase,activeSessionId="stale")))
    }
    @Test fun foregroundFlagsAreScopedToTheSameSessionPlanAsTheOverlay() {
        assertTrue(plan(foreground=true).foregroundWindow)
        assertFalse(plan(foreground=true,wanted=false).foregroundWindow)
        assertFalse(plan(foreground=true,off=true).foregroundWindow)
        assertFalse(plan(foreground=true,locked=true).foregroundWindow)
    }
}
