package com.simplerc.app.webrtc

import org.junit.Assert.*
import org.junit.Test

class DetailProtectionTest {
    private fun sample(p:DetailProtection,now:Long,pressure:Boolean=false,
        cadence:Boolean=true,known:Boolean=true)=p.observe(now,pressure,if(known){if(pressure)40.0 else 5.0} else null,
            if(known)4.0 else null,if(known)3.0 else null,10.0,2.0,cadence,30.0,30.0)
    @Test fun realLoadIsMeasuredEvenDuringContinuousInteraction() {
        val p=DetailProtection();repeat(3){sample(p,it*1000L,pressure=true)}
        assertEquals(1.25,p.scale,0.0)
    }
    @Test fun sustainedRealDetailPressureFallsBackInsteadOfOverloadingCpu() {
        val p=DetailProtection();sample(p,1000,pressure=true);sample(p,2000,pressure=true)
        assertEquals(1.0,p.scale,0.0);sample(p,3000,pressure=true)
        assertEquals(1.25,p.scale,0.0)
    }
    @Test fun oneCodecRestartSpikeCannotDowngradeDetail() {
        val p=DetailProtection();sample(p,1000,pressure=true)
        repeat(30){sample(p,2000+it*1000L)}
        assertEquals(1.0,p.scale,0.0)
    }
    @Test fun rapidOrRepeatedSamplesCannotRushCpuFallback() {
        val p=DetailProtection();repeat(100){sample(p,1000,pressure=true)}
        assertEquals(1.0,p.scale,0.0)
    }
    @Test fun continuousPressureHasBoundedFallbackLevels() {
        val p=DetailProtection();repeat(100){sample(p,1000+it*1000L,pressure=true)}
        assertEquals(3.0,p.scale,0.0)
    }
    @Test fun missingDeviceMeasurementsCannotInventRecovery() {
        val p=DetailProtection();repeat(3){sample(p,1000+it*1000L,pressure=true)}
        repeat(30){sample(p,4000+it*1000L,known=false)}
        assertEquals(1.25,p.scale,0.0)
    }
    @Test fun sustainedMeasuredHeadroomRestoresFullDetail() {
        val p=DetailProtection();repeat(3){sample(p,1000+it*1000L,pressure=true)}
        repeat(12){sample(p,4000+it*1000L)}
        assertEquals(1.0,p.scale,0.0)
    }
    @Test fun resetDoesNotCarryOldCpuLimitsIntoNewSession() {
        val p=DetailProtection();repeat(3){sample(p,1000+it*1000L,pressure=true)};p.reset()
        assertEquals(1.0,p.scale,0.0)
    }
    @Test fun oldHealthySamplesCannotBeBankedAcrossALongMeasurementGap() {
        val p=DetailProtection();repeat(3){sample(p,1000+it*1000L,pressure=true)}
        repeat(9){sample(p,4000+it*1000L)}
        sample(p,100_000)
        assertEquals(1.25,p.scale,0.0)
    }
    @Test fun staleCpuReasonAloneCannotKeepBlurringHealthyVideo() {
        val p=DetailProtection()
        repeat(30){p.observe(1000+it*1000L,true,5.0,4.0,3.0,10.0,2.0,true,30.0,30.0)}
        assertEquals(1.0,p.scale,0.0)
    }
    @Test fun cpuReasonWithRealSustainedSendDeficitCanStillProtectDevice() {
        val p=DetailProtection()
        repeat(3){p.observe(1000+it*1000L,true,5.0,4.0,3.0,10.0,2.0,false,30.0,12.0)}
        assertEquals(1.25,p.scale,0.0)
    }
    @Test fun slowVideoAndUnknownCadenceAreNotCpuDeficits() {
        val p=DetailProtection()
        repeat(10){p.observe(1000+it*1000L,true,null,null,null,null,null,false,12.0,12.0)}
        repeat(10){p.observe(11000+it*1000L,true,null,null,null,null,null,false,null,null)}
        assertEquals(1.0,p.scale,0.0)
    }

    @Test fun stillScreenCostDoesNotPretendToBeThirtyFpsOverload() {
        val p=DetailProtection();repeat(30){p.observe(it*1000L,false,40.0,30.0,25.0,50.0,2.0,false,1.0,1.0)}
        assertEquals(1.0,p.scale,0.0);assertFalse(p.hasHeadroom)
    }
    @Test fun missingDeviceCostsDoNotEnableNetworkProbes() {
        val p=DetailProtection();p.observe(1000,false,null,null,null,null,null,true,30.0,30.0)
        assertFalse(p.hasHeadroom)
    }

    @Test fun slowerSourceCadenceIsNotAReceiverDeficit() {
        val p=DetailProtection();repeat(30){p.observe(it*1000L,false,5.0,5.0,2.0,10.0,2.0,true,12.0,12.0,12.0,12.0)}
        assertEquals(1.0,p.scale,0.0)
    }
    @Test fun unknownReceiverRatesCannotInventDroppedFrames() {
        val p=DetailProtection();repeat(30){p.observe(it*1000L,false,5.0,5.0,2.0,10.0,2.0,false,30.0,30.0,null,null)}
        assertEquals(1.0,p.scale,0.0)
    }

    @Test fun confirmedPartialRecoveryCanBeProtectedAgainWithoutTierIndexErrors() {
        val p=DetailProtection();repeat(3){sample(p,1000+it*1000L,pressure=true)}
        p.commitRecovery(1.12,4000);assertEquals(1.12,p.scale,0.0)
        repeat(3){sample(p,5000+it*1000L,pressure=true)}
        assertEquals(1.25,p.scale,0.0)
    }
}
