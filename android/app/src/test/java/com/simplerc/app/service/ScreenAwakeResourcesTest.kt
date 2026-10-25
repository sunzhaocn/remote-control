package com.simplerc.app.service

import com.simplerc.app.model.AppState
import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import org.junit.Assert.*
import org.junit.Test

class ScreenAwakeResourcesTest {
    private class FakeWindow:AwakeWindowHandle {
        var connected=false;var shown=true;var immediateAttach=true;var flag=false
        var attaches=0;var renewals=0;var removes=0
        var attachFailures=0;var renewFailures=0;var removeFailures=0;var healthFailures=0
        override val attached:Boolean get() {
            if(healthFailures-->0)error("health unavailable")
            return connected
        }
        override val visible:Boolean get()=shown
        override fun attach() {attaches++;connected=immediateAttach;flag=true;if(attachFailures-->0)error("partly attached")}
        override fun renew() {renewals++;if(renewFailures-->0)error("layout rejected");flag=true}
        override fun remove() {removes++;flag=false;if(removeFailures-->0)error("detach pending");connected=false}
    }
    private class Device:ScreenAwakeDriver {
        var now=0L;var held=false;var expires=0L;var foreground=false
        var acquires=0;var releases=0;var acquireFailures=0;var releaseFailures=0;var foregroundFailures=0
        var queryFailures=0;var createFailures=0
        val windows=mutableListOf<FakeWindow>()
        var configure:(FakeWindow)->Unit={}
        override val displayHeld:Boolean get() {if(queryFailures-->0)error("display query");return held && now<expires}
        override fun acquireDisplay(timeoutMs:Long) {acquires++;if(acquireFailures-->0)error("display acquire");held=true;expires=now+timeoutMs}
        override fun releaseDisplay() {releases++;if(releaseFailures-->0)error("display release");held=false}
        override fun setForegroundKeepScreenOn(keep:Boolean) {if(foregroundFailures-->0)error("window attributes");foreground=keep}
        override fun createWindow(type:AwakeWindow,host:Any):FakeWindow {
            if(createFailures-->0)error("window context unavailable")
            return FakeWindow().also {configure(it);windows.add(it)}
        }
    }
    private class Rig {
        val device=Device();val errors=mutableListOf<String>()
        val resources=ScreenAwakeResources(device){action,_->errors.add(action)}
        val host=Any()
        val background=ScreenAwakePlan(true,AwakeWindow.APPLICATION)
        val idle=ScreenAwakePlan(false,AwakeWindow.NONE)
        fun tick(now:Long,plan:ScreenAwakePlan=background,host:Any?=this.host):AwakeResourceResult {
            device.now=now;return resources.apply(plan,host,now)
        }
    }
    @Test fun twentyFourHoursOfBackgroundTicksRenewWithoutRecreatingHealthyWindows() {
        val r=Rig()
        for(now in 0L..86_400_000L step 15_000) {
            val result=r.tick(now)
            assertTrue(result.displayHeld);assertTrue(result.windowReady);assertTrue(result.errors.isEmpty())
            assertTrue(r.device.expires>now)
        }
        assertEquals(1,r.device.windows.size);assertEquals(2880,r.device.windows.single().renewals)
        assertEquals(2881,r.device.acquires)
        assertFalse(r.tick(86_400_001,r.idle).releasePending)
        assertFalse(r.resources.needsMaintenance);assertFalse(r.device.windows.single().connected)
    }
    @Test fun expirationOrExternalLockLossIsReacquiredWithoutAnyVideoOrTouchEvent() {
        val r=Rig();r.tick(0);r.device.held=false
        assertTrue(r.tick(15_000).displayHeld)
        assertTrue(r.tick(300_000).displayHeld);assertEquals(3,r.device.acquires)
    }
    @Test fun healthyLocksAreNotReacquiredForEveryDiagnosticUpdate() {
        val r=Rig();r.tick(0);repeat(1000){r.tick(it+1L)}
        assertEquals(1,r.device.acquires);assertEquals(1,r.device.windows.size)
    }
    @Test fun disconnectedForegroundClearsBothKindsOfWindowOwnership() {
        val r=Rig();r.tick(0,ScreenAwakePlan(true,AwakeWindow.NONE,foregroundWindow=true))
        assertTrue(r.device.foreground)
        val result=r.tick(1,r.idle)
        assertFalse(r.device.foreground);assertFalse(result.displayHeld);assertFalse(r.resources.needsMaintenance)
    }
    @Test fun backgroundForegroundAndDisconnectLeaveNoScreenHolder() {
        val r=Rig();r.tick(0)
        r.tick(1,ScreenAwakePlan(true,AwakeWindow.NONE,foregroundWindow=true))
        assertFalse(r.device.windows.single().connected);assertTrue(r.device.foreground)
        r.tick(2,r.idle);assertFalse(r.device.foreground);assertFalse(r.resources.needsMaintenance)
    }
    @Test fun detachmentIsDetectedEvenWhenTheOldHandleStillExists() {
        val r=Rig();r.tick(0);r.device.windows.single().connected=false
        assertTrue(r.tick(1000).windowReady);assertEquals(2,r.device.windows.size)
        assertEquals(1,r.device.windows.first().removes)
    }
    @Test fun invisibleAttachedWindowIsReplacedInsteadOfTrustedForever() {
        val r=Rig();r.tick(0);r.device.windows.single().shown=false
        assertTrue(r.tick(1000).windowReady);assertEquals(2,r.device.windows.size)
    }
    @Test fun anAttachmentGetsAGracePeriodInsteadOfARecreationStorm() {
        val r=Rig();r.device.configure={it.immediateAttach=false}
        r.tick(0);r.tick(100);r.tick(2500)
        assertEquals(1,r.device.windows.size)
        r.device.windows.first().connected=true
        assertTrue(r.tick(2900).windowReady);assertEquals(1,r.device.windows.size)
    }
    @Test fun anAttachmentThatNeverCompletesIsRetriedAfterItsDeadline() {
        val r=Rig();r.device.configure={it.immediateAttach=false};r.tick(0)
        r.device.configure={}
        assertTrue(r.tick(3000).windowReady);assertEquals(2,r.device.windows.size)
    }
    @Test fun renewalReassertsAFlagThatWasClearedAfterAttachment() {
        val r=Rig();r.tick(0);val window=r.device.windows.single();window.flag=false
        r.tick(30_000);assertTrue(window.flag);assertEquals(1,window.renewals)
    }
    @Test fun aFailedRenewalIsCleanedAndRebuiltOnTheNextTick() {
        val r=Rig();r.tick(0);r.device.windows.single().renewFailures=1
        assertTrue(r.tick(30_000).errors.contains("window-renew"))
        assertTrue(r.tick(45_000).windowReady);assertEquals(2,r.device.windows.size)
    }
    @Test fun removalFailureRetainsTheHandleAndRetriesAfterSessionEnd() {
        val r=Rig();r.tick(0);r.device.windows.single().removeFailures=2
        assertTrue(r.tick(1,r.idle).releasePending);assertTrue(r.resources.needsMaintenance)
        assertTrue(r.tick(15_001,r.idle).releasePending)
        assertFalse(r.tick(30_001,r.idle).releasePending)
        assertFalse(r.resources.needsMaintenance);assertEquals(3,r.device.windows.single().removes)
    }
    @Test fun failedOldWindowRemovalCannotCreateUntrackedOverlappingWindows() {
        val r=Rig();r.tick(0);r.device.windows.single().removeFailures=1;val newHost=Any()
        assertTrue(r.tick(1000,host=newHost).releasePending);assertEquals(1,r.device.windows.size)
        assertTrue(r.tick(16_000,host=newHost).windowReady);assertEquals(2,r.device.windows.size)
        assertFalse(r.device.windows.first().connected)
    }
    @Test fun partiallyFailedAttachmentIsStillOwnedUntilItsCleanupSucceeds() {
        val r=Rig();r.device.configure={it.attachFailures=1;it.removeFailures=1}
        assertTrue(r.tick(0).releasePending);assertEquals(1,r.device.windows.size)
        r.device.configure={}
        assertTrue(r.tick(15_000).windowReady);assertEquals(2,r.device.windows.size)
        assertFalse(r.device.windows.first().connected)
    }
    @Test fun backgroundPermissionRevocationRemovesTheExistingWindow() {
        val r=Rig();r.tick(0)
        r.tick(1,ScreenAwakePlan(true,AwakeWindow.NONE,needsPermission=true),null)
        assertFalse(r.device.windows.single().connected)
        assertEquals(1,r.device.windows.single().removes)
    }
    @Test fun accessibilityHostReplacementReleasesTheOldServiceWindow() {
        val r=Rig();val plan=ScreenAwakePlan(true,AwakeWindow.ACCESSIBILITY)
        r.tick(0,plan);assertTrue(r.tick(1,plan,Any()).windowReady)
        assertEquals(2,r.device.windows.size);assertFalse(r.device.windows.first().connected)
    }
    @Test fun lockReleaseFailureKeepsMaintenanceAliveAfterTheLastLeaseEnds() {
        val r=Rig();r.tick(0);r.device.releaseFailures=1
        assertTrue(r.tick(1,r.idle).releasePending);assertTrue(r.resources.needsMaintenance)
        assertFalse(r.tick(15_001,r.idle).displayHeld);assertFalse(r.resources.needsMaintenance)
    }
    @Test fun foregroundClearingFailureIsRetriedEvenWithoutABackgroundWindow() {
        val r=Rig();r.tick(0,ScreenAwakePlan(true,AwakeWindow.NONE,foregroundWindow=true))
        r.device.foregroundFailures=1
        assertTrue(r.tick(1,r.idle).releasePending);assertTrue(r.resources.needsMaintenance)
        r.tick(15_001,r.idle);assertFalse(r.device.foreground);assertFalse(r.resources.needsMaintenance)
    }
    @Test fun aSingleAcquireFailureDoesNotPreventFutureWatchdogRecovery() {
        val r=Rig();r.device.acquireFailures=1
        assertFalse(r.tick(0).displayHeld)
        assertTrue(r.tick(15_000).displayHeld);assertTrue(r.errors.contains("display-acquire"))
    }
    @Test fun windowQueryFailureIsIsolatedAndDoesNotPermanentlyStopProtection() {
        val r=Rig();r.tick(0);r.device.windows.single().healthFailures=1
        assertTrue(r.tick(1000).windowReady);assertEquals(2,r.device.windows.size)
    }
    @Test fun manualScreenOffReleasesAndLateCallbacksCannotRecreateAnIdleWindow() {
        val r=Rig();r.tick(0)
        repeat(10){assertFalse(r.tick(it+1L,r.idle,null).windowReady)}
        assertEquals(1,r.device.windows.size);assertFalse(r.resources.needsMaintenance)
    }
    @Test fun bothRolesCanStayInBackgroundForHoursAndThenReturnToNormalTimeout() {
        for(role in DeviceRole.entries) {
            val leases=ScreenAwakeLeases();val r=Rig()
            val mode=if(role==DeviceRole.CONTROLLER)ForegroundMode.CONTROLLER else ForegroundMode.SCREEN_SHARE
            leases.add("service",mode,"live")
            val connected=AppState(role=role,activeSessionId="live",sessionState=SessionState.CONNECTED)
            for(now in 0L..7_200_000L step 15_000) {
                val plan=ScreenAwakePolicy.plan(leases.wanted(connected),false,true,false,false,true,true)
                assertTrue(r.tick(now,plan).displayHeld)
            }
            val disconnected=connected.copy(activeSessionId=null,sessionState=SessionState.REGISTERING,standbyServiceRunning=true)
            val plan=ScreenAwakePolicy.plan(leases.wanted(disconnected),true,true,false,false,true,true)
            val result=r.tick(7_200_001,plan)
            assertFalse(result.displayHeld);assertFalse(result.windowReady);assertFalse(r.device.foreground)
            assertFalse(r.resources.needsMaintenance)
        }
    }
    @Test fun failedDisplayQueriesCannotClaimCleanupCompletedOrDisableIdleRetries() {
        val r=Rig();r.device.queryFailures=2
        assertTrue(r.tick(0,r.idle).releasePending);assertTrue(r.resources.needsMaintenance)
        assertFalse(r.tick(15_000,r.idle).releasePending);assertFalse(r.resources.needsMaintenance)
    }
    @Test fun aWindowFactoryFailureDoesNotDisableTheNextBackgroundAttempt() {
        val r=Rig();r.device.createFailures=1
        assertTrue(r.tick(0).errors.contains("window-create"))
        assertTrue(r.tick(15_000).windowReady)
    }
}
