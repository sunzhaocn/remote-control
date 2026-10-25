package com.simplerc.app.control

import com.simplerc.app.model.DeviceRole
import com.simplerc.app.model.SessionState
import org.junit.Assert.*
import org.junit.Test

class ControllerExitGuardTest {
    private fun enable(g:ControllerExitGuard,id:String="session") =
        g.protected(DeviceRole.CONTROLLER,id,SessionState.CONNECTED,true,false)
    @Test fun fewerThanOneHundredBackActionsAreConsumed() {
        val g=ControllerExitGuard();assertTrue(enable(g))
        repeat(99) {assertFalse(g.press(it.toLong()))}
        assertTrue(g.press(999))
    }
    @Test fun exactlyOneSecondOldPressesDoNotCount() {
        val g=ControllerExitGuard();enable(g)
        repeat(99) {assertFalse(g.press(0))}
        assertFalse(g.press(1000))
    }
    @Test fun usesARollingWindowInsteadOfFixedBuckets() {
        val g=ControllerExitGuard();enable(g);assertFalse(g.press(0))
        repeat(98) {assertFalse(g.press(900+it.toLong()))}
        assertFalse(g.press(1001))
        assertTrue(g.press(1002))
    }
    @Test fun controlledRoleNeverEnablesTheGuard() {
        val g=ControllerExitGuard()
        assertFalse(g.protected(DeviceRole.CONTROLLED,"session",SessionState.CONNECTED,true,false))
        repeat(101) {assertFalse(g.press(it.toLong()))}
    }
    @Test fun disconnectedAndTerminalStatesReleaseImmediately() {
        for(s in listOf(SessionState.IDLE,SessionState.STOPPED,SessionState.ERROR,SessionState.STANDBY)) {
            val g=ControllerExitGuard();enable(g);repeat(99) {g.press(0)}
            assertFalse(g.protected(DeviceRole.CONTROLLER,"session",s,true,false))
            assertFalse(g.press(1))
            enable(g);assertFalse(g.press(2))
        }
    }
    @Test fun missingSessionCannotProtectControllerRegistrationOrConnecting() {
        val g=ControllerExitGuard()
        assertFalse(g.protected(DeviceRole.CONTROLLER,null,SessionState.CONNECTING,true,false))
        assertFalse(g.protected(DeviceRole.CONTROLLER,"session",SessionState.REGISTERING,true,false))
    }
    @Test fun screenOffAndLockBothReleaseAndForgetTheBurst() {
        for(locked in listOf(false,true)) {
            val g=ControllerExitGuard();enable(g);repeat(99) {g.press(0)}
            assertFalse(g.protected(DeviceRole.CONTROLLER,"session",SessionState.CONNECTED,locked,locked))
            enable(g);assertFalse(g.press(1))
        }
    }
    @Test fun aNewSessionDoesNotInheritPreviousBackActions() {
        val g=ControllerExitGuard();enable(g);repeat(99) {g.press(0)}
        enable(g,"new");assertFalse(g.press(1))
    }
    @Test fun backgroundTransitionResetsTheCount() {
        val g=ControllerExitGuard();enable(g);repeat(99) {g.press(0)}
        g.reset();assertFalse(g.press(1))
    }
    @Test fun timeRegressionDoesNotCarryOldActions() {
        val g=ControllerExitGuard();enable(g);repeat(99) {g.press(10)}
        assertFalse(g.press(5))
    }
    @Test fun successClearsTheBurstAndReconnectingRemainsProtected() {
        val g=ControllerExitGuard();enable(g)
        repeat(99) {g.press(0)};assertTrue(g.press(1));assertFalse(g.press(2))
        assertTrue(g.protected(DeviceRole.CONTROLLER,"session",SessionState.RECONNECTING,true,false))
    }
}
